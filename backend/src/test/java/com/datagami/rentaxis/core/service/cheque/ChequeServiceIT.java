package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.DepositBatchRequest;
import com.datagami.rentaxis.api.dto.cheque.ReplaceChequeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cheque register's lifecycle, one test per row of the spec §7.2 table,
 * against a real database.
 *
 * <p>Nothing here can be usefully mocked. Every assertion is about what is in the
 * ledger afterwards — which accounts, which counter-accounts, which date, which
 * dimensions — and about all-or-nothing behaviour that only a real transaction
 * exhibits. The figures are PACT's Galah 2 shape: 51,000 of rent over four
 * cheques of 12,750 plus a 2,000 admin fee, numbered 100040 upwards.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} only enables the Hibernate tenant
 * filter inside one, so every read-back goes through {@link #tx}.</p>
 */
@SpringBootTest
class ChequeServiceIT extends AbstractPostgresIT {

    @Autowired ChequeService service;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired ChequeRepository chequeRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository lines;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final LocalDate DEPOSIT_DATE = LocalDate.of(2026, 10, 5);
    private static final LocalDate CLEAR_DATE = LocalDate.of(2026, 10, 8);
    /**
     * A day after the last row of {@link #posted()}'s grid falls due (2027-07-02).
     * A test that banks the whole grid in one call needs a date on which every row
     * is payable, because a post-dated cheque may not be presented before its date.
     */
    private static final LocalDate WHOLE_GRID_DEPOSIT_DATE = LocalDate.of(2027, 7, 5);
    private static final LocalDate WHOLE_GRID_CLEAR_DATE = LocalDate.of(2027, 7, 8);
    private static final LocalDate BOUNCE_DATE = LocalDate.of(2026, 10, 12);
    private static final LocalDate REPLACE_DATE = LocalDate.of(2026, 10, 15);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** 53,000 of contract over five numbered instruments, on the books. */
    private PostLeaseResponse posted() {
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, "100040");
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    /** A second bank leaf, so "the account actually debited" can differ from the role's. */
    private Account otherBank() {
        return tx.execute(s -> accountService.createLeaf(
                "Mashreq - collections", accountService.getAccountByCode("A-02-02"), fixtures.property().getId()));
    }

    private JournalEntry entry(UUID entryId) {
        return tx.execute(s -> entries.findById(entryId).orElseThrow());
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> lines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    private Cheque reread(UUID chequeId) {
        return tx.execute(s -> chequeRepo.findById(chequeId).orElseThrow());
    }

    /** The lease's own slice of an account's ledger: debit minus credit. */
    private BigDecimal balance(Account account, UUID leaseId) {
        return tx.execute(s -> ledger.accountLedger(account.getId(),
                new LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private long entryCount(JournalDocType docType, UUID chequeId) {
        return jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = ? and source_id = ?",
                Long.class, fixtures.tenantId(), docType.name(), chequeId);
    }

    private long journalEntryRows() {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?",
                Long.class, fixtures.tenantId());
    }

    /**
     * One two-line entry, asserted whole: the document it is, the day it files
     * under, both accounts and the counter-account each faces, the money, and every
     * dimension the register needs to find it again.
     */
    private void assertPair(UUID entryId, JournalDocType docType, LocalDate entryDate, UUID chequeId,
                            UUID leaseId, Account debit, Account credit, String amount) {
        JournalEntry e = entry(entryId);
        assertThat(e.getDocType()).isEqualTo(docType);
        assertThat(e.getEntryDate()).isEqualTo(entryDate);
        assertThat(e.getSourceType()).isEqualTo(JournalSourceType.CHEQUE);
        assertThat(e.getSourceId()).isEqualTo(chequeId);

        List<JournalLine> l = linesOf(entryId);
        assertThat(l).hasSize(2);
        assertThat(l.get(0).getAccountId()).isEqualTo(debit.getId());
        assertThat(l.get(0).getDebit()).isEqualByComparingTo(amount);
        assertThat(l.get(0).getContraAccountId()).isEqualTo(credit.getId());
        assertThat(l.get(1).getAccountId()).isEqualTo(credit.getId());
        assertThat(l.get(1).getCredit()).isEqualByComparingTo(amount);
        assertThat(l.get(1).getContraAccountId()).isEqualTo(debit.getId());
        assertThat(l).allSatisfy(line -> {
            assertThat(line.getChequeId()).isEqualTo(chequeId);
            assertThat(line.getLeaseId()).isEqualTo(leaseId);
            assertThat(line.getPropertyId()).isEqualTo(fixtures.property().getId());
            assertThat(line.getUnitId()).isEqualTo(fixtures.unit().getId());
            assertThat(line.getRenterId()).isEqualTo(fixtures.renter().getId());
        });
    }

    private static ChequeRowInput row(String number, LocalDate postingDate, LocalDate chequeDate,
                                      String amount, ChequeMode mode) {
        return new ChequeRowInput(null, null, postingDate, number, chequeDate, "Emirates NBD",
                null, null, new BigDecimal(amount), "Replacement", mode);
    }

    // ------------------------------------------------------------------
    // DRAFT -> REGISTERED: a row added to a posted lease
    // ------------------------------------------------------------------

    @Test
    void aRowAddedToAPostedLeaseRegistersWithItsOwnPdr() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();

        ChequeDTO added = service.addRowToPostedLease(leaseId,
                row("100099", REPLACE_DATE, LocalDate.of(2026, 12, 1), "5000", ChequeMode.PDC));

        assertThat(added.status()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(added.seqNo()).isEqualTo(6);
        assertThat(added.pdrJournalId()).isNotNull();
        assertPair(added.pdrJournalId(), JournalDocType.PDR, REPLACE_DATE, added.id(), leaseId,
                leaf(AccountRole.PDC_RECEIVABLE), leaf(AccountRole.RENT_RECEIVABLE), "5000");
    }

    @Test
    void addingARowToADraftLeaseIsRefused() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END, List.of(line("RENT", "51000")));

        assertThatThrownBy(() -> service.addRowToPostedLease(leaseId,
                row("100099", REPLACE_DATE, LocalDate.of(2026, 12, 1), "5000", ChequeMode.PDC)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("use the cheque grid");
    }

    // ------------------------------------------------------------------
    // REGISTERED -> DEPOSITED: no journal
    // ------------------------------------------------------------------

    @Test
    void depositingARegisteredChequeWritesNoJournal() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        long before = journalEntryRows();

        ChequeDTO deposited = service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));

        assertThat(deposited.status()).isEqualTo(ChequeStatus.DEPOSITED);
        assertThat(deposited.depositedAt()).isEqualTo(DEPOSIT_DATE);
        assertThat(deposited.crtJournalId()).isNull();
        assertThat(journalEntryRows()).isEqualTo(before);
        assertThat(reread(chequeId).getStatusChangedAt()).isNotNull();
    }

    /**
     * The clue is in the name. A bank refuses a post-dated cheque presented before
     * its date, so a register that accepted one would carry cash in transit that
     * could not exist and a CRT dated before the instrument was payable.
     */
    @Test
    void aPostDatedChequeCannotBeBankedBeforeItsDate() {
        PostLeaseResponse r = posted();
        // Row 1 falls due a quarter after row 0, so DEPOSIT_DATE is early for it.
        ChequeDTO notYetDue = r.cheques().get(1);

        assertThatThrownBy(() -> service.deposit(notYetDue.id(), ChequeActionRequest.on(DEPOSIT_DATE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("may not be presented before its date");

        assertThat(reread(notYetDue.id()).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(reread(notYetDue.id()).getDepositedAt()).isNull();
    }

    /**
     * One date covers the whole selection, so a clerk banking October's pile can
     * sweep up a January cheque without noticing. The run names the row and banks
     * nothing.
     */
    @Test
    void depositBatchRefusesARowThatIsNotYetPayableAndBanksNothing() {
        PostLeaseResponse r = posted();
        List<UUID> ids = r.cheques().stream().map(ChequeDTO::id).toList();

        assertThatThrownBy(() -> service.depositBatch(new DepositBatchRequest(ids, DEPOSIT_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("may not be presented before its date");

        for (UUID id : ids) {
            assertThat(reread(id).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
            assertThat(reread(id).getDepositedAt()).isNull();
        }
    }

    /** A tenancy that started eight months ago: three rent cheques already payable, one still to come. */
    private PostLeaseResponse postedInThePast() {
        LocalDate start = LocalDate.now().minusMonths(8).withDayOfMonth(1);
        return fixtures.postedLease(start.minusDays(10), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "48000")), 4, "200100");
    }

    /**
     * #10: a run entered after the fact banks each cheque on its own date, rather
     * than stamping one date on October's and November's paper alike.
     */
    @Test
    void depositBatchCanUseEachChequesOwnDate() {
        PostLeaseResponse r = postedInThePast();
        List<ChequeDTO> payable = r.cheques().stream()
                .filter(c -> !c.chequeDate().isAfter(LocalDate.now())).toList();
        assertThat(payable).hasSizeGreaterThanOrEqualTo(2);

        List<ChequeDTO> deposited = service.depositBatch(new DepositBatchRequest(
                payable.stream().map(ChequeDTO::id).toList(), LocalDate.now(), null, true));

        assertThat(deposited).hasSize(payable.size());
        for (ChequeDTO c : payable) {
            assertThat(reread(c.id()).getDepositedAt()).isEqualTo(c.chequeDate());
            assertThat(reread(c.id()).getStatus()).isEqualTo(ChequeStatus.DEPOSITED);
        }
    }

    /** Its own date has not arrived: it cannot have been banked on it, and nothing in the run is. */
    @Test
    void depositBatchOnOwnDatesRefusesARowDatedInTheFuture() {
        PostLeaseResponse r = postedInThePast();
        List<UUID> ids = r.cheques().stream().map(ChequeDTO::id).toList();
        assertThat(r.cheques()).anySatisfy(c -> assertThat(c.chequeDate()).isAfter(LocalDate.now()));

        assertThatThrownBy(() -> service.depositBatch(new DepositBatchRequest(ids, LocalDate.now(), null, true)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("has not arrived yet");

        for (UUID id : ids) {
            assertThat(reread(id).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        }
    }

    /** Banking on the day the cheque falls due is the ordinary case, not an edge. */
    @Test
    void aChequeCanBeBankedOnItsOwnDate() {
        PostLeaseResponse r = posted();
        Cheque first = reread(r.cheques().get(0).id());

        ChequeDTO deposited = service.deposit(first.getId(),
                ChequeActionRequest.on(first.getChequeDate()));

        assertThat(deposited.status()).isEqualTo(ChequeStatus.DEPOSITED);
        assertThat(deposited.depositedAt()).isEqualTo(first.getChequeDate());
    }

    @Test
    void depositBatchBanksEveryTickedRowAgainstTheChosenBank() {
        PostLeaseResponse r = posted();
        Account mashreq = otherBank();
        List<UUID> ids = r.cheques().stream().map(ChequeDTO::id).toList();

        List<ChequeDTO> deposited = service.depositBatch(
                new DepositBatchRequest(ids, WHOLE_GRID_DEPOSIT_DATE, mashreq.getId()));

        assertThat(deposited).hasSize(5);
        assertThat(deposited).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo(ChequeStatus.DEPOSITED);
            assertThat(c.depositedAt()).isEqualTo(WHOLE_GRID_DEPOSIT_DATE);
            assertThat(c.debitAccountId()).isEqualTo(mashreq.getId());
        });
    }

    /**
     * The clerk is holding a physical pile. A run that banked four of five rows and
     * refused the fifth would leave a deposit slip the register cannot explain, so
     * one bad row refuses the whole call and names itself.
     */
    @Test
    void depositBatchIsAllOrNothing() {
        PostLeaseResponse r = posted();
        List<UUID> ids = r.cheques().stream().map(ChequeDTO::id).toList();
        service.deposit(ids.get(1), ChequeActionRequest.on(WHOLE_GRID_DEPOSIT_DATE));

        assertThatThrownBy(() -> service.depositBatch(
                new DepositBatchRequest(ids, WHOLE_GRID_DEPOSIT_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("100041 is DEPOSITED")
                .hasMessageContaining("nothing was deposited");

        // The other four are untouched — not deposited, not dated.
        for (int i = 0; i < ids.size(); i++) {
            Cheque c = reread(ids.get(i));
            assertThat(c.getStatus()).isEqualTo(i == 1 ? ChequeStatus.DEPOSITED : ChequeStatus.REGISTERED);
            if (i != 1) assertThat(c.getDepositedAt()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // DEPOSITED -> CLEARED: CRT
    // ------------------------------------------------------------------

    @Test
    void clearingADepositedChequePostsCrtAgainstTheBank() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID chequeId = r.cheques().get(0).id();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));

        ChequeDTO cleared = service.clear(chequeId, ChequeActionRequest.on(CLEAR_DATE));

        assertThat(cleared.status()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(cleared.clearedAt()).isEqualTo(CLEAR_DATE);
        assertThat(cleared.crtJournalId()).isNotNull();
        assertPair(cleared.crtJournalId(), JournalDocType.CRT, CLEAR_DATE, chequeId, leaseId,
                leaf(AccountRole.BANK), leaf(AccountRole.PDC_RECEIVABLE), "12750");
    }

    /**
     * Clearing a REGISTERED cheque — the paper is still in the drawer, so there is
     * nothing for the bank to have confirmed.
     */
    @Test
    void illegalTransitionIs400() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();

        assertThatThrownBy(() -> service.clear(chequeId, ChequeActionRequest.on(CLEAR_DATE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Can only clear cheques in DEPOSITED (current: REGISTERED)");

        assertThat(entryCount(JournalDocType.CRT, chequeId)).isZero();
    }

    // ------------------------------------------------------------------
    // REGISTERED -> CLEARED: cash / transfer "Received"
    // ------------------------------------------------------------------

    @Test
    void receiveCashUsesCashRoleWhenNoDebitAccount() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        ChequeDTO cash = service.addRowToPostedLease(leaseId,
                row(null, REPLACE_DATE, REPLACE_DATE, "1500", ChequeMode.CASH));
        assertThat(cash.debitAccountId()).isNull();

        ChequeDTO received = service.receive(cash.id(), ChequeActionRequest.on(REPLACE_DATE));

        Account cashLeaf = leaf(AccountRole.CASH);
        assertThat(received.status()).isEqualTo(ChequeStatus.CLEARED);
        assertPair(received.crtJournalId(), JournalDocType.CRT, REPLACE_DATE, cash.id(), leaseId,
                cashLeaf, leaf(AccountRole.PDC_RECEIVABLE), "1500");
        // Remembered, so a later reversal knows which drawer the money came out of.
        assertThat(received.debitAccountId()).isEqualTo(cashLeaf.getId());
    }

    @Test
    void receivingATransferLandsInTheBank() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        ChequeDTO transfer = service.addRowToPostedLease(leaseId,
                row(null, REPLACE_DATE, REPLACE_DATE, "3000", ChequeMode.TRANSFER));

        ChequeDTO received = service.receive(transfer.id(), ChequeActionRequest.on(REPLACE_DATE));

        Account bank = leaf(AccountRole.BANK);
        assertThat(received.status()).isEqualTo(ChequeStatus.CLEARED);
        assertPair(received.crtJournalId(), JournalDocType.CRT, REPLACE_DATE, transfer.id(), leaseId,
                bank, leaf(AccountRole.PDC_RECEIVABLE), "3000");
        assertThat(received.debitAccountId()).isEqualTo(bank.getId());
    }

    @Test
    void receivingAPostDatedChequeIsRefused() {
        PostLeaseResponse r = posted();

        assertThatThrownBy(() -> service.receive(r.cheques().get(0).id(), ChequeActionRequest.on(CLEAR_DATE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only a CASH or TRANSFER receipt can be received directly");
    }

    /** Cash never went to a bank, so there is no deposit run to put it on. */
    @Test
    void depositingACashRowIsRefused() {
        PostLeaseResponse r = posted();
        ChequeDTO cash = service.addRowToPostedLease(r.lease().getId(),
                row(null, REPLACE_DATE, REPLACE_DATE, "1500", ChequeMode.CASH));

        assertThatThrownBy(() -> service.deposit(cash.id(), ChequeActionRequest.on(DEPOSIT_DATE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only a post-dated cheque can be deposited");

        assertThat(reread(cash.id()).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
    }

    /** Cash in the drawer does not un-arrive, and a settled transfer is reversed as its own receipt. */
    @Test
    void cashThatWasReceivedCannotLaterBounce() {
        PostLeaseResponse r = posted();
        ChequeDTO cash = service.addRowToPostedLease(r.lease().getId(),
                row(null, REPLACE_DATE, REPLACE_DATE, "1500", ChequeMode.CASH));
        service.receive(cash.id(), ChequeActionRequest.on(REPLACE_DATE));

        assertThatThrownBy(() -> service.bounce(cash.id(),
                new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only a post-dated cheque can bounce after it has cleared");

        assertThat(entryCount(JournalDocType.CBR, cash.id())).isZero();
    }

    // ------------------------------------------------------------------
    // where the money is allowed to land
    // ------------------------------------------------------------------

    /**
     * {@code debitAccountId} was a free hand into the chart of accounts. Debiting
     * the rent receivable this very cheque was raised against would double the
     * debt and show the money as still owed rather than collected.
     */
    @Test
    void aDebitAccountThatIsNotBankOrCashIsRefused() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        Account rentReceivable = leaf(AccountRole.RENT_RECEIVABLE);
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));

        assertThatThrownBy(() -> service.clear(chequeId,
                new ChequeActionRequest(CLEAR_DATE, null, null, rentReceivable.getId())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Debit account " + rentReceivable.getCode() + " must be a bank or cash account");

        Cheque unchanged = reread(chequeId);
        assertThat(unchanged.getStatus()).isEqualTo(ChequeStatus.DEPOSITED);
        assertThat(unchanged.getCrtJournalId()).isNull();
    }

    @Test
    void aSecondBankLeafIsAnAcceptableDebitAccount() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID chequeId = r.cheques().get(0).id();
        Account mashreq = otherBank();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));

        ChequeDTO cleared = service.clear(chequeId,
                new ChequeActionRequest(CLEAR_DATE, null, null, mashreq.getId()));

        assertPair(cleared.crtJournalId(), JournalDocType.CRT, CLEAR_DATE, chequeId, leaseId,
                mashreq, leaf(AccountRole.PDC_RECEIVABLE), "12750");
    }

    /**
     * Audit C-F1: a bank leaf of ANOTHER building of the same landlord. The receipt
     * would settle this lease's receivable while the money showed up in the other
     * building's bank, breaking both reconciliations. A tenant-level leaf (no
     * property) stays acceptable.
     */
    @Test
    void anotherBuildingsBankIsRefusedAndATenantLevelBankIsFine() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        com.datagami.rentaxis.domain.entity.Property elsewhere = fixtures.createProperty("ELSE");
        Account theirBank = tx.execute(s -> accountService.createLeaf(
                "Elsewhere - collections", accountService.getAccountByCode("A-02-02"), elsewhere.getId()));
        Account headOffice = tx.execute(s -> accountService.createLeaf(
                "Head office - collections", accountService.getAccountByCode("A-02-02"), null));

        assertThatThrownBy(() -> service.deposit(chequeId,
                new ChequeActionRequest(DEPOSIT_DATE, null, null, theirBank.getId())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not belong to this property");
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        assertThatThrownBy(() -> service.clear(chequeId,
                new ChequeActionRequest(CLEAR_DATE, null, null, theirBank.getId())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not belong to this property");
        assertThat(reread(chequeId).getCrtJournalId()).isNull();

        ChequeDTO cleared = service.clear(chequeId,
                new ChequeActionRequest(CLEAR_DATE, null, null, headOffice.getId()));
        assertThat(cleared.debitAccountId()).isEqualTo(headOffice.getId());
    }

    /** The same rule on the deposit run, where the account is only remembered. */
    @Test
    void depositBatchRefusesADebitAccountThatIsNotBankOrCash() {
        PostLeaseResponse r = posted();
        List<UUID> ids = r.cheques().stream().map(ChequeDTO::id).toList();
        Account rentReceivable = leaf(AccountRole.RENT_RECEIVABLE);

        assertThatThrownBy(() -> service.depositBatch(
                new DepositBatchRequest(ids, WHOLE_GRID_DEPOSIT_DATE, rentReceivable.getId())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must be a bank or cash account");

        assertThat(reread(ids.get(0)).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
    }

    // ------------------------------------------------------------------
    // -> BOUNCED: CBR
    // ------------------------------------------------------------------

    @Test
    void bouncingADepositedChequePostsCbrAgainstPdcReceivable() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID chequeId = r.cheques().get(0).id();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));

        ChequeDTO bounced = service.bounce(chequeId,
                new ChequeActionRequest(BOUNCE_DATE, "Returned unpaid", ChequeFailureReason.BOUNCE, null));

        assertThat(bounced.status()).isEqualTo(ChequeStatus.BOUNCED);
        assertThat(bounced.bouncedAt()).isEqualTo(BOUNCE_DATE);
        assertThat(bounced.failureReason()).isEqualTo(ChequeFailureReason.BOUNCE);
        assertPair(bounced.cbrJournalId(), JournalDocType.CBR, BOUNCE_DATE, chequeId, leaseId,
                leaf(AccountRole.RENT_RECEIVABLE), leaf(AccountRole.PDC_RECEIVABLE), "12750");

        // The debt is back on the renter, and only once.
        assertThat(balance(leaf(AccountRole.RENT_RECEIVABLE), leaseId)).isEqualByComparingTo("12750");
    }

    /**
     * The bank took the money back out. Crediting PDC receivable here would credit
     * an account the CRT already settled — the landlord would look like they were
     * still holding the cheque <em>and</em> would still show the cash in the bank.
     */
    @Test
    void bounceAfterClearDebitsBankNotPdc() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID chequeId = r.cheques().get(0).id();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        service.clear(chequeId, ChequeActionRequest.on(CLEAR_DATE));

        ChequeDTO bounced = service.bounce(chequeId,
                new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.ACCOUNT_CLOSED, null));

        Account bank = leaf(AccountRole.BANK);
        assertPair(bounced.cbrJournalId(), JournalDocType.CBR, BOUNCE_DATE, chequeId, leaseId,
                leaf(AccountRole.RENT_RECEIVABLE), bank, "12750");
        // Clear then bounce leaves the bank where it started and the debt outstanding.
        assertThat(balance(bank, leaseId)).isEqualByComparingTo("0");
        // The cleared cheque stays settled against PDC receivable: 53,000 registered
        // less the 12,750 that cleared. The bounce moved money between the bank and
        // rent receivable, not back into the drawer.
        assertThat(balance(leaf(AccountRole.PDC_RECEIVABLE), leaseId)).isEqualByComparingTo("40250");
    }

    /**
     * The same rule when the money did not land in the property's default bank: a
     * role re-resolved a month later can answer with a different leaf, so the
     * account that was actually debited is the one that is credited back.
     */
    @Test
    void bounceAfterClearCreditsTheAccountDebitedAtClear() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID chequeId = r.cheques().get(0).id();
        Account mashreq = otherBank();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        service.clear(chequeId, new ChequeActionRequest(CLEAR_DATE, null, null, mashreq.getId()));

        ChequeDTO bounced = service.bounce(chequeId, ChequeActionRequest.on(BOUNCE_DATE));

        assertPair(bounced.cbrJournalId(), JournalDocType.CBR, BOUNCE_DATE, chequeId, leaseId,
                leaf(AccountRole.RENT_RECEIVABLE), mashreq, "12750");
        assertThat(balance(mashreq, leaseId)).isEqualByComparingTo("0");
        assertThat(balance(leaf(AccountRole.BANK), leaseId)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------
    // BOUNCED -> REPLACED
    // ------------------------------------------------------------------

    /**
     * 12,750 came back; the renter could manage 10,000 now and 2,000 next month.
     * The 750 nobody replaced is not forgiven — it stays in rent receivable, where
     * the bounce put it, and is what the renter still owes.
     */
    @Test
    void replaceWithTwoRowsPostsTwoPdrsAndResidualStaysInReceivable() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID chequeId = r.cheques().get(0).id();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        service.bounce(chequeId, new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));

        List<ChequeDTO> replacements = service.replace(chequeId, new ReplaceChequeRequest(List.of(
                row("200001", REPLACE_DATE, LocalDate.of(2026, 11, 1), "10000", ChequeMode.PDC),
                row("200002", REPLACE_DATE, LocalDate.of(2026, 12, 1), "2000", ChequeMode.PDC)),
                REPLACE_DATE, "Two replacements agreed"));

        assertThat(replacements).hasSize(2);
        Account pdc = leaf(AccountRole.PDC_RECEIVABLE);
        Account rentReceivable = leaf(AccountRole.RENT_RECEIVABLE);
        assertPair(replacements.get(0).pdrJournalId(), JournalDocType.PDR, REPLACE_DATE,
                replacements.get(0).id(), leaseId, pdc, rentReceivable, "10000");
        assertPair(replacements.get(1).pdrJournalId(), JournalDocType.PDR, REPLACE_DATE,
                replacements.get(1).id(), leaseId, pdc, rentReceivable, "2000");
        assertThat(replacements).allSatisfy(c -> {
            assertThat(c.status()).isEqualTo(ChequeStatus.REGISTERED);
            assertThat(c.replacesId()).isEqualTo(chequeId);
        });

        Cheque bounced = reread(chequeId);
        assertThat(bounced.getStatus()).isEqualTo(ChequeStatus.REPLACED);
        UUID replacedBy = tx.execute(s -> chequeRepo.findById(chequeId).orElseThrow().getReplacedBy().getId());
        assertThat(replacedBy).isEqualTo(replacements.get(0).id());

        // 12,750 back on, 12,000 taken off again: the residual is exactly the gap.
        assertThat(balance(rentReceivable, leaseId)).isEqualByComparingTo("750");
        // And the other leg: 53,000 registered, the bounce releases 12,750 of paper,
        // the two replacements put 12,000 of new paper in the drawer.
        assertThat(balance(pdc, leaseId)).isEqualByComparingTo("52250");
    }

    @Test
    void replacementsCannotCollectMoreThanTheBouncedCheque() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        service.bounce(chequeId, new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));

        assertThatThrownBy(() -> service.replace(chequeId, new ReplaceChequeRequest(List.of(
                row("200001", REPLACE_DATE, LocalDate.of(2026, 11, 1), "13000", ChequeMode.PDC)),
                REPLACE_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot collect more than the cheque it replaces");

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.BOUNCED);
    }

    /** A replacement is a new instrument, so it faces the same row rules as a grid row. */
    @Test
    void aReplacementChequeStillNeedsItsOwnDateAndAFreeNumber() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        service.bounce(chequeId, new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));

        assertThatThrownBy(() -> service.replace(chequeId, new ReplaceChequeRequest(List.of(
                row("200001", REPLACE_DATE, null, "1000", ChequeMode.PDC)), REPLACE_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("a post-dated cheque needs the date written on it");

        // 100042 is still live on this lease.
        assertThatThrownBy(() -> service.replace(chequeId, new ReplaceChequeRequest(List.of(
                row("100042", REPLACE_DATE, LocalDate.of(2026, 11, 1), "1000", ChequeMode.PDC)), REPLACE_DATE, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cheque number 100042 is already used on this lease");
    }

    // ------------------------------------------------------------------
    // -> CANCELLED / RETURNED: the PDR is reversed
    // ------------------------------------------------------------------

    @Test
    void cancelReversesPdr() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        ChequeDTO first = r.cheques().get(0);

        ChequeDTO cancelled = service.cancel(first.id(),
                new ChequeActionRequest(BOUNCE_DATE, "Entered in error", null, null));

        assertThat(cancelled.status()).isEqualTo(ChequeStatus.CANCELLED);
        JournalEntry original = entry(first.pdrJournalId());
        assertThat(original.getStatus()).isEqualTo(JournalStatus.REVERSED);
        JournalEntry reversal = entry(original.getReversedById());
        assertThat(reversal.getDocType()).isEqualTo(JournalDocType.PDR);
        assertThat(reversal.getEntryDate()).isEqualTo(BOUNCE_DATE);
        assertThat(reversal.getNarration()).contains("Entered in error");

        // The mirror, not a hand-built opposite: same accounts, sides swapped,
        // dimensions and counter-accounts carried across.
        List<JournalLine> reversed = linesOf(reversal.getId());
        assertThat(reversed).hasSize(2);
        assertThat(reversed.get(0).getAccountId()).isEqualTo(leaf(AccountRole.PDC_RECEIVABLE).getId());
        assertThat(reversed.get(0).getCredit()).isEqualByComparingTo("12750");
        assertThat(reversed.get(1).getAccountId()).isEqualTo(leaf(AccountRole.RENT_RECEIVABLE).getId());
        assertThat(reversed.get(1).getDebit()).isEqualByComparingTo("12750");
        assertThat(reversed).allSatisfy(l -> assertThat(l.getChequeId()).isEqualTo(first.id()));

        // Cancelling the instrument leaves the contract's debt standing.
        assertThat(balance(leaf(AccountRole.RENT_RECEIVABLE), leaseId)).isEqualByComparingTo("12750");
    }

    @Test
    void returnToTenantReversesThePdrFromRegisteredAndFromDeposited() {
        PostLeaseResponse r = posted();
        // Row 0 is the only one already payable on DEPOSIT_DATE, so it is the one
        // that gets banked; row 1 falls due a quarter later and stays REGISTERED.
        ChequeDTO registered = r.cheques().get(1);
        ChequeDTO toDeposit = r.cheques().get(0);
        service.deposit(toDeposit.id(), ChequeActionRequest.on(DEPOSIT_DATE));

        ChequeDTO a = service.returnToTenant(registered.id(), BOUNCE_DATE, "Termination");
        ChequeDTO b = service.returnToTenant(toDeposit.id(), BOUNCE_DATE, "Termination");

        for (ChequeDTO returned : List.of(a, b)) {
            assertThat(returned.status()).isEqualTo(ChequeStatus.RETURNED);
            assertThat(returned.returnedAt()).isEqualTo(BOUNCE_DATE);
        }
        assertThat(entry(registered.pdrJournalId()).getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(entry(toDeposit.pdrJournalId()).getStatus()).isEqualTo(JournalStatus.REVERSED);
        assertThat(balance(leaf(AccountRole.RENT_RECEIVABLE), r.lease().getId())).isEqualByComparingTo("25500");
    }

    // ------------------------------------------------------------------
    // REGISTERED -> ONLINE_PENDING -> CLEARED
    // ------------------------------------------------------------------

    @Test
    void onlinePendingHoldsNoJournalAndCaptureClearsIntoTheSettlementAccount() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID chequeId = r.cheques().get(0).id();
        Account settlement = otherBank();
        long before = journalEntryRows();

        assertThat(service.registerOnlinePending(chequeId).status()).isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(journalEntryRows()).isEqualTo(before);
        // An abandoned checkout puts the row back on the register untouched.
        assertThat(service.revertOnlinePending(chequeId).status()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(journalEntryRows()).isEqualTo(before);
        service.registerOnlinePending(chequeId);

        ChequeDTO cleared = service.clearOnline(chequeId, CLEAR_DATE, settlement.getId());

        assertThat(cleared.status()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(cleared.clearedAt()).isEqualTo(CLEAR_DATE);
        assertPair(cleared.crtJournalId(), JournalDocType.CRT, CLEAR_DATE, chequeId, leaseId,
                settlement, leaf(AccountRole.PDC_RECEIVABLE), "12750");
    }

    /**
     * A bounced row is the one the renter most wants to pay and the one that must
     * not be paid in place: its CBR already credited PDC receivable back to nothing,
     * so a capture against it would credit a balance that is not there.
     */
    @Test
    void aBouncedChequeCannotBePaidOnlineWithoutBeingReplaced() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        service.bounce(chequeId, new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));

        assertThatThrownBy(() -> service.registerOnlinePending(chequeId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Replace the bounced cheque before paying online");

        // And the bounce is still on the record, not laundered into REGISTERED.
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.BOUNCED);
    }

    @Test
    void revertOnlinePendingOnlyAcceptsAPendingRow() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();

        assertThatThrownBy(() -> service.revertOnlinePending(chequeId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Can only revert cheques in ONLINE_PENDING (current: REGISTERED)");
    }

    /**
     * A CASH <em>rent</em> row is recorded when the money arrives, by
     * {@code receive()}; a gateway has no business clearing one. The exception is a
     * penalty's collection row, which {@code PenaltyAssessmentService.approve}
     * writes as CASH and the spec means to be payable online — see
     * {@link ChequeGatewayRules}, which is the one place the two are told apart and
     * is also what the renter's portal computes its Pay-now flag from. The accepting
     * half of that rule is asserted end to end in
     * {@code OnlinePaymentServiceIT.anApprovedPenaltyIsPayableThroughTheGatewayAndClearsTheSameWay},
     * against a real approval rather than a fabricated assessment id.
     */
    @Test
    void aCashRentRowCannotBePaidThroughTheGateway() {
        PostLeaseResponse r = posted();
        ChequeDTO cash = service.addRowToPostedLease(r.lease().getId(),
                row(null, REPLACE_DATE, REPLACE_DATE, "1500", ChequeMode.CASH));

        assertThatThrownBy(() -> service.registerOnlinePending(cash.id()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("can be paid through the gateway")
                .hasMessageContaining("is a CASH receipt");
    }

    /**
     * The gateway's actual door: bounce, supersede with an ONLINE row, pay it. The
     * point of the detour is the closing balances — the replacement's own PDR is
     * what the capture clears, so both receivables end at zero and the settlement
     * account holds the money.
     */
    @Test
    void gatewayReplacementClearsTheBouncedDebtEndToEnd() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID bouncedId = r.cheques().get(0).id();
        Account settlement = otherBank();
        service.deposit(bouncedId, ChequeActionRequest.on(DEPOSIT_DATE));
        service.bounce(bouncedId, new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));

        ChequeDTO online = service.replaceForOnlinePayment(bouncedId, REPLACE_DATE);

        assertThat(online.mode()).isEqualTo(ChequeMode.ONLINE);
        assertThat(online.status()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(online.amount()).isEqualByComparingTo("12750");
        assertThat(online.chequeDate()).isEqualTo(REPLACE_DATE);
        assertThat(online.chequeNumber()).isNull();
        assertThat(online.narration()).isEqualTo("Online payment for cheque 100040");
        assertThat(online.replacesId()).isEqualTo(bouncedId);
        assertPair(online.pdrJournalId(), JournalDocType.PDR, REPLACE_DATE, online.id(), leaseId,
                leaf(AccountRole.PDC_RECEIVABLE), leaf(AccountRole.RENT_RECEIVABLE), "12750");

        Cheque superseded = reread(bouncedId);
        assertThat(superseded.getStatus()).isEqualTo(ChequeStatus.REPLACED);
        UUID replacedBy = tx.execute(s -> chequeRepo.findById(bouncedId).orElseThrow().getReplacedBy().getId());
        assertThat(replacedBy).isEqualTo(online.id());

        service.registerOnlinePending(online.id());
        ChequeDTO captured = service.clearOnline(online.id(), CLEAR_DATE, settlement.getId());

        assertThat(captured.status()).isEqualTo(ChequeStatus.CLEARED);
        assertPair(captured.crtJournalId(), JournalDocType.CRT, CLEAR_DATE, online.id(), leaseId,
                settlement, leaf(AccountRole.PDC_RECEIVABLE), "12750");
        // This leg is square: the bounce raised 12,750 of rent receivable, the
        // replacement's PDR took it off again, and the capture turned the paper into
        // money. The other four cheques are still outstanding, so only this leg nets.
        assertThat(balance(leaf(AccountRole.RENT_RECEIVABLE), leaseId)).isEqualByComparingTo("0");
        assertThat(balance(settlement, leaseId)).isEqualByComparingTo("12750");
        assertThat(balance(leaf(AccountRole.PDC_RECEIVABLE), leaseId)).isEqualByComparingTo("40250");
    }

    /** Gateways retry their webhooks; a retry must not collect the instalment twice. */
    @Test
    void clearOnlineIsIdempotent() {
        PostLeaseResponse r = posted();
        UUID bouncedId = r.cheques().get(0).id();
        service.deposit(bouncedId, ChequeActionRequest.on(DEPOSIT_DATE));
        service.bounce(bouncedId, new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));
        UUID onlineId = service.replaceForOnlinePayment(bouncedId, REPLACE_DATE).id();
        service.registerOnlinePending(onlineId);

        ChequeDTO first = service.clearOnline(onlineId, CLEAR_DATE, null);
        ChequeDTO again = service.clearOnline(onlineId, CLEAR_DATE.plusDays(1), null);

        assertThat(again.status()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(again.crtJournalId()).isEqualTo(first.crtJournalId());
        assertThat(again.clearedAt()).isEqualTo(CLEAR_DATE);
        assertThat(entryCount(JournalDocType.CRT, onlineId)).isEqualTo(1L);
    }

    /**
     * A cheque that cleared at the bank arriving at the capture endpoint means the
     * webhook is pointing at the wrong row. Answering "fine, already done" would
     * hide that, so only an ONLINE row gets the idempotent shortcut.
     */
    @Test
    void clearOnlineRefusesAChequeThatClearedAtTheBank() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        service.clear(chequeId, ChequeActionRequest.on(CLEAR_DATE));

        assertThatThrownBy(() -> service.clearOnline(chequeId, CLEAR_DATE, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is not an online payment row");
    }

    // ------------------------------------------------------------------
    // refusals that leave the register exactly as they found it
    // ------------------------------------------------------------------

    /**
     * A cheque whose lease is not on the books has a journal behind it only by
     * accident. Depositing and clearing it would settle a receivable the ledger
     * never raised.
     */
    @Test
    void transitionsOnADraftLeaseAreRefused() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        UUID chequeId = r.cheques().get(0).id();
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            lease.setStatus(LeaseStatus.DRAFT);
            leaseRepo.save(lease);
        });

        assertThatThrownBy(() -> service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("This cheque's lease is DRAFT");
        assertThatThrownBy(() -> service.cancel(chequeId, ChequeActionRequest.on(DEPOSIT_DATE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("post the lease before changing its register rows");

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
    }

    /**
     * Defensive: a REGISTERED row with no registering journal is a row the ledger
     * has never heard of. Cancelling it must say so rather than dying inside
     * {@code PostingService.reverse(null, …)}.
     */
    @Test
    void cancellingARowWithNoRegisteringJournalIsRefusedCleanly() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        tx.executeWithoutResult(s -> {
            Cheque c = chequeRepo.findById(chequeId).orElseThrow();
            c.setPdrJournalId(null);
            chequeRepo.save(c);
        });

        assertThatThrownBy(() -> service.cancel(chequeId, ChequeActionRequest.on(BOUNCE_DATE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("has no registering journal to reverse");

        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
    }

    /**
     * The tenant boundary, from the outside. Another landlord org holding this
     * cheque's id sees nothing, and cannot smuggle one of its own bank accounts
     * into somebody else's clearing entry either.
     */
    @Test
    void anotherTenantCanNeitherMoveTheChequeNorLendItAnAccount() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        UUID tenantA = fixtures.tenantId();

        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        UUID otherBankId = tx.execute(s -> resolver.resolve(AccountRole.BANK, other.property().getId())).getId();

        // As the other tenant: the cheque is simply not there.
        assertThatThrownBy(() -> service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE)))
                .isInstanceOf(NotFoundException.class);

        // Back as the owner: the other tenant's bank does not exist for this one.
        TenantContextHolder.setTenantId(tenantA);
        assertThatThrownBy(() -> service.deposit(chequeId,
                new ChequeActionRequest(DEPOSIT_DATE, null, null, otherBankId)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not exist");

        Cheque unchanged = reread(chequeId);
        assertThat(unchanged.getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(unchanged.getDepositedAt()).isNull();
    }

    /**
     * Two clerks adding a row to the same lease at the same moment.
     *
     * <p>A new row's position is max+1 over the register, so without the lease-row
     * lock both read 5 and both write position 6 — nothing in the database forbids
     * it, because only the cheque number is indexed. With the lock one of them
     * either waits its turn and gets 7, or fails fast with "try again". Both are
     * acceptable; two rows claiming position 6 is not.</p>
     */
    @Test
    void concurrentAddsNeverDuplicateASequenceNumber() throws Exception {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
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
                    return service.addRowToPostedLease(leaseId,
                            row(null, REPLACE_DATE, REPLACE_DATE, "500", ChequeMode.CASH));
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

        assertThat(outcomes).filteredOn(o -> !(o instanceof ChequeDTO))
                .allMatch(BusinessRuleViolationException.class::isInstance);
        List<Integer> seqNos = tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId).stream()
                .map(Cheque::getSeqNo).toList());
        assertThat(seqNos).doesNotHaveDuplicates();
        assertThat(outcomes).filteredOn(ChequeDTO.class::isInstance).isNotEmpty();
    }

    @Test
    void lockedPeriodRefusesTheTransitionAndLeavesTheChequeUnchanged() {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));
        fiscal.lockThrough(LocalDate.of(2026, 10, 31));

        assertThatThrownBy(() -> service.clear(chequeId, ChequeActionRequest.on(CLEAR_DATE)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books are locked through 2026-10-31");

        Cheque unchanged = reread(chequeId);
        assertThat(unchanged.getStatus()).isEqualTo(ChequeStatus.DEPOSITED);
        assertThat(unchanged.getCrtJournalId()).isNull();
        assertThat(unchanged.getClearedAt()).isNull();
        assertThat(entryCount(JournalDocType.CRT, chequeId)).isZero();
    }

    /**
     * Two clerks working the same returned cheque.
     *
     * <p>Without the row lock both read DEPOSITED, both pass the guard and the
     * ledger ends up with two CBRs for one cheque — the renter charged twice for
     * one bounce. The brief pairs a clear with a bounce here; two bounces is the
     * pairing that can actually assert a single winner, because a clear followed by
     * a bounce is a <em>legal</em> sequence and both would rightly succeed once
     * they serialised.</p>
     */
    @Test
    void concurrentBouncesProduceExactlyOneCbr() throws Exception {
        PostLeaseResponse r = posted();
        UUID chequeId = r.cheques().get(0).id();
        UUID tenant = fixtures.tenantId();
        service.deposit(chequeId, ChequeActionRequest.on(DEPOSIT_DATE));

        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Object>> racers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            racers.add(() -> {
                TenantContextHolder.setTenantId(tenant);
                LeaseTestFixtures.authenticateAsTenantAdmin();
                try {
                    bothReady.await(10, TimeUnit.SECONDS);
                    return service.bounce(chequeId,
                            new ChequeActionRequest(BOUNCE_DATE, null, ChequeFailureReason.BOUNCE, null));
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

        assertThat(outcomes).filteredOn(ChequeDTO.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !(o instanceof ChequeDTO))
                .hasSize(1)
                .allMatch(BusinessRuleViolationException.class::isInstance);
        assertThat(entryCount(JournalDocType.CBR, chequeId)).isEqualTo(1L);
        assertThat(reread(chequeId).getStatus()).isEqualTo(ChequeStatus.BOUNCED);
    }

    // ------------------------------------------------------------------
    // the whole register, end to end
    // ------------------------------------------------------------------

    /**
     * Post, bank the lot, clear the lot. The landlord holds no paper against this
     * lease any more and the bank holds the contract, to the fils — which is the
     * only statement that proves the register and the ledger are describing the
     * same 53,000.
     */
    @Test
    void clearingEveryChequeEmptiesPdcReceivableIntoTheBank() {
        PostLeaseResponse r = posted();
        UUID leaseId = r.lease().getId();
        List<UUID> ids = r.cheques().stream().map(ChequeDTO::id).toList();

        service.depositBatch(new DepositBatchRequest(ids, WHOLE_GRID_DEPOSIT_DATE, null));
        for (UUID id : ids) {
            service.clear(id, ChequeActionRequest.on(WHOLE_GRID_CLEAR_DATE));
        }

        assertThat(balance(leaf(AccountRole.PDC_RECEIVABLE), leaseId)).isEqualByComparingTo("0");
        assertThat(balance(leaf(AccountRole.BANK), leaseId)).isEqualByComparingTo("53000");
        assertThat(balance(leaf(AccountRole.RENT_RECEIVABLE), leaseId)).isEqualByComparingTo("0");
        tx.executeWithoutResult(s -> assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId))
                .allMatch(c -> c.getStatus() == ChequeStatus.CLEARED && c.getCrtJournalId() != null));
    }
}
