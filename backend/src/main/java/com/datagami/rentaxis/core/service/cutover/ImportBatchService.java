package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.ImportBatchEntity;
import com.datagami.rentaxis.domain.entity.ImportBatchLease;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportedEntityType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.ImportBatchEntityRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchLeaseRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The cut-over's unit of undo (spec §10.3).
 *
 * <p>An import batch groups everything one contract-import run created: the leases
 * it drafted, and — once it is bulk-posted — every journal written on their behalf.
 * Those journals carry the batch's id, which is both what lets the whole run be
 * taken off the books in one act and what exempts them from the period lock
 * ({@code PostingService.post}/{@code .reverse} test {@code importBatchId} for
 * exactly that reason: a cut-over posts into months that are closed by
 * definition).</p>
 *
 * <h2>Why a cut-over journal keeps its natural source type</h2>
 *
 * <p>Spec §10.3 says every journal of an import carries
 * {@code source_type = IMPORT}. It does not, and that is deliberate: a cut-over
 * {@code TCO} is sourced {@code LEASE}, a {@code PDR}/{@code CRT}/{@code CBR} is
 * sourced {@code CHEQUE}, a catch-up {@code CIL} is sourced {@code RECOGNITION},
 * exactly as the same journal written by hand would be. {@code import_batch_id}
 * alone says it belongs to the import.</p>
 *
 * <p>Two things depend on the natural source and would break if it were flattened:</p>
 * <ul>
 *   <li>{@code LeaseReverter.blockersAgainstRevert} finds an amendment's fresh
 *       {@code TCO} and an extension's second one through
 *       {@code findBySourceTypeAndSourceId(LEASE, leaseId)}. With an imported
 *       contract's own TCO sourced {@code IMPORT}, that query would no longer see
 *       the batch's own entries and could not tell "this is ours" from "somebody
 *       amended it afterwards" — which is the whole question it exists to answer.</li>
 *   <li>{@code uq_je_recognition_source} (changeset 86) is a partial unique index
 *       on {@code source_id WHERE source_type = 'RECOGNITION'} — one CIL per
 *       recognition entry. A catch-up CIL sourced {@code IMPORT} would fall outside
 *       the predicate and the index would stop guarding exactly the rows a cut-over
 *       writes most of.</li>
 * </ul>
 *
 * <p>More generally, an imported contract's ledger should be indistinguishable from
 * a typed one once it is on the books; the batch id is the one extra fact, and it is
 * a column of its own. {@code JournalSourceType.IMPORT} survives for the
 * opening-balance-shaped entries that genuinely have no other source.</p>
 *
 * <p><b>Every method here is transactional</b>, reads included.
 * {@code TenantAspect} enables Hibernate's tenant filter on the session bound to
 * the current transaction; without one, each repository call gets a session of its
 * own and the filter is enabled on a session the query never runs on — an
 * unfiltered, cross-tenant read. The two places that load a row with
 * {@code EntityManager} rather than through a repository bypass the aspect
 * altogether and carry an explicit tenant comparison instead.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImportBatchService {

    private final ImportBatchRepository batches;
    private final ImportBatchLeaseRepository links;
    private final ImportBatchEntityRepository entityLinks;
    private final JournalEntryRepository journals;
    private final PostingService posting;
    private final EntityManager entityManager;

    /**
     * Only for {@link #OPENING_BALANCES_ARE_LIVE}'s guard. No cycle: the fiscal
     * service reads its own repositories and knows nothing of this one.
     */
    private final TenantFiscalSettingsService fiscal;

    /**
     * An {@code ObjectProvider}, not a direct dependency: the lease-side undo is
     * implemented by plan 4 Task 11 on {@code LeaseService}, and this service has to
     * start without it. Reversing a batch that created leases with no reverter
     * configured is a hard error, not a silent half-undo — see {@link #reverse}.
     */
    private final ObjectProvider<LeaseReverter> leaseReverter;

    /** Finance-ops spec §4. Setter-injected: a unit test builds this service by hand without it. */
    private com.datagami.rentaxis.core.service.ledger.BankLockService bankLock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setBankLock(com.datagami.rentaxis.core.service.ledger.BankLockService bankLock) {
        this.bankLock = bankLock;
    }

    /**
     * The one sentence every cut-over step says when the books have already been
     * opened (review C2, ruling R17; spec §10.3 "Amendment 2026-09-22").
     *
     * <p><b>Why it is a rule and not a warning.</b> The opening journal posts
     * {@code PACT − ours} per account, so it is computed against what step 1 left on
     * the books. A bulk post, a batch reverse or a Post-again afterwards moves
     * {@code ours} under a journal that already netted the old value out, and the
     * books then hold neither PACT's figure nor ours until somebody notices and
     * presses Replace. The opening balances are the <em>last</em> step of the
     * cut-over, which is the order spec §10.3 puts them in; the remedy is two clicks
     * and is named in the sentence.</p>
     *
     * <p>Shared with {@code ContractImportPostService} so the three refusals cannot
     * drift into three different sentences.</p>
     */
    public static final String OPENING_BALANCES_ARE_LIVE =
            "Opening balances are posted. Reverse them first, then post them again after this step.";

    @Transactional
    public ImportBatch create(UUID importJobId, String label) {
        return createSuccessor(importJobId, label, null);
    }

    /**
     * The same, naming the REVERSED batch this one exists to re-post.
     *
     * <p>{@link #markPosted} refuses REVERSED → POSTED, so putting a reversed
     * cut-over back on the books means a successor row to hold the new journals
     * (R12). {@code repostOf} makes the relationship a column rather than a label
     * convention, which is what {@link #successorOf} reads.</p>
     */
    @Transactional
    public ImportBatch createSuccessor(UUID importJobId, String label, UUID repostOf) {
        ImportBatch b = new ImportBatch();
        b.setImportJobId(importJobId);
        b.setLabel(label);
        b.setStatus(ImportBatchStatus.DRAFT);
        b.setRepostOf(repostOf);
        return batches.save(b);
    }

    /**
     * The successor a reversed batch already has, if any.
     *
     * <p>Read before one is created, because a re-post whose run died half-way has
     * committed its successor and some of its journals: making a second successor
     * would strand the first one's journals in a DRAFT batch nobody looks at. Only a
     * successor that is still DRAFT counts — one that posted is finished, and a
     * further re-post of the same reversed batch is then a genuinely new run.</p>
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ImportBatch> successorOf(UUID reversedBatchId) {
        return batches.findByRepostOfAndStatus(reversedBatchId, ImportBatchStatus.DRAFT).stream().findFirst();
    }

    /**
     * Record that {@code leaseId} belongs to this batch. Idempotent: the link's
     * primary key is the pair, so re-linking a lease the importer already recorded
     * is a no-op rather than a duplicate-key failure part-way through a workbook.
     */
    @Transactional
    public void linkLease(UUID batchId, UUID leaseId) {
        ImportBatch b = get(batchId);
        if (leaseId == null) throw new BusinessRuleViolationException("A batch link needs a lease id");
        links.save(new ImportBatchLease(batchId, leaseId));
        b.setLeasesImported(links.findByBatchIdOrderByLeaseIdAsc(batchId).size());
        batches.save(b);
    }

    /**
     * Record that this batch created a property, building, unit or renter.
     *
     * <p>Idempotent for the same reason {@link #linkLease} is: the primary key is
     * all three columns. It does not touch {@code leasesImported} — that counter
     * means leases, and a batch's headline number should not move because it also
     * made a building.</p>
     *
     * <p>Why record it at all: see {@code ImportBatchEntity}. A batch that is
     * reversed and then discarded has to delete exactly the rows it made, and
     * nothing else.</p>
     */
    @Transactional
    public void linkEntity(UUID batchId, ImportedEntityType type, UUID entityId) {
        get(batchId);
        if (type == null || entityId == null) {
            throw new BusinessRuleViolationException("A batch link needs an entity type and id");
        }
        entityLinks.save(new ImportBatchEntity(batchId, type, entityId));
    }

    /** What this batch created, beside its leases. Resolves the batch first. */
    @Transactional(readOnly = true)
    public List<ImportBatchEntity> createdEntities(UUID batchId) {
        get(batchId);
        return entityLinks.findByBatchIdOrderByEntityTypeAscEntityIdAsc(batchId);
    }

    /** The leases this batch created. Resolves the batch first, so another tenant's id is "not found". */
    @Transactional(readOnly = true)
    public List<UUID> leaseIds(UUID batchId) {
        get(batchId);
        return links.findByBatchIdOrderByLeaseIdAsc(batchId).stream().map(ImportBatchLease::getLeaseId).toList();
    }

    @Transactional(readOnly = true)
    public List<ImportBatch> list() {
        return batches.findAllByOrderByCreatedAtAsc();
    }

    /**
     * One batch by id, scoped to the calling tenant by the Hibernate filter the
     * aspect enables around this repository call. Another organisation's id is a
     * {@code NotFoundException} — a 404, not a 403: whether that id exists at all is
     * not this tenant's business.
     */
    @Transactional(readOnly = true)
    public ImportBatch get(UUID batchId) {
        return batches.findById(batchId).orElseThrow(() -> new NotFoundException("Import batch not found"));
    }

    /**
     * Mark a batch as posted, with the number of journals its bulk post wrote.
     *
     * <p>A REVERSED batch is refused: putting it back to POSTED would make an undo
     * that has already happened reversible a second time, and the second pass would
     * find only the mirror entries it must skip.</p>
     */
    @Transactional
    public ImportBatch markPosted(UUID batchId, int journalsPosted) {
        ImportBatch b = get(batchId);
        if (b.getStatus() == ImportBatchStatus.REVERSED) {
            throw new BusinessRuleViolationException(
                    "Import batch is REVERSED; a reversed batch cannot be posted again. Import the corrected file as a new batch.");
        }
        // The same doubled guard discard has. ContractImportPostService refuses a
        // DISCARDED batch before it starts, but this transition is public and the
        // status it would write is the one that says "these contracts are on the
        // books" — about a batch whose contracts were deleted.
        if (b.getStatus() == ImportBatchStatus.DISCARDED) {
            throw new BusinessRuleViolationException(
                    "Import batch is DISCARDED; what it created has been deleted. Import the corrected workbook again.");
        }
        b.setStatus(ImportBatchStatus.POSTED);
        b.setJournalsPosted(journalsPosted);
        b.setPostedAt(Instant.now());
        b.setPostedBy(currentUserId());
        return batches.save(b);
    }

    /**
     * The batch and everything it created are gone (ruling I4).
     *
     * <p><b>Only from DRAFT.</b> A POSTED batch has journals behind its contracts —
     * reverse it first. A <b>REVERSED</b> one is the case that looks discardable and
     * is not: its journals are still in the ledger, permanently naming its leases,
     * its units, its property and its renters ({@code journal_entries}' restricting
     * keys plus {@code trg_journal_entries_immutable}), so there is nothing a discard
     * could actually remove. Marking it DISCARDED anyway left the contracts standing
     * while taking away the one thing that still worked — posting the batch again —
     * and the corrected workbook could not import over them either. So it is refused,
     * and the sentence says which of the two real options to take.</p>
     *
     * <p>The batch row itself survives: after a discard it is the only record that
     * the import ever happened, which is why {@code discarded_at}/{@code _by} are
     * columns rather than a deletion.</p>
     */
    @Transactional
    public ImportBatch markDiscarded(UUID batchId) {
        ImportBatch b = get(batchId);
        requireDiscardableStatus(b);
        b.setStatus(ImportBatchStatus.DISCARDED);
        b.setDiscardedAt(Instant.now());
        b.setDiscardedBy(currentUserId());
        return batches.save(b);
    }

    /**
     * The one definition of "this batch may be thrown away", shared by
     * {@link #markDiscarded} and {@code ImportBatchDiscardService}'s up-front check
     * so the early refusal and the transition cannot drift apart.
     */
    public static void requireDiscardableStatus(ImportBatch b) {
        if (b.getStatus() == ImportBatchStatus.DRAFT) return;
        throw new BusinessRuleViolationException(switch (b.getStatus()) {
            case POSTED -> "Import batch is POSTED; reverse it first — its contracts are on the books.";
            case REVERSED -> "A reversed batch keeps its contracts; post it again or leave it reversed.";
            case DISCARDED -> "Import batch is DISCARDED; there is nothing left to throw away.";
            default -> "Import batch is " + b.getStatus() + "; only a DRAFT batch can be discarded.";
        });
    }

    /**
     * The batch row, locked for the length of a whole run — what {@code post},
     * {@code discard} and {@link #reverse} all take, so no two of them can dismantle
     * one batch from both ends.
     *
     * <p><b>NOWAIT, for every caller</b> (review I1, ruling R18). A blocking lock is
     * wrong here in three different ways, one per caller: a second post of a POSTED
     * batch is <em>legal</em> — it is the retry path — so a blocking loser would
     * quietly walk the whole portfolio again and report a run that did nothing; a
     * discard that blocked behind a post would start deleting rows the post had just
     * put on the books; and a <em>reverse</em> that blocked behind a six-hundred
     * contract bulk post would sit there for minutes, hand the accountant a proxy
     * timeout, and then — once the post committed — take the whole cut-over off the
     * books with nobody watching. Reverse used to block, on the argument that the
     * loser "re-reads REVERSED and is refused"; that argument only ever covered two
     * reverses racing each other.</p>
     *
     * <p><b>One sentence, whoever is holding it.</b> The refusal names what is
     * happening to the batch, not what the refused caller was trying to do — the
     * caller already knows that, and a message saying "being reversed" to somebody
     * whose post lost to a discard was simply wrong.</p>
     *
     * <p>{@code find} then {@code refresh(…, PESSIMISTIC_WRITE)} rather than a
     * {@code @Lock} finder, for the reason {@code VoucherService#lockForWrite}
     * documents: a locking JPQL query hands back the first-level-cache instance with
     * its stale state, so the loser of the race would take the lock and then decide
     * on the pre-lock status.</p>
     *
     * <p><b>The tenant is compared before the lock is taken, and again after.</b>
     * {@code TenantAspect} enables the Hibernate filter {@code @Before} a
     * {@code domain.repository} call, and this may be the first thing a transaction
     * does — so the filter is off for both the {@code find} and the {@code refresh},
     * and the explicit comparison is the only guard on this path. Doing it first as
     * well costs nothing and stops a caller with a foreign id from holding a row lock
     * on another organisation's batch until its 404 rolls back — which is exactly
     * what the reverse path did while it had a lock of its own.</p>
     *
     * <p><b>Lock ordering</b> is <em>batch row → journal entry row → entry-number
     * sequence row</em>, matching the order every other ledger write path takes its
     * locks in ({@code PostingService.reverse} takes the entry, then the sequence).
     * {@code markPosted} deliberately takes no row lock: it is called at the end of a
     * bulk post that is already holding the sequence, and locking the batch there
     * would invert that order.</p>
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ImportBatch lockForRun(UUID batchId) {
        ImportBatch b = entityManager.find(ImportBatch.class, batchId);
        requireOwnTenant(b);
        try {
            entityManager.refresh(b, LockModeType.PESSIMISTIC_WRITE,
                    Map.of("jakarta.persistence.lock.timeout", 0));
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException e) {
            // Three types for one event: an EntityManager call is not put through
            // Spring Data's exception translation, so JPA's own types get out.
            throw new RowLockedException(BEING_WORKED_ON);
        }
        requireOwnTenant(b);
        return b;
    }

    /** What every caller of {@link #lockForRun} is told when somebody else holds the batch. */
    public static final String BEING_WORKED_ON =
            "This import batch is being posted, reversed or discarded right now; try again";

    private static void requireOwnTenant(ImportBatch b) {
        if (b == null) throw new NotFoundException("Import batch not found");
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(b.getTenantId())) {
            throw new NotFoundException("Import batch not found");
        }
    }

    /**
     * Undo the whole cut-over import (spec §10.3, controller ruling R12): every
     * journal the batch wrote is reversed, then every lease it created goes back to
     * a clean DRAFT.
     *
     * <p><b>One transaction, deliberately.</b> Journals reversed without the leases
     * coming back — or leases back in DRAFT over journals still on the books — is
     * the one state nobody can explain afterwards. Either the whole batch comes off
     * or none of it does, including when the lease side throws.</p>
     *
     * <p><b>Newest-first.</b> A CBR posted after a CRT for the same cheque has to
     * come off before the CRT does, or the intermediate ledger state is nonsense
     * even though the end state balances. The order is the batch's own write order
     * reversed; entries written within the same microsecond fall back to the
     * repository's, which affects only the intermediate states.</p>
     *
     * <p><b>What is skipped.</b> Entries whose {@code reversalOfId} is set are the
     * mirrors — their own {@code importBatchId} is copied from the original by
     * {@code PostingService.reverse}, so they come back from the same query, and
     * reversing a reversal is refused there anyway. Entries already REVERSED are
     * skipped for the same reason. That is what makes this safe to run after a
     * partial undo done by hand.</p>
     *
     * <p><b>The period lock does not apply</b>, at either end: the batch's journals
     * were posted under the {@code importBatchId} exemption, and
     * {@code PostingService.reverse} applies the same test, so a cut-over dated
     * inside the closed period can still be taken off.</p>
     *
     * <p><b>Every mirror is dated on the entry it reverses, and the caller does not
     * get a say</b> (review C1, ruling R16). This used to take a reversal date and
     * the web defaulted it to <em>today</em>, which was a live accounting defect
     * rather than a convenience: {@code PostingService.reverse} does not apply the
     * period lock to an entry carrying a batch id, so any date was accepted, and
     * {@code JournalLineRepository.balancesAsOf} has no status predicate — a
     * REVERSED entry still counts towards the balances at its own date. A mirror
     * dated later therefore left the whole cut-over standing as at D − 1 (the
     * derived column, the reconciliation report and every opening balance computed
     * from it), pushed the undo of history into the first live month's P&amp;L and
     * bank position, and made "Post again" write the same journals a second time at
     * their pre-D dates — <b>doubling</b> every derived balance at every date before
     * the reversal. Pinning the mirror to the original's own day is also what keeps
     * the lock exemption to the pre-books period it exists for: a caller-chosen date
     * let an import mirror be written into any closed month.</p>
     *
     * <p>Same ruling, one class over, as {@code OpeningBalanceService.reverse}.</p>
     *
     * <p>{@code PostingService.reverse} is the internal posting API, not
     * {@code JournalService.reverse}: the HTTP-facing one only reverses MANUAL
     * journals and would refuse every entry in the batch by design.</p>
     */
    @Transactional
    public ImportBatch reverse(UUID batchId, String reason) {
        ImportBatch b = lockForRun(batchId);
        if (b.getStatus() != ImportBatchStatus.POSTED) {
            throw new BusinessRuleViolationException(
                    "Import batch is " + b.getStatus() + "; only a POSTED batch can be reversed");
        }
        // The opening balances are the LAST step (ruling R17). Taking the cut-over off
        // the books underneath a live OB journal would leave that journal netting out a
        // holding that is no longer there. Asked before anything is written.
        if (fiscal.hasLiveOpeningBalance()) {
            throw new BusinessRuleViolationException(OPENING_BALANCES_ARE_LIVE);
        }

        List<UUID> leases = links.findByBatchIdOrderByLeaseIdAsc(batchId).stream()
                .map(ImportBatchLease::getLeaseId).toList();
        LeaseReverter reverter = leaseReverter.getIfAvailable();
        // Asked BEFORE anything is written. The transaction would roll a late failure
        // back anyway, but the refusal a clerk reads should not arrive after a log line
        // saying twelve journals were reversed.
        if (!leases.isEmpty() && reverter == null) {
            throw new BusinessRuleViolationException(
                    "This batch created " + leases.size() + " leases but no lease module is available to "
                            + "return them to DRAFT. Reversing the journals alone would leave posted leases "
                            + "with no journals.");
        }

        // R12: asked of every lease BEFORE a single journal is reversed. A contract
        // that has been terminated, settled, amended, extended or renewed since the
        // cut-over — or that has had a cheque move, rent recognised or a penalty
        // raised outside this batch — is a fact the batch does not own and cannot
        // undo. The transaction would roll a late refusal back correctly; what it
        // would not do is tell the accountant which of six hundred contracts is in
        // the way. See LeaseReverter#blockersAgainstRevert.
        List<String> blockers = new ArrayList<>();
        for (UUID leaseId : leases) {
            blockers.addAll(reverter.blockersAgainstRevert(leaseId, batchId));
        }
        if (!blockers.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "This batch cannot be reversed: " + String.join(" ", blockers));
        }

        List<JournalEntry> toReverse = new ArrayList<>(
                journals.findByImportBatchIdOrderByCreatedAtAsc(batchId).stream()
                        .filter(e -> e.getStatus() == JournalStatus.POSTED && e.getReversalOfId() == null)
                        .sorted(Comparator.comparing(JournalEntry::getCreatedAt))
                        .toList());
        Collections.reverse(toReverse);
        // Finance-ops spec §4: every mirror is dated on its original; one inside a
        // reconciled bank period refuses the whole batch before anything is reversed.
        if (bankLock != null) {
            for (JournalEntry e : toReverse) bankLock.assertOpenForEntry(e.getId(), e.getEntryDate());
        }
        for (JournalEntry e : toReverse) {
            // The entry's OWN date, never a supplied one — see the method Javadoc.
            posting.reverse(e.getId(), e.getEntryDate(),
                    reason == null || reason.isBlank() ? "Import batch reversed" : reason);
        }
        for (UUID leaseId : leases) {
            reverter.revertToDraft(leaseId);
        }
        log.info("Reversed import batch {}: {} journals, {} leases returned to DRAFT",
                batchId, toReverse.size(), leases.size());

        b.setStatus(ImportBatchStatus.REVERSED);
        b.setReversedAt(Instant.now());
        b.setReversedBy(currentUserId());
        return batches.save(b);
    }

    /** Same shape as {@code PostingService.currentUserId}: null for a system-run import. */
    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
