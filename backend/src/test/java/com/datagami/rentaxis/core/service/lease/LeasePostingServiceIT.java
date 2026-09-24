package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.PostLeaseDryRunResponse;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.ledger.UnmappedAccountRoleException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.lineCreditedTo;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Posting a lease, against a real database.
 *
 * <p>Nothing here can be usefully mocked: the assertions are about what is in the
 * ledger afterwards — which accounts, which contra accounts, which dimensions,
 * which entry numbers — and about all-or-nothing behaviour that only a real
 * transaction exhibits. The figures are PACT's Galah 2 shape: 51,000 of rent over
 * four cheques plus a 2,000 admin fee, contract dated before the tenancy starts.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} only enables the Hibernate tenant
 * filter inside one, so every read-back goes through {@link #tx}.</p>
 */
@SpringBootTest
class LeasePostingServiceIT extends AbstractPostgresIT {

    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LeaseRepository leaseRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired PropertyAccountMappingRepository propertyMappings;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    /** The contract is dated before the tenancy begins, as PACT's are. */
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);

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

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private CreateLeaseDTO dto(Unit unit, List<LeaseLineInput> leaseLines) {
        CreateLeaseDTO dto = fixtures.draftDto(unit, fixtures.renter(), START, END, leaseLines);
        dto.setContractDate(CONTRACT_DATE);
        dto.setFirstDueDate(START);
        return dto;
    }

    /** The Galah 2 draft: 51,000 rent credited to Advance Rent, a 2,000 admin fee. */
    private UUID draft() {
        return draft(fixtures.unit(), List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")));
    }

    private UUID draft(Unit unit, List<LeaseLineInput> leaseLines) {
        return leaseService.createDraftLease(dto(unit, leaseLines)).getId();
    }

    /**
     * Four rent instalments plus the extras as rows of their own. Unfolded so the
     * admin fee is its own instrument, which is the shape the brief's numbers
     * describe and which gives the grid one row per charged thing.
     */
    private List<ChequeDTO> grid(UUID leaseId) {
        cheques.generate(leaseId, new GenerateChequesRequest(
                4, START, null, "Emirates NBD", null, false, null));
        // Numbered, as a grid is before it posts: a PDC needs its number (#80).
        return cheques.generateNumbers(leaseId, com.datagami.rentaxis.testsupport.LeaseTestFixtures.nextChequeBook());
    }

    private UUID readyToPost() {
        UUID leaseId = draft();
        grid(leaseId);
        return leaseId;
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> lines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    private Lease reread(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }

    /** Journal rows for this tenant, counted in SQL: the assertion is "nothing was written". */
    private long journalEntryRows() {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?",
                Long.class, fixtures.tenantId());
    }

    private void unmap(AccountRole... roles) {
        tx.executeWithoutResult(s -> propertyMappings.deleteAll(
                propertyMappings.findByPropertyIdAndRoleIn(fixtures.property().getId(), List.of(roles))));
    }

    // ------------------------------------------------------------------
    // the happy path
    // ------------------------------------------------------------------

    @Test
    void postWritesTcoWithOnePairPerLineAndOnePdrPerCheque() {
        UUID leaseId = readyToPost();
        Account rentReceivable = leaf(AccountRole.RENT_RECEIVABLE);
        Account advanceRent = leaf(AccountRole.ADVANCE_RENT);
        Account adminFee = leaf(AccountRole.ADMIN_FEE);
        Account pdcReceivable = leaf(AccountRole.PDC_RECEIVABLE);

        PostLeaseResponse r = posting.post(leaseId);

        assertThat(r.tcoEntryNumber()).isEqualTo("TCO-26/1");

        // ---- the TCO ---------------------------------------------------
        JournalEntry tco = tx.execute(s -> entries.findById(r.tcoJournalId()).orElseThrow());
        assertThat(tco.getDocType()).isEqualTo(JournalDocType.TCO);
        assertThat(tco.getEntryDate()).isEqualTo(CONTRACT_DATE);
        assertThat(tco.getSourceType()).isEqualTo(JournalSourceType.LEASE);
        assertThat(tco.getSourceId()).isEqualTo(leaseId);

        List<JournalLine> tcoLines = linesOf(r.tcoJournalId());
        assertThat(tcoLines).hasSize(4);
        assertThat(tcoLines.get(0).getAccountId()).isEqualTo(rentReceivable.getId());
        assertThat(tcoLines.get(0).getDebit()).isEqualByComparingTo("51000");
        assertThat(tcoLines.get(1).getAccountId()).isEqualTo(advanceRent.getId());
        assertThat(tcoLines.get(1).getCredit()).isEqualByComparingTo("51000");
        assertThat(tcoLines.get(2).getAccountId()).isEqualTo(rentReceivable.getId());
        assertThat(tcoLines.get(2).getDebit()).isEqualByComparingTo("2000");
        assertThat(tcoLines.get(3).getAccountId()).isEqualTo(adminFee.getId());
        assertThat(tcoLines.get(3).getCredit()).isEqualByComparingTo("2000");

        // Each pair faces its own counter-account, on both sides. Without this the
        // receivable's ledger would print "Advance Rent, Admin Fee" against every
        // one of its rows instead of the particular that row was raised for.
        assertThat(tcoLines.get(0).getContraAccountId()).isEqualTo(advanceRent.getId());
        assertThat(tcoLines.get(1).getContraAccountId()).isEqualTo(rentReceivable.getId());
        assertThat(tcoLines.get(2).getContraAccountId()).isEqualTo(adminFee.getId());
        assertThat(tcoLines.get(3).getContraAccountId()).isEqualTo(rentReceivable.getId());

        assertThat(tcoLines).allSatisfy(l -> {
            assertThat(l.getLeaseId()).isEqualTo(leaseId);
            assertThat(l.getPropertyId()).isEqualTo(fixtures.property().getId());
            assertThat(l.getUnitId()).isEqualTo(fixtures.unit().getId());
            assertThat(l.getRenterId()).isEqualTo(fixtures.renter().getId());
            assertThat(l.getChequeId()).isNull();
        });
        assertThat(tcoLines.get(0).getNarration()).isEqualTo("Rent");
        assertThat(tcoLines.get(2).getNarration()).isEqualTo("Admin Fee");

        // ---- the PDRs --------------------------------------------------
        assertThat(r.cheques()).hasSize(5);
        assertThat(r.cheques()).allMatch(c -> c.status() == ChequeStatus.REGISTERED && c.pdrJournalId() != null);

        List<String> pdrNumbers = new ArrayList<>();
        for (ChequeDTO c : r.cheques()) {
            JournalEntry pdr = tx.execute(s -> entries.findById(c.pdrJournalId()).orElseThrow());
            pdrNumbers.add(pdr.getEntryNumber());
            assertThat(pdr.getDocType()).isEqualTo(JournalDocType.PDR);
            assertThat(pdr.getEntryDate()).isEqualTo(c.postingDate());
            assertThat(pdr.getSourceType()).isEqualTo(JournalSourceType.CHEQUE);
            assertThat(pdr.getSourceId()).isEqualTo(c.id());

            List<JournalLine> pdrLines = linesOf(pdr.getId());
            assertThat(pdrLines).hasSize(2);
            assertThat(pdrLines.get(0).getAccountId()).isEqualTo(pdcReceivable.getId());
            assertThat(pdrLines.get(0).getDebit()).isEqualByComparingTo(c.amount());
            assertThat(pdrLines.get(1).getAccountId()).isEqualTo(rentReceivable.getId());
            assertThat(pdrLines.get(1).getCredit()).isEqualByComparingTo(c.amount());
            assertThat(pdrLines).allSatisfy(l -> assertThat(l.getChequeId()).isEqualTo(c.id()));
        }
        // The posting date is the contract date, so every PDR files in the same period.
        assertThat(r.cheques()).allSatisfy(c -> assertThat(c.postingDate()).isEqualTo(CONTRACT_DATE));
        assertThat(pdrNumbers).containsExactly("PDR-26/1", "PDR-26/2", "PDR-26/3", "PDR-26/4", "PDR-26/5");

        // ---- the renter's ledger nets to zero, as in PACT ---------------
        tx.executeWithoutResult(s -> assertThat(ledger.accountLedger(rentReceivable.getId(),
                new LedgerFilter(null, null, null, null, leaseId, null)).closingBalance())
                .isEqualByComparingTo("0"));

        // ---- and the lease is on the books ------------------------------
        Lease lease = reread(leaseId);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(lease.getPostingJournalId()).isEqualTo(r.tcoJournalId());
        assertThat(lease.getPostedAt()).isNotNull();
        assertThat(r.lease().getStatus()).isEqualTo(LeaseStatus.ACTIVE);

        Unit unit = tx.execute(s -> unitRepo.findById(fixtures.unit().getId()).orElseThrow());
        assertThat(unit.getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertThat(unit.getCurrentTenantName()).isEqualTo(fixtures.renter().getNameEn());
    }

    /**
     * A VAT-applicable line raises a second pair against OUTPUT_VAT, and the grid
     * has to have collected the VAT too — which it does, because the generator and
     * the posting both go through {@code LeaseVat}.
     */
    @Test
    void vatLinePostsOutputVatPair() {
        UUID leaseId = draft(fixtures.unit(), List.of(line("RENT", "51000"), vatLine("ADMIN_FEE", "2000")));
        List<ChequeDTO> grid = grid(leaseId);
        assertThat(grid.stream().map(ChequeDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("53100");

        PostLeaseResponse r = posting.post(leaseId);

        List<JournalLine> tcoLines = linesOf(r.tcoJournalId());
        assertThat(tcoLines).hasSize(6);
        Account rentReceivable = leaf(AccountRole.RENT_RECEIVABLE);
        Account outputVat = leaf(AccountRole.OUTPUT_VAT);
        assertThat(tcoLines.get(4).getAccountId()).isEqualTo(rentReceivable.getId());
        assertThat(tcoLines.get(4).getDebit()).isEqualByComparingTo("100.00");
        assertThat(tcoLines.get(4).getNarration()).isEqualTo("VAT on Admin Fee");
        assertThat(tcoLines.get(5).getAccountId()).isEqualTo(outputVat.getId());
        assertThat(tcoLines.get(5).getCredit()).isEqualByComparingTo("100.00");
        assertThat(tcoLines.get(5).getContraAccountId()).isEqualTo(rentReceivable.getId());

        // The receivable still nets to zero: the PDRs collect 53,100.
        tx.executeWithoutResult(s -> assertThat(ledger.accountLedger(rentReceivable.getId(),
                new LedgerFilter(null, null, null, null, leaseId, null)).closingBalance())
                .isEqualByComparingTo("0"));
    }

    /** A deposit carries no VAT whatever the line says, so no OUTPUT_VAT pair appears. */
    @Test
    void aVatFlaggedDepositRaisesNoOutputVat() {
        UUID leaseId = draft(fixtures.unit(),
                List.of(line("RENT", "51000"), vatLine("SECURITY_DEPOSIT", "3000")));
        List<ChequeDTO> grid = grid(leaseId);
        assertThat(grid.stream().map(ChequeDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("54000");

        PostLeaseResponse r = posting.post(leaseId);

        List<JournalLine> tcoLines = linesOf(r.tcoJournalId());
        assertThat(tcoLines).hasSize(4);
        UUID outputVatId = leaf(AccountRole.OUTPUT_VAT).getId();
        assertThat(tcoLines).noneMatch(l -> outputVatId.equals(l.getAccountId()));
    }

    // ------------------------------------------------------------------
    // refusals
    // ------------------------------------------------------------------

    @Test
    void postRejectsWhenChequesDoNotSumToContractValue() {
        UUID leaseId = draft();
        List<ChequeDTO> grid = grid(leaseId);
        // Drop the admin-fee row: 51,000 of cheques against a 53,000 contract.
        UUID droppedId = grid.get(4).id();
        tx.executeWithoutResult(s -> chequeRepo.deleteById(droppedId));

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque grid totals 51,000.00 but contract value is 53,000.00");

        assertThat(journalEntryRows()).isZero();
        assertThat(reread(leaseId).getStatus()).isEqualTo(LeaseStatus.DRAFT);
        tx.executeWithoutResult(s -> assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId))
                .allMatch(c -> c.getStatus() == ChequeStatus.DRAFT && c.getPdrJournalId() == null));
    }

    /**
     * Every unmapped role at once, not one refusal per attempt: an accountant
     * opening the property's Accounts tab wants the whole list.
     */
    @Test
    void postRejectsUnmappedRolesListingAllOfThem() {
        UUID leaseId = readyToPost();
        unmap(AccountRole.PDC_RECEIVABLE, AccountRole.RENT_RECEIVABLE);

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(UnmappedAccountRoleException.class)
                .hasMessageContaining("PDC_RECEIVABLE")
                .hasMessageContaining("RENT_RECEIVABLE");

        assertThat(journalEntryRows()).isZero();
        assertThat(reread(leaseId).getStatus()).isEqualTo(LeaseStatus.DRAFT);
    }

    /**
     * BANK is not required by the posting — no PDR touches it. It is required only
     * when a cheque row names no account of its own, because then there is nowhere
     * for that row's funds to land when it clears and the row would strand.
     */
    @Test
    void bankIsRequiredOnlyWhenAChequeRowNamesNoAccountOfItsOwn() {
        // Grid cut while BANK was mapped: every row carries the leaf, so unmapping
        // the role afterwards changes nothing about this posting.
        UUID withAccounts = readyToPost();
        unmap(AccountRole.BANK);
        assertThat(posting.dryRun(withAccounts).ok()).isTrue();
        assertThat(posting.post(withAccounts).tcoEntryNumber()).isEqualTo("TCO-26/1");

        // A grid cut with no BANK mapping has no debit account on any row, and that
        // is the case the guard exists for.
        Unit otherUnit = fixtures.createUnit(fixtures.property(), "102");
        UUID stranded = draft(otherUnit, List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")));
        grid(stranded);
        tx.executeWithoutResult(s -> assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(stranded))
                .allMatch(c -> c.getDebitAccount() == null));

        assertThatThrownBy(() -> posting.post(stranded))
                .isInstanceOf(UnmappedAccountRoleException.class)
                .hasMessageContaining("BANK");
    }

    /**
     * A line that names its credit account outright does not need its charge type's
     * role mapped: the TCO credits the account by id and the role is never read.
     * Requiring it anyway refused perfectly good contracts over a mapping the
     * posting does not touch.
     */
    @Test
    void anExplicitCreditAccountDoesNotRequireItsRoleToBeMapped() {
        Account adminFee = leaf(AccountRole.ADMIN_FEE);
        unmap(AccountRole.ADMIN_FEE);
        UUID leaseId = draft(fixtures.unit(), List.of(
                line("RENT", "51000"),
                lineCreditedTo("ADMIN_FEE", "2000", adminFee.getId())));
        grid(leaseId);

        PostLeaseResponse r = posting.post(leaseId);

        assertThat(linesOf(r.tcoJournalId()).get(3).getAccountId()).isEqualTo(adminFee.getId());
    }

    /**
     * A line the resolver could not map when the draft was saved. The line-level
     * message wins over the role-level one: the gap is the same gap, but the line
     * is where the accountant can see it.
     */
    @Test
    void postRejectsLineWithoutCreditAccount() {
        unmap(AccountRole.ADMIN_FEE);
        UUID leaseId = readyToPost();

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Line 2 (ADMIN_FEE) has no credit account");

        assertThat(journalEntryRows()).isZero();
    }

    /**
     * A leaf retired between drafting the lease and posting it. The line's credit
     * account was an active leaf of the right type when it was entered — a chart of
     * accounts is edited, so it is re-checked here rather than trusted.
     */
    @Test
    void postRejectsALineWhoseCreditAccountHasBeenDeactivated() {
        UUID leaseId = readyToPost();
        Account adminFee = leaf(AccountRole.ADMIN_FEE);
        tx.executeWithoutResult(s -> {
            Account a = accountRepo.findById(adminFee.getId()).orElseThrow();
            a.setActive(false);
            accountRepo.save(a);
        });

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Line 2 (ADMIN_FEE): credit account " + adminFee.getCode() + " is inactive");

        assertThat(journalEntryRows()).isZero();
    }

    @Test
    void postingIntoLockedPeriodIsRejected() {
        UUID leaseId = readyToPost();
        fiscal.lockThrough(LocalDate.of(2026, 9, 30));

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books are locked through 2026-09-30");

        // All or nothing: no TCO, no PDRs, and the lease is still a draft.
        assertThat(journalEntryRows()).isZero();
        assertThat(reread(leaseId).getStatus()).isEqualTo(LeaseStatus.DRAFT);
        tx.executeWithoutResult(s -> assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId))
                .allMatch(c -> c.getStatus() == ChequeStatus.DRAFT));
    }

    @Test
    void aPostedLeaseCannotBePostedTwice() {
        UUID leaseId = readyToPost();
        posting.post(leaseId);

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only a DRAFT or PENDING_SIGNATURE lease can be posted");
    }

    /**
     * Each PDR files on <em>its own</em> row's posting date, not on the contract's.
     *
     * <p>They coincide for a grid cut with the contract, which is why the happy path
     * cannot tell the two rules apart. A row taken later — a deposit the renter
     * brought in a week after signing — has to file in the period it was actually
     * received, or the month's PDC receivable balance is money the landlord was not
     * yet holding.</p>
     */
    @Test
    void eachPdrFilesOnItsOwnRowsPostingDate() {
        UUID leaseId = draft();
        List<ChequeDTO> generated = grid(leaseId);
        LocalDate late = LocalDate.of(2026, 9, 24);

        // Re-save the grid with row 5 (the admin fee) posting a week later.
        List<ChequeRowInput> rows = new ArrayList<>();
        for (int i = 0; i < generated.size(); i++) {
            ChequeDTO r = generated.get(i);
            LocalDate postingDate = i == 4 ? late : r.postingDate();
            rows.add(new ChequeRowInput(r.id(), null, postingDate, r.chequeNumber(), r.chequeDate(),
                    r.payeeBank(), r.payerName(), r.debitAccountId(), r.amount(), r.narration(), r.mode()));
        }
        cheques.saveRows(leaseId, rows);

        PostLeaseResponse r = posting.post(leaseId);

        assertThat(r.cheques()).hasSize(5);
        for (ChequeDTO c : r.cheques()) {
            JournalEntry pdr = tx.execute(s -> entries.findById(c.pdrJournalId()).orElseThrow());
            assertThat(pdr.getEntryDate()).as("PDR for row " + c.seqNo()).isEqualTo(c.postingDate());
        }
        // Four on the contract date, one on its own — which is the distinction a
        // `lease.getContractDate()` shortcut would erase.
        assertThat(r.cheques()).extracting(ChequeDTO::postingDate)
                .containsExactly(CONTRACT_DATE, CONTRACT_DATE, CONTRACT_DATE, CONTRACT_DATE, late);
        JournalEntry lastPdr = tx.execute(s -> entries.findById(r.cheques().get(4).pdrJournalId()).orElseThrow());
        assertThat(lastPdr.getEntryDate()).isEqualTo(late);
        assertThat(lastPdr.getEntryDate()).isNotEqualTo(CONTRACT_DATE);
    }

    /**
     * A failure partway through writing the grid leaves nothing at all.
     *
     * <p>The lock is set so the contract date is open and only the last cheque's
     * posting date falls inside it, which is the nastiest shape: the TCO is
     * perfectly postable and four PDRs would have gone in before the fifth was
     * refused. Everything rolls back together, so the guard's value is that the
     * refusal arrives before any of it rather than after most of it.</p>
     */
    @Test
    void aLockedPeriodOnOneLateChequeRollsTheWholePostBack() {
        UUID leaseId = draft();
        List<ChequeDTO> generated = grid(leaseId);
        LocalDate late = LocalDate.of(2026, 8, 20);

        List<ChequeRowInput> rows = new ArrayList<>();
        for (int i = 0; i < generated.size(); i++) {
            ChequeDTO r = generated.get(i);
            LocalDate postingDate = i == 4 ? late : r.postingDate();
            rows.add(new ChequeRowInput(r.id(), null, postingDate, r.chequeNumber(), r.chequeDate(),
                    r.payeeBank(), r.payerName(), r.debitAccountId(), r.amount(), r.narration(), r.mode()));
        }
        cheques.saveRows(leaseId, rows);

        // 2026-08-31 leaves the contract date (16 Sep) open and closes only the
        // 20 Aug row.
        fiscal.lockThrough(LocalDate.of(2026, 8, 31));

        PostLeaseDryRunResponse dry = posting.dryRun(leaseId);
        assertThat(dry.ok()).isFalse();
        assertThat(dry.errors()).hasSize(1);
        String fifth = generated.get(4).chequeNumber();
        assertThat(dry.errors().get(0)).isEqualTo(
                "Cheque " + fifth + " cannot post on 2026-08-20: books are locked through 2026-08-31.");

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque " + fifth + " cannot post on 2026-08-20")
                .hasMessageContaining("books are locked through 2026-08-31");

        // Nothing at all: no TCO, no four-fifths of a grid, no claimed unit.
        assertThat(journalEntryRows()).isZero();
        Lease lease = reread(leaseId);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(lease.getPostingJournalId()).isNull();
        assertThat(lease.getPostedAt()).isNull();
        tx.executeWithoutResult(s -> assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId))
                .allMatch(c -> c.getStatus() == ChequeStatus.DRAFT && c.getPdrJournalId() == null));
        Unit unit = tx.execute(s -> unitRepo.findById(fixtures.unit().getId()).orElseThrow());
        assertThat(unit.getStatus()).isNotEqualTo(UnitStatus.OCCUPIED);
        assertThat(unit.getCurrentTenantName()).isNull();
    }

    /**
     * A zero-amount row cannot reach the posting guard at all, and this is what
     * stops it: {@code ck_cheques_amount_positive} (changeset 83) refuses the row at
     * the database. The service-level check in {@code validate} is therefore
     * defence-in-depth rather than the barrier — it exists so that a future writer
     * building cheques in memory gets a sentence in the dry run instead of
     * {@code PostingService}'s "Line amounts must be positive" halfway through the
     * grid, and this test records why it is not otherwise exercised.
     */
    @Test
    void aZeroAmountChequeRowCannotExistInTheFirstPlace() {
        UUID leaseId = readyToPost();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Cheque c = chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).get(4);
            c.setAmount(BigDecimal.ZERO);
            chequeRepo.saveAndFlush(c);
        })).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cheques_amount_positive");

        // The grid editor refuses it with a sentence long before that.
        List<ChequeDTO> grid = cheques.list(leaseId);
        ChequeDTO first = grid.get(0);
        List<ChequeRowInput> zeroed = List.of(new ChequeRowInput(first.id(), null, first.postingDate(),
                first.chequeNumber(), first.chequeDate(), first.payeeBank(), first.payerName(),
                first.debitAccountId(), BigDecimal.ZERO, first.narration(), first.mode()));
        assertThatThrownBy(() -> cheques.saveRows(leaseId, zeroed))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("greater than zero");
    }

    // ------------------------------------------------------------------
    // the lease's own receivable account
    // ------------------------------------------------------------------

    /**
     * A lease may name its own receivable (spec §6.3). Both halves of the contract
     * have to land on it — the TCO's debits and every PDR's credit — or the two sit
     * in different ledgers and neither nets to zero.
     */
    @Test
    void theLeasesOwnReceivableAccountCarriesBothHalvesOfTheContract() {
        UUID leaseId = readyToPost();
        Account ownReceivable = tx.execute(s -> accountService.createLeaf(
                "Rent Receivable - Galah 2", accountService.getAccountByCode("A-02-01"), fixtures.property().getId()));
        setReceivableOverride(leaseId, ownReceivable.getId());
        Account propertyReceivable = leaf(AccountRole.RENT_RECEIVABLE);

        PostLeaseResponse r = posting.post(leaseId);

        List<JournalLine> tcoLines = linesOf(r.tcoJournalId());
        assertThat(tcoLines.get(0).getAccountId()).isEqualTo(ownReceivable.getId());
        assertThat(tcoLines.get(2).getAccountId()).isEqualTo(ownReceivable.getId());
        for (ChequeDTO c : r.cheques()) {
            List<JournalLine> pdrLines = linesOf(c.pdrJournalId());
            assertThat(pdrLines.get(1).getAccountId()).isEqualTo(ownReceivable.getId());
        }

        // It nets to zero, and the property's own leaf was never touched.
        tx.executeWithoutResult(s -> {
            assertThat(ledger.accountLedger(ownReceivable.getId(),
                    new LedgerFilter(null, null, null, null, leaseId, null)).closingBalance())
                    .isEqualByComparingTo("0");
            assertThat(ledger.accountLedger(propertyReceivable.getId(),
                    new LedgerFilter(null, null, null, null, leaseId, null)).rows()).isEmpty();
        });
    }

    /** The override is re-checked at posting time, exactly as a line's credit account is. */
    @Test
    void anUnusableReceivableOverrideIsRefused() {
        UUID groupLease = readyToPost();
        Account group = tx.execute(s -> accountService.getAccountByCode("A-02-01"));
        setReceivableOverride(groupLease, group.getId());

        assertThatThrownBy(() -> posting.post(groupLease))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("The lease's receivable account A-02-01 is a group account");

        // An income leaf balances just as happily and books the contract backwards,
        // which is why the type is checked and not only the leaf-ness.
        Account income = leaf(AccountRole.ADMIN_FEE);
        setReceivableOverride(groupLease, income.getId());
        assertThatThrownBy(() -> posting.post(groupLease))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must be an ASSET account");

        assertThat(journalEntryRows()).isZero();
    }

    /**
     * An id belonging to another tenant is "does not exist", because the lookup goes
     * through a tenant-filtered query. Handing it to {@code PostingService}, which
     * resolves accounts by id, would have raised this lease's receivable against
     * somebody else's books.
     */
    @Test
    void aReceivableOverrideFromAnotherTenantIsRefused() {
        UUID leaseId = readyToPost();
        UUID ourTenant = fixtures.tenantId();

        // A whole second tenant with its own chart, then back to ours.
        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        UUID strangersLeaf = tx.execute(s -> resolver.resolve(AccountRole.RENT_RECEIVABLE, other.property().getId())).getId();
        TenantContextHolder.setTenantId(ourTenant);
        fixtures.asTenantAdmin();

        setReceivableOverride(leaseId, strangersLeaf);

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("The lease's receivable account " + strangersLeaf + " does not exist");
        assertThat(journalEntryRows()).isZero();
    }

    private void setReceivableOverride(UUID leaseId, UUID accountId) {
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            lease.setReceivableAccountId(accountId);
            leaseRepo.save(lease);
        });
    }

    // ------------------------------------------------------------------
    // dry run
    // ------------------------------------------------------------------

    /**
     * The review step before the button. Every problem at once, HTTP 200, and — the
     * part that matters — not one row written, not one entry number consumed.
     */
    @Test
    void dryRunListsEveryProblemAndWritesNothing() {
        unmap(AccountRole.ADMIN_FEE);
        UUID leaseId = readyToPost();
        unmap(AccountRole.PDC_RECEIVABLE);
        UUID droppedId = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).get(4).getId());
        tx.executeWithoutResult(s -> chequeRepo.deleteById(droppedId));

        PostLeaseDryRunResponse dry = posting.dryRun(leaseId);

        assertThat(dry.ok()).isFalse();
        assertThat(dry.errors()).hasSize(3);
        assertThat(dry.errors().get(0)).isEqualTo("Line 2 (ADMIN_FEE) has no credit account.");
        assertThat(dry.errors().get(1)).contains("ADMIN_FEE").contains("PDC_RECEIVABLE");
        assertThat(dry.errors().get(2)).isEqualTo("Cheque grid totals 51,000.00 but contract value is 53,000.00.");
        assertThat(dry.contractValue()).isEqualByComparingTo("53000");
        assertThat(dry.contractValueInclVat()).isEqualByComparingTo("53000");
        assertThat(dry.chequeTotal()).isEqualByComparingTo("51000");
        // One pair survived — the rent line — so one TCO of two lines, and four PDRs.
        assertThat(dry.journals().tco()).isEqualTo(1);
        assertThat(dry.journals().tcoLines()).isEqualTo(2);
        assertThat(dry.journals().pdr()).isEqualTo(4);

        assertThat(journalEntryRows()).isZero();
        assertThat(reread(leaseId).getStatus()).isEqualTo(LeaseStatus.DRAFT);
        tx.executeWithoutResult(s -> assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId))
                .allMatch(c -> c.getStatus() == ChequeStatus.DRAFT && c.getPdrJournalId() == null));
    }

    /** A lease that would post cleanly says so, and still writes nothing. */
    @Test
    void dryRunOnASoundLeaseIsOkAndStillWritesNothing() {
        UUID leaseId = readyToPost();

        PostLeaseDryRunResponse dry = posting.dryRun(leaseId);

        assertThat(dry.ok()).isTrue();
        assertThat(dry.errors()).isEmpty();
        assertThat(dry.journals()).isEqualTo(new PostLeaseDryRunResponse.JournalPlan(1, 4, 5));
        assertThat(journalEntryRows()).isZero();

        // And the post that follows it produces exactly what was promised.
        PostLeaseResponse r = posting.post(leaseId);
        assertThat(linesOf(r.tcoJournalId())).hasSize(4);
        assertThat(r.cheques()).hasSize(5);
    }

    // ------------------------------------------------------------------
    // amend
    // ------------------------------------------------------------------

    @Test
    void amendLinesReversesAndRepostsWhenAllChequesRegistered() {
        UUID leaseId = readyToPost();
        PostLeaseResponse first = posting.post(leaseId);
        UUID originalTcoId = first.tcoJournalId();
        List<UUID> originalPdrIds = first.cheques().stream().map(ChequeDTO::pdrJournalId).toList();

        // A second income leaf under the same group: the same money, credited
        // somewhere else. Contract value is unchanged, so the grid still matches.
        Account otherIncome = tx.execute(s -> accountService.createLeaf(
                "Admin Fee (reclassified)", accountService.getAccountByCode("C-01-01"), fixtures.property().getId()));

        PostLeaseResponse amended = posting.amendLines(leaseId, List.of(
                line("RENT", "51000"),
                lineCreditedTo("ADMIN_FEE", "2000", otherIncome.getId())), "Fee reclassified");

        // The original is reversed, not edited.
        JournalEntry original = tx.execute(s -> entries.findById(originalTcoId).orElseThrow());
        assertThat(original.getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(original.getReversedById()).isNotNull();
        JournalEntry tcr = tx.execute(s -> entries.findById(original.getReversedById()).orElseThrow());
        assertThat(tcr.getDocType()).isEqualTo(JournalDocType.TCR);
        assertThat(tcr.getEntryNumber()).isEqualTo("TCR-26/1");

        // And a fresh TCO carries the contract date, not today's.
        assertThat(amended.tcoJournalId()).isNotEqualTo(originalTcoId);
        JournalEntry reposted = tx.execute(s -> entries.findById(amended.tcoJournalId()).orElseThrow());
        assertThat(reposted.getDocType()).isEqualTo(JournalDocType.TCO);
        assertThat(reposted.getEntryDate()).isEqualTo(CONTRACT_DATE);
        assertThat(linesOf(reposted.getId()).get(3).getAccountId()).isEqualTo(otherIncome.getId());
        assertThat(reread(leaseId).getPostingJournalId()).isEqualTo(amended.tcoJournalId());

        // The cheques are untouched: same rows, same PDRs, still REGISTERED.
        assertThat(amended.cheques()).extracting(ChequeDTO::pdrJournalId)
                .containsExactlyElementsOf(originalPdrIds);
        assertThat(amended.cheques()).allMatch(c -> c.status() == ChequeStatus.REGISTERED);

        // Reversal plus repost leaves the receivable where it was.
        tx.executeWithoutResult(s -> assertThat(ledger.accountLedger(leaf(AccountRole.RENT_RECEIVABLE).getId(),
                new LedgerFilter(null, null, null, null, leaseId, null)).closingBalance())
                .isEqualByComparingTo("0"));
    }

    /**
     * The Σ-cheques guard is checked before the reversal is written. It has to be:
     * the transaction would roll an early reversal back anyway, but it would have
     * consumed a TCR number for a correction that never happened.
     */
    @Test
    void amendLinesThatNoLongerMatchTheChequeGridAreRefusedBeforeAnythingIsReversed() {
        UUID leaseId = readyToPost();
        PostLeaseResponse first = posting.post(leaseId);

        assertThatThrownBy(() -> posting.amendLines(leaseId, List.of(
                line("RENT", "51000"),
                line("ADMIN_FEE", "1000")), "Fee halved"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque grid totals 53,000.00 but contract value is 52,000.00");

        // Nothing moved: the original TCO is still the posted one, no TCR exists,
        // and the lines are the ones that were posted.
        JournalEntry original = tx.execute(s -> entries.findById(first.tcoJournalId()).orElseThrow());
        assertThat(original.getStatus()).isEqualTo(JournalStatus.POSTED);
        assertThat(reread(leaseId).getPostingJournalId()).isEqualTo(first.tcoJournalId());
        BigDecimal stillCharged = tx.execute(s -> leaseService.contractValue(leaseId));
        assertThat(stillCharged).isEqualByComparingTo("53000");
        Long tcrs = jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = 'TCR'",
                Long.class, fixtures.tenantId());
        assertThat(tcrs).isZero();
    }

    @Test
    void amendLinesRejectedOnceAChequeIsDeposited() {
        UUID leaseId = draft();
        grid(leaseId);
        cheques.generateNumbers(leaseId, "100040");
        PostLeaseResponse first = posting.post(leaseId);

        UUID depositedId = first.cheques().get(1).id();
        tx.executeWithoutResult(s -> {
            Cheque c = chequeRepo.findById(depositedId).orElseThrow();
            c.setStatus(ChequeStatus.DEPOSITED);
            chequeRepo.save(c);
        });

        assertThatThrownBy(() -> posting.amendLines(leaseId,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), "No change"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque 100041 is DEPOSITED; amend is only possible while all cheques are REGISTERED");

        JournalEntry original = tx.execute(s -> entries.findById(first.tcoJournalId()).orElseThrow());
        assertThat(original.getStatus()).isEqualTo(JournalStatus.POSTED);
    }

    @Test
    void amendLinesRefusesALeaseThatWasNeverPosted() {
        UUID leaseId = readyToPost();

        assertThatThrownBy(() -> posting.amendLines(leaseId, List.of(line("RENT", "53000")), "nope"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only an ACTIVE lease can have its lines amended");
    }

    // ------------------------------------------------------------------
    // renewal
    // ------------------------------------------------------------------

    /**
     * The successor's post retires the predecessor. The unit is never vacated in
     * between: the renter has not moved out, and a moment of VACANT is a moment the
     * unit is lettable to somebody else (spec §6.6).
     */
    @Test
    void postingARenewalRetiresThePredecessorAndKeepsTheUnitOccupied() {
        // Both drafts are cut while the unit is still vacant — createDraftLease
        // refuses an occupied one, and after the first post it is occupied.
        UUID firstLeaseId = readyToPost();
        UUID renewalId = draft();
        grid(renewalId);

        posting.post(firstLeaseId);
        tx.executeWithoutResult(s -> {
            Lease renewal = leaseRepo.findById(renewalId).orElseThrow();
            renewal.setRenewedFromLeaseId(firstLeaseId);
            leaseRepo.save(renewal);
        });

        posting.post(renewalId);

        assertThat(reread(firstLeaseId).getStatus()).isEqualTo(LeaseStatus.RENEWED);
        assertThat(reread(renewalId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        Unit unit = tx.execute(s -> unitRepo.findById(fixtures.unit().getId()).orElseThrow());
        assertThat(unit.getStatus()).isEqualTo(UnitStatus.OCCUPIED);
    }

    // ------------------------------------------------------------------
    // concurrency
    // ------------------------------------------------------------------

    /**
     * Two accountants hitting Post on the same contract at the same moment.
     *
     * <p>Without the row lock both read DRAFT, both write a TCO and five PDRs and
     * both claim the unit; the lease's {@code @Version} fails the second commit, so
     * the books survive — but the loser gets an
     * {@code ObjectOptimisticLockingFailureException} (a 500, and an entry number
     * burnt on journals that were rolled back) instead of the sentence asserted
     * here. Locking first is what makes the loser a clean refusal that never
     * touched the ledger.</p>
     */
    @Test
    void concurrentPostsProduceExactlyOneTco() throws Exception {
        UUID leaseId = readyToPost();
        UUID tenant = fixtures.tenantId();

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            racers.add(() -> {
                TenantContextHolder.setTenantId(tenant);
                LeaseTestFixtures.authenticateAsTenantAdmin();
                try {
                    bothReady.await(10, TimeUnit.SECONDS);
                    return posting.post(leaseId);
                } catch (RuntimeException ex) {
                    return ex;
                } finally {
                    TenantContextHolder.clear();
                    LeaseTestFixtures.clearAuth();
                }
            });
        }
        List<Future<Object>> results;
        try {
            results = pool.invokeAll(racers, 60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Object> outcomes = new ArrayList<>();
        for (Future<Object> f : results) outcomes.add(f.get());
        List<Object> winners = outcomes.stream().filter(PostLeaseResponse.class::isInstance).toList();
        List<Object> losers = outcomes.stream().filter(o -> !(o instanceof PostLeaseResponse)).toList();

        assertThat(winners).hasSize(1);
        assertThat(losers).hasSize(1);
        assertThat(losers.get(0)).isInstanceOf(BusinessRuleViolationException.class);

        Long tcos = jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = 'TCO' and source_id = ?",
                Long.class, tenant, leaseId);
        assertThat(tcos).isEqualTo(1L);
        Long pdrs = jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = 'PDR' and lease_id = ?",
                Long.class, tenant, leaseId);
        assertThat(pdrs).isEqualTo(5L);
        assertThat(reread(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }

    // ------------------------------------------------------------------
    // #80: a post-dated cheque needs its number
    // ------------------------------------------------------------------

    /**
     * The generator writes PDC rows without numbers; posting them would register
     * paper that cannot be matched at the bank, bounced by number or found again.
     * The review dialog (dry run) lists every such row and the post refuses.
     */
    @Test
    void aPdcWithoutANumberIsRefusedByTheDryRunAndThePost() {
        UUID leaseId = draft();
        cheques.generate(leaseId, new GenerateChequesRequest(4, START, null, "Emirates NBD", null, false, null));

        PostLeaseDryRunResponse dry = posting.dryRun(leaseId);
        assertThat(dry.ok()).isFalse();
        assertThat(dry.errors()).contains(
                "Cheque #3 has no number; a post-dated cheque needs its number before the lease is posted.");

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque #1 has no number");
        assertThat(journalEntryRows()).isZero();
        assertThat(reread(leaseId).getStatus()).isEqualTo(LeaseStatus.DRAFT);
    }

    /** Cash and transfer receipts have no cheque number by nature and are not asked for one. */
    @Test
    void cashAndTransferRowsNeedNoNumber() {
        UUID leaseId = draft();
        List<ChequeDTO> generated = cheques.generate(leaseId,
                new GenerateChequesRequest(4, START, null, "Emirates NBD", null, false, null));
        List<ChequeRowInput> rows = new ArrayList<>();
        for (int i = 0; i < generated.size(); i++) {
            ChequeDTO r = generated.get(i);
            ChequeMode mode = i % 2 == 0 ? ChequeMode.CASH : ChequeMode.TRANSFER;
            rows.add(new ChequeRowInput(r.id(), null, r.postingDate(), null, r.chequeDate(),
                    null, r.payerName(), r.debitAccountId(), r.amount(), r.narration(), mode));
        }
        cheques.saveRows(leaseId, rows);

        PostLeaseDryRunResponse dry = posting.dryRun(leaseId);
        assertThat(dry.errors()).noneMatch(e -> e.contains("has no number"));
        assertThat(dry.ok()).as(String.join(" | ", dry.errors())).isTrue();
        posting.post(leaseId);
        assertThat(reread(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }

    /**
     * The import doors are exempt (see LeasePostingService.Preconditions): a
     * portfolio import posts an unnumbered PDC grid, and the numbers are filled in
     * afterwards on the REGISTERED rows.
     */
    @Test
    void thePortfolioImportDoorPostsAnUnnumberedGrid() {
        UUID leaseId = draft();
        cheques.generate(leaseId, new GenerateChequesRequest(4, START, null, "Emirates NBD", null, false, null));

        posting.postForPortfolioImport(leaseId);

        assertThat(reread(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }
}
