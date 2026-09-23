package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cheque grid against a real database.
 *
 * <p>Testcontainers rather than mocks because the things that can go wrong here
 * are all database-shaped: the partial unique index on (lease, cheque number),
 * the tenant filter that only runs inside a transaction, and the delete-then-
 * insert ordering that a naive replace gets wrong in exactly one flush.</p>
 */
@SpringBootTest
class ChequeGenerationServiceIT extends AbstractPostgresIT {

    @Autowired ChequeGenerationService service;
    @Autowired LeaseService leaseService;
    @Autowired LeaseRepository leaseRepository;
    @Autowired ChequeRepository chequeRepository;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    /** A second connection, for holding the lease row lock the way a Post does. */
    @Autowired DataSource dataSource;

    private LeaseTestFixtures fixtures;

    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** The Task 4 draft: 51,000 rent, a 2,000 admin fee and a 3,000 deposit. */
    private LeaseDTO draft() {
        return leaseService.createDraftLease(fixtures.draftDto(START, END, List.of(
                line("RENT", "51000"),
                line("ADMIN_FEE", "2000"),
                line("SECURITY_DEPOSIT", "3000"))));
    }

    private static GenerateChequesRequest fourCheques() {
        return new GenerateChequesRequest(4, null, null, "Emirates NBD", null, null, null);
    }

    private static BigDecimal sum(List<ChequeDTO> rows) {
        return rows.stream().map(ChequeDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void generatesTheFoldedGridFromTheLeaseLines() {
        UUID leaseId = draft().getId();

        List<ChequeDTO> rows = service.generate(leaseId, fourCheques());

        assertThat(rows).hasSize(4);
        // 12,750 of rent plus the whole of the fee and the deposit, because the
        // renter hands all three over with the first cheque.
        assertThat(rows.get(0).amount()).isEqualByComparingTo("17750");
        assertThat(rows.get(0).narration()).isEqualTo("Rent - 1st Installment | Admin | SD");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("12750");
        assertThat(rows.get(1).narration()).isEqualTo("Rent - 2nd Installment");
        // The grid collects the contract, not just the rent.
        assertThat(sum(rows)).isEqualByComparingTo("56000");

        // 12 months over 4 cheques: offsets [0, 3, 6, 9] from the first due date.
        assertThat(rows).extracting(ChequeDTO::chequeDate).containsExactly(
                LocalDate.of(2026, 9, 24),
                LocalDate.of(2026, 12, 24),
                LocalDate.of(2027, 3, 24),
                LocalDate.of(2027, 6, 24));
        assertThat(rows).extracting(ChequeDTO::seqNo).containsExactly(1, 2, 3, 4);

        Lease lease = tx.execute(s -> leaseRepository.findById(leaseId).orElseThrow());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.postingDate()).isEqualTo(lease.getContractDate());
            assertThat(r.status()).isEqualTo(ChequeStatus.DRAFT);
            assertThat(r.mode()).isEqualTo(ChequeMode.PDC);
            assertThat(r.payeeBank()).isEqualTo("Emirates NBD");
            assertThat(r.payerName()).isEqualTo(fixtures.renter().getNameEn());
            assertThat(r.chequeNumber()).isNull();
            // Cleared funds land in the property's own bank leaf, resolved from
            // the template rather than named by the caller.
            assertThat(r.debitAccountName()).isEqualTo("Emirates Islamic - " + fixtures.propertyName());
        });

        // A DRAFT lease's cheques are invisible to the register: they are a
        // proposal, not money owed.
        tx.executeWithoutResult(s -> assertThat(chequeRepository.findDue(
                null, LocalDate.of(2030, 1, 1), true, java.util.List.of(),
                org.springframework.data.domain.Pageable.unpaged())
                .getContent()).isEmpty());
    }

    /**
     * The grid collects VAT, because the renter's cheques have to add up to what
     * the contract charges — and the contract charges VAT on the lines that carry
     * it. 51,000 of VAT-free rent over four cheques, plus a 2,000 admin fee whose
     * gross is 2,100: row 1 is 14,850 and the grid is 53,100.
     */
    @Test
    void theGridCollectsVatOnTheLinesThatCarryIt() {
        UUID leaseId = leaseService.createDraftLease(fixtures.draftDto(START, END, List.of(
                line("RENT", "51000"),
                vatLine("ADMIN_FEE", "2000")))).getId();

        List<ChequeDTO> rows = service.generate(leaseId, fourCheques());

        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("14850");
        assertThat(rows.get(0).narration()).isEqualTo("Rent - 1st Installment | Admin");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("12750");
        assertThat(sum(rows)).isEqualByComparingTo("53100");
    }

    /** VAT-bearing rent is spread across the instalments, tens rounding intact. */
    @Test
    void vatOnRentIsSpreadAcrossTheInstallments() {
        UUID leaseId = leaseService.createDraftLease(fixtures.draftDto(START, END, List.of(
                vatLine("RENT", "60000")))).getId();

        List<ChequeDTO> rows = service.generate(leaseId, fourCheques());

        assertThat(rows).extracting(ChequeDTO::amount)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("15750"));
        assertThat(sum(rows)).isEqualByComparingTo("63000");
    }

    /** A line naming its charge type by code, VAT-applicable. */
    private static LeaseLineInput vatLine(String code, String gross) {
        return new LeaseLineInput(null, code, new BigDecimal(gross), BigDecimal.ZERO,
                null, true, null, null, null);
    }

    /**
     * A row id from another lease is a 400, not a silent adoption. Same tenant, so
     * the tenant filter does not catch it: the only thing standing between the two
     * grids is the "is this a DRAFT row of *this* lease" check.
     */
    @Test
    void aRowIdFromAnotherLeaseIsRefused() {
        UUID leaseId = draft().getId();
        service.generate(leaseId, fourCheques());

        Unit otherUnit = fixtures.createUnit(fixtures.property(), "102");
        UUID otherLeaseId = leaseService.createDraftLease(fixtures.draftDto(
                otherUnit, fixtures.renter(), START, END, List.of(line("RENT", "24000")))).getId();
        ChequeDTO stranger = service.generate(otherLeaseId, fourCheques()).get(0);

        List<ChequeRowInput> poached = List.of(asInput(stranger, null));
        assertThatThrownBy(() -> service.saveRows(leaseId, poached))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not a draft row of this lease");

        // Neither grid moved.
        assertThat(service.list(leaseId)).hasSize(4);
        assertThat(service.list(otherLeaseId)).hasSize(4);
    }

    /** Unfolded, the fee and the deposit are instruments of their own. */
    @Test
    void unfoldedExtrasStandAsTheirOwnRows() {
        UUID leaseId = draft().getId();

        List<ChequeDTO> rows = service.generate(leaseId,
                new GenerateChequesRequest(4, null, null, null, null, false, null));

        assertThat(rows).hasSize(6);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("12750");
        assertThat(rows.get(4).narration()).isEqualTo("Admin");
        assertThat(rows.get(5).narration()).isEqualTo("SD");
        assertThat(sum(rows)).isEqualByComparingTo("56000");
    }

    /** Generating again replaces the previous draft rows rather than adding to them. */
    @Test
    void regeneratingReplacesTheDraftGrid() {
        UUID leaseId = draft().getId();
        service.generate(leaseId, fourCheques());

        List<ChequeDTO> rows = service.generate(leaseId,
                new GenerateChequesRequest(6, null, null, null, null, null, null));

        assertThat(rows).hasSize(6);
        assertThat(service.list(leaseId)).hasSize(6);
        assertThat(sum(rows)).isEqualByComparingTo("56000");
    }

    @Test
    void numbersFillSequentiallyFromTheStartingNumber() {
        UUID leaseId = draft().getId();
        service.generate(leaseId, fourCheques());

        List<ChequeDTO> numbered = service.generateNumbers(leaseId, "100040");

        assertThat(numbered).extracting(ChequeDTO::chequeNumber)
                .containsExactly("100040", "100041", "100042", "100043");
        // And they survive the round trip to the database.
        assertThat(service.list(leaseId)).extracting(ChequeDTO::chequeNumber)
                .containsExactly("100040", "100041", "100042", "100043");

        // Renumbering an already-numbered grid is not a unique-index collision:
        // the numbers overlap the ones already held.
        assertThat(service.generateNumbers(leaseId, "100041"))
                .extracting(ChequeDTO::chequeNumber)
                .containsExactly("100041", "100042", "100043", "100044");
    }

    /**
     * Σ cheques ≠ the contract value is <em>allowed</em> here. Every edit of an
     * amount passes through that state, and the equality is a posting precondition
     * (Task 6), not a saving one.
     */
    @Test
    void saveRowsUpsertsDeletesAndToleratesAMismatchedTotal() {
        UUID leaseId = draft().getId();
        List<ChequeDTO> generated = service.generate(leaseId, fourCheques());
        service.generateNumbers(leaseId, "100040");
        List<ChequeDTO> numbered = service.list(leaseId);

        List<ChequeRowInput> edited = new ArrayList<>();
        // Row 1 kept by id, with an amount the renter actually wrote.
        ChequeDTO first = numbered.get(0);
        edited.add(new ChequeRowInput(first.id(), null, null, "100050", first.chequeDate(),
                "Mashreq", null, null, new BigDecimal("18000"), first.narration(), ChequeMode.PDC));
        // Row 2 kept untouched.
        ChequeDTO second = numbered.get(1);
        edited.add(new ChequeRowInput(second.id(), null, null, second.chequeNumber(), second.chequeDate(),
                null, null, null, second.amount(), second.narration(), ChequeMode.PDC));
        // A brand new cash row — no cheque number, but an expected receipt date.
        edited.add(new ChequeRowInput(null, null, null, null, LocalDate.of(2027, 3, 24),
                null, null, null, new BigDecimal("500"), "Cash top-up", ChequeMode.CASH));
        // Rows 3 and 4 are simply left out, which deletes them.

        List<ChequeDTO> saved = service.saveRows(leaseId, edited);

        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(ChequeDTO::seqNo).containsExactly(1, 2, 3);
        assertThat(saved.get(0).id()).isEqualTo(first.id());
        assertThat(saved.get(0).amount()).isEqualByComparingTo("18000");
        assertThat(saved.get(0).chequeNumber()).isEqualTo("100050");
        assertThat(saved.get(0).payeeBank()).isEqualTo("Mashreq");
        assertThat(saved.get(2).id()).isNotIn(generated.stream().map(ChequeDTO::id).toList());
        assertThat(saved.get(2).mode()).isEqualTo(ChequeMode.CASH);
        assertThat(saved.get(2).chequeNumber()).isNull();
        // 18,000 + 12,750 + 500 = 31,250, nowhere near the 56,000 contract. Saved.
        assertThat(sum(saved)).isEqualByComparingTo("31250");
        // The dropped rows are gone, not orphaned.
        assertThat(service.list(leaseId)).hasSize(3);
    }

    /**
     * Swapping two rows' numbers is the case the partial unique index refuses if
     * the update order is naive: row 1 takes 100041 while row 2 still holds it.
     */
    @Test
    void chequeNumbersMaySwapBetweenRows() {
        UUID leaseId = draft().getId();
        service.generate(leaseId, fourCheques());
        List<ChequeDTO> rows = service.generateNumbers(leaseId, "100040");

        List<ChequeRowInput> swapped = new ArrayList<>();
        swapped.add(asInput(rows.get(0), rows.get(1).chequeNumber()));
        swapped.add(asInput(rows.get(1), rows.get(0).chequeNumber()));
        swapped.add(asInput(rows.get(2), rows.get(2).chequeNumber()));
        swapped.add(asInput(rows.get(3), rows.get(3).chequeNumber()));

        assertThat(service.saveRows(leaseId, swapped)).extracting(ChequeDTO::chequeNumber)
                .containsExactly("100041", "100040", "100042", "100043");
    }

    private static ChequeRowInput asInput(ChequeDTO r, String chequeNumber) {
        return new ChequeRowInput(r.id(), null, r.postingDate(), chequeNumber, r.chequeDate(),
                r.payeeBank(), r.payerName(), r.debitAccountId(), r.amount(), r.narration(), r.mode());
    }

    @Test
    void theRowRulesAreEnforcedUpFront() {
        UUID leaseId = draft().getId();
        List<ChequeDTO> rows = service.generate(leaseId, fourCheques());
        ChequeDTO first = rows.get(0);

        // The same number twice — caught here, with a sentence, rather than as a
        // 409 quoting ux_cheques_lease_number.
        List<ChequeRowInput> duplicate = List.of(
                asInput(first, "100040"),
                asInput(rows.get(1), "100040"));
        assertThatThrownBy(() -> service.saveRows(leaseId, duplicate))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already used on this lease");

        // A cash receipt has no cheque number.
        List<ChequeRowInput> cashWithNumber = List.of(new ChequeRowInput(null, null, null, "100040",
                START, null, null, null, new BigDecimal("100"), "Cash", ChequeMode.CASH));
        assertThatThrownBy(() -> service.saveRows(leaseId, cashWithNumber))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("has no cheque number");

        // A transfer still needs the date it is expected on.
        List<ChequeRowInput> undatedTransfer = List.of(new ChequeRowInput(null, null, null, null,
                null, null, null, null, new BigDecimal("100"), "Transfer", ChequeMode.TRANSFER));
        assertThatThrownBy(() -> service.saveRows(leaseId, undatedTransfer))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("expected on");

        // Zero is not an amount.
        List<ChequeRowInput> zero = List.of(new ChequeRowInput(null, null, null, null,
                START, null, null, null, BigDecimal.ZERO, "Nothing", ChequeMode.PDC));
        assertThatThrownBy(() -> service.saveRows(leaseId, zero))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("greater than zero");

        // ONLINE is the gateway's to create, not the grid's.
        List<ChequeRowInput> online = List.of(new ChequeRowInput(null, null, null, null,
                START, null, null, null, new BigDecimal("100"), "Card", ChequeMode.ONLINE));
        assertThatThrownBy(() -> service.saveRows(leaseId, online))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("payment gateway");
        assertThatThrownBy(() -> service.generate(leaseId,
                new GenerateChequesRequest(4, null, null, null, null, null, ChequeMode.ONLINE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("payment gateway");

        // The same row sent twice would collapse into one, keeping whichever
        // amount came last.
        List<ChequeRowInput> twice = List.of(asInput(first, null), asInput(first, null));
        assertThatThrownBy(() -> service.saveRows(leaseId, twice))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("appears twice in the grid");

        // A refused save changes nothing.
        assertThat(service.list(leaseId)).hasSize(4);
    }

    /**
     * The DRAFT-only guard. A registered cheque has a journal against it; replacing
     * it is Task 9's business, and a regenerate must not quietly delete the row the
     * ledger points at.
     */
    @Test
    void generatingOnANonDraftLeaseIsRefused() {
        UUID leaseId = draft().getId();

        // Straight to the repository: the point is the service's guard, and going
        // through activateLease would drag unit occupancy into this test.
        tx.executeWithoutResult(s -> {
            Lease row = leaseRepository.findById(leaseId).orElseThrow();
            row.setStatus(LeaseStatus.ACTIVE);
            leaseRepository.save(row);
        });

        assertThatThrownBy(() -> service.generate(leaseId, fourCheques()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only DRAFT");
        assertThatThrownBy(() -> service.saveRows(leaseId, List.of()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only DRAFT");
        assertThatThrownBy(() -> service.generateNumbers(leaseId, "100040"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only DRAFT");
    }

    /**
     * The grid writers take the same lease row lock Post takes.
     *
     * <p>Under READ_COMMITTED they did not, and that is a race with money in it: a
     * property manager's {@code saveRows} reads the lease as DRAFT and its rows as
     * DRAFT; an accountant's Post then locks the lease, writes the TCO and a PDR per
     * row and flips them to REGISTERED; the grid save finally flushes its
     * {@code deleteAll} and its full-column updates over rows that are now
     * registered instruments on an ACTIVE lease — status back to DRAFT,
     * {@code pdr_journal_id} back to null, amounts and positions rewritten. The
     * ledger keeps the journals; the register no longer describes them, and nothing
     * anywhere errors.</p>
     *
     * <p>Locking makes the loser a clean "try again". The lock is held here from a
     * second connection, which is exactly what a Post in another transaction looks
     * like from this one, and all three writers are asserted because they are three
     * doors onto the same rows.</p>
     *
     * <p><b>"Immediately" is asserted, not assumed.</b> NOWAIT is the half of the
     * fix that keeps the loser off the connection pool, and a writer that took no
     * lock at all would not fail here — it would <em>block</em>, because inserting a
     * cheque takes a FOR KEY SHARE lock on its lease row and the FOR UPDATE held
     * below already conflicts with that. So each call runs on a worker with a
     * deadline: a refusal that never arrives is as much a failure as the wrong one,
     * and the test says so instead of hanging.</p>
     */
    @Test
    void everyGridWriterRefusesWhileTheLeaseRowIsLockedByAPost() throws Exception {
        UUID leaseId = draft().getId();
        List<ChequeDTO> rows = service.generate(leaseId, fourCheques());
        List<ChequeRowInput> edit = rows.stream().map(r -> asInput(r, null)).toList();

        try (Connection posting = dataSource.getConnection()) {
            posting.setAutoCommit(false);
            try (PreparedStatement lock = posting.prepareStatement(
                    "select id from leases where id = ? for update")) {
                lock.setObject(1, leaseId);
                lock.executeQuery();
            }

            assertRefusedImmediately("generate", () -> service.generate(leaseId, fourCheques()));
            assertRefusedImmediately("saveRows", () -> service.saveRows(leaseId, edit));
            assertRefusedImmediately("generateNumbers", () -> service.generateNumbers(leaseId, "100040"));

            posting.rollback();
        }

        // And the refusal really was the lock, not a lease this test had broken:
        // the same call goes through the moment the row is free.
        assertThat(service.generateNumbers(leaseId, "100040"))
                .filteredOn(r -> r.chequeNumber() != null)
                .isNotEmpty();
    }

    /**
     * One grid write, on a worker, with a deadline: it must come back refused, and
     * it must come back. A writer holding no lock does not throw here, it waits for
     * the row — so "the call never returned" is reported as the failure it is
     * rather than as a hung suite.
     */
    private void assertRefusedImmediately(String what, Runnable call) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> outcome = worker.submit(() -> {
                LeaseTestFixtures.authenticateAsTenantAdmin();
                TenantContextHolder.setTenantId(fixtures.tenantId());
                try {
                    call.run();
                    return null;
                } catch (Throwable t) {
                    return t;
                } finally {
                    TenantContextHolder.clear();
                    LeaseTestFixtures.clearAuth();
                }
            });
            Throwable thrown;
            try {
                thrown = outcome.get(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                outcome.cancel(true);
                throw new AssertionError(what + " never returned: it is waiting for the lease row "
                        + "rather than refusing, which is the whole bug this guards.");
            }
            assertThat(thrown)
                    .as("%s must be refused while the lease row is locked", what)
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("try again");
        } finally {
            worker.shutdownNow();
        }
    }

    /**
     * The status is re-read under the lock, not carried in from before it.
     *
     * <p>A grid write that locked the row and then decided on a status it had
     * already read would be no better than not locking: Post commits between the
     * two, and the writer proceeds against an ACTIVE lease holding a lock that
     * proves nothing. The lease is moved out of DRAFT by a separate transaction
     * here — which is what the accountant's Post is — and the writer has to notice.</p>
     */
    @Test
    void aGridWriteSeesALeaseThatLeftDraftInAnotherTransaction() {
        UUID leaseId = draft().getId();
        service.generate(leaseId, fourCheques());

        tx.executeWithoutResult(s -> {
            Lease row = leaseRepository.findById(leaseId).orElseThrow();
            row.setStatus(LeaseStatus.ACTIVE);
            leaseRepository.save(row);
        });

        assertThatThrownBy(() -> service.saveRows(leaseId, List.of()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only DRAFT");
    }

    /** A row that has left DRAFT is neither regenerated over nor editable as a grid row. */
    @Test
    void nonDraftRowsSurviveARegenerateAndCannotBeEdited() {
        UUID leaseId = draft().getId();
        List<ChequeDTO> rows = service.generate(leaseId, fourCheques());
        UUID registeredId = rows.get(0).id();

        tx.executeWithoutResult(s -> {
            Cheque c = chequeRepository.findById(registeredId).orElseThrow();
            c.setStatus(ChequeStatus.REGISTERED);
            chequeRepository.save(c);
        });

        // Regenerating drops the three DRAFT rows and leaves the registered one.
        service.generate(leaseId, fourCheques());
        List<ChequeDTO> after = service.list(leaseId);
        assertThat(after).hasSize(5);
        assertThat(after).extracting(ChequeDTO::id).contains(registeredId);
        assertThat(after).filteredOn(r -> r.status() == ChequeStatus.DRAFT).hasSize(4);

        // And it cannot be edited through the grid.
        ChequeDTO registered = after.stream().filter(r -> r.id().equals(registeredId)).findFirst().orElseThrow();
        List<ChequeRowInput> edit = List.of(asInput(registered, null));
        assertThatThrownBy(() -> service.saveRows(leaseId, edit))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not a draft row of this lease");
    }

    /**
     * The system-import doors are not a way round {@code LeaseAccessPolicy}.
     *
     * <p>They exist because the bulk import runs on a background thread with no
     * {@code Authentication} at all, and the id-taking form fails closed there. The
     * absence of a user is the licence, so it is asserted: when a user <em>is</em>
     * authenticated they must be one who could have managed the lease anyway. A
     * renter reaching this through some future caller is refused exactly as they
     * would be on the ordinary door — not found, so the error cannot be used to
     * discover the lease exists.</p>
     */
    @Test
    void theSystemImportDoorsRefuseAUserWhoCouldNotManageTheLease() {
        UUID leaseId = draft().getId();
        List<ChequeRowInput> rows = List.of(new ChequeRowInput(
                null, null, null, null, START, "Emirates NBD", null, null,
                new java.math.BigDecimal("1000"), "Rent", ChequeMode.PDC));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_RENTER"))));

        // Inside a transaction, as the importer calls it: the lease is a managed
        // entity there, and its unit and property resolve.
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                service.saveRowsForSystemImport(leaseRepository.findById(leaseId).orElseThrow(), rows)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                service.generateForSystemImport(leaseRepository.findById(leaseId).orElseThrow(), fourCheques())))
                .isInstanceOf(NotFoundException.class);
        List<Cheque> untouched = tx.execute(s -> chequeRepository.findByLease_IdOrderBySeqNoAsc(leaseId));
        assertThat(untouched).isEmpty();

        // The background caller — no Authentication at all — is the one they are for.
        SecurityContextHolder.clearContext();
        List<ChequeDTO> written = tx.execute(s ->
                service.saveRowsForSystemImport(leaseRepository.findById(leaseId).orElseThrow(), rows));
        assertThat(written).hasSize(1);
    }

    /**
     * And they refuse a lease belonging to another organisation. The entity comes
     * in from outside the tenant filter's reach, so this form has no lookup to get
     * the check from.
     */
    @Test
    void theSystemImportDoorsRefuseAnotherTenantsLease() {
        UUID leaseId = draft().getId();
        Lease theirs = tx.execute(s -> leaseRepository.findById(leaseId).orElseThrow());

        fixtures.newTenant();
        LeaseTestFixtures.authenticateAsTenantAdmin();

        assertThatThrownBy(() -> tx.executeWithoutResult(s ->
                service.generateForSystemImport(theirs, fourCheques())))
                .isInstanceOf(NotFoundException.class);
    }

    /** Numbering skips the cash rows rather than burning a number on them. */
    @Test
    void onlyPdcRowsAreNumbered() {
        UUID leaseId = draft().getId();
        List<ChequeDTO> rows = service.generate(leaseId, fourCheques());

        List<ChequeRowInput> mixed = new ArrayList<>();
        mixed.add(asInput(rows.get(0), null));
        mixed.add(new ChequeRowInput(rows.get(1).id(), null, null, null, rows.get(1).chequeDate(),
                null, null, null, rows.get(1).amount(), "Cash", ChequeMode.CASH));
        mixed.add(asInput(rows.get(2), null));
        service.saveRows(leaseId, mixed);

        List<ChequeDTO> numbered = service.generateNumbers(leaseId, "100040");

        assertThat(numbered).extracting(ChequeDTO::chequeNumber)
                .containsExactly("100040", null, "100041");
    }
}
