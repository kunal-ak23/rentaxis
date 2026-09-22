package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.dto.ImportErrorDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.ImportBatchEntity;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Step 1 of the cut-over (spec §10.3): turn a DRAFT import batch into a posted
 * portfolio.
 *
 * <p>Per contract, in one transaction of its own: the {@code TCO} at the contract's
 * own date, one {@code PDR} per instrument, each cheque replayed to the status and
 * the day PACT recorded for it, and the rent already earned recognised up to the
 * day before the client's books open. <b>Every journal carries the batch id</b> —
 * which is what exempts them from the period lock (a cut-over is dated into months
 * that are closed by definition) and what lets the whole run be taken back off in
 * one act afterwards.</p>
 *
 * <h2>What this class owns, and what it deliberately does not</h2>
 *
 * <p><b>It does not own a transaction around the run.</b> One failing contract must
 * not cost the other five hundred and ninety-nine, so each lease commits on its own
 * through {@link ContractImportLeasePoster}. What this method's transaction <em>is</em>
 * for is the batch's row lock: two clerks pressing Post, or a double-click, would
 * otherwise both read the same set of DRAFT leases and both post them.</p>
 *
 * <p><b>It does not decide what {@code journalsPosted} is.</b> That number is read
 * back out of the ledger at the end. A counter accumulated through the loop would
 * be a claim about what was written; this is what was written.</p>
 *
 * <p><b>A second Post is not an error.</b> It retries exactly the leases that
 * failed — the rest are already ACTIVE and are skipped rather than posted twice —
 * which is the loop an accountant actually works: press Post, read the failures,
 * map the account the message names, press Post again.</p>
 */
@Service
public class ContractImportPostService {

    private static final Logger log = LoggerFactory.getLogger(ContractImportPostService.class);

    private final ImportBatchService batches;
    private final LeaseRepository leases;
    private final JournalEntryRepository journals;
    private final ContractImportLeasePoster leasePoster;
    private final TenantFiscalSettingsService fiscal;

    /**
     * Read-write, for the transaction that holds the batch's row lock across the
     * run. Built here rather than injected so the propagation and the read-only
     * flag are this class's own choice.
     */
    private final TransactionTemplate tx;

    /**
     * A transaction of its own, suspended out of the run's. Used for exactly one
     * thing: committing the successor batch row before the first contract posts —
     * see {@link #successorOf}.
     */
    private final TransactionTemplate ownTx;

    public ContractImportPostService(ImportBatchService batches,
                                     LeaseRepository leases, JournalEntryRepository journals,
                                     ContractImportLeasePoster leasePoster,
                                     TenantFiscalSettingsService fiscal,
                                     PlatformTransactionManager transactionManager) {
        this.batches = batches;
        this.leases = leases;
        this.journals = journals;
        this.leasePoster = leasePoster;
        this.fiscal = fiscal;
        this.tx = new TransactionTemplate(transactionManager);
        this.ownTx = new TransactionTemplate(transactionManager);
        this.ownTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------
    // the result
    // ------------------------------------------------------------------

    /** How one contract of the batch fared. */
    public record LeaseOutcome(UUID leaseId, String externalContractRef, Outcome outcome, String reason,
                               int journals, int chequesDeposited, int chequesCleared, int chequesBounced,
                               int recognitionEntriesPosted) {

        public enum Outcome {
            /** Posted by this run. */
            POSTED,
            /** Already on the books when this run reached it — a retry, not a double post. */
            SKIPPED_ALREADY_POSTED,
            /** Refused; {@link #reason} says why, and the lease is still a DRAFT of this batch. */
            FAILED
        }
    }

    /**
     * What the whole run did.
     *
     * @param batchId  the batch that now holds the journals. Normally the one asked
     *                 for; for a re-post of a REVERSED batch it is the successor
     *                 batch this call created — see {@link #post(UUID)}.
     * @param repostOf the REVERSED batch this run re-posted, else null.
     * @param journalsPosted derived from the ledger, never accumulated.
     * @param failures one per failed contract, in the shape the import screen
     *                 already renders.
     */
    public record BulkPostResult(UUID batchId, UUID repostOf, ImportBatchStatus status,
                                 int leasesPosted, int leasesSkipped, int leasesFailed,
                                 int chequesDeposited, int chequesCleared, int chequesBounced,
                                 int recognitionEntriesPosted, int journalsPosted,
                                 List<LeaseOutcome> leases, List<ImportErrorDTO> failures) {
    }

    // ------------------------------------------------------------------
    // posting
    // ------------------------------------------------------------------

    /** {@link #post(UUID, Consumer)} with no progress reporting. */
    public BulkPostResult post(UUID batchId) {
        return post(batchId, progress -> { });
    }

    /**
     * Bulk-post the batch.
     *
     * <p><b>A REVERSED batch re-posts as a new one.</b> {@code markPosted} refuses
     * REVERSED → POSTED by design (Task 7): an undo that has already happened must
     * not become undoable a second time, and a second pass over the reversed batch's
     * journals would find only the mirrors it has to skip. But the leases are still
     * there, still carrying the imported statuses and the imported dates the reverse
     * deliberately kept, so the corrected portfolio does not need re-uploading —
     * what it needs is a fresh batch row to hold the new journals. This creates one
     * over the same leases and the same created entities, posts that, and says so in
     * {@code repostOf}. The alternative the ruling offered — discard and re-import —
     * requires the workbook again, which the server does not keep.</p>
     *
     * @param progress called after each contract with {@code (processed, total)}, so
     *                 a long run can be watched. Never called inside the lease's own
     *                 transaction.
     */
    public BulkPostResult post(UUID batchId, Consumer<Progress> progress) {
        return tx.execute(status -> runUnderBatchLock(batchId, progress));
    }

    /** How far a run has got, for the job row the web polls. */
    public record Progress(int processed, int total) {
    }

    private BulkPostResult runUnderBatchLock(UUID batchId, Consumer<Progress> progress) {
        ImportBatch batch = batches.lockForRun(batchId);
        // The opening balances are the LAST step of the cut-over (review C2, ruling
        // R17; spec §10.3 "Amendment 2026-09-22"). The OB journal posts PACT's figure
        // LESS what step 1 left on the books, so putting more of step 1 on the books
        // underneath it — a first bulk post, or a Post-again of a reversed batch —
        // leaves the books holding neither PACT's figure nor ours until somebody
        // notices the grid say "Replace". Refused up front, under the batch lock,
        // before one contract has been touched.
        if (fiscal.hasLiveOpeningBalance()) {
            throw new BusinessRuleViolationException(ImportBatchService.OPENING_BALANCES_ARE_LIVE);
        }
        UUID repostOf = null;
        if (batch.getStatus() == ImportBatchStatus.REVERSED) {
            repostOf = batch.getId();
            batch = successorOf(batch);
        } else if (batch.getStatus() == ImportBatchStatus.DISCARDED) {
            // Says only what is true: a discard deletes what it can and reports what
            // it kept, so "its leases have been deleted" was a claim this code cannot
            // make.
            throw new BusinessRuleViolationException(
                    "Import batch is DISCARDED; what it created has been thrown away."
                            + " Import the corrected workbook again.");
        }
        UUID target = batch.getId();

        LocalDate booksStart = fiscal.booksStartDate();
        if (booksStart == null) {
            throw new BusinessRuleViolationException(
                    "Set the books start date in Settings → Fiscal before posting a cut-over batch");
        }
        // R13: recognition catches up to the day before the books open and stops.
        // Everything from booksStart is the ordinary month-end close's.
        LocalDate recogniseThrough = booksStart.minusDays(1);

        List<Plan> plan = planOf(target);
        List<LeaseOutcome> outcomes = new ArrayList<>(plan.size());
        List<ImportErrorDTO> failures = new ArrayList<>();
        int posted = 0;
        int skipped = 0;
        int deposited = 0;
        int cleared = 0;
        int bounced = 0;
        int recognised = 0;
        int processed = 0;
        progress.accept(new Progress(0, plan.size()));

        for (Plan row : plan) {
            try {
                ContractImportLeasePoster.Posted done = leasePoster.postOne(target, row.leaseId(), recogniseThrough);
                if (done == null) {
                    skipped++;
                    outcomes.add(new LeaseOutcome(row.leaseId(), row.ref(),
                            LeaseOutcome.Outcome.SKIPPED_ALREADY_POSTED, null, 0, 0, 0, 0, 0));
                } else {
                    posted++;
                    deposited += done.chequesDeposited();
                    cleared += done.chequesCleared();
                    bounced += done.chequesBounced();
                    recognised += done.recognitionEntriesPosted();
                    outcomes.add(new LeaseOutcome(row.leaseId(), row.ref(), LeaseOutcome.Outcome.POSTED, null,
                            0, done.chequesDeposited(), done.chequesCleared(), done.chequesBounced(),
                            done.recognitionEntriesPosted()));
                }
            } catch (RuntimeException e) {
                // The lease's own transaction rolled back; this one is untouched,
                // which is the whole point of the separate bean. The contract is
                // still a DRAFT of this batch, so a later Post retries exactly it.
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                log.warn("Cut-over contract {} ({}) could not be posted: {}", row.ref(), row.leaseId(), reason);
                outcomes.add(new LeaseOutcome(row.leaseId(), row.ref(), LeaseOutcome.Outcome.FAILED,
                        reason, 0, 0, 0, 0, 0));
                failures.add(new ImportErrorDTO("Contracts", null, "ContractNumber",
                        "Contract " + row.ref() + ": " + reason));
            }
            progress.accept(new Progress(++processed, plan.size()));
        }

        // Derived, not counted: what the ledger actually holds for this batch.
        Map<UUID, Long> byLease = new java.util.HashMap<>();
        int journalsPosted = 0;
        for (JournalEntry e : journals.findByImportBatchIdOrderByCreatedAtAsc(target)) {
            journalsPosted++;
            if (e.getLeaseId() != null) byLease.merge(e.getLeaseId(), 1L, Long::sum);
        }
        List<LeaseOutcome> withCounts = outcomes.stream()
                .map(o -> new LeaseOutcome(o.leaseId(), o.externalContractRef(), o.outcome(), o.reason(),
                        byLease.getOrDefault(o.leaseId(), 0L).intValue(),
                        o.chequesDeposited(), o.chequesCleared(), o.chequesBounced(),
                        o.recognitionEntriesPosted()))
                .toList();

        ImportBatchStatus finalStatus = batch.getStatus();
        if (posted > 0 || skipped > 0) {
            // "POSTED only if at least one contract posted" — plus every retry that
            // found something already on the books, because `journals_posted` is a
            // figure the batches screen shows and a partially successful retry that
            // skipped this would leave it stale. A batch where every contract failed
            // stays DRAFT: nothing of it is on the books, and offering "Reverse" for
            // it would be a button with nothing to undo.
            finalStatus = batches.markPosted(target, journalsPosted).getStatus();
        }

        log.info("Bulk-posted import batch {}{}: {} posted, {} skipped, {} failed, {} journals",
                target, repostOf == null ? "" : " (re-post of " + repostOf + ")",
                posted, skipped, failures.size(), journalsPosted);

        return new BulkPostResult(target, repostOf, finalStatus, posted, skipped, failures.size(),
                deposited, cleared, bounced, recognised, journalsPosted, withCounts, failures);
    }

    /** One contract of the batch, and the reference a failure has to name. */
    private record Plan(UUID leaseId, String ref, LocalDate contractDate) {
    }

    /**
     * The batch's contracts in a deterministic order: contract date, then the
     * reference the sheet gave them.
     *
     * <p>Order matters because a run can be interrupted and because entry numbers
     * are handed out in it — two runs of the same workbook should produce the same
     * numbering, and an accountant reading the journal should find the portfolio in
     * the order the contracts were signed rather than in UUID order.</p>
     */
    private List<Plan> planOf(UUID batchId) {
        List<Plan> plan = new ArrayList<>();
        for (UUID leaseId : batches.leaseIds(batchId)) {
            Lease lease = leases.findByIdScopedToTenant(leaseId).orElse(null);
            if (lease == null) continue;   // the link may outlive its lease; changeset 88 has no FK
            plan.add(new Plan(leaseId,
                    lease.getExternalContractRef() == null ? leaseId.toString() : lease.getExternalContractRef(),
                    lease.getContractDate()));
        }
        plan.sort(Comparator
                .comparing(Plan::contractDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Plan::ref, Comparator.nullsLast(Comparator.naturalOrder())));
        return plan;
    }

    /**
     * A fresh batch over the same leases and the same created entities — how a
     * reversed cut-over is put back on the books.
     *
     * <p>The links are copied rather than moved: the reversed batch keeps saying
     * which contracts it once held, which is what its own reversal is a record of.</p>
     *
     * <p><b>Committed before the run starts, in a transaction of its own</b>
     * (review I3). The contracts commit one at a time in {@code REQUIRES_NEW}
     * transactions while this run's own transaction — the one holding the lock —
     * stays open for the whole run, and a long-running transaction is exactly the
     * one an idle timeout, a connection recycle or a pooler kills. If the successor
     * row lived in that transaction, a rollback any time after the first contract
     * committed would leave journals carrying a batch id no row has: invisible to
     * "Reverse batch" forever. {@code REQUIRES_NEW} here means the id those journals
     * will carry is durable before the first one is written; a failed run then costs
     * an empty DRAFT batch, which is discardable and postable, rather than an
     * unreachable ledger. {@code fk_je_import_batch} (changeset 88) is the database's
     * half of the same guarantee.</p>
     *
     * <p><b>And it is found again rather than made twice.</b> A run that died
     * half-way already has its successor; creating a second one would strand the
     * first one's journals in a DRAFT batch nobody looks at. The lock on the reversed
     * batch is what makes the look-up-then-create safe.</p>
     */
    private ImportBatch successorOf(ImportBatch reversed) {
        UUID reversedId = reversed.getId();
        String label = label("Re-post of ", reversed.getLabel());
        UUID jobId = reversed.getImportJobId();
        // Read under the outer transaction's lock, so the lists cannot move while the
        // successor is being built.
        List<UUID> leaseIds = batches.leaseIds(reversedId);
        List<ImportBatchEntity> created = batches.createdEntities(reversedId);

        return ownTx.execute(s -> {
            ImportBatch existing = batches.successorOf(reversedId).orElse(null);
            if (existing != null) {
                log.info("Re-posting reversed batch {} into the successor {} it already has",
                        reversedId, existing.getId());
                return existing;
            }
            ImportBatch successor = batches.createSuccessor(jobId, label, reversedId);
            for (UUID leaseId : leaseIds) {
                batches.linkLease(successor.getId(), leaseId);
            }
            for (ImportBatchEntity e : created) {
                batches.linkEntity(successor.getId(), e.getEntityType(), e.getEntityId());
            }
            log.info("Re-posting reversed batch {} as a new batch {}", reversedId, successor.getId());
            return successor;
        });
    }

    /** {@code import_batches.label} is varchar(120); a prefix must not push it over. */
    private static String label(String prefix, String original) {
        String full = prefix + (original == null ? "cut-over import" : original);
        return full.length() <= 120 ? full : full.substring(0, 120);
    }

}
