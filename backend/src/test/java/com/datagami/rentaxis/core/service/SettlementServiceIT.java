package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.SaveSettlementDTO;
import com.datagami.rentaxis.api.dto.SettlementResponseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.dto.penalty.PenaltyAssessmentDTO;
import com.datagami.rentaxis.api.dto.penalty.ProposePenaltyRequest;
import com.datagami.rentaxis.api.dto.settlement.AdditionLineDTO;
import com.datagami.rentaxis.api.dto.settlement.DeductionLineDTO;
import com.datagami.rentaxis.api.dto.settlement.FinalizeSettlementRequest;
import com.datagami.rentaxis.api.dto.settlement.OutstandingInstrumentDTO;
import com.datagami.rentaxis.api.dto.settlement.SettlementStatementDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeaseChequeRegistrar;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.penalty.PenaltyAssessmentService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AdditionCategory;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.LineItemType;
import com.datagami.rentaxis.domain.entity.enums.PenaltyReason;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The move-out statement and the {@code STL} that closes it (spec §9.2), against
 * a real database.
 *
 * <p>The fixture continues Task 5's: 51,000 of rent over 24 Sep 2026 → 23 Sep 2027
 * (365 days, day rate 139.726027), a 2,000 admin fee and a <b>3,000 security
 * deposit</b>, paid by six instruments. The admin, the deposit and the first two
 * rent cheques clear; recognition runs to 31 Jan 2027; the lease is terminated on
 * 15 Feb 2027, which hands the April and July cheques back and reverses the
 * unearned rent; recognition then runs to 15 Feb so the truncated replacement is
 * in the ledger. That is exactly the state a settlement walks into.</p>
 *
 * <p><b>Every figure is re-derived, not copied off the plan.</b></p>
 * <table>
 *   <tr><th>figure</th><th>value</th><th>from</th></tr>
 *   <tr><td>{@code earnedRent}</td><td>20,260.27</td>
 *       <td>Σ POSTED rows: 18,164.39 (Sep–Jan) + 2,095.88 (1–15 Feb)</td></tr>
 *   <tr><td>{@code receivedTotal}</td><td>30,500.00</td>
 *       <td>Σ CLEARED: 2,000 + 12,750 + 12,750 + 3,000</td></tr>
 *   <tr><td>{@code receivableBalance}</td><td>−5,239.73</td>
 *       <td>0 + 25,500 handed back − 30,739.73 unearned</td></tr>
 *   <tr><td>{@code depositsHeld}</td><td>3,000.00</td><td>the deposit leaf's credit balance</td></tr>
 *   <tr><td>{@code netRefund}</td><td>8,239.73</td><td>3,000 − (−5,239.73)</td></tr>
 *   <tr><td>…with a 10,000 damage line</td><td>−1,760.27</td><td>3,000 + 5,239.73 − 10,000</td></tr>
 * </table>
 *
 * <p><b>The plan's "received 28,500" is 30,500.</b> Its own definition of
 * {@code receivedTotal} is Σ CLEARED cheques, and the deposit cheque the same
 * bullet asks for is one of them.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} enables the Hibernate tenant filter
 * only inside one, so every read-back goes through {@link #tx}.</p>
 */
@SpringBootTest
class SettlementServiceIT extends AbstractPostgresIT {

    @Autowired SettlementService settlement;
    @Autowired LeaseTerminationService termination;
    @Autowired RecognitionService recognition;
    @Autowired PenaltyAssessmentService penalties;
    @Autowired ChequeService chequeService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeaseService leaseService;
    @Autowired PostingService postingService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired AccountRepository accounts;
    @Autowired ChequeRepository chequeRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseSettlementRepository settlementRepo;
    @Autowired LeaseEventRepository leaseEvents;
    @Autowired JournalEntryRepository journals;
    @Autowired JournalLineRepository journalLines;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);

    private static final LocalDate ADMIN_CHEQUE = LocalDate.of(2026, 9, 11);
    private static final LocalDate DEPOSIT_CHEQUE = LocalDate.of(2026, 9, 12);
    private static final LocalDate PARKING_CHEQUE = LocalDate.of(2026, 9, 13);
    private static final LocalDate RENT_1 = LocalDate.of(2026, 10, 2);
    private static final LocalDate RENT_2 = LocalDate.of(2027, 1, 2);
    private static final LocalDate RENT_3 = LocalDate.of(2027, 4, 2);
    private static final LocalDate RENT_4 = LocalDate.of(2027, 7, 2);

    /** Recognition is run to here before the termination, as Task 5's fixture does. */
    private static final LocalDate RECOGNISED_TO = LocalDate.of(2027, 1, 31);
    /** The termination date: mid-month, mid-term. */
    private static final LocalDate T = LocalDate.of(2027, 2, 15);
    /** …and the day finance actually settles, five days later. */
    private static final LocalDate SETTLED_ON = LocalDate.of(2027, 2, 20);

    /**
     * The day a renewed predecessor is settled: after its own term ran out, which
     * is the floor {@code requireUsableDate} applies to a lease that was never cut
     * short.
     */
    private static final LocalDate RENEWAL_SETTLED_ON = END.plusDays(3);

    /**
     * A second termination date, chosen so the 2 Jan cheque is uncleared and dated
     * <em>before</em> it — which is what §9.1's keep list is for, and the only way
     * to get an instrument the settlement can still be waiting on.
     */
    private static final LocalDate KEPT_T = LocalDate.of(2027, 1, 20);
    private static final LocalDate KEPT_PENALTY_ON = LocalDate.of(2027, 1, 5);
    private static final LocalDate KEPT_SETTLED_ON = LocalDate.of(2027, 1, 25);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // the fixture
    // ------------------------------------------------------------------

    /**
     * The lease on the books with its six instruments registered, four of them
     * cleared on their own dates — so nothing is ever late and no penalty proposal
     * appears to muddy what the register is holding.
     */
    private UUID galah() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000"),
                        line("SECURITY_DEPOSIT", "3000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("100040", ADMIN_CHEQUE, ADMIN_CHEQUE, "2000"),
                row("100045", DEPOSIT_CHEQUE, DEPOSIT_CHEQUE, "3000"),
                row("100041", CONTRACT_DATE, RENT_1, "12750"),
                row("100042", CONTRACT_DATE, RENT_2, "12750"),
                row("100043", CONTRACT_DATE, RENT_3, "12750"),
                row("100044", CONTRACT_DATE, RENT_4, "12750")));
        posting.post(leaseId);
        clearOnItsOwnDate(chequeOn(leaseId, ADMIN_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, DEPOSIT_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_1));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_2));
        return leaseId;
    }

    /**
     * …recognised to 31 Jan, terminated on 15 Feb, and recognised again to 15 Feb
     * so the truncated February slice is POSTED rather than PLANNED.
     *
     * <p>That last run is the whole of {@code unrecognisedEntries}: without it the
     * statement would show 18,164.39 earned against a receivable that has already
     * had the unearned rent reversed out of it, which is a renter apparently 2,095.88
     * better off than they are.</p>
     */
    private UUID terminatedGalah() {
        UUID leaseId = galah();
        recognition.runTo(RECOGNISED_TO, false);
        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, "Renter relocating"), null);
        recognition.runTo(T, false);
        return leaseId;
    }

    /**
     * The same lease with the January cheque left <em>uncleared</em>, terminated on
     * 20 Jan so §9.1 keeps it for collection.
     *
     * <p>Recognition is deliberately not run: this fixture is about the register,
     * and the receivable it leaves ({@code 25,500 handed back − 34,372.60 unearned
     * = −8,872.60}) is the same whichever way round that is.</p>
     */
    private UUID galahWithAKeptCheque() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000"),
                        line("SECURITY_DEPOSIT", "3000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("100040", ADMIN_CHEQUE, ADMIN_CHEQUE, "2000"),
                row("100045", DEPOSIT_CHEQUE, DEPOSIT_CHEQUE, "3000"),
                row("100041", CONTRACT_DATE, RENT_1, "12750"),
                row("100042", CONTRACT_DATE, RENT_2, "12750"),
                row("100043", CONTRACT_DATE, RENT_3, "12750"),
                row("100044", CONTRACT_DATE, RENT_4, "12750")));
        posting.post(leaseId);
        clearOnItsOwnDate(chequeOn(leaseId, ADMIN_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, DEPOSIT_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_1));
        termination.terminate(leaseId, new TerminateLeaseRequest(KEPT_T, null, null, "Early exit"), null);
        return leaseId;
    }

    /**
     * …and with a 500 fine approved before the termination, so its CASH collection
     * row is dated before {@code T} and is kept too.
     *
     * <p>Approved <em>before</em> the termination because {@code approve} raises the
     * collection row through the user-facing door, which refuses a lease that has
     * ended — which is itself the reason a fine has to be settled through the
     * register rather than added as a deduction.</p>
     */
    private UUID galahKeptAndFined() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000"),
                        line("SECURITY_DEPOSIT", "3000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("100040", ADMIN_CHEQUE, ADMIN_CHEQUE, "2000"),
                row("100045", DEPOSIT_CHEQUE, DEPOSIT_CHEQUE, "3000"),
                row("100041", CONTRACT_DATE, RENT_1, "12750"),
                row("100042", CONTRACT_DATE, RENT_2, "12750"),
                row("100043", CONTRACT_DATE, RENT_3, "12750"),
                row("100044", CONTRACT_DATE, RENT_4, "12750")));
        posting.post(leaseId);
        clearOnItsOwnDate(chequeOn(leaseId, ADMIN_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, DEPOSIT_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_1));
        PenaltyAssessmentDTO proposed = penalties.propose(new ProposePenaltyRequest(
                leaseId, null, PenaltyReason.LATE_PAYMENT, new BigDecimal("500"), "Late"), null);
        penalties.approve(proposed.id(), KEPT_PENALTY_ON);
        termination.terminate(leaseId, new TerminateLeaseRequest(KEPT_T, null, null, "Early exit"), null);
        return leaseId;
    }

    /** The Galah lease with a 1,500 parking deposit on its own leaf, then terminated. */
    private UUID galahWithParkingDeposit() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000"),
                        line("SECURITY_DEPOSIT", "3000"), line("PARKING_DEPOSIT", "1500")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("100040", ADMIN_CHEQUE, ADMIN_CHEQUE, "2000"),
                row("100045", DEPOSIT_CHEQUE, DEPOSIT_CHEQUE, "3000"),
                row("100046", PARKING_CHEQUE, PARKING_CHEQUE, "1500"),
                row("100041", CONTRACT_DATE, RENT_1, "12750"),
                row("100042", CONTRACT_DATE, RENT_2, "12750"),
                row("100043", CONTRACT_DATE, RENT_3, "12750"),
                row("100044", CONTRACT_DATE, RENT_4, "12750")));
        posting.post(leaseId);
        clearOnItsOwnDate(chequeOn(leaseId, ADMIN_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, DEPOSIT_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, PARKING_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_1));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_2));
        recognition.runTo(RECOGNISED_TO, false);
        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);
        recognition.runTo(T, false);
        return leaseId;
    }

    /**
     * The same lease with every instrument collected, so its register is empty and
     * the only thing left on its books is the deposit. What a tenancy that simply
     * ran its course looks like on the day it is settled.
     */
    private UUID galahFullyCollected() {
        UUID leaseId = galah();
        clearOnItsOwnDate(chequeOn(leaseId, RENT_3));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_4));
        return leaseId;
    }

    /**
     * A tenancy that simply ran out: the same lease, marked EXPIRED the day after
     * its term ended, with no termination and therefore no returned paper.
     */
    private UUID expiredGalah() {
        UUID leaseId = galah();
        leaseService.markExpired(leaseId, END.plusDays(1));
        return leaseId;
    }

    private static ChequeRowInput row(String number, LocalDate postingDate, LocalDate chequeDate, String amount) {
        return new ChequeRowInput(null, null, postingDate, number, chequeDate, "Emirates NBD",
                null, null, new BigDecimal(amount), null, null);
    }

    private void clearOnItsOwnDate(Cheque cheque) {
        chequeService.deposit(cheque.getId(), ChequeActionRequest.on(cheque.getChequeDate()));
        chequeService.clear(cheque.getId(), ChequeActionRequest.on(cheque.getChequeDate()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<Cheque> register(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private Cheque chequeOn(UUID leaseId, LocalDate chequeDate) {
        return register(leaseId).stream().filter(c -> chequeDate.equals(c.getChequeDate()))
                .findFirst().orElseThrow(() -> new AssertionError("No cheque dated " + chequeDate));
    }

    private Cheque chequeById(UUID id) {
        return tx.execute(s -> chequeRepo.findById(id).orElseThrow());
    }

    private Lease lease(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    /** The debit a journal carries against one account, found <em>by account</em>, never by index. */
    private BigDecimal debitOn(JournalEntry entry, UUID accountId) {
        return linesOf(entry.getId()).stream()
                .filter(l -> accountId.equals(l.getAccountId()) && l.getDebit() != null)
                .map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal creditOn(JournalEntry entry, UUID accountId) {
        return linesOf(entry.getId()).stream()
                .filter(l -> accountId.equals(l.getAccountId()) && l.getCredit() != null)
                .map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** An account's closing balance on this lease alone; a credit balance reads negative. */
    private BigDecimal balanceOf(AccountRole role, UUID leaseId) {
        return balanceOf(leaf(role).getId(), leaseId);
    }

    private BigDecimal balanceOf(UUID accountId, UUID leaseId) {
        return tx.execute(s -> ledger.accountLedger(accountId,
                new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private boolean hasSettlementRow(UUID leaseId) {
        Boolean present = tx.execute(s -> settlementRepo.findByLeaseId(leaseId).isPresent());
        return Boolean.TRUE.equals(present);
    }

    private long stlCount() {
        Long count = tx.execute(s -> journals.findAll().stream()
                .filter(j -> j.getDocType() == JournalDocType.STL).count());
        return count == null ? 0L : count;
    }

    private JournalEntry stlOf(UUID leaseId) {
        SettlementResponseDTO saved = tx.execute(s -> settlement.buildSettlementResponse(leaseId));
        assertThat(saved.getJournalId()).as("the STL").isNotNull();
        JournalEntry entry = tx.execute(s -> journals.findById(saved.getJournalId()).orElseThrow());
        assertThat(entry.getDocType()).isEqualTo(JournalDocType.STL);
        return entry;
    }

    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(LocalDate.of(2030, 1, 1), null));
        assertThat(rows).as("trial balance rows").isNotEmpty();
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance debits").isGreaterThan(BigDecimal.ZERO);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }

    /** An empty draft, which is what "open the settlement screen and press Save" sends. */
    private void saveDraft(UUID leaseId, SaveSettlementDTO.DeductionItemDTO... items) {
        SaveSettlementDTO dto = new SaveSettlementDTO();
        dto.setDeductions(List.of(items));
        settlement.saveDraft(leaseId, dto, null);
    }

    private static SaveSettlementDTO.DeductionItemDTO deduction(DeductionCategory category, String amount) {
        return deduction(category, amount, null);
    }

    private static SaveSettlementDTO.DeductionItemDTO deduction(DeductionCategory category, String amount,
                                                                UUID accountId) {
        SaveSettlementDTO.DeductionItemDTO item = new SaveSettlementDTO.DeductionItemDTO();
        item.setType(LineItemType.DEDUCTION);
        item.setCategory(category);
        item.setDescription(category.name().toLowerCase().replace('_', ' '));
        item.setAmount(new BigDecimal(amount));
        item.setAccountId(accountId);
        return item;
    }

    private static SaveSettlementDTO.DeductionItemDTO addition(AdditionCategory category, String amount) {
        SaveSettlementDTO.DeductionItemDTO item = new SaveSettlementDTO.DeductionItemDTO();
        item.setType(LineItemType.ADDITION);
        item.setAdditionCategory(category.name());
        item.setDescription(category.name().toLowerCase().replace('_', ' '));
        item.setAmount(new BigDecimal(amount));
        return item;
    }

    private SettlementResponseDTO finalize(UUID leaseId, UUID bankAccountId) {
        return settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(SETTLED_ON, bankAccountId, false), null);
    }

    // ------------------------------------------------------------------
    // the statement
    // ------------------------------------------------------------------

    /**
     * The landlord owes this renter money, and every figure that says so comes off
     * the ledger.
     *
     * <p>The interesting one is {@code receivableBalance}: it is <b>negative</b>,
     * because the termination reversed 30,739.73 of unearned rent onto a receivable
     * that two returned cheques had only put 25,500 back on. A statement that read
     * "arrears" off the register's dates — which is what the old preview did —
     * would show zero there and refund the deposit alone.</p>
     */
    @Test
    void statementShowsCreditOwedToTenant() {
        UUID leaseId = terminatedGalah();

        SettlementStatementDTO statement = settlement.statement(leaseId);

        assertThat(statement.earnedRent()).as("Σ POSTED recognition").isEqualByComparingTo("20260.27");
        assertThat(statement.receivedTotal()).as("Σ CLEARED cheques").isEqualByComparingTo("30500.00");
        assertThat(statement.receivableBalance()).as("−ve: the landlord owes").isEqualByComparingTo("-5239.73");
        assertThat(statement.depositsHeld()).isEqualByComparingTo("3000.00");
        assertThat(statement.penaltiesOutstanding()).isEqualByComparingTo("0.00");
        assertThat(statement.deductions()).isEmpty();
        assertThat(statement.additions()).isEmpty();
        assertThat(statement.totalDeductions()).isEqualByComparingTo("0.00");
        assertThat(statement.totalAdditions()).isEqualByComparingTo("0.00");
        assertThat(statement.netRefund()).as("3,000 − (−5,239.73)").isEqualByComparingTo("8239.73");
        assertThat(statement.unrecognisedEntries()).as("nothing left to recognise").isZero();

        // A statement writes nothing.
        assertThat(hasSettlementRow(leaseId)).as("a statement writes nothing").isFalse();
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.TERMINATED);
        assertTrialBalanceBalances();
    }

    /**
     * Rent that has been earned but not recognised is not in {@code earnedRent} and
     * not in the receivable, and the statement says how many rows are missing.
     *
     * <p>Run the termination but not the catch-up recognition and the 1–15 February
     * slice sits PLANNED: the ledger has 18,164.39 of income against a receivable
     * that has already had the whole unearned balance reversed out of it. The
     * number is not wrong — it is what the books say — but it is not the number to
     * settle on, which is why the flag exists and why the screen tells the user to
     * run recognition first.</p>
     */
    @Test
    void statementCountsTheRentStillWaitingToBeRecognised() {
        UUID leaseId = galah();
        recognition.runTo(RECOGNISED_TO, false);
        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);

        SettlementStatementDTO before = settlement.statement(leaseId);
        assertThat(before.unrecognisedEntries()).as("the 1–15 Feb slice").isEqualTo(1);
        assertThat(before.earnedRent()).isEqualByComparingTo("18164.39");

        recognition.runTo(T, false);

        SettlementStatementDTO after = settlement.statement(leaseId);
        assertThat(after.unrecognisedEntries()).isZero();
        assertThat(after.earnedRent()).isEqualByComparingTo("20260.27");
        // The receivable never moved: recognition releases advance rent to income
        // and touches neither side of what the renter owes.
        assertThat(after.receivableBalance()).isEqualByComparingTo(before.receivableBalance());
    }

    // ------------------------------------------------------------------
    // finalise — the refund case
    // ------------------------------------------------------------------

    /**
     * One {@code STL}, three lines, and a contract that is finished with.
     *
     * <p>Asserted line by line and <em>by account</em>, because the order
     * {@code PostingService} emits them in is not the thing being tested. After it,
     * the two balances that have to be exactly zero on this lease are the receivable
     * and the deposit: anything left in either is money the books still think is
     * moving between a landlord and a renter who have settled.</p>
     */
    /**
     * The finalized statement carries the settler's name, not a bare id.
     *
     * <p>The move-out statement is a legal document — shown to the departing tenant
     * and admissible in a dispute — so the "Finalized … by …" line must name the
     * person. This guards that the mapping populates {@code settledByName} for the
     * actual settler.</p>
     *
     * <p><b>Scope note:</b> the production defect only bit a SUPER_ADMIN acting
     * inside a pivoted tenant, whose {@code tenant_id} is NULL and who the
     * tenant-filtered {@code findById} therefore missed; the fix swaps to the
     * native {@code findDisplayNameById}. This test cannot reproduce that half —
     * the tenant Hibernate filter is not engaged in this harness, so a plain
     * {@code findById} would resolve the name here too. It does lock in that the
     * name is resolved at all, which catches a mapping that stops populating it.</p>
     */
    @Test
    void finalizeCarriesTheSettlersNameOnTheStatement() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId);
        UUID bank = leaf(AccountRole.BANK).getId();

        com.datagami.rentaxis.domain.entity.User settler = new com.datagami.rentaxis.domain.entity.User();
        settler.setName("Kunal (Platform Admin)");
        settler.setEmail("platform-admin-" + UUID.randomUUID() + "@example.invalid");
        settler.setRole(com.datagami.rentaxis.domain.entity.enums.UserRole.SUPER_ADMIN);
        settler.setPasswordHash("x");
        settler.setTenantId(null);
        UUID settlerId = userRepo.saveAndFlush(settler).getId();

        SettlementResponseDTO response = settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(SETTLED_ON, bank, false), settlerId);

        assertThat(response.getSettledBy()).isEqualTo(settlerId);
        assertThat(response.getSettledByName()).isEqualTo("Kunal (Platform Admin)");
    }

    @Test
    void finalizeRefundPostsStlAndClosesLease() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId);
        UUID bank = leaf(AccountRole.BANK).getId();
        UUID deposit = leaf(AccountRole.SECURITY_DEPOSIT).getId();
        UUID receivable = leaf(AccountRole.RENT_RECEIVABLE).getId();

        SettlementResponseDTO response = finalize(leaseId, bank);

        assertThat(response.getStatus()).isEqualTo(SettlementStatus.FINALIZED.name());
        assertThat(response.getSettlementDate()).isEqualTo(SETTLED_ON);
        assertThat(response.getRefundAmount()).isEqualByComparingTo("8239.73");
        assertThat(response.getBalanceDue()).isEqualByComparingTo("0.00");
        assertThat(response.getEarnedRent()).isEqualByComparingTo("20260.27");
        assertThat(response.getReceivedTotal()).isEqualByComparingTo("30500.00");
        assertThat(response.getReceivableBalance()).isEqualByComparingTo("-5239.73");
        assertThat(response.getDepositsHeld()).isEqualByComparingTo("3000.00");
        assertThat(response.getRefundBankAccountId()).isEqualTo(bank);
        assertThat(response.getCollectionChequeId()).as("nothing to collect").isNull();
        assertThat(response.getJournalNumber()).startsWith("STL");

        JournalEntry stl = stlOf(leaseId);
        assertThat(stl.getEntryDate()).isEqualTo(SETTLED_ON);
        assertThat(linesOf(stl.getId())).hasSize(3);
        assertThat(debitOn(stl, deposit)).as("Dr Security Deposit").isEqualByComparingTo("3000.00");
        assertThat(debitOn(stl, receivable)).as("Dr Rent Receivable").isEqualByComparingTo("5239.73");
        assertThat(creditOn(stl, bank)).as("Cr Bank").isEqualByComparingTo("8239.73");
        // Dimensions: the entry is filed under the contract it closes.
        assertThat(stl.getLeaseId()).isEqualTo(leaseId);
        assertThat(stl.getPropertyId()).isEqualTo(fixtures.property().getId());
        assertThat(stl.getUnitId()).isEqualTo(fixtures.unit().getId());
        assertThat(stl.getRenterId()).isEqualTo(fixtures.renter().getId());
        assertThat(linesOf(stl.getId())).allSatisfy(l -> {
            assertThat(l.getLeaseId()).isEqualTo(leaseId);
            assertThat(l.getChequeId()).isNull();
        });

        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId)).isEqualByComparingTo("0.00");
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.CLOSED);
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // finalise — the balance-due case
    // ------------------------------------------------------------------

    /**
     * The damage costs more than the deposit and the credit together, so the renter
     * owes the difference and the register gets a row for it.
     *
     * <p>3,000 held + 5,239.73 owed to the renter − 10,000 of damage =
     * <b>−1,760.27</b>. The {@code STL} moves the receivable to exactly that and
     * posts <em>no</em> bank line; the CASH row's own {@code PDR} then takes it off
     * the receivable and onto PDC receivable, which is where an uncollected
     * instrument lives. The lease stays TERMINATED — closing a contract the
     * landlord is still chasing money on is what Task 7's clearing hook is for.</p>
     */
    @Test
    void finalizeWithDeductionsExceedingDepositLeavesBalanceDue() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId, deduction(DeductionCategory.PROPERTY_DAMAGE, "10000"));
        UUID deposit = leaf(AccountRole.SECURITY_DEPOSIT).getId();
        UUID receivable = leaf(AccountRole.RENT_RECEIVABLE).getId();
        UUID maintenance = leaf(AccountRole.MAINTENANCE_CHARGES).getId();
        UUID bank = leaf(AccountRole.BANK).getId();

        assertThat(settlement.statement(leaseId).netRefund()).isEqualByComparingTo("-1760.27");

        SettlementResponseDTO response = finalize(leaseId, null);

        assertThat(response.getRefundAmount()).isEqualByComparingTo("0.00");
        assertThat(response.getBalanceDue()).isEqualByComparingTo("1760.27");
        assertThat(response.getRefundBankAccountId()).isNull();
        assertThat(response.getCollectionChequeId()).isNotNull();

        JournalEntry stl = stlOf(leaseId);
        assertThat(linesOf(stl.getId())).hasSize(3);
        assertThat(debitOn(stl, deposit)).as("Dr Security Deposit").isEqualByComparingTo("3000.00");
        assertThat(debitOn(stl, receivable)).as("Dr Rent Receivable, shortfall included")
                .isEqualByComparingTo("7000.00");
        assertThat(creditOn(stl, maintenance)).as("Cr Maintenance Charges").isEqualByComparingTo("10000.00");
        assertThat(creditOn(stl, bank)).as("no refund is paid").isEqualByComparingTo("0.00");

        Cheque collection = chequeById(response.getCollectionChequeId());
        assertThat(collection.getAmount()).isEqualByComparingTo("1760.27");
        assertThat(collection.getMode()).isEqualTo(ChequeMode.CASH);
        assertThat(collection.getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(collection.getChequeDate()).isEqualTo(SETTLED_ON);
        assertThat(collection.getNarration()).isEqualTo("Settlement balance due");

        // The deposit is gone, and what the renter owes now sits against the
        // instrument raised to collect it.
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId)).isEqualByComparingTo("1760.27");
        assertThat(lease(leaseId).getStatus()).as("still chasing money").isEqualTo(LeaseStatus.TERMINATED);
        assertTrialBalanceBalances();
    }

    /**
     * A deduction the deposit <em>can</em> cover still refunds, and the arithmetic
     * is the same expression with a different sign.
     */
    @Test
    void aDeductionTheDepositCoversStillRefunds() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "4000"));

        assertThat(settlement.statement(leaseId).netRefund())
                .as("3,000 + 5,239.73 − 4,000").isEqualByComparingTo("4239.73");

        SettlementResponseDTO response = finalize(leaseId, leaf(AccountRole.BANK).getId());

        assertThat(response.getRefundAmount()).isEqualByComparingTo("4239.73");
        assertThat(response.getCollectionChequeId()).isNull();
        assertThat(creditOn(stlOf(leaseId), leaf(AccountRole.BANK).getId())).isEqualByComparingTo("4239.73");
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.CLOSED);
        assertTrialBalanceBalances();
    }

    /**
     * An addition is money the landlord hands back on top of the deposit, so the
     * {@code STL} <em>debits</em> its account and the refund grows.
     */
    @Test
    void anAdditionIsDebitedAndIncreasesTheRefund() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId, addition(AdditionCategory.DEPOSIT_INTEREST, "150"));
        UUID otherIncome = leaf(AccountRole.OTHER_INCOME).getId();

        SettlementStatementDTO statement = settlement.statement(leaseId);
        assertThat(statement.additions()).singleElement()
                .extracting(AdditionLineDTO::category, AdditionLineDTO::accountId)
                .containsExactly(AdditionCategory.DEPOSIT_INTEREST, otherIncome);
        assertThat(statement.netRefund()).isEqualByComparingTo("8389.73");

        finalize(leaseId, leaf(AccountRole.BANK).getId());

        JournalEntry stl = stlOf(leaseId);
        assertThat(debitOn(stl, otherIncome)).isEqualByComparingTo("150.00");
        assertThat(creditOn(stl, leaf(AccountRole.BANK).getId())).isEqualByComparingTo("8389.73");
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // the category rules
    // ------------------------------------------------------------------

    /**
     * Four categories that are already in the receivable, refused on Save.
     *
     * <p>Every one of them would charge the same money twice: the statement starts
     * from the receivable balance, which already carries unpaid rent, every
     * approved penalty, prepaid rent and a utility overpayment. A line for one of
     * them subtracts it a second time.</p>
     */
    @Test
    void rejectsUnpaidRentAndPenaltiesCategories() {
        UUID leaseId = terminatedGalah();

        assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.PENALTIES, "500")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Approved penalties are collected through their own register row;"
                        + " add a post-termination charge as EARLY_TERMINATION_FEE or OTHER");

        assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.UNPAID_RENT, "500")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Unpaid rent is already accounted for")
                .hasMessageContaining("what was kept for collection is on the cheque register");

        assertThatThrownBy(() -> saveDraft(leaseId, addition(AdditionCategory.PREPAID_RENT, "500")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("PREPAID_RENT is already a credit on the receivable balance");

        assertThatThrownBy(() -> saveDraft(leaseId, addition(AdditionCategory.UTILITY_OVERPAYMENT, "500")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("UTILITY_OVERPAYMENT is already a credit on the receivable balance");

        // Nothing was written, not even the settlement row the first call would
        // have created before it reached the line.
        assertThat(hasSettlementRow(leaseId)).as("a statement writes nothing").isFalse();

        // …and a post-termination charge has somewhere to go.
        saveDraft(leaseId, deduction(DeductionCategory.EARLY_TERMINATION_FEE, "500"));
        assertThat(settlement.statement(leaseId).deductions()).singleElement()
                .extracting(DeductionLineDTO::accountId)
                .isEqualTo(leaf(AccountRole.RENT_PENALTY).getId());
    }

    // ------------------------------------------------------------------
    // the account override
    // ------------------------------------------------------------------

    /** A valid override is what the {@code STL} credits, not the category's default. */
    @Test
    void anAccountOverrideIsWhatTheStlCredits() {
        UUID leaseId = terminatedGalah();
        UUID otherIncome = leaf(AccountRole.OTHER_INCOME).getId();
        UUID maintenance = leaf(AccountRole.MAINTENANCE_CHARGES).getId();

        saveDraft(leaseId, deduction(DeductionCategory.PROPERTY_DAMAGE, "1000", otherIncome));

        assertThat(settlement.statement(leaseId).deductions()).singleElement()
                .extracting(DeductionLineDTO::accountId).isEqualTo(otherIncome);

        finalize(leaseId, leaf(AccountRole.BANK).getId());

        JournalEntry stl = stlOf(leaseId);
        assertThat(creditOn(stl, otherIncome)).isEqualByComparingTo("1000.00");
        assertThat(creditOn(stl, maintenance)).as("not the category default").isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    /**
     * An override has to be an account this tenant can actually post a deduction
     * to: an active INCOME leaf of its own chart.
     *
     * <p>The bank case is the one that matters most. A deduction pointed at the
     * refund account would credit the very leaf the refund debits, netting the
     * charge to nothing while the statement went on showing it — a line the
     * accountant typed, agreed with the renter, and which moved no money.</p>
     */
    @Test
    void rejectsAnAccountOverrideThatIsNotAnActiveIncomeLeaf() {
        UUID leaseId = terminatedGalah();

        UUID group = tx.execute(s -> accounts.findByParentIsNullOrderByDisplayOrderAscCodeAsc().stream()
                .filter(Account::isGroup).findFirst().orElseThrow().getId());
        assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "100", group)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is a group account");

        UUID bank = leaf(AccountRole.BANK).getId();
        assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "100", bank)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("deduction needs an INCOME account");

        UUID retired = retire(leaf(AccountRole.OTHER_INCOME).getId());
        assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "100", retired)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("is inactive");

        assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "100", UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);

        assertThat(hasSettlementRow(leaseId)).as("a statement writes nothing").isFalse();
    }

    /** An account belonging to somebody else is simply not there. */
    @Test
    void anAccountFromAnotherTenantIsNotFound() {
        UUID leaseId = terminatedGalah();
        UUID mine = fixtures.tenantId();

        // A second organisation with its own chart, then back to ours.
        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        UUID theirIncome = tx.execute(s ->
                resolver.resolve(AccountRole.OTHER_INCOME, other.property().getId()).getId());
        TenantContextHolder.setTenantId(mine);

        assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "100", theirIncome)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Account not found");
    }

    // ------------------------------------------------------------------
    // the draft's lifecycle
    // ------------------------------------------------------------------

    /**
     * A draft is editable — rows added, amended and removed — right up to the
     * moment it is finalised, and closed to all three afterwards.
     */
    @Test
    void draftCanBeEditedUntilFinalized() {
        UUID leaseId = terminatedGalah();

        saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "500"));
        assertThat(settlement.statement(leaseId).totalDeductions()).isEqualByComparingTo("500.00");

        // Amend the row in place: the id comes back on the statement.
        UUID lineId = settlement.statement(leaseId).deductions().get(0).id();
        SaveSettlementDTO.DeductionItemDTO amended = deduction(DeductionCategory.CLEANING, "750");
        amended.setId(lineId);
        saveDraft(leaseId, amended, deduction(DeductionCategory.KEY_REPLACEMENT, "200"));

        SettlementStatementDTO twoLines = settlement.statement(leaseId);
        assertThat(twoLines.deductions()).hasSize(2);
        assertThat(twoLines.deductions().get(0).id()).as("amended in place, not replaced").isEqualTo(lineId);
        assertThat(twoLines.totalDeductions()).isEqualByComparingTo("950.00");
        assertThat(twoLines.netRefund()).as("8,239.73 − 950").isEqualByComparingTo("7289.73");

        // Drop one.
        SaveSettlementDTO.DeductionItemDTO keep = deduction(DeductionCategory.CLEANING, "750");
        keep.setId(lineId);
        saveDraft(leaseId, keep);
        assertThat(settlement.statement(leaseId).deductions()).hasSize(1);

        finalize(leaseId, leaf(AccountRole.BANK).getId());

        assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "1")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Settlement is already finalized");
        assertThat(settlement.statement(leaseId).deductions()).hasSize(1);
    }

    /** Finalising twice posts one {@code STL}, not two. */
    @Test
    void aSecondFinaliseIsRefused() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId);
        UUID bank = leaf(AccountRole.BANK).getId();
        finalize(leaseId, bank);
        UUID stlId = stlOf(leaseId).getId();

        assertThatThrownBy(() -> finalize(leaseId, bank))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Settlement is already finalized");

        assertThat(stlOf(leaseId).getId()).isEqualTo(stlId);
        assertThat(stlCount()).as("one settlement journal, not two").isEqualTo(1L);
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId)).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    /**
     * Save takes the same lease-row lock finalise takes, so a draft cannot be
     * written over a settlement that was finalised while the form was open
     * (review I-4).
     *
     * <p>Without the lock the two are not serialised at all: finalise claims the
     * lease row, save claims nothing, and a save that read a DRAFT before finalise
     * committed writes the whole row back afterwards — {@code status=DRAFT},
     * {@code journal_id=NULL}, {@code settlement_date=NULL} — with the {@code STL}
     * already posted and the refund already paid. The settlement can then be
     * finalised a second time, releasing the same deposit and re-charging the same
     * deductions.</p>
     *
     * <p>Sequenced rather than raced: a second transaction holds the lease row for
     * exactly as long as this test needs it to, which is what finalise would be
     * holding it for. The lock is NOWAIT, so the save fails immediately with the
     * register's "try again" rather than parking a connection.</p>
     */
    @Test
    void aSaveWhileTheLeaseRowIsHeldIsRefusedRatherThanOverwritingTheSettlement() throws Exception {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId);
        UUID bank = leaf(AccountRole.BANK).getId();
        UUID tenantId = fixtures.tenantId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                try {
                    tx.executeWithoutResult(s -> {
                        leaseRepo.findByIdForUpdate(leaseId).orElseThrow();
                        held.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                } finally {
                    TenantContextHolder.clear();
                }
            });
            assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "4000")))
                    .isInstanceOf(BusinessRuleViolationException.class)
                    .hasMessageContaining("This lease is being updated by another request");
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // Nothing of the refused save survived, and the settlement finalises once.
        assertThat(settlement.statement(leaseId).deductions()).as("the refused line").isEmpty();
        finalize(leaseId, bank);
        assertThat(stlCount()).as("one STL").isEqualTo(1L);
        assertThatThrownBy(() -> saveDraft(leaseId, deduction(DeductionCategory.CLEANING, "4000")))
                .as("the late save")
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Settlement is already finalized");
        assertThat(tx.execute(s -> settlement.buildSettlementResponse(leaseId)).getStatus())
                .isEqualTo(SettlementStatus.FINALIZED.name());
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // the guards around finalise
    // ------------------------------------------------------------------

    /**
     * A settlement cannot be finalised on a contract that is still running.
     *
     * <p>Finalising used to terminate the lease as a side effect. Without this
     * guard the two simply come apart: a FINALIZED settlement with the deposit
     * deemed released, on an ACTIVE lease whose unit is still occupied, whose
     * uncleared cheques are still on the register and whose rent the nightly job is
     * still recognising.</p>
     */
    @Test
    void aSettlementCannotBeFinalisedWhileTheLeaseIsStillRunning() {
        UUID leaseId = galah();
        saveDraft(leaseId);

        assertThatThrownBy(() -> finalize(leaseId, leaf(AccountRole.BANK).getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Terminate the lease before settling it; this one is ACTIVE.");

        assertThat(tx.execute(s -> settlement.buildSettlementResponse(leaseId)).getStatus())
                .isEqualTo(SettlementStatus.DRAFT.name());
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }

    /**
     * The {@code STL}'s date is a real day: on or after the tenancy ended, and in a
     * month the books are still open on.
     */
    @Test
    void theSettlementDateMustBeAfterTerminationAndInAnOpenPeriod() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId);
        UUID bank = leaf(AccountRole.BANK).getId();

        assertThatThrownBy(() -> settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(null, bank, false), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("A settlement needs a settlement date");

        assertThatThrownBy(() -> settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(T.minusDays(1), bank, false), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("The settlement date 2027-02-14 is before the lease was terminated (2027-02-15).");

        fiscal.lockThrough(LocalDate.of(2027, 2, 28));
        assertThatThrownBy(() -> finalize(leaseId, bank))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("Cannot settle on 2027-02-20: books are locked through 2027-02-28.");

        assertThat(tx.execute(s -> settlement.buildSettlementResponse(leaseId)).getStatus())
                .isEqualTo(SettlementStatus.DRAFT.name());

        // The first open day settles.
        SettlementResponseDTO done = settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(LocalDate.of(2027, 3, 1), bank, false), null);
        assertThat(done.getStatus()).isEqualTo(SettlementStatus.FINALIZED.name());
        assertThat(done.getSettlementDate()).isEqualTo(LocalDate.of(2027, 3, 1));
    }

    /** A refund needs somewhere to come from, and a balance due needs nothing. */
    @Test
    void aRefundNeedsAUsableBankAccount() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId);

        assertThatThrownBy(() -> finalize(leaseId, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("needs a bank account to pay from");

        assertThatThrownBy(() -> finalize(leaseId, leaf(AccountRole.OTHER_INCOME).getId()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot pay a refund; name an active asset leaf");

        assertThat(tx.execute(s -> settlement.buildSettlementResponse(leaseId)).getStatus())
                .isEqualTo(SettlementStatus.DRAFT.name());
    }

    /**
     * Without a tenant in context every figure a settlement is built from is
     * unreliable: the deposit, the receivable and the schedule are all JPQL reads
     * that depend on the Hibernate tenant filter, which {@code TenantAspect} only
     * enables when one is set.
     */
    @Test
    void aSettlementWithoutATenantInContextIsRefused() {
        UUID leaseId = galah();
        TenantContextHolder.clear();

        assertThatThrownBy(() -> settlement.statement(leaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
        // The same guard on both write paths: saveDraft reaches it through
        // findLeaseWithTenantCheck and finalise through lockLease.
        assertThatThrownBy(() -> saveDraft(leaseId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
        assertThatThrownBy(() -> finalize(leaseId, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant in context");
    }

    // ------------------------------------------------------------------
    // what the deposit figure is, and what it is not
    // ------------------------------------------------------------------

    /**
     * The contract charged 3,000; 1,000 has since been refunded. The statement owes
     * the renter what is left, not what the contract said.
     */
    @Test
    void depositsHeldIsWhatIsLeftAfterAPartialRefund() {
        UUID leaseId = galah();
        assertThat(settlement.statement(leaseId).depositsHeld()).isEqualByComparingTo("3000.00");

        refundDeposit(leaseId, "1000");

        assertThat(settlement.statement(leaseId).depositsHeld()).isEqualByComparingTo("2000.00");
        // The contract column is untouched, which is the point: the two numbers
        // legitimately differ and the statement is reading the ledger.
        assertThat(lease(leaseId).getDepositAmount()).isEqualByComparingTo("3000");
    }

    /**
     * The renter renewed and their deposit went with them. Settling the contract
     * they left owes them nothing — the money is held against the new one.
     *
     * <p>This is the case that used to refund a deposit the landlord had already
     * moved, i.e. pay it out twice.</p>
     */
    @Test
    void aCarriedForwardPredecessorHoldsNothing() {
        UUID predecessor = galah();

        LeaseDTO successor = renewal.renew(predecessor, new RenewLeaseRequest(
                END.minusDays(14), END.plusDays(1), END.plusYears(1), null, true));
        fixtures.generateGrid(successor.getId(), 4, END.plusDays(1));
        posting.post(successor.getId());

        assertThat(lease(predecessor).getStatus()).isEqualTo(LeaseStatus.RENEWED);
        assertThat(settlement.statement(predecessor).depositsHeld()).isEqualByComparingTo("0.00");
        assertThat(settlement.statement(successor.getId()).depositsHeld()).isEqualByComparingTo("3000.00");
    }

    // ------------------------------------------------------------------
    // a renewed predecessor is settled, never terminated (review I-1)
    // ------------------------------------------------------------------

    /**
     * Spec §6.6 makes the deposit the accountant's choice: carry it forward
     * <em>or</em> settle the old lease. Choosing not to carry it leaves 3,000 on
     * the predecessor's dimension, and that contract has to be settleable or the
     * liability is stranded for ever (review I-1).
     *
     * <p>A renewed predecessor is <b>settled, not terminated</b> — nothing was cut
     * short, so there is no unearned rent and no paper to hand back — which is why
     * {@code terminate} goes on refusing it and {@code finalizeSettlement} no longer
     * does. The statement is the ordinary one drawn from the ledger, and the closure
     * rule finishes the contract off exactly as it does an expired one.</p>
     */
    @Test
    void aRenewedPredecessorWhoseDepositStayedBehindIsSettledAndClosed() {
        UUID predecessor = galahFullyCollected();
        LeaseDTO successor = renewal.renew(predecessor, new RenewLeaseRequest(
                END.minusDays(14), END.plusDays(1), END.plusYears(1), null, false));
        fixtures.generateGrid(successor.getId(), 4, END.plusDays(1));
        posting.post(successor.getId());
        assertThat(lease(predecessor).getStatus()).isEqualTo(LeaseStatus.RENEWED);

        // Terminating it is still the wrong verb and is still refused.
        assertThatThrownBy(() -> termination.terminate(predecessor,
                new TerminateLeaseRequest(END, null, null, null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only an ACTIVE or NOTICE_GIVEN lease can be terminated");

        SettlementStatementDTO statement = settlement.statement(predecessor);
        assertThat(statement.depositsHeld()).as("nothing carried it forward")
                .isEqualByComparingTo("3000.00");
        assertThat(statement.receivableBalance()).as("a full term, fully collected")
                .isEqualByComparingTo("0.00");
        assertThat(statement.netRefund()).isEqualByComparingTo("3000.00");

        saveDraft(predecessor);
        UUID bank = leaf(AccountRole.BANK).getId();
        SettlementResponseDTO response = settlement.finalizeSettlement(predecessor,
                new FinalizeSettlementRequest(RENEWAL_SETTLED_ON, bank, false), null);

        assertThat(response.getStatus()).isEqualTo(SettlementStatus.FINALIZED.name());
        assertThat(response.getRefundAmount()).isEqualByComparingTo("3000.00");
        JournalEntry stl = stlOf(predecessor);
        assertThat(debitOn(stl, leaf(AccountRole.SECURITY_DEPOSIT).getId())).isEqualByComparingTo("3000.00");
        assertThat(creditOn(stl, bank)).isEqualByComparingTo("3000.00");

        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, predecessor)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, predecessor)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, predecessor)).isEqualByComparingTo("0.00");
        assertThat(lease(predecessor).getStatus()).isEqualTo(LeaseStatus.CLOSED);
        // The successor is untouched: it charged and collected a deposit of its own.
        assertThat(settlement.statement(successor.getId()).depositsHeld()).isEqualByComparingTo("3000.00");
        assertTrialBalanceBalances();
    }

    /**
     * …and the carried-forward half of the same choice settles to nothing and closes
     * just as cleanly: no deposit left, no receivable, no journal to post.
     */
    @Test
    void aCarriedForwardPredecessorSettlesToZeroAndCloses() {
        UUID predecessor = galahFullyCollected();
        LeaseDTO successor = renewal.renew(predecessor, new RenewLeaseRequest(
                END.minusDays(14), END.plusDays(1), END.plusYears(1), null, true));
        fixtures.generateGrid(successor.getId(), 4, END.plusDays(1));
        posting.post(successor.getId());

        SettlementStatementDTO statement = settlement.statement(predecessor);
        assertThat(statement.depositsHeld()).as("the JV moved it onto the successor")
                .isEqualByComparingTo("0.00");
        assertThat(statement.netRefund()).isEqualByComparingTo("0.00");

        saveDraft(predecessor);
        SettlementResponseDTO response = settlement.finalizeSettlement(predecessor,
                new FinalizeSettlementRequest(RENEWAL_SETTLED_ON, null, false), null);

        assertThat(response.getStatus()).isEqualTo(SettlementStatus.FINALIZED.name());
        assertThat(response.getRefundAmount()).isEqualByComparingTo("0.00");
        assertThat(response.getJournalId()).as("an entry for nothing is not a document").isNull();
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, predecessor)).isEqualByComparingTo("0.00");
        assertThat(lease(predecessor).getStatus()).isEqualTo(LeaseStatus.CLOSED);
        assertThat(settlement.statement(successor.getId()).depositsHeld()).isEqualByComparingTo("3000.00");
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // penalties are shown, never charged twice
    // ------------------------------------------------------------------

    /**
     * An approved penalty is shown outstanding and is <em>not</em> added to the
     * deductions: approving it already put the fine on the receivable, which the
     * statement subtracts.
     */
    @Test
    void anApprovedPenaltyIsShownOutstandingAndNeverDeductedTwice() {
        UUID leaseId = galah();
        BigDecimal receivableBefore = settlement.statement(leaseId).receivableBalance();

        PenaltyAssessmentDTO proposed = penalties.propose(new ProposePenaltyRequest(
                leaseId, null, PenaltyReason.LATE_PAYMENT, new BigDecimal("500"), "Late again"), null);
        assertThat(settlement.statement(leaseId).penaltiesOutstanding())
                .as("a proposal is finance still deciding").isEqualByComparingTo("0.00");

        PenaltyAssessmentDTO approved = penalties.approve(proposed.id(), LocalDate.of(2027, 1, 5));

        SettlementStatementDTO after = settlement.statement(leaseId);
        assertThat(after.penaltiesOutstanding()).isEqualByComparingTo("500.00");
        assertThat(after.totalDeductions()).as("shown, not charged again").isEqualByComparingTo("0.00");
        // The approval debited the receivable and its collection row's PDR credited
        // it straight back, so the fine's net movement THERE is nil — it is not "in
        // the receivable balance", it is on the register. That is why it must not
        // also be a deduction, and why it shows up as an outstanding instrument.
        assertThat(after.receivableBalance()).isEqualByComparingTo(receivableBefore);
        // The fine is in PDC receivable, with the two rent cheques that have not
        // fallen due yet — 12,750 + 12,750 + 500 on a lease that is still running.
        assertThat(after.instrumentsOutstanding())
                .as("the fine lives in PDC receivable").isEqualByComparingTo("26000.00");
        assertThat(after.outstandingInstruments())
                .filteredOn(OutstandingInstrumentDTO::penaltyCollection).singleElement()
                .extracting(OutstandingInstrumentDTO::amount, OutstandingInstrumentDTO::mode)
                .containsExactly(new BigDecimal("500.00"), ChequeMode.CASH);

        chequeService.receive(approved.collectionChequeId(), ChequeActionRequest.on(LocalDate.of(2027, 1, 5)));
        SettlementStatementDTO collected = settlement.statement(leaseId);
        assertThat(collected.penaltiesOutstanding()).isEqualByComparingTo("0.00");
        assertThat(collected.instrumentsOutstanding())
                .as("collecting it takes exactly its 500 off the register")
                .isEqualByComparingTo(after.instrumentsOutstanding().subtract(new BigDecimal("500.00")));
        assertThat(collected.outstandingInstruments())
                .noneMatch(OutstandingInstrumentDTO::penaltyCollection);
    }

    // ------------------------------------------------------------------
    // what the register is still holding (review I1)
    // ------------------------------------------------------------------

    /**
     * A kept cheque and an unpaid fine are money the landlord is still owed, and
     * neither is in the receivable the statement nets.
     *
     * <p>§9.1 keeps every uncleared instrument dated on or before {@code T} for
     * collection: its {@code PDR} stands, so its money is in {@code PDC_RECEIVABLE}.
     * An approved penalty is the same shape — the {@code PEN} debits the receivable
     * and the collection row's {@code PDR} credits it back. So the statement can
     * show a healthy refund while 13,250 of paper is still outstanding, and paying
     * that refund out is a decision somebody has to take deliberately.</p>
     *
     * <p>The fixture terminates on <b>2027-01-20</b> rather than 15 Feb so the
     * 2 Jan cheque is <em>uncleared and dated before T</em>, which is exactly what
     * the keep list is for. 2 Oct clears, 2 Jan is kept, 2 Apr and 2 Jul go back.</p>
     */
    @Test
    void statementListsWhatTheRegisterIsStillHolding() {
        UUID leaseId = galahKeptAndFined();

        SettlementStatementDTO statement = settlement.statement(leaseId);

        assertThat(statement.penaltiesOutstanding()).isEqualByComparingTo("500.00");
        assertThat(statement.instrumentsOutstanding())
                .as("12,750 kept + 500 fine").isEqualByComparingTo("13250.00");
        assertThat(statement.outstandingInstruments())
                .extracting(OutstandingInstrumentDTO::amount, OutstandingInstrumentDTO::chequeDate,
                        OutstandingInstrumentDTO::mode, OutstandingInstrumentDTO::status,
                        OutstandingInstrumentDTO::penaltyCollection)
                .containsExactly(
                        tuple(new BigDecimal("12750.00"), RENT_2, ChequeMode.PDC,
                                ChequeStatus.REGISTERED, false),
                        tuple(new BigDecimal("500.00"), KEPT_PENALTY_ON, ChequeMode.CASH,
                                ChequeStatus.REGISTERED, true));
        assertThat(statement.outstandingInstruments().get(0).seqNo())
                .as("the register's own numbering travels with the row").isPositive();
        // And none of it moved netRefund: an uncleared instrument is collected
        // through the register, never netted silently against a deposit.
        assertThat(statement.netRefund()).isEqualByComparingTo(
                statement.depositsHeld().subtract(statement.receivableBalance()));
    }

    /**
     * Refunding a deposit while the register is still holding something has to be
     * acknowledged; collecting a balance never does.
     */
    @Test
    void aRefundWhileInstrumentsAreOutstandingNeedsAcknowledgement() {
        UUID leaseId = galahKeptAndFined();
        saveDraft(leaseId);
        UUID bank = leaf(AccountRole.BANK).getId();

        assertThat(settlement.statement(leaseId).netRefund()).isPositive();

        assertThatThrownBy(() -> settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(KEPT_SETTLED_ON, bank, false), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("AED 13,250.00 is still outstanding on the cheque register;"
                        + " acknowledge it to refund the deposit anyway");
        assertThat(tx.execute(s -> settlement.buildSettlementResponse(leaseId)).getStatus())
                .isEqualTo(SettlementStatus.DRAFT.name());

        SettlementResponseDTO done = settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(KEPT_SETTLED_ON, bank, true), null);

        assertThat(done.getStatus()).isEqualTo(SettlementStatus.FINALIZED.name());
        assertThat(done.getRefundAmount()).isPositive();
        // Task 7's rule: the kept cheque and the fine are still outstanding, so the
        // contract is not finished with however healthy the refund was.
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.TERMINATED);
        // …and the acknowledgement is on the lease's trail, since no column fits it.
        assertThat(leaseEventNotes(leaseId))
                .anyMatch(n -> n.contains("13,250.00") && n.contains("acknowledged"));
        assertTrialBalanceBalances();
    }

    /**
     * A settlement the renter owes money on needs no acknowledgement: nothing is
     * being handed back, so there is nothing to decide.
     */
    @Test
    void aBalanceDueNeedsNoAcknowledgement() {
        UUID leaseId = galahWithAKeptCheque();
        saveDraft(leaseId, deduction(DeductionCategory.PROPERTY_DAMAGE, "40000"));
        assertThat(settlement.statement(leaseId).netRefund()).isNegative();
        assertThat(settlement.statement(leaseId).instrumentsOutstanding()).isPositive();

        SettlementResponseDTO done = settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(KEPT_SETTLED_ON, null, false), null);

        assertThat(done.getStatus()).isEqualTo(SettlementStatus.FINALIZED.name());
        assertThat(done.getBalanceDue()).isPositive();
        assertThat(done.getCollectionChequeId()).isNotNull();
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // the cases the review found untested (I2)
    // ------------------------------------------------------------------

    /**
     * Two deposits on two accounts: each leaf is debited for <em>its own</em>
     * balance, and a deposit account holding nothing produces no line at all.
     *
     * <p>This is the one place where the posted lines and {@code depositsHeld}
     * could silently diverge — they are both read off
     * {@code LeaseDepositLedger.heldByAccount}, so the assertion that the statement
     * figure equals the sum of the journal's deposit debits is what pins them
     * together.</p>
     */
    @Test
    void twoDepositAccountsAreEachDebitedForTheirOwnBalance() {
        UUID leaseId = galahWithParkingDeposit();
        saveDraft(leaseId);
        UUID security = leaf(AccountRole.SECURITY_DEPOSIT).getId();
        UUID parking = leaf(AccountRole.PARKING_DEPOSIT).getId();
        UUID maintenance = leaf(AccountRole.MAINTENANCE_CHARGES).getId();
        assertThat(parking).isNotEqualTo(security);

        SettlementStatementDTO statement = settlement.statement(leaseId);
        assertThat(statement.depositsHeld()).as("3,000 + 1,500").isEqualByComparingTo("4500.00");

        finalize(leaseId, leaf(AccountRole.BANK).getId());

        JournalEntry stl = stlOf(leaseId);
        assertThat(debitOn(stl, security)).isEqualByComparingTo("3000.00");
        assertThat(debitOn(stl, parking)).isEqualByComparingTo("1500.00");
        assertThat(debitOn(stl, security).add(debitOn(stl, parking)))
                .as("depositsHeld is exactly the sum of the posted deposit lines")
                .isEqualByComparingTo(statement.depositsHeld());
        // A deposit account this lease holds nothing in is not a zero line.
        assertThat(debitOn(stl, maintenance)).isEqualByComparingTo("0.00");
        assertThat(linesOf(stl.getId())).hasSize(4);

        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.PARKING_DEPOSIT, leaseId)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    /**
     * A tenancy that simply ran out is settled by the same statement, without
     * §9.1's steps 1–2 (spec §9.2, last sentence).
     *
     * <p>Nothing was handed back and nothing unearned was reversed, so the renter's
     * receivable is whatever their uncleared paper left — and the deposit still has
     * to come off the books.</p>
     */
    @Test
    void anExpiredLeaseIsSettledByTheSameStatement() {
        UUID leaseId = expiredGalah();
        saveDraft(leaseId);
        UUID bank = leaf(AccountRole.BANK).getId();
        UUID deposit = leaf(AccountRole.SECURITY_DEPOSIT).getId();

        SettlementStatementDTO statement = settlement.statement(leaseId);
        assertThat(statement.depositsHeld()).isEqualByComparingTo("3000.00");
        assertThat(statement.netRefund()).isEqualByComparingTo(
                statement.depositsHeld().subtract(statement.receivableBalance()));

        SettlementResponseDTO done = settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(END.plusDays(5), bank, true), null);

        assertThat(done.getStatus()).isEqualTo(SettlementStatus.FINALIZED.name());
        assertThat(done.getSettlementDate()).isEqualTo(END.plusDays(5));
        assertThat(debitOn(stlOf(leaseId), deposit)).isEqualByComparingTo("3000.00");
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId)).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    /** …and it is refused on a date before the tenancy actually ended. */
    @Test
    void anExpiredLeaseCannotBeSettledBeforeItEnded() {
        UUID leaseId = expiredGalah();
        saveDraft(leaseId);

        assertThatThrownBy(() -> settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(END.minusDays(1), leaf(AccountRole.BANK).getId(), true), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("The settlement date 2027-09-22 is before the lease ended (2027-09-23).");
    }

    /**
     * A settlement that owes nothing either way: no bank line, no collection row,
     * and an {@code STL} that still balances.
     *
     * <p>Reached by a deduction sized to the refund exactly. There <em>is</em> a
     * journal — the deposit still has to come off the books and the receivable
     * still has to flatten — it simply has no cash leg.</p>
     */
    @Test
    void aSettlementThatNetsToZeroPostsNoBankLineAndCollectsNothing() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId, deduction(DeductionCategory.PROPERTY_DAMAGE, "8239.73"));
        UUID bank = leaf(AccountRole.BANK).getId();
        UUID deposit = leaf(AccountRole.SECURITY_DEPOSIT).getId();
        UUID receivable = leaf(AccountRole.RENT_RECEIVABLE).getId();
        UUID maintenance = leaf(AccountRole.MAINTENANCE_CHARGES).getId();

        assertThat(settlement.statement(leaseId).netRefund()).isEqualByComparingTo("0.00");

        // No bank account is named and none is needed.
        SettlementResponseDTO done = settlement.finalizeSettlement(leaseId,
                new FinalizeSettlementRequest(SETTLED_ON, null, false), null);

        assertThat(done.getRefundAmount()).isEqualByComparingTo("0.00");
        assertThat(done.getBalanceDue()).isEqualByComparingTo("0.00");
        assertThat(done.getCollectionChequeId()).isNull();
        assertThat(done.getRefundBankAccountId()).isNull();

        JournalEntry stl = stlOf(leaseId);
        assertThat(linesOf(stl.getId())).hasSize(3);
        assertThat(debitOn(stl, deposit)).isEqualByComparingTo("3000.00");
        assertThat(debitOn(stl, receivable)).isEqualByComparingTo("5239.73");
        assertThat(creditOn(stl, maintenance)).isEqualByComparingTo("8239.73");
        assertThat(creditOn(stl, bank)).as("nothing is paid out").isEqualByComparingTo("0.00");

        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, leaseId)).isEqualByComparingTo("0.00");
        // Nothing outstanding and the settlement is finalised, so Task 7's rule closes it.
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.CLOSED);
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // tenant isolation
    // ------------------------------------------------------------------

    /** Another organisation's settlement is simply not there. */
    @Test
    void anotherTenantCanNeitherReadNorFinaliseThisSettlement() {
        UUID leaseId = terminatedGalah();
        saveDraft(leaseId);

        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        assertThat(other.tenantId()).isNotEqualTo(fixtures.tenantId());

        assertThatThrownBy(() -> settlement.statement(leaseId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> saveDraft(leaseId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> finalize(leaseId, null)).isInstanceOf(NotFoundException.class);

        TenantContextHolder.setTenantId(fixtures.tenantId());
        assertThat(tx.execute(s -> settlement.buildSettlementResponse(leaseId)).getStatus())
                .isEqualTo(SettlementStatus.DRAFT.name());
    }

    // ------------------------------------------------------------------
    // fixtures that write money
    // ------------------------------------------------------------------

    /**
     * Hand part of the deposit back: {@code Dr SECURITY_DEPOSIT} on the lease's
     * dimension, {@code Cr BANK}. The shape a refund voucher will have in plan 4 —
     * what matters here is that the deposit account's balance on this lease drops.
     */
    private void refundDeposit(UUID leaseId, String amount) {
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            Account deposit = resolver.resolve(AccountRole.SECURITY_DEPOSIT, fixtures.property().getId());
            Account bank = resolver.resolve(AccountRole.BANK, fixtures.property().getId());
            BigDecimal value = new BigDecimal(amount);
            PostingRequest.Dimensions dims = LeaseChequeRegistrar.dimensions(lease, null);
            postingService.post(PostingRequest.ofPairs(
                    JournalDocType.JV, LocalDate.of(2027, 1, 20), "Partial deposit refund", dims,
                    com.datagami.rentaxis.domain.entity.enums.JournalSourceType.LEASE, lease.getId(), null,
                    List.of(PostingRequest.pair(
                            PostingRequest.dr(deposit.getId(), value).withDims(dims),
                            PostingRequest.cr(bank.getId(), value).withDims(dims)))));
        });
    }

    /** The lease's audit trail, newest first — where the acknowledgement is recorded. */
    private List<String> leaseEventNotes(UUID leaseId) {
        return tx.execute(s -> leaseEvents.findByLeaseIdOrderByCreatedAtDesc(leaseId).stream()
                .map(com.datagami.rentaxis.domain.entity.LeaseEvent::getNotes)
                .filter(n -> n != null).toList());
    }

    /** Retires a leaf, so "inactive" is a real state of a real account. */
    private UUID retire(UUID accountId) {
        return tx.execute(s -> {
            Account account = accounts.findById(accountId).orElseThrow();
            account.setActive(false);
            return accounts.save(account).getId();
        });
    }

}
