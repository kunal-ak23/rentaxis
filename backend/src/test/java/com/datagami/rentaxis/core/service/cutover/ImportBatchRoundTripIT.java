package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.cutover.ContractImportPostService.BulkPostResult;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
import com.datagami.rentaxis.domain.repository.RentSegmentRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Import, post, reverse — and post again (controller ruling R12).
 *
 * <p>Two things have to be true for a cut-over to be usable at all. The first is
 * that "Reverse batch" really is an undo: the lease-dimension ledger nets to zero
 * for every account and the trial balance is back where it started, with every
 * contract a clean DRAFT rather than merely a DRAFT. The second is that the undo
 * is not a one-way door — the corrected portfolio goes back on the books and lands
 * on exactly the same balances, because the imported statuses and the imported
 * dates survive the reverse and the replay files the same journals on the same
 * days.</p>
 *
 * <p>And the refusals, which matter more than they look: a batch whose contracts
 * have been lived in since the cut-over must not be reversible at all, because
 * taking the import off the books would leave a settlement, an amendment or a
 * month-end close standing on journals that no longer exist.</p>
 */
@SpringBootTest
@Testcontainers
class ImportBatchRoundTripIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired CutoverFixture fixture;
    @Autowired ContractImportPersistService contractPersist;
    @Autowired ContractImportPostService postService;
    @Autowired ImportBatchService batches;
    @Autowired ChequeService chequeService;
    @Autowired RecognitionService recognition;
    @Autowired LedgerQueryService ledger;
    @Autowired LeaseRepository leaseRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository journalLines;
    @Autowired RecognitionEntryRepository recognitionEntries;
    @Autowired RentSegmentRepository segments;
    @Autowired TransactionTemplate tx;

    UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = fixture.newCutOverTenant("ROUND");
        fixture.authenticateAsTenantAdmin();
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

    private Lease leaseOf(String ref) {
        return leaseRepo.findAll().stream()
                .filter(l -> ref.equals(l.getExternalContractRef()))
                .findFirst().orElseThrow(() -> new AssertionError("No lease " + ref));
    }

    private UUID leaseIdOf(String ref) {
        return tx.execute(s -> leaseOf(ref).getId());
    }

    private List<com.datagami.rentaxis.domain.entity.JournalEntry> batchJournals(UUID batchId) {
        return tx.execute(s -> entries.findByImportBatchIdOrderByCreatedAtAsc(batchId));
    }

    /** Every batch created to re-post this one. */
    private List<ImportBatch> successorsOf(UUID reversedBatchId) {
        return tx.execute(s -> batches.list().stream()
                .filter(b -> reversedBatchId.equals(b.getRepostOf())).toList());
    }

    /** Account code → balance as at the cut-over, debit-positive, in code order. */
    private Map<String, BigDecimal> trialBalance() {
        return trialBalanceAt(CutoverFixture.AS_OF);
    }

    /**
     * The same, as at any date.
     *
     * <p>An undo has to be flat on <em>every</em> date, not only on the last one:
     * the batch's journals are spread across the days the contracts were signed and
     * the cheques cleared, and {@code balancesAsOf} has no status predicate, so a
     * REVERSED entry still counts towards the balances at its own day.</p>
     */
    private Map<String, BigDecimal> trialBalanceAt(LocalDate asOf) {
        return tx.execute(s -> {
            Map<String, BigDecimal> out = new LinkedHashMap<>();
            for (TrialBalanceRowDTO row : ledger.trialBalance(asOf, null)) {
                out.put(row.code(), row.balance().stripTrailingZeros());
            }
            return out;
        });
    }

    /**
     * What the ledger holds against one lease, per account — the dimension a cut-over
     * is undone along. Summed from the lines rather than from the trial balance,
     * because the trial balance has no lease dimension.
     */
    private Map<UUID, BigDecimal> leaseDimensionBalances(UUID leaseId) {
        return tx.execute(s -> {
            Map<UUID, BigDecimal> out = new LinkedHashMap<>();
            for (JournalLine l : journalLines.findAll()) {
                if (!leaseId.equals(l.getLeaseId())) continue;
                out.merge(l.getAccount().getId(),
                        l.getDebit().subtract(l.getCredit()), BigDecimal::add);
            }
            return out;
        });
    }

    // ------------------------------------------------------------------
    // the round trip
    // ------------------------------------------------------------------

    @Test
    void reversingTheBatchNetsEveryLeaseDimensionAccountToZeroAndLeavesTheTrialBalanceFlat() throws Exception {
        Map<String, BigDecimal> before = trialBalance();
        assertThat(before).isEmpty();   // the import posts nothing; this is the baseline

        UUID batchId = importTheTemplate();
        postService.post(batchId);
        UUID first = leaseIdOf("SAMPLE-0001");
        assertThat(leaseDimensionBalances(first).values())
                .anySatisfy(v -> assertThat(v.signum()).isNotZero());

        batches.reverse(batchId, "corrected workbook");

        assertThat(leaseDimensionBalances(first).values())
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));
        assertThat(leaseDimensionBalances(leaseIdOf("SAMPLE-0002")).values())
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));
        assertThat(trialBalance().values())
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
    }

    /** "Clean DRAFT" is not "status = DRAFT" — the LeaseReverter contract, item by item. */
    @Test
    void everyContractComesBackAsACleanDraft() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);

        batches.reverse(batchId, "corrected workbook");

        UUID first = leaseIdOf("SAMPLE-0001");
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(first).orElseThrow();
            assertThat(lease.getStatus()).isEqualTo(LeaseStatus.DRAFT);
            assertThat(lease.getPostingJournalId()).isNull();
            assertThat(lease.getPostedAt()).isNull();
            assertThat(lease.getPostedBy()).isNull();
            // The import's INPUT survives: the lines and the reference are what the
            // spreadsheet said, not what the posting did.
            assertThat(lease.getExternalContractRef()).isEqualTo("SAMPLE-0001");

            List<Cheque> rows = chequeRepo.findByLease_IdOrderBySeqNoAsc(first);
            assertThat(rows).hasSize(2).allSatisfy(c -> {
                assertThat(c.getStatus()).isEqualTo(ChequeStatus.DRAFT);
                assertThat(c.getPdrJournalId()).isNull();
                assertThat(c.getCrtJournalId()).isNull();
                assertThat(c.getCbrJournalId()).isNull();
                assertThat(c.getDepositedAt()).isNull();
                assertThat(c.getClearedAt()).isNull();
                assertThat(c.getBouncedAt()).isNull();
                assertThat(c.getReturnedAt()).isNull();
                assertThat(c.getStatusChangedAt()).isNull();
                assertThat(c.getReplacedBy()).isNull();
                assertThat(c.getReplaces()).isNull();
            });
            // …and the replay instruction is KEPT, which is what makes a re-post land
            // on the same days.
            Cheque cleared = rows.stream().filter(c -> "100001".equals(c.getChequeNumber()))
                    .findFirst().orElseThrow();
            assertThat(cleared.getImportedStatus()).isEqualTo(ChequeStatus.CLEARED);
            assertThat(cleared.getImportedClearedOn()).isEqualTo(LocalDate.of(2026, 9, 25));
            assertThat(cleared.getImportedDepositedOn()).isEqualTo(LocalDate.of(2026, 9, 25));

            assertThat(recognitionEntries.findByLease_IdOrderByPeriodStartAsc(first))
                    .isNotEmpty()
                    .allSatisfy(e -> assertThat(e.getStatus()).isEqualTo(RecognitionStatus.CANCELLED));
            assertThat(segments.findByLease_IdOrderByFromDateAsc(first))
                    .isNotEmpty()
                    .allSatisfy(seg -> assertThat(seg.getStatus()).isEqualTo(SegmentStatus.CANCELLED));
        });

        // The flat is lettable again, which is what lets a corrected workbook create
        // a lease on it without tripping ux_leases_one_active_per_unit.
        tx.executeWithoutResult(s -> assertThat(unitRepo.findAll())
                .allSatisfy(u -> assertThat(u.getStatus()).isEqualTo(UnitStatus.VACANT)));
    }

    /**
     * R12's headline: a reversed batch goes back on the books and lands on exactly
     * the balances it had the first time.
     *
     * <p>It is a <em>successor</em> batch that holds the new journals —
     * {@code markPosted} refuses REVERSED → POSTED by design, so an undo that has
     * happened cannot become undoable twice — and the result says which one it is.</p>
     */
    @Test
    void aReversedBatchRePostsToIdenticalBalances() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);
        Map<String, BigDecimal> firstTrialBalance = trialBalance();
        UUID first = leaseIdOf("SAMPLE-0001");
        UUID second = leaseIdOf("SAMPLE-0002");
        Map<UUID, BigDecimal> firstLeaseOne = leaseDimensionBalances(first);
        Map<UUID, BigDecimal> firstLeaseTwo = leaseDimensionBalances(second);
        assertThat(firstTrialBalance).isNotEmpty();

        batches.reverse(batchId, "corrected workbook");
        BulkPostResult again = postService.post(batchId);

        assertThat(again.repostOf()).isEqualTo(batchId);
        assertThat(again.batchId()).isNotEqualTo(batchId);
        assertThat(again.leasesPosted()).isEqualTo(2);
        assertThat(again.status()).isEqualTo(ImportBatchStatus.POSTED);
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.REVERSED);

        // The first post's journals and their mirrors net to zero, so the books after
        // the second post read exactly as they did after the first.
        assertThat(trialBalance()).isEqualTo(firstTrialBalance);
        assertThat(leaseDimensionBalances(first)).isEqualTo(firstLeaseOne);
        assertThat(leaseDimensionBalances(second)).isEqualTo(firstLeaseTwo);

        // And on the same days: the CRT is still filed on the day PACT says the money
        // reached the bank, not on the day somebody pressed Post the second time.
        assertThat(batchJournals(again.batchId()))
                .filteredOn(e -> e.getDocType() == com.datagami.rentaxis.domain.entity.enums.JournalDocType.CRT)
                .singleElement()
                .satisfies(e -> assertThat(e.getEntryDate()).isEqualTo(LocalDate.of(2026, 9, 25)));
    }

    /**
     * Review C1 / ruling R16, composed: the undo is flat on every date, and posting
     * the batch again does not double anything.
     *
     * <p><b>Mid-September is the date that matters.</b> Both contracts are dated 11
     * Sep and the cleared cheque reaches the bank on the 25th, so as at the 20th the
     * books hold the two {@code TCO}s and the three {@code PDR}s and nothing else.
     * While the reversal date was the caller's — and the web sent <em>today</em> —
     * the mirrors all landed on one late day, so the books as at the 20th still
     * carried the whole cut-over after it had supposedly been taken off, and "Post
     * again" then wrote the same journals a second time at their own pre-D dates:
     * every balance before the reversal date <b>doubled</b>, permanently. Pinning
     * each mirror to its own entry's day is what makes both halves below true.</p>
     */
    @Test
    void aReverseIsFlatOnEveryDateAndPostingAgainDoesNotDoubleTheBalances() throws Exception {
        LocalDate midSeptember = LocalDate.of(2026, 9, 20);

        UUID batchId = importTheTemplate();
        postService.post(batchId);
        Map<String, BigDecimal> firstAtMidSeptember = trialBalanceAt(midSeptember);
        Map<String, BigDecimal> firstAtCutOver = trialBalance();
        assertThat(firstAtMidSeptember).as("the contracts are on the books by the 20th").isNotEmpty();

        batches.reverse(batchId, "corrected workbook");

        assertThat(trialBalanceAt(midSeptember).values())
                .as("as at the 20th, the undo has to have happened too")
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));
        assertThat(trialBalance().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));
        assertThat(trialBalanceAt(LocalDate.of(2030, 1, 1)).values())
                .as("and nothing of it is left in any later period")
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));

        postService.post(batchId);

        assertThat(trialBalanceAt(midSeptember)).isEqualTo(firstAtMidSeptember);
        assertThat(trialBalance()).isEqualTo(firstAtCutOver);
    }

    /**
     * A re-post that dies after its first contract has committed (review I3).
     *
     * <p>This is the path the outer transaction's length actually endangers. The
     * successor batch holds the id every journal of the re-post will carry; the
     * contracts commit one at a time in transactions of their own while the run's own
     * transaction stays open for the whole run. If the successor row lived in <em>that</em>
     * transaction, an idle timeout, a recycled connection or a pooler killing it would
     * leave committed journals naming a batch that does not exist — unreachable by
     * "Reverse batch" forever, and with a database that would happily hold them.</p>
     *
     * <p>So the successor is committed first, and found again rather than made twice:
     * a second successor would strand the first one's journals in a DRAFT batch
     * nobody looks at.</p>
     */
    @Test
    void aFailedRePostKeepsItsSuccessorBatchAndTheNextAttemptReusesIt() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);
        batches.reverse(batchId, "corrected workbook");

        assertThatThrownBy(() -> postService.post(batchId, progress -> {
            if (progress.processed() == 1) throw new IllegalStateException("the connection went away");
        })).isInstanceOf(IllegalStateException.class);

        // The successor survived the rollback, and it says what it is.
        List<ImportBatch> successors = successorsOf(batchId);
        assertThat(successors).singleElement()
                .satisfies(b -> assertThat(b.getStatus()).isEqualTo(ImportBatchStatus.DRAFT));
        UUID successorId = successors.get(0).getId();
        // And the contract that did commit is in it, reachable by the id it carries.
        assertThat(batchJournals(successorId)).isNotEmpty();

        // Pressing Post again finds that successor rather than making a second one.
        BulkPostResult retried = postService.post(batchId);
        assertThat(retried.batchId()).isEqualTo(successorId);
        assertThat(retried.repostOf()).isEqualTo(batchId);
        assertThat(retried.leasesSkipped()).isEqualTo(1);
        assertThat(retried.leasesPosted()).isEqualTo(1);
        assertThat(successorsOf(batchId)).hasSize(1);
        assertThat(batches.get(successorId).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
    }

    // ------------------------------------------------------------------
    // the refusals
    // ------------------------------------------------------------------

    @Test
    void aBatchWhoseChequeHasClearedSinceTheCutOverCannotBeReversed() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);
        int journals = batchJournals(batchId).size();

        UUID outstanding = tx.execute(s -> chequeRepo
                .findByLease_IdOrderBySeqNoAsc(leaseOf("SAMPLE-0001").getId()).stream()
                .filter(c -> "100002".equals(c.getChequeNumber())).findFirst().orElseThrow().getId());
        chequeService.deposit(outstanding, ChequeActionRequest.on(LocalDate.of(2026, 10, 5)));
        chequeService.clear(outstanding, ChequeActionRequest.on(LocalDate.of(2026, 10, 6)));

        assertThatThrownBy(() -> batches.reverse(batchId, "oops"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("SAMPLE-0001")
                .hasMessageContaining("100002")
                .hasMessageContaining("cleared since the cut-over");

        // Nothing was written: the refusal came before the first reversal.
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
        assertThat(batchJournals(batchId)).hasSize(journals);
        tx.executeWithoutResult(s ->
                assertThat(leaseOf("SAMPLE-0001").getStatus()).isEqualTo(LeaseStatus.ACTIVE));
    }

    @Test
    void aBatchWhoseRentAMonthEndCloseHasRecognisedCannotBeReversed() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);

        // The ordinary close, the way the nightly job runs it: October's rent, after
        // the books have opened, carrying no batch id.
        RecognitionService.RecognitionRunResult run = recognition.runTo(LocalDate.of(2026, 10, 31), false);
        assertThat(run.posted()).isGreaterThan(0);

        // Review I3 / ruling R20: the blocker used to send the accountant to
        // "Reverse that period's recognition first", a door that does not exist —
        // no endpoint reverses a CIL, and the only paths that touch a posted one
        // (termination, amendment) are themselves blockers here. The sentence now
        // states the product fact instead: the window for undoing a WHOLE cut-over
        // closes at the first month-end close, and after it contracts are corrected
        // one at a time.
        assertThatThrownBy(() -> batches.reverse(batchId, "oops"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("was recognised by the month-end close on 2026-10-31")
                .hasMessageContaining(
                        "a cut-over batch cannot be reversed once its contracts have been through a close")
                .hasMessageContaining("Correct individual contracts by amendment instead")
                .hasMessageNotContaining("Reverse that period's recognition first");
        assertThat(batches.get(batchId).getStatus()).isEqualTo(ImportBatchStatus.POSTED);
    }

    @Test
    void aBatchWhoseContractHasBeenTerminatedCannotBeReversed() throws Exception {
        UUID batchId = importTheTemplate();
        postService.post(batchId);
        UUID first = leaseIdOf("SAMPLE-0001");
        tx.executeWithoutResult(s -> {
            // Straight through the repository: this test is about the state, not about
            // how a tenancy gets into it, and a real termination posts its own journals
            // which would then be the blocker under test.
            Lease lease = leaseRepo.findById(first).orElseThrow();
            lease.setStatus(LeaseStatus.TERMINATED);
            leaseRepo.save(lease);
        });

        assertThatThrownBy(() -> batches.reverse(batchId, "oops"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("SAMPLE-0001")
                .hasMessageContaining("TERMINATED");
    }
}
