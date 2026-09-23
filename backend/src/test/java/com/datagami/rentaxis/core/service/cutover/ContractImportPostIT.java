package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostService.BulkPostResult;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostService.LeaseOutcome;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LandlordOrgFineSettings;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgFineSettingsRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import com.datagami.rentaxis.domain.repository.PenaltyAssessmentRepository;
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 1 of the cut-over (spec §10.3): a DRAFT import batch becomes a posted
 * portfolio — contracts on the books at their own contract dates, cheques replayed
 * to the statuses and days PACT recorded, and recognition caught up to the day
 * before the client's books open.
 *
 * <p><b>Every date in this scenario is inside the locked period, and that is the
 * point.</b> The fixture locks the books through 2026-09-30 and every journal the
 * bulk post writes is dated on or before it. They are accepted only because they
 * carry the batch id ({@code PostingService} ~:54), which is also what lets the
 * whole run be reversed later — so "the id is on every journal" is not tidiness,
 * it is the precondition for both.</p>
 */
@SpringBootTest
@Import(ContractImportPostIT.EmailRecorder.class)
class ContractImportPostIT extends AbstractPostgresIT {

    /**
     * Every renter-facing email the run publishes, recorded in the thread that
     * publishes it.
     *
     * <p>A plain {@code @EventListener} rather than {@code @RecordApplicationEvents}:
     * the recorder has to work when the publisher is the import executor's thread,
     * and the framework's recorder is bound to the test's own.</p>
     */
    static class EmailRecorder {
        final List<EmailEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        void on(EmailEvent e) {
            events.add(e);
        }
    }

    @Autowired CutoverFixture fixture;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired TenantFiscalSettingsRepository fiscalRepo;
    @Autowired AccountRepository accountRepo;
    @Autowired PostingService posting;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired ContractImportPostService postService;
    @Autowired ImportBatchService batches;
    @Autowired ChequeService chequeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired RecognitionEntryRepository recognitionEntries;
    @Autowired PenaltyAssessmentRepository penalties;
    @Autowired NotificationRepository notifications;
    @Autowired LandlordOrgFineSettingsRepository fineSettings;
    @Autowired TenantDefaultAccountMappingRepository defaultMappings;
    @Autowired EmailRecorder emails;
    @Autowired TransactionTemplate tx;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = fixture.newCutOverTenant("POST");
        fixture.authenticateAsTenantAdmin();
        emails.events.clear();
    }

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
        fixture.clearAuthentication();
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private UUID importTheTemplate() throws Exception {
        try (Workbook wb = fixture.template()) {
            return contractPersist.persist(wb, fixture.newJob()).batchId();
        }
    }

    private Lease leaseOf(String externalContractRef) {
        return leaseRepo.findAll().stream()
                .filter(l -> externalContractRef.equals(l.getExternalContractRef()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No lease with externalContractRef " + externalContractRef));
    }

    private UUID leaseIdOf(String ref) {
        return tx.execute(s -> leaseOf(ref).getId());
    }

    private List<JournalEntry> batchJournals(UUID batchId) {
        return tx.execute(s -> entries.findByImportBatchIdOrderByCreatedAtAsc(batchId));
    }

    private static JournalEntry only(List<JournalEntry> all, JournalDocType type, UUID leaseId) {
        List<JournalEntry> hits = all.stream()
                .filter(e -> e.getDocType() == type && leaseId.equals(e.getLeaseId())).toList();
        assertThat(hits).as("%s entries on lease %s", type, leaseId).hasSize(1);
        return hits.get(0);
    }

    // ------------------------------------------------------------------
    // the happy path
    // ------------------------------------------------------------------

    @Test
    void bulkPostWritesEveryJournalIntoTheBatchAndCatchesRecognitionUpToTheCutOver() throws Exception {
        UUID batchId = importTheTemplate();

        BulkPostResult result = postService.post(batchId);

        assertThat(result.leasesPosted()).isEqualTo(2);
        assertThat(result.leasesFailed()).isZero();
        assertThat(result.failures()).isEmpty();
        assertThat(result.chequesCleared()).isEqualTo(1);
        assertThat(result.chequesBounced()).isZero();
        assertThat(result.recognitionEntriesPosted()).isEqualTo(1);

        UUID first = leaseIdOf("SAMPLE-0001");
        tx.executeWithoutResult(s ->
                assertThat(leaseRepo.findById(first).orElseThrow().getStatus()).isEqualTo(LeaseStatus.ACTIVE));
        tx.executeWithoutResult(s -> assertThat(unitRepo.findAll())
                .allSatisfy(u -> assertThat(u.getStatus()).isEqualTo(UnitStatus.OCCUPIED)));

        List<JournalEntry> journals = batchJournals(batchId);
        assertThat(journals).isNotEmpty()
                .allSatisfy(e -> assertThat(e.getImportBatchId()).isEqualTo(batchId));
        assertThat(journals).extracting(JournalEntry::getDocType)
                .contains(JournalDocType.TCO, JournalDocType.PDR, JournalDocType.CRT, JournalDocType.CIL);

        // The TCO is dated the contract date, not the day of the import.
        assertThat(only(journals, JournalDocType.TCO, first).getEntryDate())
                .isEqualTo(LocalDate.of(2026, 9, 11));
        // The CRT is dated the day PACT says the money actually reached the bank.
        assertThat(only(journals, JournalDocType.CRT, first).getEntryDate())
                .isEqualTo(LocalDate.of(2026, 9, 25));

        // journalsPosted is derived from what was written, never from the caller.
        assertThat(result.journalsPosted()).isEqualTo(journals.size());
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
        assertThat(batches.get(batchId).getJournalsPosted()).isEqualTo(journals.size());
    }

    /** No CIL may be dated on or after the cut-over: the client's live books start there. */
    @Test
    void recognitionCatchUpStopsTheDayBeforeTheBooksOpen() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);

        assertThat(batchJournals(batchId))
                .filteredOn(e -> e.getDocType() == JournalDocType.CIL)
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getEntryDate()).isBefore(CutoverFixture.BOOKS_START));

        // SAMPLE-0001 runs 2026-09-24 – 2027-09-23: exactly one period ends before the
        // books open, 24–30 Sep, and 51,000 / 365 × 7 = 978.08.
        UUID first = leaseIdOf("SAMPLE-0001");
        tx.executeWithoutResult(s -> {
            var schedule = recognitionEntries.findByLease_IdOrderByPeriodStartAsc(first);
            assertThat(schedule).filteredOn(e -> e.getStatus() == RecognitionStatus.POSTED)
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.getPeriodStart()).isEqualTo(LocalDate.of(2026, 9, 24));
                        assertThat(e.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 9, 30));
                        assertThat(e.getAmount()).isEqualByComparingTo("978.08");
                    });
            // The rest of the year stays PLANNED for the ordinary month-end close.
            assertThat(schedule).filteredOn(e -> e.getStatus() == RecognitionStatus.PLANNED).isNotEmpty();
        });

        // SAMPLE-0002's term starts on the day the books open, so it has nothing to
        // catch up and must not have been given a CIL anyway.
        UUID second = leaseIdOf("SAMPLE-0002");
        assertThat(batchJournals(batchId))
                .filteredOn(e -> e.getDocType() == JournalDocType.CIL && second.equals(e.getLeaseId()))
                .isEmpty();
    }

    /**
     * R4, the other half: an ordinary transition on the very same lease, after the
     * cut-over, must NOT land in the batch — otherwise "reverse the batch" would
     * take a payment the renter really made back off the books.
     */
    @Test
    void aTransitionMadeAfterTheCutOverCarriesNoBatchId() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);
        int imported = batchJournals(batchId).size();

        // The imported row keeps its own cheque date, and a post-dated cheque may
        // not be banked before it — so the date the clerk would actually use is
        // the cheque's own, not a fixed one.
        Cheque row = tx.execute(s -> chequeRepo
                .findByLease_IdOrderBySeqNoAsc(leaseOf("SAMPLE-0001").getId()).stream()
                .filter(c -> "100002".equals(c.getChequeNumber())).findFirst().orElseThrow());
        UUID registered = row.getId();
        LocalDate payableOn = row.getChequeDate();

        chequeService.deposit(registered, ChequeActionRequest.on(payableOn));
        chequeService.clear(registered, ChequeActionRequest.on(payableOn.plusDays(1)));

        assertThat(batchJournals(batchId)).hasSize(imported);
        tx.executeWithoutResult(s -> assertThat(entries.findAll())
                .filteredOn(e -> e.getDocType() == JournalDocType.CRT
                        && e.getEntryDate().isEqual(payableOn.plusDays(1)))
                .singleElement()
                .satisfies(e -> assertThat(e.getImportBatchId()).isNull()));
    }

    // ------------------------------------------------------------------
    // cheque replay
    // ------------------------------------------------------------------

    @Test
    void aBouncedChequeReplaysAsCbrOnItsOwnDate() throws Exception {
        UUID batchId = importTemplateWithSecondChequeBounced();

        BulkPostResult result = postService.post(batchId);

        assertThat(result.chequesBounced()).isEqualTo(1);
        UUID first = leaseIdOf("SAMPLE-0001");
        assertThat(only(batchJournals(batchId), JournalDocType.CBR, first).getEntryDate())
                .isEqualTo(LocalDate.of(2026, 9, 28));
        tx.executeWithoutResult(s -> assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(first))
                .filteredOn(c -> "100002".equals(c.getChequeNumber()))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.getStatus()).isEqualTo(ChequeStatus.BOUNCED);
                    assertThat(c.getDepositedAt()).isEqualTo(LocalDate.of(2026, 9, 26));
                    assertThat(c.getBouncedAt()).isEqualTo(LocalDate.of(2026, 9, 28));
                }));
    }

    /**
     * PACT already dealt with the fines it raised while it was the system of record.
     * A cut-over that re-proposed them would put a year of historical penalties on
     * finance's worklist on the first morning.
     */
    @Test
    void anImportedBounceProposesNoPenaltyAndTellsNobody() throws Exception {
        landlordProposesAFineOnEveryBounce();
        UUID batchId = importTemplateWithSecondChequeBounced();

        postService.post(batchId);

        tx.executeWithoutResult(s -> assertThat(penalties.findAll()).isEmpty());
        tx.executeWithoutResult(s -> assertThat(notifications.findAll()).isEmpty());
        assertThat(emails.events).isEmpty();
    }

    /** The same rule seen from the user-facing door: an ordinary bounce still proposes. */
    @Test
    void anOrdinaryBounceAfterTheCutOverStillProposesTheSamePenalty() throws Exception {
        landlordProposesAFineOnEveryBounce();
        UUID batchId = importTheTemplate();
        postService.post(batchId);

        // The imported row keeps its own cheque date, and a post-dated cheque may
        // not be banked before it — so the date the clerk would actually use is
        // the cheque's own, not a fixed one.
        Cheque row = tx.execute(s -> chequeRepo
                .findByLease_IdOrderBySeqNoAsc(leaseOf("SAMPLE-0001").getId()).stream()
                .filter(c -> "100002".equals(c.getChequeNumber())).findFirst().orElseThrow());
        UUID registered = row.getId();
        LocalDate payableOn = row.getChequeDate();
        chequeService.deposit(registered, ChequeActionRequest.on(payableOn));
        chequeService.bounce(registered, ChequeActionRequest.on(payableOn.plusDays(1)));

        tx.executeWithoutResult(s -> assertThat(penalties.findAll()).hasSize(1));
    }

    private void landlordProposesAFineOnEveryBounce() {
        LandlordOrgFineSettings s = new LandlordOrgFineSettings();
        s.setLandlordOrgId(tenantId);
        s.setFineBounceAmount(new BigDecimal("500.00"));
        s.setFineSignatureMismatchAmount(new BigDecimal("500.00"));
        s.setFineAccountClosedAmount(new BigDecimal("500.00"));
        s.setFineGraceDays(0);
        s.setFinePerDayRate(BigDecimal.ZERO);
        s.setBouncesBeforePenalty(1);
        s.setAutoProposeChequeReturn(true);
        s.setAutoProposeLatePayment(true);
        fineSettings.save(s);
    }

    /** The template with SAMPLE-0001's second instalment returned by the bank on 28 Sep. */
    private UUID importTemplateWithSecondChequeBounced() throws Exception {
        try (Workbook wb = fixture.template()) {
            CutoverFixture.set(wb, "Cheques", 2, 4, "2026-09-24");   // ChequeDate, so the bank dates are legal
            CutoverFixture.set(wb, "Cheques", 2, 10, "BOUNCED");     // Status
            CutoverFixture.set(wb, "Cheques", 2, 11, "2026-09-26");  // DepositedDate
            CutoverFixture.set(wb, "Cheques", 2, 13, "2026-09-28");  // BouncedDate
            return contractPersist.persist(wb, fixture.newJob()).batchId();
        }
    }

    // ------------------------------------------------------------------
    // per-lease isolation, idempotence, concurrency
    // ------------------------------------------------------------------

    /**
     * One bad contract must not abort the run. SAMPLE-0002 is the VAT-bearing one,
     * so it is the only lease that needs OUTPUT_VAT mapped — taking that mapping
     * away fails exactly one lease and leaves the other untouched.
     */
    @Test
    void aLeaseThatCannotPostIsReportedAndTheOthersStillPost() throws Exception {
        UUID batchId = importTheTemplate();
        unmapOutputVat();

        BulkPostResult result = postService.post(batchId);

        assertThat(result.leasesPosted()).isEqualTo(1);
        assertThat(result.leasesFailed()).isEqualTo(1);
        assertThat(result.failures()).singleElement()
                .satisfies(f -> {
                    assertThat(f.getMessage()).contains("OUTPUT_VAT");
                    assertThat(f.getMessage()).contains("SAMPLE-0002");
                });
        assertThat(result.leases())
                .filteredOn(o -> "SAMPLE-0002".equals(o.externalContractRef()))
                .singleElement()
                .satisfies(o -> assertThat(o.outcome()).isEqualTo(LeaseOutcome.Outcome.FAILED));

        // The batch is POSTED because something posted; the failed lease is still a
        // DRAFT of this batch, so a later run can retry just it.
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
        tx.executeWithoutResult(s -> {
            assertThat(leaseOf("SAMPLE-0001").getStatus()).isEqualTo(LeaseStatus.ACTIVE);
            assertThat(leaseOf("SAMPLE-0002").getStatus()).isEqualTo(LeaseStatus.DRAFT);
        });
        assertThat(batches.leaseIds(batchId)).hasSize(2);

        // Nothing of the failed lease reached the ledger.
        UUID failed = leaseIdOf("SAMPLE-0002");
        assertThat(batchJournals(batchId)).noneMatch(e -> failed.equals(e.getLeaseId()));
    }

    /** Fix the mapping, press Post again: only the lease that failed is retried. */
    @Test
    void asecondPostRetriesOnlyTheLeasesThatFailedAndNeverDoublePostsTheRest() throws Exception {
        UUID batchId = importTheTemplate();
        unmapOutputVat();
        postService.post(batchId);
        int afterFirst = batchJournals(batchId).size();

        remapOutputVat();
        BulkPostResult second = postService.post(batchId);

        assertThat(second.leasesPosted()).isEqualTo(1);
        assertThat(second.leasesSkipped()).isEqualTo(1);
        assertThat(second.leases())
                .filteredOn(o -> "SAMPLE-0001".equals(o.externalContractRef()))
                .singleElement()
                .satisfies(o -> assertThat(o.outcome())
                        .isEqualTo(LeaseOutcome.Outcome.SKIPPED_ALREADY_POSTED));

        assertThat(batchJournals(batchId)).hasSizeGreaterThan(afterFirst);
        // Exactly one TCO for the lease that had already posted: it was not posted twice.
        UUID first = leaseIdOf("SAMPLE-0001");
        assertThat(batchJournals(batchId))
                .filteredOn(e -> e.getDocType() == JournalDocType.TCO && first.equals(e.getLeaseId()))
                .hasSize(1);
        tx.executeWithoutResult(s ->
                assertThat(leaseOf("SAMPLE-0002").getStatus()).isEqualTo(LeaseStatus.ACTIVE));
    }

    @Test
    void postingABatchThatIsAlreadyFullyPostedWritesNothingMore() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);
        int journals = batchJournals(batchId).size();

        BulkPostResult again = postService.post(batchId);

        assertThat(again.leasesPosted()).isZero();
        assertThat(again.leasesSkipped()).isEqualTo(2);
        assertThat(batchJournals(batchId)).hasSize(journals);
    }

    @Test
    void twoSimultaneousPostsOfOneBatchLeaveOneSetOfJournals() throws Exception {
        UUID batchId = importTheTemplate();

        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        Runnable attempt = () -> {
            TenantContextHolder.setTenantId(tenantId);
            fixture.authenticateAsTenantAdmin();
            try {
                go.await(10, TimeUnit.SECONDS);
                postService.post(batchId);
            } catch (Throwable t) {
                (firstError.compareAndSet(null, t) ? firstError : secondError).set(t);
            } finally {
                TenantContextHolder.clear();
                fixture.clearAuthentication();
            }
        };
        Thread a = new Thread(attempt);
        Thread b = new Thread(attempt);
        a.start();
        b.start();
        go.countDown();
        a.join(60_000);
        b.join(60_000);

        // One of them was refused with the retry message; nothing was posted twice.
        assertThat(firstError.get()).isNotNull();
        assertThat(firstError.get()).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("try again");
        assertThat(secondError.get()).isNull();
        UUID first = leaseIdOf("SAMPLE-0001");
        assertThat(batchJournals(batchId))
                .filteredOn(e -> e.getDocType() == JournalDocType.TCO && first.equals(e.getLeaseId()))
                .hasSize(1);
    }

    // ------------------------------------------------------------------
    // refusals
    // ------------------------------------------------------------------

    @Test
    void aBatchCannotBePostedWithoutABooksStartDate() throws Exception {
        UUID batchId = importTheTemplate();
        // Straight through the repository: the guarded setter refuses a change while
        // opening balances are live, and this test is about the absent value.
        tx.executeWithoutResult(s -> {
            TenantFiscalSettings settings = fiscalRepo.findById(tenantId).orElseThrow();
            settings.setBooksStartDate(null);
            fiscalRepo.save(settings);
        });

        assertThatThrownBy(() -> postService.post(batchId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("books start date");
    }

    private void unmapOutputVat() {
        tx.executeWithoutResult(s -> defaultMappings.findAll().stream()
                .filter(m -> m.getRole() == AccountRole.OUTPUT_VAT)
                .forEach(defaultMappings::delete));
    }

    private void remapOutputVat() {
        propertyAccounts.setTenantDefault(AccountRole.OUTPUT_VAT,
                accountService.getAccountByCode("B-01-03-001").getId());
    }

    /** The register after a clean run: nothing left DRAFT, and the replay's own dates on the rows. */
    @Test
    void theRegisterCarriesWhatTheSpreadsheetSaidHappenedToEachInstrument() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);

        UUID first = leaseIdOf("SAMPLE-0001");
        tx.executeWithoutResult(s -> {
            List<Cheque> rows = chequeRepo.findByLease_IdOrderBySeqNoAsc(first);
            assertThat(rows).hasSize(2);
            Cheque cleared = rows.stream().filter(c -> "100001".equals(c.getChequeNumber())).findFirst().orElseThrow();
            assertThat(cleared.getStatus()).isEqualTo(ChequeStatus.CLEARED);
            assertThat(cleared.getDepositedAt()).isEqualTo(LocalDate.of(2026, 9, 25));
            assertThat(cleared.getClearedAt()).isEqualTo(LocalDate.of(2026, 9, 25));
            assertThat(cleared.getCrtJournalId()).isNotNull();
            Cheque outstanding = rows.stream().filter(c -> "100002".equals(c.getChequeNumber())).findFirst().orElseThrow();
            assertThat(outstanding.getStatus()).isEqualTo(ChequeStatus.REGISTERED);
            assertThat(outstanding.getPdrJournalId()).isNotNull();
            // The replay's instruction is kept, so the batch can be re-posted to the
            // same days after a reverse.
            assertThat(cleared.getImportedStatus()).isEqualTo(ChequeStatus.CLEARED);
            assertThat(cleared.getImportedClearedOn()).isEqualTo(LocalDate.of(2026, 9, 25));
        });
    }

    /** Nobody is told anything about a year of history the landlord is merely copying over. */
    @Test
    void aCleanRunTellsNoRenterAnything() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);

        assertThat(emails.events).isEmpty();
        tx.executeWithoutResult(s -> assertThat(notifications.findAll()).isEmpty());
    }

    // ------------------------------------------------------------------
    // the argument this whole class rests on
    // ------------------------------------------------------------------

    /**
     * The positive control for the class Javadoc (review M4).
     *
     * <p>Every test here proves something about journals posted into a locked period,
     * and the whole exemption argument rests on {@code books_locked_through =
     * 2026-09-30} really being in force in <em>this</em> fixture. If
     * {@code fiscal.lockThrough} silently stopped working, every other test would
     * still pass and would be proving nothing at all. So: the same date, the same
     * organisation, one journal that carries no batch id — refused.</p>
     */
    @Test
    void aJournalWithNoBatchIdStillCannotBePostedIntoThisFixturesLockedPeriod() {
        assertThatThrownBy(() -> posting.post(new PostingRequest(
                JournalDocType.JV, LocalDate.of(2026, 9, 11), "a manual entry, no batch",
                PostingRequest.Dimensions.none(), JournalSourceType.MANUAL, UUID.randomUUID(), null,
                List.of(PostingRequest.dr(accountService.getAccountByCode("A-02-01").getId(), new BigDecimal("10")),
                        PostingRequest.cr(accountService.getAccountByCode("B-01-01").getId(), new BigDecimal("10"))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("locked through 2026-09-30");
    }

    /**
     * A contract whose cheque replay fails <em>after</em> its TCO and PDRs are
     * written leaves no journals at all (review M5).
     *
     * <p>Every other isolation test in this class fails at validate time, before the
     * TCO exists, so none of them exercises the guarantee
     * {@code ContractImportLeasePoster}'s Javadoc is actually about: the cheque
     * transitions join the contract's own transaction, so a half-replayed contract
     * cannot commit. Deactivating the bank leaf after the import is the cheapest way
     * to break a clearance and nothing else — the TCO does not touch it, the PDR
     * does not touch it, and the posting validation does not look at a cheque's own
     * debit account.</p>
     */
    @Test
    void aContractWhoseChequeReplayFailsLeavesNoJournalsAtAll() throws Exception {
        UUID batchId = importTheTemplate();
        tx.executeWithoutResult(s -> {
            var bank = accountService.getAllAccounts().stream()
                    .filter(a -> "Sample Bank - ST1".equals(a.getName())).findFirst().orElseThrow();
            bank.setActive(false);
            accountRepo.save(bank);
        });

        BulkPostResult result = postService.post(batchId);

        // SAMPLE-0001 is the one with a CLEARED cheque; SAMPLE-0002's single row stays
        // REGISTERED and never goes near a bank.
        assertThat(result.leasesFailed()).isEqualTo(1);
        assertThat(result.leases())
                .filteredOn(o -> "SAMPLE-0001".equals(o.externalContractRef()))
                .singleElement()
                .satisfies(o -> assertThat(o.outcome()).isEqualTo(LeaseOutcome.Outcome.FAILED));
        assertThat(result.leasesPosted()).isEqualTo(1);

        UUID failed = leaseIdOf("SAMPLE-0001");
        // Not one journal of it survived: no TCO, no PDR, not a single orphan.
        assertThat(batchJournals(batchId)).noneMatch(e -> failed.equals(e.getLeaseId()));
        tx.executeWithoutResult(s -> {
            assertThat(leaseRepo.findById(failed).orElseThrow().getStatus()).isEqualTo(LeaseStatus.DRAFT);
            assertThat(chequeRepo.findByLease_IdOrderBySeqNoAsc(failed))
                    .allSatisfy(c -> {
                        assertThat(c.getStatus()).isEqualTo(ChequeStatus.DRAFT);
                        assertThat(c.getPdrJournalId()).isNull();
                    });
        });
    }

    /**
     * A run that dies after the first contract has committed leaves those journals in
     * a batch row that exists (review I3).
     *
     * <p>The contracts commit one at a time in transactions of their own while the
     * run's outer transaction — the one holding the batch lock — stays open for the
     * whole run. Anything that rolls that outer transaction back afterwards used to be
     * survivable only because the batch row was already there; on the re-post path it
     * was created in that same outer transaction, so a rollback would have left
     * committed journals carrying a batch id no row had, invisible to "Reverse batch"
     * forever. The progress callback is the failure injector, because it is the one
     * place a test can stand inside the run.</p>
     */
    @Test
    void aRunThatFailsAfterTheFirstContractLeavesItsJournalsInABatchThatExists() throws Exception {
        UUID batchId = importTheTemplate();

        assertThatThrownBy(() -> postService.post(batchId, progress -> {
            if (progress.processed() == 1) throw new IllegalStateException("the connection went away");
        })).isInstanceOf(IllegalStateException.class);

        // The batch row survived the rollback — it was committed before the run began.
        assertThat(batches.get(batchId)).isNotNull();
        List<JournalEntry> written = batchJournals(batchId);
        assertThat(written).isNotEmpty()
                .allSatisfy(e -> assertThat(e.getImportBatchId()).isEqualTo(batchId));
        // markPosted never ran, so the batch is still DRAFT — and pressing Post again
        // is exactly the recovery: the committed contract is skipped, the rest posts.
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.DRAFT);

        BulkPostResult again = postService.post(batchId);
        assertThat(again.leasesSkipped()).isEqualTo(1);
        assertThat(again.leasesPosted()).isEqualTo(1);
        assertThat(again.status()).isEqualTo(ImportBatchStatus.POSTED);
        // And the whole thing is reversible, which is what "not stranded" means.
        batches.reverse(batchId, "starting again");
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
    }
}
