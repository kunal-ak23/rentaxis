package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminationPreviewDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.recognition.ProrationEngine;
import com.datagami.rentaxis.core.service.recognition.RecognitionPoster;
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
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    @Autowired LeaseRenewalService renewal;
    @Autowired AccountRepository accounts;
    @Autowired RentSegmentRepository segments;
    @Autowired RecognitionEntryRepository entriesRepo;
    @Autowired RecognitionPoster poster;
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

    /** The extension's window: the day after the base term, through the year end. */
    private static final LocalDate EXTENSION_START = LocalDate.of(2027, 9, 24);
    private static final LocalDate EXTENSION_END = LocalDate.of(2027, 12, 31);

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

    /**
     * The same lease, extended by 15,000 over 24 Sep → 31 Dec 2027 (99 days at
     * 151.515152 a day), paid by one further cheque. Two RENT lines, two segments.
     *
     * @param creditAccountId the extension line's own deferral account, or null to
     *                        let it resolve the property's {@code ADVANCE_RENT}.
     */
    private UUID galahExtended(UUID creditAccountId) {
        UUID leaseId = galahWithThreeCleared();
        renewal.extend(leaseId, new ExtendLeaseRequest(
                EXTENSION_END,
                LocalDate.of(2027, 9, 20),
                List.of(new LeaseLineInput(null, "RENT", new BigDecimal("15000"), BigDecimal.ZERO,
                        null, null, creditAccountId, null, null)),
                List.of(LeaseTestFixtures.chequeRow("15000", LocalDate.of(2027, 10, 2)))));
        return leaseId;
    }

    /**
     * A commercial tenancy: the same 51,000 of rent, VAT-applicable, so the
     * {@code TCO} charges 53,550 and credits 2,550 to {@code OUTPUT_VAT}. Four
     * instruments of 13,387.50 on the same quarterly dates, none of them cleared.
     *
     * <p>One line only, because VAT is what this fixture is about and an admin fee
     * would put a second, un-recognised charge into every figure below.</p>
     */
    private UUID commercialGalah() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(LeaseTestFixtures.vatLine("RENT", "51000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("200041", CONTRACT_DATE, RENT_1, "13387.50"),
                row("200042", CONTRACT_DATE, RENT_2, "13387.50"),
                row("200043", CONTRACT_DATE, RENT_3, "13387.50"),
                row("200044", CONTRACT_DATE, RENT_4, "13387.50")));
        posting.post(leaseId);
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

    /**
     * A PROPERTY_MANAGER assigned to nothing. {@code LeaseAccessPolicy} fails
     * closed, so this caller reads and manages no lease at all — the shape a
     * manager has for somebody else's building.
     */
    private void asUnassignedPropertyManager() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
    }

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

    private List<RentSegment> segmentsOf(UUID leaseId) {
        return tx.execute(s -> segments.findByLease_IdOrderByFromDateAsc(leaseId));
    }

    private RentSegment segment(UUID leaseId) {
        List<RentSegment> all = segmentsOf(leaseId);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private List<RecognitionEntryDTO> entriesOfSegment(UUID segmentId) {
        return schedule(leaseIdOfSegment(segmentId)).stream()
                .filter(r -> segmentId.equals(r.segmentId())).toList();
    }

    private UUID leaseIdOfSegment(UUID segmentId) {
        return tx.execute(s -> segments.findById(segmentId).orElseThrow().getLease().getId());
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

    /**
     * The debit a journal carries against one account, found <em>by account</em>.
     *
     * <p>Not by line index: `lines.get(0)` only works while `PostingService` emits
     * every pair debit-first, which is an implementation detail of the posting and
     * not the thing being asserted. A multi-pair TCR has no meaningful line order
     * at all.</p>
     */
    private BigDecimal debitOn(JournalEntry entry, AccountRole role) {
        return debitOn(entry, leaf(role).getId());
    }

    private BigDecimal debitOn(JournalEntry entry, UUID accountId) {
        return linesOf(entry.getId()).stream()
                .filter(l -> accountId.equals(l.getAccountId()) && l.getDebit() != null)
                .map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal creditOn(JournalEntry entry, AccountRole role) {
        UUID accountId = leaf(role).getId();
        return linesOf(entry.getId()).stream()
                .filter(l -> accountId.equals(l.getAccountId()) && l.getCredit() != null)
                .map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The schedule's row for a period, found by the period rather than by its index. */
    private RecognitionEntryDTO rowStarting(UUID leaseId, LocalDate periodStart) {
        List<RecognitionEntryDTO> found = schedule(leaseId).stream()
                .filter(r -> r.periodStart().equals(periodStart)).toList();
        assertThat(found).as("rows starting " + periodStart).hasSize(1);
        return found.get(0);
    }

    /** Σ of the lease's PLANNED rows — the earned tail a later run will still post. */
    private BigDecimal plannedTotal(UUID leaseId) {
        return withStatus(leaseId, RecognitionStatus.PLANNED).stream()
                .map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** An account's closing balance on this lease alone; a credit balance reads negative. */
    private BigDecimal balanceOf(AccountRole role, UUID leaseId) {
        return balanceAsOf(role, leaseId, null);
    }

    /**
     * The same, bounded to entries dated on or before {@code asOf}.
     *
     * <p>This is the view a settlement statement drawn at T takes, and the only
     * one that can see a reversal dated before the entry it reverses — an all-time
     * balance nets the pair out whatever dates they carry.</p>
     */
    private BigDecimal balanceAsOf(AccountRole role, UUID leaseId, LocalDate asOf) {
        return tx.execute(s -> ledger.accountLedger(leaf(role).getId(),
                new LedgerQueryService.LedgerFilter(null, asOf, null, null, leaseId, null)).closingBalance());
    }

    /** …for a leaf named outright rather than through a role. */
    private BigDecimal balanceOf(UUID accountId, UUID leaseId) {
        return tx.execute(s -> ledger.accountLedger(accountId,
                new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private long journalCount(JournalDocType docType) {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = ?",
                Long.class, fixtures.tenantId(), docType.name());
    }

    /**
     * Live recognition journals whose entry is no longer POSTED — the shape a lost
     * update leaves behind. Asked of the journal, not of the entry: a dirty update
     * rewrites every column, so the losing write blanks {@code journal_id} and the
     * orphan is invisible from the entry's side.
     */
    private long orphanedRecognitionJournals() {
        return jdbc.queryForObject(
                "select count(*) from journal_entries je where je.tenant_id = ?"
                        + " and je.source_type = 'RECOGNITION' and je.status = 'POSTED'"
                        + " and not exists (select 1 from recognition_entries re"
                        + "                 where re.id = je.source_id and re.status = 'POSTED')",
                Long.class, fixtures.tenantId());
    }

    /**
     * Blocks until some backend is waiting on a lock, so the other thread has
     * demonstrably reached the row we are holding. Polling {@code pg_stat_activity}
     * rather than sleeping a guessed interval: a fixed sleep is either flaky or
     * slow, and usually both.
     */
    private void awaitABlockedBackend() {
        for (int i = 0; i < 300; i++) {
            Long waiting = jdbc.queryForObject(
                    "select count(*) from pg_stat_activity"
                            + " where datname = current_database() and wait_event_type = 'Lock'", Long.class);
            if (waiting != null && waiting > 0) return;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("No backend ever blocked on the row lock");
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
        // A residential tenancy charges no VAT, so there is none to credit back.
        assertThat(preview.unearnedVat()).isEqualByComparingTo("0.00");

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
        RecognitionEntryDTO february = rowStarting(leaseId, LocalDate.of(2027, 2, 1));
        assertThat(february.status()).isEqualTo(RecognitionStatus.PLANNED);
        assertThat(february.periodStart()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(february.periodEnd()).isEqualTo(T);
        assertThat(february.days()).isEqualTo(15);
        // 20,260.27 earned through 15 Feb, less the 18,164.39 already recognised.
        assertThat(february.amount()).isEqualByComparingTo("2095.88");
        assertThat(february.journalId()).isNull();
        assertThat(schedule(leaseId).stream()
                .filter(r -> r.periodStart().isAfter(LocalDate.of(2027, 2, 28))).toList())
                .as("every period after the one containing T")
                .hasSize(7)
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(RecognitionStatus.CANCELLED));

        RentSegment segment = segment(leaseId);
        assertThat(segment.getStatus()).isEqualTo(SegmentStatus.TRUNCATED);
        assertThat(segment.getToDate()).isEqualTo(T);
        assertThat(segment.getDays()).isEqualTo(145);
        // The window it actually ran, with the contract it was cut from preserved
        // beside it — see aTruncatedSegmentDescribesTheTermItActuallyRan.
        assertThat(segment.getAmount()).isEqualByComparingTo("20260.27");
        assertThat(segment.getOriginalAmount()).isEqualByComparingTo("51000");
        assertThat(segment.getDayRate()).isEqualByComparingTo("139.726027");

        // ---- the TCR --------------------------------------------------------
        UUID tcrId = lease(leaseId).getTerminationJournalId();
        assertThat(tcrId).isNotNull();
        JournalEntry tcr = journal(tcrId);
        assertThat(tcr.getDocType()).isEqualTo(JournalDocType.TCR);
        assertThat(tcr.getEntryDate()).isEqualTo(T);
        assertThat(tcr.getNarration()).isEqualTo("Unearned rent reversed on termination");
        assertThat(tcr.getLeaseId()).isEqualTo(leaseId);
        assertThat(linesOf(tcrId)).hasSize(2);
        assertThat(debitOn(tcr, AccountRole.ADVANCE_RENT)).isEqualByComparingTo("30739.73");
        assertThat(creditOn(tcr, AccountRole.RENT_RECEIVABLE)).isEqualByComparingTo("30739.73");

        // ---- what the renter is left owing ----------------------------------
        // 22,260.27 earned (20,260.27 of rent + the 2,000 admin fee) against 27,500
        // received: the landlord owes 5,239.73, and a rent receivable in credit is
        // exactly how that reads.
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("-5239.73");
        // Every instrument is either collected or handed back, so nothing is left
        // sitting in PDC receivable...
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId)).isEqualByComparingTo("0.00");
        // ...and what is still deferred is exactly the 1–15 Feb row a later run
        // will recognise: earned, not yet taken to income.
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, leaseId)).isEqualByComparingTo("-2095.88");
        assertThat(plannedTotal(leaseId)).isEqualByComparingTo("2095.88");

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
     * A commercial tenancy hands back the VAT on the rent it hands back, as a
     * credit note on the same journal (review I-5).
     *
     * <p>The {@code TCO} charged 51,000 of rent and 2,550 of VAT on it, both debited
     * to the receivable. Cutting the term at 15 Feb un-earns 30,739.73 of that rent,
     * and the tax on rent the renter never used is not the landlord's to keep or the
     * Authority's to be paid: the {@code TCR} debits {@code OUTPUT_VAT} for
     * 1,536.99 alongside the advance rent, and credits the receivable with both.</p>
     *
     * <p>Two invariants come out of it and both are asserted from the ledger:
     * {@code OUTPUT_VAT} on this lease ends at 5% of the rent that was
     * <em>earned</em> (1,013.01 on 20,260.27), and spec §9.1's "receivable = earned
     * − received" holds again — on the gross figures, which is what the renter
     * actually owes. Without the VAT pair the receivable carried 1,536.99 of tax on
     * rent nobody supplied, and the settlement collected it.</p>
     */
    @Test
    void terminatingAVatBearingLeaseCreditsTheVatOnTheUnearnedRent() {
        UUID leaseId = commercialGalah();
        UUID outputVat = leaf(AccountRole.OUTPUT_VAT).getId();
        assertThat(balanceOf(outputVat, leaseId)).as("VAT charged on the whole contract")
                .isEqualByComparingTo("-2550.00");

        TerminationPreviewDTO preview = termination.preview(leaseId, T);
        assertThat(preview.unearnedRent()).isEqualByComparingTo("30739.73");
        // 5% of 30,739.73 = 1,536.9865, to the fils.
        assertThat(preview.unearnedVat()).isEqualByComparingTo("1536.99");
        // 26,775 of paper handed back, less 30,739.73 of rent and 1,536.99 of VAT.
        assertThat(preview.receivableAfter()).isEqualByComparingTo("-5501.72");

        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);
        recognition.runTo(T, false);

        JournalEntry tcr = journal(lease(leaseId).getTerminationJournalId());
        assertThat(linesOf(tcr.getId())).as("two pairs: the rent and the tax on it").hasSize(4);
        assertThat(debitOn(tcr, AccountRole.ADVANCE_RENT)).isEqualByComparingTo("30739.73");
        assertThat(debitOn(tcr, outputVat)).isEqualByComparingTo("1536.99");
        assertThat(creditOn(tcr, AccountRole.RENT_RECEIVABLE)).isEqualByComparingTo("32276.72");

        // VAT on EARNED rent only: 5% of 20,260.27 = 1,013.0135.
        assertThat(balanceOf(outputVat, leaseId)).as("output VAT after the credit note")
                .isEqualByComparingTo("-1013.01");
        // 21,273.28 earned gross (20,260.27 + 1,013.01) against the 26,775 of kept
        // paper the register is still holding.
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("-5501.72");
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, leaseId)).isEqualByComparingTo("26775.00");
        assertThat(balanceOf(AccountRole.RENTAL_INCOME, leaseId)).isEqualByComparingTo("-20260.27");
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, leaseId)).isEqualByComparingTo("0.00");
        assertTrialBalanceBalances();
    }

    /**
     * Terminating on the last day of a VAT-bearing term reverses nothing at all —
     * no advance rent, and therefore no VAT either, and no {@code TCR}.
     *
     * <p>The boundary matters because the VAT pair is computed from the same
     * unearned figure the rent pair is, and a helper that rounded 5% of zero into a
     * fils would post a one-sided credit note against a tenancy that ran its full
     * course.</p>
     */
    @Test
    void aVatBearingLeaseTerminatedOnItsLastDayCreditsNoVat() {
        UUID leaseId = commercialGalah();

        TerminationPreviewDTO preview = termination.preview(leaseId, END);
        assertThat(preview.unearnedRent()).isEqualByComparingTo("0.00");
        assertThat(preview.unearnedVat()).isEqualByComparingTo("0.00");

        termination.terminate(leaseId, new TerminateLeaseRequest(END, null, null, null), null);

        assertThat(lease(leaseId).getTerminationJournalId()).as("nothing to hand back").isNull();
        assertThat(journalCount(JournalDocType.TCR)).isZero();
        assertThat(balanceOf(leaf(AccountRole.OUTPUT_VAT).getId(), leaseId))
                .as("the whole contract was supplied, so the whole 2,550 is due")
                .isEqualByComparingTo("-2550.00");
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

        // ---- and it is right at every date, not only at the end of time --------
        //
        // A reversal must never predate the entry it reverses. The Feb CIL is dated
        // 2027-02-28 and the Mar one 2027-03-31, both after T, so their reversals
        // carry those dates rather than T. Reading the books AS OF T — which is
        // exactly what a settlement statement drawn at T does — the income is the
        // 18,164.39 closed through January plus the 2,095.88 replacement dated T,
        // and the months that were never earned have not been recognised yet at
        // all. Dating the reversals T instead nets them in early and answers
        // 12,016.43 income and −8,243.84 of advance rent on that date.
        assertThat(balanceAsOf(AccountRole.RENTAL_INCOME, leaseId, T))
                .as("rental income as of T").isEqualByComparingTo("-20260.27");
        assertThat(balanceAsOf(AccountRole.ADVANCE_RENT, leaseId, T))
                .as("advance rent as of T").isEqualByComparingTo("0.00");
        // Each later month-end nets its own CIL against its own reversal.
        for (LocalDate monthEnd : List.of(LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31),
                LocalDate.of(2027, 9, 30))) {
            assertThat(balanceAsOf(AccountRole.RENTAL_INCOME, leaseId, monthEnd))
                    .as("rental income as of " + monthEnd).isEqualByComparingTo("-20260.27");
            assertThat(balanceAsOf(AccountRole.ADVANCE_RENT, leaseId, monthEnd))
                    .as("advance rent as of " + monthEnd).isEqualByComparingTo("0.00");
        }
        JournalEntry febReversal = journal(journal(reversed.journalId()).getReversedById());
        assertThat(febReversal.getEntryDate()).isEqualTo(LocalDate.of(2027, 2, 28));
        JournalEntry marReversal = journal(journal(march.journalId()).getReversedById());
        assertThat(marReversal.getEntryDate()).isEqualTo(LocalDate.of(2027, 3, 31));
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
        assertThat(debitOn(tcr, AccountRole.ADVANCE_RENT)).isEqualByComparingTo("32835.62");
        // 25,500 of returned paper against 32,835.62 handed back.
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("-7335.62");
        assertTrialBalanceBalances();
    }

    /**
     * A truncated segment describes the term the tenancy actually ran, so the
     * module's own single-source-of-truth function answers correctly when it is
     * asked about the row.
     *
     * <p>This is the trap. A settlement author reads "earned rent to T" and writes
     * the obvious call — the one {@code summarise} itself makes,
     * {@code earnedThrough(segment.amount, segment.fromDate, segment.toDate, T)}.
     * If a truncated row kept the contract's 51,000 against a 145-day window, that
     * call answers <b>51,000.00</b> instead of 20,260.27: a 30,739.73 error,
     * silent, on the statement that decides what the renter is refunded. So
     * {@code amount}, {@code to_date} and {@code days} move together, the contract
     * figures move to {@code original_amount}/{@code original_to_date}, and the
     * unearned rent the {@code TCR} reversed is exactly the difference between
     * them.</p>
     *
     * <p>{@code day_rate} is the one field that does <em>not</em> move: it is what
     * the months before the cut were worth.</p>
     */
    @Test
    void aTruncatedSegmentDescribesTheTermItActuallyRan() {
        UUID leaseId = galahWithThreeCleared();
        recognition.runTo(LocalDate.of(2027, 1, 31), false);

        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);

        RentSegment seg = segment(leaseId);
        assertThat(seg.getStatus()).isEqualTo(SegmentStatus.TRUNCATED);
        assertThat(seg.getFromDate()).isEqualTo(START);
        assertThat(seg.getToDate()).isEqualTo(T);
        assertThat(seg.getDays()).isEqualTo(145);
        assertThat(seg.getAmount()).isEqualByComparingTo("20260.27");
        assertThat(seg.getOriginalAmount()).isEqualByComparingTo("51000");
        assertThat(seg.getOriginalToDate()).isEqualTo(END);
        assertThat(seg.getDayRate()).isEqualByComparingTo("139.726027");

        // The obvious call, on the truncated row, at T and at any later date.
        assertThat(ProrationEngine.earnedThrough(seg.getAmount(), seg.getFromDate(), seg.getToDate(), T))
                .isEqualByComparingTo("20260.27");
        assertThat(ProrationEngine.earnedThrough(seg.getAmount(), seg.getFromDate(), seg.getToDate(), END))
                .isEqualByComparingTo("20260.27");

        // unearned == original_amount − amount, and that is what the TCR reversed.
        assertThat(seg.getOriginalAmount().subtract(seg.getAmount())).isEqualByComparingTo("30739.73");
        assertThat(debitOn(journal(lease(leaseId).getTerminationJournalId()), AccountRole.ADVANCE_RENT))
                .isEqualByComparingTo(seg.getOriginalAmount().subtract(seg.getAmount()));

        // ...and Σ the rows the segment still owns is the amount it now claims.
        assertThat(recognised(leaseId).add(plannedTotal(leaseId))).isEqualByComparingTo(seg.getAmount());
    }

    /**
     * A terminated contract cannot be amended, which is what keeps a truncated
     * segment safe from {@code rebuildAfterAmend} — the one path that reads live
     * segments (ACTIVE <em>and</em> TRUNCATED) and would cut fresh ones from the
     * lines.
     */
    @Test
    void aTerminatedLeaseCannotBeAmended() {
        UUID leaseId = galahWithThreeCleared();
        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);

        assertThatThrownBy(() -> posting.amendLines(leaseId,
                List.of(line("RENT", "60000"), line("ADMIN_FEE", "2000")), "after the fact"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only an ACTIVE lease can have its lines amended");

        assertThat(segment(leaseId).getStatus()).isEqualTo(SegmentStatus.TRUNCATED);
        assertThat(segment(leaseId).getAmount()).isEqualByComparingTo("20260.27");
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
        RecognitionEntryDTO february = rowStarting(leaseId, LocalDate.of(2027, 2, 1));
        assertThat(february.periodEnd()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(february.amount()).isEqualByComparingTo("3912.33");
        assertThat(recognised(leaseId)).isEqualByComparingTo("26408.23");

        Lease lease = lease(leaseId);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(lease.getTerminatedOn()).isNull();
        assertThat(lease.getTerminationJournalId()).isNull();
        assertThat(unit(fixtures.unit().getId()).getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        assertTrialBalanceBalances();
    }

    /**
     * The nightly close posts a row while the termination is deciding what to do
     * with it: the row must end REVERSED, never CANCELLED with a live {@code CIL}
     * behind it.
     *
     * <p>Sequenced rather than raced, so it means the same thing every run. The
     * main thread takes the March row's write lock — exactly the lock
     * {@code RecognitionPoster} takes — signals, waits until a backend is actually
     * blocked on it, then posts March from inside that lock and commits. The
     * termination running on the other thread therefore meets a row that was
     * PLANNED when it planned and POSTED by the time it writes.</p>
     *
     * <p>Without the lock the termination reads PLANNED, decides "cancel", and its
     * UPDATE lands after the poster's commit: a CANCELLED row over a {@code CIL}
     * nobody will ever reverse — rent recognised for a period after the tenancy
     * ended, advance rent over-released, and a trial balance that still balances so
     * nobody notices. The assertions below are written against the ledger as well
     * as the schedule for that reason: Hibernate rewrites every column on a dirty
     * update, so the losing write also blanks {@code journal_id} and the orphaned
     * journal can only be found from the journal's side.</p>
     *
     * <p>No deadlock: the termination holds the lease row and the PDR sequence
     * counter, the poster holds one entry and wants the CIL counter, and neither
     * wants what the other has. (That is not general — a poster reversing a CIL
     * would contend on the CIL counter; see the report's Concerns.)</p>
     */
    @Test
    void aRowPostedMidTerminationIsReversedNotCancelled() throws Exception {
        UUID leaseId = galahWithThreeCleared();
        recognition.runTo(LocalDate.of(2027, 1, 31), false);
        UUID marchId = rowStarting(leaseId, LocalDate.of(2027, 3, 1)).id();
        UUID tenantId = fixtures.tenantId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch rowLocked = new CountDownLatch(1);
        try {
            Future<LeaseDTO> terminating = pool.submit(() -> {
                assertThat(rowLocked.await(30, TimeUnit.SECONDS)).isTrue();
                TenantContextHolder.setTenantId(tenantId);
                LeaseTestFixtures.authenticateAsTenantAdmin();
                try {
                    return termination.terminate(leaseId,
                            new TerminateLeaseRequest(T, null, null, null), null);
                } finally {
                    TenantContextHolder.clear();
                    LeaseTestFixtures.clearAuth();
                }
            });

            tx.executeWithoutResult(s -> {
                entriesRepo.lockById(marchId).orElseThrow();
                rowLocked.countDown();
                awaitABlockedBackend();
                poster.postJoining(marchId);
            });

            assertThat(terminating.get(60, TimeUnit.SECONDS).getStatus()).isEqualTo(LeaseStatus.TERMINATED);
        } finally {
            pool.shutdownNow();
        }

        RecognitionEntryDTO march = rowStarting(leaseId, LocalDate.of(2027, 3, 1));
        assertThat(march.status()).isEqualTo(RecognitionStatus.REVERSED);
        assertThat(march.journalId()).isNotNull();
        assertThat(journal(march.journalId()).getStatus()).isEqualTo(JournalStatus.REVERSED);

        // The invariant, stated from the ledger's side so a blanked journal_id
        // cannot hide an orphan: every live recognition journal belongs to a row
        // that is still POSTED.
        assertThat(orphanedRecognitionJournals()).as("live CILs with no POSTED row").isZero();
        // ...and the schedule and the ledger agree on what was recognised.
        assertThat(balanceOf(AccountRole.RENTAL_INCOME, leaseId)).isEqualByComparingTo(recognised(leaseId).negate());
        // Σ POSTED-net + the earned tail still waiting == what the tenancy earned.
        assertThat(recognised(leaseId).add(plannedTotal(leaseId))).isEqualByComparingTo("20260.27");
        assertThat(segment(leaseId).getAmount()).isEqualByComparingTo("20260.27");
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // two segments: a lease that was extended
    // ------------------------------------------------------------------

    /**
     * Terminate inside the original term of an extended lease: the base segment is
     * cut, the extension — which never began — is cancelled outright, and one
     * {@code TCR} hands back both.
     *
     * <p>Both lines defer into the property's own advance-rent leaf, so the
     * reversal is <b>one</b> debit of the two amounts together and not two debits
     * of the same account in one journal. A bookkeeper reading the ledger should
     * not have to ask why the same account appears twice on one entry.</p>
     */
    @Test
    void terminatingInsideTheOriginalTermCancelsTheExtensionUnstarted() {
        UUID leaseId = galahExtended(null);
        recognition.runTo(LocalDate.of(2027, 1, 31), false);

        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);

        List<RentSegment> segs = segmentsOf(leaseId);
        assertThat(segs).hasSize(2);
        RentSegment base = segs.get(0);
        RentSegment extension = segs.get(1);

        assertThat(base.getStatus()).isEqualTo(SegmentStatus.TRUNCATED);
        assertThat(base.getAmount()).isEqualByComparingTo("20260.27");
        assertThat(base.getOriginalAmount()).isEqualByComparingTo("51000");

        // It starts after T, so there was never a day of it to earn.
        assertThat(extension.getFromDate()).isEqualTo(EXTENSION_START);
        assertThat(extension.getStatus()).isEqualTo(SegmentStatus.CANCELLED);
        assertThat(extension.getAmount()).isEqualByComparingTo("15000");
        assertThat(entriesOfSegment(extension.getId())).allSatisfy(r ->
                assertThat(r.status()).isEqualTo(RecognitionStatus.CANCELLED));

        // 30,739.73 of the base term plus the whole 15,000 of the extension.
        JournalEntry tcr = journal(lease(leaseId).getTerminationJournalId());
        assertThat(linesOf(tcr.getId())).as("one debit, one credit — not one pair per segment").hasSize(2);
        assertThat(debitOn(tcr, AccountRole.ADVANCE_RENT)).isEqualByComparingTo("45739.73");
        assertThat(creditOn(tcr, AccountRole.RENT_RECEIVABLE)).isEqualByComparingTo("45739.73");

        // Everything deferred is now either recognised, handed back, or the earned
        // tail still waiting for a run — and that tail is all that is left.
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, leaseId))
                .isEqualByComparingTo(plannedTotal(leaseId).negate());
        assertThat(plannedTotal(leaseId)).isEqualByComparingTo("2095.88");
        assertTrialBalanceBalances();
    }

    /**
     * An extension whose rent line names its own deferral account is reversed
     * <em>there</em>, not against the property's mapping.
     *
     * <p>Same subtlety the {@code CIL} has: the {@code TCO} credited whatever leaf
     * the line named, and a reversal that re-resolves {@code ADVANCE_RENT} would
     * release from one account what was parked in another — a liability stranded in
     * the override for good, and the trial balance still balancing.</p>
     */
    @Test
    void theUnearnedReversalDebitsEachLinesOwnDeferralAccount() {
        Account mapped = leaf(AccountRole.ADVANCE_RENT);
        Account override = tx.execute(s -> accountService.createLeaf("Advance Rent - extension",
                accounts.findById(mapped.getId()).orElseThrow().getParent(), fixtures.property().getId()));
        UUID leaseId = galahExtended(override.getId());
        recognition.runTo(LocalDate.of(2027, 1, 31), false);

        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, null), null);

        JournalEntry tcr = journal(lease(leaseId).getTerminationJournalId());
        assertThat(linesOf(tcr.getId())).as("two accounts, two pairs").hasSize(4);
        assertThat(debitOn(tcr, mapped.getId())).isEqualByComparingTo("30739.73");
        assertThat(debitOn(tcr, override.getId())).isEqualByComparingTo("15000");
        assertThat(creditOn(tcr, AccountRole.RENT_RECEIVABLE)).isEqualByComparingTo("45739.73");

        // Each leaf is emptied of exactly what its own TCO parked there.
        assertThat(balanceOf(override.getId(), leaseId)).isEqualByComparingTo("0.00");
        // The property's own leaf keeps only the base term's earned-but-unrecognised
        // tail: the 1–15 Feb row, still PLANNED.
        assertThat(balanceOf(mapped.getId(), leaseId))
                .isEqualByComparingTo(plannedTotal(leaseId).negate());
        assertTrialBalanceBalances();
    }

    /**
     * Terminate inside the <em>extension's</em> term: the base segment ran its
     * course and is left exactly as it is — not "truncated", because nothing was
     * cut off it.
     */
    @Test
    void terminatingInsideTheExtensionLeavesTheFinishedSegmentAlone() {
        UUID leaseId = galahExtended(null);
        LocalDate late = LocalDate.of(2027, 11, 15);
        recognition.runTo(LocalDate.of(2027, 10, 31), false);

        termination.terminate(leaseId, new TerminateLeaseRequest(late, null, null, null), null);

        List<RentSegment> segs = segmentsOf(leaseId);
        RentSegment base = segs.get(0);
        RentSegment extension = segs.get(1);

        assertThat(base.getStatus()).as("it finished; it was not cut short").isEqualTo(SegmentStatus.ACTIVE);
        assertThat(base.getToDate()).isEqualTo(END);
        assertThat(base.getAmount()).isEqualByComparingTo("51000");
        assertThat(base.getOriginalAmount()).isNull();

        // 24 Sep → 15 Nov inclusive is 53 of the extension's 99 days, at
        // 15,000 / 99 = 151.515152 a day: 151.515152 × 53 = 8,030.30 earned.
        assertThat(extension.getStatus()).isEqualTo(SegmentStatus.TRUNCATED);
        assertThat(extension.getToDate()).isEqualTo(late);
        assertThat(extension.getDays()).isEqualTo(53);
        assertThat(extension.getAmount()).isEqualByComparingTo("8030.30");
        assertThat(extension.getOriginalAmount()).isEqualByComparingTo("15000");
        assertThat(extension.getOriginalToDate()).isEqualTo(EXTENSION_END);

        JournalEntry tcr = journal(lease(leaseId).getTerminationJournalId());
        assertThat(debitOn(tcr, AccountRole.ADVANCE_RENT)).isEqualByComparingTo("6969.70");
        assertThat(recognised(leaseId).add(plannedTotal(leaseId)))
                .as("the whole base term plus the part of the extension that ran")
                .isEqualByComparingTo("59030.30");
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // the rest of the register
    // ------------------------------------------------------------------

    /**
     * A renter with a checkout open when the contract ends.
     *
     * <p>An authorisation is not money — nothing has posted, the instalment is
     * exactly as unpaid as it was — so the row is uncleared and belongs in one of
     * the two lists like any other. Returning it means putting it back to
     * REGISTERED first, which is the only status {@code returnToTenant} accepts and
     * the one it came from. A row that is <em>kept</em> is left in
     * ONLINE_PENDING: finishing or abandoning that checkout is the renter's, and
     * cancelling it from here could race a capture mid-flight.</p>
     *
     * <p>The preview does none of this — it is asserted first, twice, for exactly
     * that reason.</p>
     */
    @Test
    void anOnlineCheckoutIsRevertedBeforeItIsHandedBack() {
        UUID leaseId = galahWithThreeCleared();
        UUID april = chequeOn(leaseId, RENT_3).getId();
        UUID july = chequeOn(leaseId, RENT_4).getId();
        chequeService.registerOnlinePending(april);
        chequeService.registerOnlinePending(july);
        assertThat(statusOf(leaseId, RENT_3)).isEqualTo(ChequeStatus.ONLINE_PENDING);

        // Preview: both rows are uncleared, both default to being returned, and
        // neither is touched.
        TerminationPreviewDTO preview = termination.preview(leaseId, T);
        assertThat(preview.chequesToReturn()).extracting(ChequeDTO::chequeDate)
                .containsExactly(RENT_3, RENT_4);
        assertThat(statusOf(leaseId, RENT_3)).isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(statusOf(leaseId, RENT_4)).isEqualTo(ChequeStatus.ONLINE_PENDING);

        termination.terminate(leaseId,
                new TerminateLeaseRequest(T, List.of(july), List.of(april), null), null);

        assertThat(statusOf(leaseId, RENT_4)).as("handed back").isEqualTo(ChequeStatus.RETURNED);
        assertThat(journal(chequeOn(leaseId, RENT_4).getPdrJournalId()).getStatus())
                .isEqualTo(JournalStatus.REVERSED);
        assertThat(statusOf(leaseId, RENT_3)).as("kept: the checkout is the renter's")
                .isEqualTo(ChequeStatus.ONLINE_PENDING);
        assertThat(journal(chequeOn(leaseId, RENT_3).getPdrJournalId()).getStatus())
                .isEqualTo(JournalStatus.POSTED);
        assertTrialBalanceBalances();
    }

    /**
     * A cheque that already bounced is neither handed back nor kept for collection
     * — there is nothing to hand back and nothing to bank — and the money is still
     * owed. It is the preview's third list for that reason, and it is deliberately
     * outside the "every uncleared row in exactly one list" rule, so a caller can
     * echo the preview's own lists back verbatim.
     */
    @Test
    void aBouncedRowIsOwedRatherThanReturnedOrKept() {
        UUID leaseId = galahWithThreeCleared();
        UUID april = chequeOn(leaseId, RENT_3).getId();
        chequeService.deposit(april, ChequeActionRequest.on(RENT_3));
        chequeService.bounce(april, new ChequeActionRequest(RENT_3, null, ChequeFailureReason.BOUNCE, null));
        assertThat(statusOf(leaseId, RENT_3)).isEqualTo(ChequeStatus.BOUNCED);

        TerminationPreviewDTO preview = termination.preview(leaseId, T);
        assertThat(preview.bouncedOutstanding()).extracting(ChequeDTO::chequeDate).containsExactly(RENT_3);
        assertThat(preview.chequesToReturn()).extracting(ChequeDTO::chequeDate).containsExactly(RENT_4);
        assertThat(preview.chequesToKeep()).isEmpty();

        // The July row alone is the whole answer about the uncleared rows; naming
        // the bounced one would be refused.
        termination.terminate(leaseId,
                new TerminateLeaseRequest(T, List.of(chequeOn(leaseId, RENT_4).getId()), List.of(), null), null);

        assertThat(statusOf(leaseId, RENT_3)).as("still bounced, still owed").isEqualTo(ChequeStatus.BOUNCED);
        assertThat(statusOf(leaseId, RENT_4)).isEqualTo(ChequeStatus.RETURNED);
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.TERMINATED);
        assertTrialBalanceBalances();
    }

    /**
     * A manager with no buildings sees no leases — asserted at the service, below
     * the role gate.
     *
     * <p>The HTTP gate already keeps a PROPERTY_MANAGER off {@code POST
     * /terminate} ({@code LeaseControllerTerminateEndpointsIT}), so this is
     * defence in depth rather than the live path. It is worth having because
     * {@code LeaseService.markTerminated} is public and a future caller —
     * a settlement screen, an import, an ops endpoint — would reach these methods
     * without passing that gate. All three refuse with "not found" rather than
     * "forbidden": a 403 on a lease id confirms the lease exists.</p>
     */
    @Test
    void aManagerWithNoBuildingsCanNeitherPreviewNorTerminate() {
        UUID leaseId = galahWithThreeCleared();
        asUnassignedPropertyManager();

        assertThatThrownBy(() -> termination.preview(leaseId, T)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> termination.terminate(leaseId,
                new TerminateLeaseRequest(T, null, null, null), null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> leaseService.markTerminated(leaseId, T, null, null, null))
                .isInstanceOf(NotFoundException.class);

        LeaseTestFixtures.authenticateAsTenantAdmin();
        assertThat(lease(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        assertThat(lease(leaseId).getTerminatedOn()).isNull();
    }

    /**
     * Contention on the lease row is a "try again", not a 500 (review M-6).
     *
     * <p>{@code giveNotice} promises a clean refusal in its own comment — it locks
     * "like every sibling transition … without it a notice racing a termination is
     * caught only by {@code @Version}, which surfaces as a 500-shaped optimistic-lock
     * failure rather than the clean refusal below" — but the lock it takes is NOWAIT
     * and its {@code PessimisticLockingFailureException} was let out untranslated,
     * so the thing it was added to prevent happened anyway. The register's three
     * other copies of this method already translate it; this one now does too, and
     * the type is what makes it a 400 rather than an accident of wording.</p>
     */
    @Test
    void aNoticeThatMeetsALockedLeaseIsAskedToTryAgain() throws Exception {
        UUID leaseId = galah();
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

            assertThatThrownBy(() -> leaseService.giveNotice(leaseId, "Leaving", null))
                    .isInstanceOf(RowLockedException.class)
                    .hasMessageContaining("Please try again");
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(lease(leaseId).getStatus()).as("nothing moved").isEqualTo(LeaseStatus.ACTIVE);
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
