package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RecognitionEntry;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.chequeRow;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.linePeriod;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-day rent recognition against a real database (spec §8).
 *
 * <p>The fixture is the client's own example: 51,000 of rent over 24 Sep 2026 →
 * 23 Sep 2027, 365 days, a day rate of 139.726027. Every figure asserted here
 * was re-derived from {@link ProrationEngine} rather than copied, because the
 * whole point of this module is that the schedule is arithmetic and not a
 * plausible-looking table.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} enables the Hibernate tenant
 * filter only inside one, so every read-back goes through {@link #tx}.</p>
 */
@SpringBootTest
@Testcontainers
class RecognitionServiceIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired RecognitionService recognition;
    @Autowired LeasePostingService posting;
    @Autowired LeaseRenewalService renewal;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LeaseRepository leaseRepo;
    @Autowired RentSegmentRepository segments;
    @Autowired RecognitionEntryRepository entriesRepo;
    @Autowired JournalEntryRepository journals;
    @Autowired JournalLineRepository journalLines;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    /** The client's fixture (spec §8.2). */
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // fixtures and helpers
    // ------------------------------------------------------------------

    /** 51,000 of rent over the client's 365-day term plus a 2,000 admin fee, on the books. */
    private UUID galah() {
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null)
                .lease().getId();
    }

    private List<RecognitionEntryDTO> schedule(UUID leaseId) {
        return recognition.scheduleFor(leaseId);
    }

    private List<RentSegment> segmentsOf(UUID leaseId) {
        return tx.execute(s -> segments.findByLease_IdOrderByFromDateAsc(leaseId));
    }

    private List<RecognitionEntry> rowsOf(UUID leaseId) {
        return tx.execute(s -> entriesRepo.findByLease_IdOrderByPeriodStartAsc(leaseId));
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

    /** An account's closing balance on this lease alone — a credit balance is negative. */
    private BigDecimal balanceOf(AccountRole role, UUID leaseId) {
        UUID accountId = leaf(role).getId();
        return tx.execute(s -> ledger.accountLedger(accountId,
                new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    /**
     * Σ debits − Σ credits over the whole tenant. Every journal balances on its
     * own, so this can only move if a posting wrote a half-entry.
     */
    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(LocalDate.of(2030, 1, 1), null));
        // An empty trial balance balances trivially, which would make this assertion
        // say nothing at all in a test whose posting silently did not happen.
        assertThat(rows).as("trial balance rows").isNotEmpty();
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance debits").isGreaterThan(BigDecimal.ZERO);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }

    /**
     * Make the register total {@code total} by moving the difference onto the last
     * row, so an amendment that changes the contract value is not refused by
     * {@code amendLines}' Σ-cheques check.
     *
     * <p>A fixture shortcut, and deliberately raw SQL: there is no service-level
     * "re-cut a registered grid", and the subject here is the recognition
     * schedule, not the register. The PDR journals keep their original amounts,
     * which changes nothing this test asserts — every journal still balances, and
     * no assertion here reads the register's money.</p>
     */
    private void setChequeTotalTo(UUID leaseId, String total) {
        jdbc.update("update cheques set amount = ? where id = (select id from cheques where lease_id = ? "
                        + "order by seq_no desc limit 1)",
                new BigDecimal(total).subtract(
                        jdbc.queryForObject("select coalesce(sum(amount), 0) from cheques where lease_id = ? "
                                        + "and id <> (select id from cheques where lease_id = ? order by seq_no desc limit 1)",
                                BigDecimal.class, leaseId, leaseId)),
                leaseId);
    }

    // ------------------------------------------------------------------
    // build on post
    // ------------------------------------------------------------------

    /**
     * Posting the lease precomputes the whole schedule: one segment for the RENT
     * line, thirteen calendar-month rows summing to the line's net exactly, and
     * nothing at all for the admin fee, which is earned when charged.
     */
    @Test
    void postingBuildsOneSegmentAndThirteenPlannedEntries() {
        UUID leaseId = galah();

        List<RentSegment> segs = segmentsOf(leaseId);
        assertThat(segs).hasSize(1);
        RentSegment seg = segs.get(0);
        assertThat(seg.getStatus()).isEqualTo(SegmentStatus.ACTIVE);
        assertThat(seg.getFromDate()).isEqualTo(START);
        assertThat(seg.getToDate()).isEqualTo(END);
        assertThat(seg.getDays()).isEqualTo(365);
        assertThat(seg.getAmount()).isEqualByComparingTo("51000");
        assertThat(seg.getDayRate()).isEqualByComparingTo("139.726027");

        List<RecognitionEntryDTO> rows = schedule(leaseId);
        assertThat(rows).hasSize(13);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED);
            assertThat(r.journalId()).isNull();
            assertThat(r.postedAt()).isNull();
        });

        assertThat(rows.get(0).periodStart()).isEqualTo(START);
        assertThat(rows.get(0).periodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(rows.get(0).days()).isEqualTo(7);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("978.08");

        assertThat(rows.get(1).amount()).isEqualByComparingTo("4331.51");   // Oct, 31 days
        assertThat(rows.get(2).amount()).isEqualByComparingTo("4191.78");   // Nov, 30 days
        assertThat(rows.get(5).days()).isEqualTo(28);                        // Feb 2027
        assertThat(rows.get(5).amount()).isEqualByComparingTo("3912.33");

        RecognitionEntryDTO last = rows.get(12);
        assertThat(last.periodStart()).isEqualTo(LocalDate.of(2027, 9, 1));
        assertThat(last.periodEnd()).isEqualTo(END);
        assertThat(last.days()).isEqualTo(23);
        assertThat(last.amount()).isEqualByComparingTo("3213.68");

        assertThat(rows.stream().map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("51000.00");
        assertThat(rows.stream().mapToInt(RecognitionEntryDTO::days).sum()).isEqualTo(365);

        // The admin fee is a FEE: credited to income on the TCO, never deferred.
        assertThat(segs).allSatisfy(s -> assertThat(s.getAmount()).isEqualByComparingTo("51000"));
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // runTo
    // ------------------------------------------------------------------

    /**
     * A run to a date posts every row whose period has ended by then and no other,
     * each as one {@code CIL} dated the row's own period end. Running it again
     * posts nothing: a posted row is not a candidate twice.
     */
    @Test
    void runToPostsOnlyEntriesEndingOnOrBeforeTheDate() {
        UUID leaseId = galah();

        RecognitionService.RecognitionRunResult result = recognition.runTo(LocalDate.of(2026, 11, 30), false);

        assertThat(result.posted()).isEqualTo(3);
        assertThat(result.errors()).isEmpty();
        // 978.08 + 4,331.51 + 4,191.78
        assertThat(result.amount()).isEqualByComparingTo("9501.37");

        // The result describes the rows as they now are. Each entry was committed by a
        // transaction of its own, so the copy this call is holding is stale — a result
        // built from it would tell the accountant nothing had been posted.
        assertThat(result.entries()).hasSize(3);
        assertThat(result.entries()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(RecognitionStatus.POSTED);
            assertThat(r.journalId()).isNotNull();
            assertThat(r.journalNumber()).startsWith("CIL");
            assertThat(r.postedAt()).isNotNull();
        });

        List<RecognitionEntryDTO> rows = schedule(leaseId);
        assertThat(rows.subList(0, 3)).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo(RecognitionStatus.POSTED);
            assertThat(r.journalId()).isNotNull();
            assertThat(r.journalNumber()).isNotNull();
            assertThat(r.postedAt()).isNotNull();
        });
        assertThat(rows.subList(3, 13)).allSatisfy(r ->
                assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED));

        // ---- the journal for the first row --------------------------------
        JournalEntry cil = journal(rows.get(0).journalId());
        assertThat(cil.getDocType()).isEqualTo(JournalDocType.CIL);
        assertThat(cil.getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(cil.getNarration()).isEqualTo("Advance rent adjustment – Sep 2026");
        assertThat(cil.getSourceType()).isEqualTo(JournalSourceType.RECOGNITION);
        assertThat(cil.getSourceId()).isEqualTo(rows.get(0).id());
        assertThat(cil.getLeaseId()).isEqualTo(leaseId);
        assertThat(cil.getPropertyId()).isEqualTo(fixtures.property().getId());

        List<JournalLine> lines = linesOf(cil.getId());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).getAccountId()).isEqualTo(leaf(AccountRole.ADVANCE_RENT).getId());
        assertThat(lines.get(0).getDebit()).isEqualByComparingTo("978.08");
        assertThat(lines.get(1).getAccountId()).isEqualTo(leaf(AccountRole.RENTAL_INCOME).getId());
        assertThat(lines.get(1).getCredit()).isEqualByComparingTo("978.08");

        // ---- the ledger moved by exactly the recognised amount ------------
        assertThat(balanceOf(AccountRole.RENTAL_INCOME, leaseId)).isEqualByComparingTo("-9501.37");

        // ---- a second run has nothing left to do --------------------------
        RecognitionService.RecognitionRunResult again = recognition.runTo(LocalDate.of(2026, 11, 30), false);
        assertThat(again.posted()).isZero();
        assertThat(again.amount()).isEqualByComparingTo("0");

        assertThat(recognition.pending(LocalDate.of(2027, 12, 31))).hasSize(10);
        assertTrialBalanceBalances();
    }

    /** A preview says what would happen and writes nothing. */
    @Test
    void previewPostsNothing() {
        UUID leaseId = galah();

        RecognitionService.RecognitionRunResult preview = recognition.runTo(LocalDate.of(2026, 11, 30), true);

        assertThat(preview.posted()).isEqualTo(3);
        assertThat(preview.amount()).isEqualByComparingTo("9501.37");
        assertThat(preview.entries()).hasSize(3);
        assertThat(schedule(leaseId)).allSatisfy(r ->
                assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED));
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = 'CIL'",
                Long.class, fixtures.tenantId())).isZero();
    }

    /**
     * A row whose period is inside a closed month is reported and left alone — it
     * is not an error the run should stop on, and it is certainly not something to
     * force into a locked period.
     */
    @Test
    void lockedPeriodEntriesAreSkippedNotFailed() {
        UUID leaseId = galah();
        fiscal.lockThrough(LocalDate.of(2026, 10, 31));

        RecognitionService.RecognitionRunResult result = recognition.runTo(LocalDate.of(2026, 11, 30), false);

        assertThat(result.posted()).isEqualTo(1);
        assertThat(result.amount()).isEqualByComparingTo("4191.78");
        assertThat(result.errors()).hasSize(2);
        // The exact wording matters: "skipped" and "attempted and refused by the ledger"
        // leave the same rows PLANNED, and only the message says which one happened.
        assertThat(result.errors()).containsExactly(
                "Entry 2026-09-24–2026-09-30 is in a locked period (books are locked through 2026-10-31)",
                "Entry 2026-10-01–2026-10-31 is in a locked period (books are locked through 2026-10-31)");

        List<RecognitionEntryDTO> rows = schedule(leaseId);
        assertThat(rows.get(0).status()).isEqualTo(RecognitionStatus.PLANNED);
        assertThat(rows.get(1).status()).isEqualTo(RecognitionStatus.PLANNED);
        assertThat(rows.get(2).status()).isEqualTo(RecognitionStatus.POSTED);
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // amend
    // ------------------------------------------------------------------

    /**
     * An amendment restates the contract, so the schedule it produced is restated
     * with it: what was posted is reversed in the ledger, what was merely planned
     * is cancelled, and a fresh schedule is cut from the new lines.
     */
    @Test
    void amendRebuildsScheduleAndReversesPostedEntries() {
        UUID leaseId = galah();
        recognition.runTo(LocalDate.of(2026, 10, 31), false);   // Sep + Oct

        List<UUID> postedJournals = schedule(leaseId).stream()
                .filter(r -> r.status() == RecognitionStatus.POSTED)
                .map(RecognitionEntryDTO::journalId).toList();
        assertThat(postedJournals).hasSize(2);

        setChequeTotalTo(leaseId, "62000");
        posting.amendLines(leaseId, List.of(line("RENT", "60000"), line("ADMIN_FEE", "2000")), "Rent corrected");

        // ---- the two posted rows were reversed in the ledger --------------
        for (UUID journalId : postedJournals) {
            assertThat(journal(journalId).getStatus()).isEqualTo(JournalStatus.REVERSED);
        }
        assertThat(jdbc.queryForObject(
                "select count(*) from journal_entries where tenant_id = ? and doc_type = 'CIL' and reversal_of_id is not null",
                Long.class, fixtures.tenantId())).isEqualTo(2L);

        // ---- old rows are history, old segment is cancelled ---------------
        List<RecognitionEntry> all = rowsOf(leaseId);
        long reversed = all.stream().filter(e -> e.getStatus() == RecognitionStatus.REVERSED).count();
        long cancelled = all.stream().filter(e -> e.getStatus() == RecognitionStatus.CANCELLED).count();
        assertThat(reversed).isEqualTo(2);
        assertThat(cancelled).isEqualTo(11);
        assertThat(segmentsOf(leaseId)).filteredOn(s -> s.getStatus() == SegmentStatus.CANCELLED).hasSize(1);

        // ---- the new schedule -------------------------------------------
        List<RecognitionEntryDTO> live = schedule(leaseId).stream()
                .filter(r -> r.status() == RecognitionStatus.PLANNED).toList();
        assertThat(live).hasSize(13);
        // 60,000 / 365 = 164.383562; × 7 days = 1,150.68
        assertThat(live.get(0).amount()).isEqualByComparingTo("1150.68");
        assertThat(live.stream().map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("60000.00");

        List<RentSegment> liveSegments = segmentsOf(leaseId).stream()
                .filter(s -> s.getStatus() == SegmentStatus.ACTIVE).toList();
        assertThat(liveSegments).hasSize(1);
        assertThat(liveSegments.get(0).getAmount()).isEqualByComparingTo("60000");

        // ---- the income the reversal took back ---------------------------
        assertThat(balanceOf(AccountRole.RENTAL_INCOME, leaseId)).isEqualByComparingTo("0");
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // extension
    // ------------------------------------------------------------------

    /**
     * An extension adds a second segment for its own window and leaves the
     * original term's rows exactly where they were.
     *
     * <p>15,000 over 24 Sep → 31 Dec 2027 is 99 days at 151.515152 a day: four
     * calendar-month rows, not three. The count is derived, not assumed.</p>
     */
    @Test
    void extensionAppendsASecondSegment() {
        UUID leaseId = galah();
        List<UUID> originalRowIds = schedule(leaseId).stream().map(RecognitionEntryDTO::id).toList();

        LocalDate newEnd = LocalDate.of(2027, 12, 31);
        LocalDate windowStart = END.plusDays(1);
        renewal.extend(leaseId, new ExtendLeaseRequest(newEnd, LocalDate.of(2027, 9, 1),
                List.of(linePeriod("RENT", "15000", windowStart, newEnd)),
                List.of(chequeRow("15000", LocalDate.of(2027, 9, 24)))));

        List<RentSegment> segs = segmentsOf(leaseId);
        assertThat(segs).hasSize(2);
        RentSegment extension = segs.get(1);
        assertThat(extension.getStatus()).isEqualTo(SegmentStatus.ACTIVE);
        assertThat(extension.getFromDate()).isEqualTo(windowStart);
        assertThat(extension.getToDate()).isEqualTo(newEnd);
        assertThat(extension.getDays()).isEqualTo(99);
        assertThat(extension.getDayRate()).isEqualByComparingTo("151.515152");

        List<RecognitionEntryDTO> added = schedule(leaseId).stream()
                .filter(r -> !originalRowIds.contains(r.id())).toList();
        assertThat(added).hasSize(4);
        assertThat(added).extracting(RecognitionEntryDTO::periodEnd).containsExactly(
                LocalDate.of(2027, 9, 30), LocalDate.of(2027, 10, 31),
                LocalDate.of(2027, 11, 30), LocalDate.of(2027, 12, 31));
        assertThat(added).extracting(RecognitionEntryDTO::days).containsExactly(7, 31, 30, 31);
        assertThat(added).extracting(RecognitionEntryDTO::amount)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("1060.61"), new BigDecimal("4696.97"),
                        new BigDecimal("4545.45"), new BigDecimal("4696.97"));
        assertThat(added.stream().map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("15000.00");

        // ---- the original term is untouched --------------------------------
        List<RecognitionEntryDTO> original = schedule(leaseId).stream()
                .filter(r -> originalRowIds.contains(r.id())).toList();
        assertThat(original).hasSize(13);
        assertThat(original).allSatisfy(r -> assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED));
        assertThat(original.get(0).amount()).isEqualByComparingTo("978.08");
        assertThat(segs.get(0).getStatus()).isEqualTo(SegmentStatus.ACTIVE);
        assertThat(segs.get(0).getAmount()).isEqualByComparingTo("51000");
        assertTrialBalanceBalances();
    }

    /**
     * An amendment on an extended lease reposts <em>every</em> line under one TCO
     * (see {@code LeasePostingService.amendLines}), so the rebuild has to cut a
     * fresh segment for every RENT line, the extension's included. Reverse only
     * the first TCO's worth and the extension's rent would never be recognised.
     */
    @Test
    void amendAfterExtensionRebuildsEverySegment() {
        UUID leaseId = galah();
        LocalDate newEnd = LocalDate.of(2027, 12, 31);
        LocalDate windowStart = END.plusDays(1);
        renewal.extend(leaseId, new ExtendLeaseRequest(newEnd, LocalDate.of(2027, 9, 1),
                List.of(linePeriod("RENT", "15000", windowStart, newEnd)),
                List.of(chequeRow("15000", LocalDate.of(2027, 9, 24)))));
        recognition.runTo(LocalDate.of(2026, 10, 31), false);   // Sep + Oct of the original term

        setChequeTotalTo(leaseId, "69000");
        posting.amendLines(leaseId, List.of(
                line("RENT", "52000"),
                line("ADMIN_FEE", "2000"),
                linePeriod("RENT", "15000", windowStart, newEnd)), "Rent corrected");

        List<RentSegment> live = segmentsOf(leaseId).stream()
                .filter(s -> s.getStatus() == SegmentStatus.ACTIVE).toList();
        assertThat(live).hasSize(2);
        assertThat(live.stream().map(RentSegment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("Σ ACTIVE segments = Σ RENT line nets")
                .isEqualByComparingTo("67000");

        List<RecognitionEntryDTO> planned = schedule(leaseId).stream()
                .filter(r -> r.status() == RecognitionStatus.PLANNED).toList();
        assertThat(planned.stream().map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("67000.00");
        assertThat(schedule(leaseId)).filteredOn(r -> r.status() == RecognitionStatus.REVERSED).hasSize(2);
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // account overrides
    // ------------------------------------------------------------------

    /** The lease may name the income account the release credits (spec §8.3). */
    @Test
    void incomeAccountOverrideIsHonoured() {
        UUID leaseId = galah();
        Account other = leaf(AccountRole.OTHER_INCOME);
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            lease.setIncomeAccountId(other.getId());
            leaseRepo.save(lease);
        });

        recognition.runTo(LocalDate.of(2026, 9, 30), false);

        List<JournalLine> lines = linesOf(schedule(leaseId).get(0).journalId());
        assertThat(lines.get(0).getAccountId()).isEqualTo(leaf(AccountRole.ADVANCE_RENT).getId());
        assertThat(lines.get(1).getAccountId()).isEqualTo(other.getId());
        assertThat(lines.get(1).getAccountId()).isNotEqualTo(leaf(AccountRole.RENTAL_INCOME).getId());
        assertTrialBalanceBalances();
    }

    // ------------------------------------------------------------------
    // tenancy
    // ------------------------------------------------------------------

    /**
     * A second tenant cannot see the first one's schedule and a run in its context
     * posts nothing of the first one's — the nightly job walks every tenant, so
     * this is the difference between a month-end close and a data leak.
     */
    @Test
    void anotherTenantSeesAndPostsNothingOfThisOne() {
        UUID leaseId = galah();
        UUID tenantA = fixtures.tenantId();
        assertThat(recognition.pending(LocalDate.of(2027, 12, 31))).hasSize(13);

        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();

        assertThat(other.tenantId()).isNotEqualTo(tenantA);
        assertThat(recognition.pending(LocalDate.of(2027, 12, 31))).isEmpty();
        assertThat(recognition.scheduleFor(leaseId)).isEmpty();

        RecognitionService.RecognitionRunResult result = recognition.runTo(LocalDate.of(2027, 12, 31), false);
        assertThat(result.posted()).isZero();

        TenantContextHolder.setTenantId(tenantA);
        assertThat(schedule(leaseId)).hasSize(13);
        assertThat(schedule(leaseId)).allSatisfy(r ->
                assertThat(r.status()).isEqualTo(RecognitionStatus.PLANNED));
    }
}
