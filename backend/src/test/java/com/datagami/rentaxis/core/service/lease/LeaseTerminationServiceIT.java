package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminationPreviewDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RentSegmentRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Terminating a contract on a date (spec §9.1), against a real database.
 *
 * <p>The fixture is Anil's own: 51,000 of rent over 24 Sep 2026 → 23 Sep 2027
 * (365 days, day rate 139.726027) plus a 2,000 admin fee, paid by a 2,000 cheque
 * dated 11 Sep 2026 and four of 12,750 dated 2 Oct 2026, 2 Jan, 2 Apr and 2 Jul
 * 2027. The admin cheque and the first two rent cheques clear; recognition is run
 * through 31 Jan 2027. That is a tenancy five months in with 25,500 of paper still
 * in the drawer, which is what a termination actually walks into.</p>
 *
 * <p><b>Every figure here was re-derived from {@code ProrationEngine}</b>, not
 * copied off the plan. Two of them differ from it by a fil and both differences
 * are the point of a test rather than a rounding nuisance:</p>
 * <ul>
 *   <li>the 1–15 Feb replacement is <b>2,095.88</b>
 *       ({@code earnedThrough(2027-02-15) 20,260.27 − recognised 18,164.39}), not
 *       2,095.89;</li>
 *   <li>terminating on 31 Jan — a date that falls exactly on a posted slice's own
 *       period end — re-cuts that slice to <b>4,331.50</b> against the
 *       <b>4,331.51</b> that was posted, because the cut amount is defined against
 *       {@code earnedThrough} and four months of rounding have drifted one fil.
 *       A termination that compared dates alone would leave that fil behind
 *       permanently.</li>
 * </ul>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} enables the Hibernate tenant filter
 * only inside one, so every read-back goes through {@link #tx}.</p>
 */
@SpringBootTest
@Testcontainers
class LeaseTerminationServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LeaseTerminationService termination;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired ChequeService chequeService;
    @Autowired RecognitionService recognition;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired RentSegmentRepository segments;
    @Autowired JournalEntryRepository journals;
    @Autowired JournalLineRepository journalLines;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);

    private static final LocalDate ADMIN_CHEQUE = LocalDate.of(2026, 9, 11);
    private static final LocalDate RENT_1 = LocalDate.of(2026, 10, 2);
    private static final LocalDate RENT_2 = LocalDate.of(2027, 1, 2);
    private static final LocalDate RENT_3 = LocalDate.of(2027, 4, 2);
    private static final LocalDate RENT_4 = LocalDate.of(2027, 7, 2);

    /** The termination date the plan's scenario uses: mid-month, mid-term. */
    private static final LocalDate T = LocalDate.of(2027, 2, 15);

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
     * The lease on the books with its five instruments registered.
     *
     * <p>The grid is typed out rather than generated because the dates are the
     * scenario: a termination's whole first step is "which of these is dated after
     * T", and a generator's own instalment dates would make the answer an accident
     * of the generator. Every row goes through {@code ChequeGenerationService} and
     * then {@code LeasePostingService}, so the register's money is real — the
     * assertions below read it.
     */
    private UUID galah() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("100040", ADMIN_CHEQUE, ADMIN_CHEQUE, "2000"),
                row("100041", CONTRACT_DATE, RENT_1, "12750"),
                row("100042", CONTRACT_DATE, RENT_2, "12750"),
                row("100043", CONTRACT_DATE, RENT_3, "12750"),
                row("100044", CONTRACT_DATE, RENT_4, "12750")));
        posting.post(leaseId);
        return leaseId;
    }

    /** …and the three instruments that have been banked, each cleared on its own date. */
    private UUID galahWithThreeCleared() {
        UUID leaseId = galah();
        clearOnItsOwnDate(chequeOn(leaseId, ADMIN_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_1));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_2));
        return leaseId;
    }

    private static ChequeRowInput row(String number, LocalDate postingDate, LocalDate chequeDate, String amount) {
        return new ChequeRowInput(null, null, postingDate, number, chequeDate, "Emirates NBD",
                null, null, new BigDecimal(amount), null, null);
    }

    /**
     * Banked and cleared on the day written on the paper — so no row is ever late
     * and no penalty proposal appears to muddy what the register is holding.
     */
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

    private ChequeStatus statusOf(UUID leaseId, LocalDate chequeDate) {
        return chequeOn(leaseId, chequeDate).getStatus();
    }

    private List<RecognitionEntryDTO> schedule(UUID leaseId) {
        return recognition.scheduleFor(leaseId);
    }

    private List<RecognitionEntryDTO> withStatus(UUID leaseId, RecognitionStatus status) {
        return schedule(leaseId).stream().filter(r -> r.status() == status).toList();
    }

    /** Σ of the lease's POSTED rows — its recognised income, net of anything reversed. */
    private BigDecimal recognised(UUID leaseId) {
        return withStatus(leaseId, RecognitionStatus.POSTED).stream()
                .map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private RentSegment segment(UUID leaseId) {
        List<RentSegment> all = tx.execute(s -> segments.findByLease_IdOrderByFromDateAsc(leaseId));
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private Lease lease(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }

    private Unit unit(UUID unitId) {
        return tx.execute(s -> unitRepo.findById(unitId).orElseThrow());
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    private JournalEntry journal(UUID id) {
        return tx.execute(s -> journals.findById(id).orElseThrow());
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    /** An account's closing balance on this lease alone; a credit balance reads negative. */
    private BigDecimal balanceOf(AccountRole role, UUID leaseId) {
        return tx.execute(s -> ledger.accountLedger(leaf(role).getId(),
                new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private long journalCount(JournalDocType docType) {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = ?",
                Long.class, fixtures.tenantId(), docType.name());
    }

    private long reversalCount() {
        return jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and source_type = 'REVERSAL'",
                Long.class, fixtures.tenantId());
    }

    /** Σ debits − Σ credits over the whole tenant: this can only move on a half-entry. */
    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(LocalDate.of(2030, 1, 1), null));
        assertThat(rows).as("trial balance rows").isNotEmpty();
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance debits").isGreaterThan(BigDecimal.ZERO);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }

    // ------------------------------------------------------------------
    // preview
    // ------------------------------------------------------------------

    /**
     * The default split and every figure on the termination screen, with nothing
     * written.
     *
     * <p>Anil's scenario exactly: terminate on 15 Feb 2027, five months into a
     * twelve-month tenancy. The two cheques dated after that day go back; the two
     * that were already banked are not the termination's business; nothing is left
     * uncleared and dated on or before T, so the keep list is empty.</p>
     */
    @Test
    void previewDefaultsReturnChequesDatedAfterT() {
        UUID leaseId = galahWithThreeCleared();
        recognition.runTo(LocalDate.of(2027, 1, 31), false);

        TerminationPreviewDTO preview = termination.preview(leaseId, T);

        assertThat(preview.terminationDate()).isEqualTo(T);
        // 139.726027 × 145 days (24 Sep → 15 Feb inclusive) = 20,260.273915
        assertThat(preview.earnedRentThroughDate()).isEqualByComparingTo("20260.27");
        // Sep 978.08 + Oct 4,331.51 + Nov 4,191.78 + Dec 4,331.51 + Jan 4,331.51
        assertThat(preview.recognisedSoFar()).isEqualByComparingTo("18164.39");
        assertThat(preview.unearnedRent()).isEqualByComparingTo("30739.73");

        assertThat(preview.chequesToReturn()).extracting(ChequeDTO::chequeDate)
                .containsExactly(RENT_3, RENT_4);
        assertThat(preview.chequesToReturn()).allSatisfy(c ->
                assertThat(c.amount()).isEqualByComparingTo("12750"));
        assertThat(preview.chequesToKeep()).isEmpty();
        assertThat(preview.bouncedOutstanding()).isEmpty();

        // 0 receivable today (the TCO's 53,000 exactly met by five PDRs), plus the
        // 25,500 the returns put back on the renter's account, less the 30,739.73 of
        // advance rent handed over: the landlord ends up owing 5,239.73.
        assertThat(preview.receivableAfter()).isEqualByComparingTo("-5239.73");

        // ...and it wrote nothing.
        assertThat(register(leaseId)).extracting(Cheque::getStatus)
                .containsExactly(ChequeStatus.CLEARED, ChequeStatus.CLEARED, ChequeStatus.CLEARED,
                        ChequeStatus.REGISTERED, ChequeStatus.REGISTERED);
        assertThat(segment(leaseId).getStatus()).isEqualTo(SegmentStatus.ACTIVE);
        assertThat(withStatus(leaseId, RecognitionStatus.PLANNED)).hasSize(8);
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(lease(leaseId).getTerminatedOn()).isNull();
        assertThat(journalCount(JournalDocType.TCR)).isZero();
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // terminate
    // ------------------------------------------------------------------

    /**
     * The whole act: the paper goes back, the schedule is cut, the unearned rent is
     * reversed and the contract closes — and the renter's receivable is left at
     * exactly <em>earned − received</em>.
     */
    @Test
    void terminatePostsReturnReversalsTruncatesRecognitionAndReversesUnearned() {
        UUID leaseId = galahWithThreeCleared();
        recognition.runTo(LocalDate.of(2027, 1, 31), false);

        LeaseDTO result = termination.terminate(leaseId,
                new TerminateLeaseRequest(T, null, null, "Renter relocating"), null);

        // ---- the cheques --------------------------------------------------
        assertThat(statusOf(leaseId, RENT_3)).isEqualTo(ChequeStatus.RETURNED);
        assertThat(statusOf(leaseId, RENT_4)).isEqualTo(ChequeStatus.RETURNED);
        assertThat(chequeOn(leaseId, RENT_3).getReturnedAt()).isEqualTo(T);
        for (LocalDate date : List.of(RENT_3, RENT_4)) {
            JournalEntry pdr = journal(chequeOn(leaseId, date).getPdrJournalId());
            assertThat(pdr.getStatus()).as("PDR for " + date).isEqualTo(JournalStatus.REVERSED);
            JournalEntry reversal = journal(pdr.getReversedById());
            assertThat(reversal.getEntryDate()).isEqualTo(T);
            assertThat(reversal.getNarration()).contains("Contract terminated " + T);
        }
        // The three that cleared are untouched: that money arrived.
        assertThat(statusOf(leaseId, RENT_1)).isEqualTo(ChequeStatus.CLEARED);

        // ---- the schedule --------------------------------------------------
        List<RecognitionEntryDTO> rows = schedule(leaseId);
        assertThat(rows).hasSize(13);
        RecognitionEntryDTO february = rows.get(5);
        assertThat(february.status()).isEqualTo(RecognitionStatus.PLANNED);
        assertThat(february.periodStart()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(february.periodEnd()).isEqualTo(T);
        assertThat(february.days()).isEqualTo(15);
        // 20,260.27 earned through 15 Feb, less the 18,164.39 already recognised.
        assertThat(february.amount()).isEqualByComparingTo("2095.88");
        assertThat(february.journalId()).isNull();
        assertThat(rows.subList(6, 13)).allSatisfy(r ->
                assertThat(r.status()).isEqualTo(RecognitionStatus.CANCELLED));

        RentSegment segment = segment(leaseId);
        assertThat(segment.getStatus()).isEqualTo(SegmentStatus.TRUNCATED);
        assertThat(segment.getToDate()).isEqualTo(T);
        assertThat(segment.getDays()).isEqualTo(145);
        // The contract value and the rate the earlier months were worth are left
        // alone: the unearned reversal was computed against the first and would be
        // restated by re-deriving the second.
        assertThat(segment.getAmount()).isEqualByComparingTo("51000");
        assertThat(segment.getDayRate()).isEqualByComparingTo("139.726027");

        // ---- the TCR --------------------------------------------------------
        UUID tcrId = lease(leaseId).getTerminationJournalId();
        assertThat(tcrId).isNotNull();
        JournalEntry tcr = journal(tcrId);
        assertThat(tcr.getDocType()).isEqualTo(JournalDocType.TCR);
        assertThat(tcr.getEntryDate()).isEqualTo(T);
        assertThat(tcr.getNarration()).isEqualTo("Unearned rent reversed on termination");
        assertThat(tcr.getLeaseId()).isEqualTo(leaseId);
        List<JournalLine> tcrLines = linesOf(tcrId);
        assertThat(tcrLines).hasSize(2);
        assertThat(tcrLines.get(0).getAccountId()).isEqualTo(leaf(AccountRole.ADVANCE_RENT).getId());
        assertThat(tcrLines.get(0).getDebit()).isEqualByComparingTo("30739.73");
        assertThat(tcrLines.get(1).getAccountId()).isEqualTo(leaf(AccountRole.RENT_RECEIVABLE).getId());
        assertThat(tcrLines.get(1).getCredit()).isEqualByComparingTo("30739.73");

        // ---- what the renter is left owing ----------------------------------
        // 22,260.27 earned (20,260.27 of rent + the 2,000 admin fee) against 27,500
        // received: the landlord owes 5,239.73, and a rent receivable in credit is
        // exactly how that reads.
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("-5239.73");

        // ---- the contract ----------------------------------------------------
        assertThat(result.getStatus()).isEqualTo(LeaseStatus.TERMINATED);
        assertThat(result.getTerminatedOn()).isEqualTo(T);
        assertThat(result.getTerminationJournalId()).isEqualTo(tcrId);
        assertThat(result.getTerminationNotes()).isEqualTo("Renter relocating");
        assertThat(unit(fixtures.unit().getId()).getStatus()).isEqualTo(UnitStatus.VACANT);
        assertThat(unit(fixtures.unit().getId()).getCurrentTenantName()).isNull();
        assertTrialBalanceBalances();
    }

    /**
     * A month already closed past {@code T} is reversed, and the month containing
     * {@code T} is reversed <em>and reposted</em> at the truncated amount — inside
     * the termination's own transaction, so it cannot outlive a refusal later on.
     */
    @Test
    void terminateWithPostedEntryAfterT() {
        UUID leaseId = galahWithThreeCleared();
        recognition.runTo(LocalDate.of(2027, 3, 31), false);
        assertThat(recognised(leaseId)).isEqualByComparingTo("26408.23");   // Sep–Mar

        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);

        List<RecognitionEntryDTO> rows = schedule(leaseId);
        // Twelve of the original thirteen, plus the replacement for 1–15 Feb.
        assertThat(rows).hasSize(14);

        List<RecognitionEntryDTO> february = rows.stream()
                .filter(r -> r.periodStart().equals(LocalDate.of(2027, 2, 1))).toList();
        assertThat(february).hasSize(2);
        assertThat(february).extracting(RecognitionEntryDTO::status, RecognitionEntryDTO::periodEnd,
                        RecognitionEntryDTO::amount)
                .containsExactlyInAnyOrder(
                        tuple(RecognitionStatus.REVERSED, LocalDate.of(2027, 2, 28), new BigDecimal("3912.33")),
                        tuple(RecognitionStatus.POSTED, T, new BigDecimal("2095.88")));

        RecognitionEntryDTO replacement = february.stream()
                .filter(r -> r.status() == RecognitionStatus.POSTED).findFirst().orElseThrow();
        assertThat(replacement.journalNumber()).startsWith("CIL");
        assertThat(journal(replacement.journalId()).getEntryDate()).isEqualTo(T);
        // The replacement is a NEW row with a CIL of its own; the reversed one keeps
        // pointing at the journal an auditor can still see.
        RecognitionEntryDTO reversed = february.stream()
                .filter(r -> r.status() == RecognitionStatus.REVERSED).findFirst().orElseThrow();
        assertThat(reversed.id()).isNotEqualTo(replacement.id());
        assertThat(journal(reversed.journalId()).getStatus()).isEqualTo(JournalStatus.REVERSED);

        // March was wholly after T: reversed, not re-cut.
        RecognitionEntryDTO march = rows.stream()
                .filter(r -> r.periodStart().equals(LocalDate.of(2027, 3, 1))).findFirst().orElseThrow();
        assertThat(march.status()).isEqualTo(RecognitionStatus.REVERSED);
        assertThat(journal(march.journalId()).getStatus()).isEqualTo(JournalStatus.REVERSED);

        // The whole point: what the ledger has taken to income is now exactly what
        // the tenancy earned, to the fil.
        assertThat(recognised(leaseId)).isEqualByComparingTo("20260.27");
        assertThat(recognised(leaseId).add(new BigDecimal("30739.73"))).isEqualByComparingTo("51000.00");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("-5239.73");
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, leaseId)).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    /**
     * {@code T} on a calendar month-end, with that month already posted.
     *
     * <p>It looks like the one case where the month containing {@code T} should be
     * left alone — the dates match exactly. They do; the money does not. The cut
     * amount is {@code earnedThrough(31 Jan) − Σ the four earlier rows} =
     * 18,164.38 − 13,832.88 = <b>4,331.50</b>, and 4,331.51 was posted, because the
     * posted rows were each rounded on their own while {@code earnedThrough} rounds
     * once. Comparing periods alone would leave the lease's recognised income one
     * fil away from what it earned, for good.</p>
     */
    @Test
    void terminationOnAMonthEndRepostsTheMonthAtTheTruncatedAmount() {
        UUID leaseId = galahWithThreeCleared();
        LocalDate monthEnd = LocalDate.of(2027, 1, 31);
        recognition.runTo(monthEnd, false);
        assertThat(recognised(leaseId)).isEqualByComparingTo("18164.39");

        termination.terminate(leaseId, new TerminateLeaseRequest(monthEnd, null, null, null), null);

        List<RecognitionEntryDTO> january = schedule(leaseId).stream()
                .filter(r -> r.periodStart().equals(LocalDate.of(2027, 1, 1))).toList();
        assertThat(january).extracting(RecognitionEntryDTO::status, RecognitionEntryDTO::periodEnd,
                        RecognitionEntryDTO::amount)
                .containsExactlyInAnyOrder(
                        tuple(RecognitionStatus.REVERSED, monthEnd, new BigDecimal("4331.51")),
                        tuple(RecognitionStatus.POSTED, monthEnd, new BigDecimal("4331.50")));

        // 139.726027 × 130 days (24 Sep → 31 Jan inclusive) = 18,164.38351
        assertThat(recognised(leaseId)).isEqualByComparingTo("18164.38");
        // ...and the split of the contract is exact: nothing was lost to rounding.
        assertThat(recognised(leaseId).add(new BigDecimal("32835.62"))).isEqualByComparingTo("51000.00");
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, leaseId)).isEqualByComparingTo("0.00");

        RentSegment segment = segment(leaseId);
        assertThat(segment.getStatus()).isEqualTo(SegmentStatus.TRUNCATED);
        assertThat(segment.getToDate()).isEqualTo(monthEnd);
        assertThat(segment.getDays()).isEqualTo(130);

        JournalEntry tcr = journal(lease(leaseId).getTerminationJournalId());
        assertThat(linesOf(tcr.getId()).get(0).getDebit()).isEqualByComparingTo("32835.62");
        // 25,500 of returned paper against 32,835.62 handed back.
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("-7335.62");
        assertTrialBalanceBalances();
    }

    /** Finance overrules the default: one cheque is kept for collection, only the other goes back. */
    @Test
    void keepListOverridesDefault() {
        UUID leaseId = galahWithThreeCleared();
        recognition.runTo(LocalDate.of(2027, 1, 31), false);
        UUID keep = chequeOn(leaseId, RENT_3).getId();
        UUID hand = chequeOn(leaseId, RENT_4).getId();

        termination.terminate(leaseId,
                new TerminateLeaseRequest(T, List.of(hand), List.of(keep), "April cheque banked anyway"), null);

        assertThat(statusOf(leaseId, RENT_3)).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(statusOf(leaseId, RENT_4)).isEqualTo(ChequeStatus.RETURNED);
        assertThat(journal(chequeOn(leaseId, RENT_3).getPdrJournalId()).getStatus())
                .isEqualTo(JournalStatus.POSTED);

        // Only 12,750 came back onto the receivable, so the renter still owes the
        // April instalment against the same 30,739.73 of advance rent handed over.
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("-17989.73");
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.TERMINATED);
        assertTrialBalanceBalances();
    }

    /**
     * A partial answer about the cheques is refused, and refused before anything
     * moves.
     *
     * <p>"Return this one" read on its own means "and do whatever you like with the
     * rest", and what happens to the rest is the difference between a renter
     * getting their cheque back and the landlord banking it. So the request has to
     * account for every uncleared row.</p>
     */
    @Test
    void everyUnclearedChequeMustBeInExactlyOneList() {
        UUID leaseId = galahWithThreeCleared();
        UUID april = chequeOn(leaseId, RENT_3).getId();
        UUID july = chequeOn(leaseId, RENT_4).getId();
        UUID alreadyCleared = chequeOn(leaseId, RENT_1).getId();

        assertThatThrownBy(() -> termination.terminate(leaseId,
                new TerminateLeaseRequest(T, List.of(april), null, null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("in neither list")
                .hasMessageContaining(july.toString());

        assertThatThrownBy(() -> termination.terminate(leaseId,
                new TerminateLeaseRequest(T, List.of(april, july), List.of(april), null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("both returned and kept");

        assertThatThrownBy(() -> termination.terminate(leaseId,
                new TerminateLeaseRequest(T, List.of(april, july, alreadyCleared), List.of(), null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not uncleared rows of this lease");

        assertThat(register(leaseId)).extracting(Cheque::getStatus)
                .containsExactly(ChequeStatus.CLEARED, ChequeStatus.CLEARED, ChequeStatus.CLEARED,
                        ChequeStatus.REGISTERED, ChequeStatus.REGISTERED);
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertTrialBalanceBalances();
    }

    /** A date outside the term, or inside a month somebody has signed off, is not a termination date. */
    @Test
    void rejectsDateOutsideTermOrLocked() {
        UUID leaseId = galahWithThreeCleared();

        assertThatThrownBy(() -> termination.preview(leaseId, START.minusDays(1)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("outside the lease term");
        assertThatThrownBy(() -> termination.terminate(leaseId,
                new TerminateLeaseRequest(END.plusDays(1), null, null, null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("outside the lease term");

        fiscal.lockThrough(LocalDate.of(2027, 2, 28));
        // The exact wording matters, and this is the assertion a weaker one would
        // hide: without the guard here the refusal still mentions the lock — it
        // just arrives from PostingService, after the first cheque has been handed
        // back inside the transaction, and the preview below would have offered the
        // date as perfectly fine. Both refusals have to be *this* one.
        String refusal = "Cannot terminate on 2027-02-15: books are locked through 2027-02-28.";
        assertThatThrownBy(() -> termination.terminate(leaseId,
                new TerminateLeaseRequest(T, null, null, null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(refusal);
        assertThatThrownBy(() -> termination.preview(leaseId, T))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage(refusal);
        // The day after the lock is fine — the guard is the lock, not the date.
        assertThat(termination.preview(leaseId, LocalDate.of(2027, 3, 1)).terminationDate())
                .isEqualTo(LocalDate.of(2027, 3, 1));

        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(lease(leaseId).getTerminatedOn()).isNull();
        assertThat(journalCount(JournalDocType.TCR)).isZero();
    }

    /**
     * The whole termination is one transaction: a refused {@code TCR} leaves no
     * cheque handed back and no schedule cut.
     *
     * <p>The <em>receivable</em> leaf is retired after the lease posts, and
     * recognition is run past {@code T} first. That is the nastiest shape on
     * purpose: by the time the {@code TCR} is refused, this transaction has already
     * reversed two {@code PDR}s, reversed two {@code CIL}s and posted a replacement
     * {@code CIL} for 1–15 Feb — and none of those may survive. Reversals copy
     * their original's accounts rather than resolving them, and the replacement
     * {@code CIL} touches advance rent and income, so the refusal lands on the
     * {@code TCR}'s credit and nowhere earlier. A cheque return committing
     * independently, or the replacement posting through
     * {@code RecognitionPoster.post}'s {@code REQUIRES_NEW}, both show up here.</p>
     */
    @Test
    void aRefusedUnearnedReversalRollsTheWholeTerminationBack() {
        UUID leaseId = galahWithThreeCleared();
        recognition.runTo(LocalDate.of(2027, 3, 31), false);
        long reversalsBefore = reversalCount();
        long cilsBefore = journalCount(JournalDocType.CIL);
        jdbc.update("update accounts set is_active = false where id = ?",
                leaf(AccountRole.RENT_RECEIVABLE).getId());

        // A retired leaf stops being the property's mapping at all, so the refusal
        // is the resolver's rather than the "inactive account" one — either way it
        // is the TCR's credit line and nothing earlier.
        assertThatThrownBy(() -> termination.terminate(leaseId,
                new TerminateLeaseRequest(T, null, null, null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("RENT_RECEIVABLE");

        assertThat(register(leaseId)).extracting(Cheque::getStatus)
                .containsExactly(ChequeStatus.CLEARED, ChequeStatus.CLEARED, ChequeStatus.CLEARED,
                        ChequeStatus.REGISTERED, ChequeStatus.REGISTERED);
        assertThat(reversalCount()).as("no reversal survived").isEqualTo(reversalsBefore);
        assertThat(journalCount(JournalDocType.CIL)).as("no replacement CIL survived").isEqualTo(cilsBefore);
        assertThat(journalCount(JournalDocType.TCR)).isZero();

        assertThat(segment(leaseId).getStatus()).isEqualTo(SegmentStatus.ACTIVE);
        assertThat(segment(leaseId).getToDate()).isEqualTo(END);
        List<RecognitionEntryDTO> rows = schedule(leaseId);
        assertThat(rows).hasSize(13);
        assertThat(withStatus(leaseId, RecognitionStatus.CANCELLED)).isEmpty();
        assertThat(withStatus(leaseId, RecognitionStatus.REVERSED)).isEmpty();
        assertThat(rows.get(5).periodEnd()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(rows.get(5).amount()).isEqualByComparingTo("3912.33");
        assertThat(recognised(leaseId)).isEqualByComparingTo("26408.23");

        Lease lease = lease(leaseId);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(lease.getTerminatedOn()).isNull();
        assertThat(lease.getTerminationJournalId()).isNull();
        assertThat(unit(fixtures.unit().getId()).getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertTrialBalanceBalances();
    }

    /** Another landlord cannot see this contract, let alone end it. */
    @Test
    void anotherTenantCanNeitherPreviewNorTerminateThisLease() {
        UUID leaseId = galahWithThreeCleared();

        fixtures.newTenant();
        fixtures.asTenantAdmin();

        assertThatThrownBy(() -> termination.preview(leaseId, T)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> termination.terminate(leaseId,
                new TerminateLeaseRequest(T, null, null, null), null)).isInstanceOf(NotFoundException.class);
    }
}
