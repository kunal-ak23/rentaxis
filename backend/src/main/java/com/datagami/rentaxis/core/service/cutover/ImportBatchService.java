package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.RowLockedException;
import com.datagami.rentaxis.core.service.ledger.PostingService;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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
     * An {@code ObjectProvider}, not a direct dependency: the lease-side undo is
     * implemented by plan 4 Task 11 on {@code LeaseService}, and this service has to
     * start without it. Reversing a batch that created leases with no reverter
     * configured is a hard error, not a silent half-undo — see {@link #reverse}.
     */
    private final ObjectProvider<LeaseReverter> leaseReverter;

    @Transactional
    public ImportBatch create(UUID importJobId, String label) {
        ImportBatch b = new ImportBatch();
        b.setImportJobId(importJobId);
        b.setLabel(label);
        b.setStatus(ImportBatchStatus.DRAFT);
        return batches.save(b);
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
        b.setStatus(ImportBatchStatus.POSTED);
        b.setJournalsPosted(journalsPosted);
        b.setPostedAt(Instant.now());
        b.setPostedBy(currentUserId());
        return batches.save(b);
    }

    /**
     * The batch and everything it created are gone (ruling I4).
     *
     * <p>Only from DRAFT or REVERSED. A POSTED batch still has journals behind its
     * leases, and deleting the contracts out from under them would leave a ledger
     * describing tenancies that no longer exist — reverse it first. A batch that is
     * already DISCARDED has nothing left to delete.</p>
     *
     * <p>The batch row itself survives: after a discard it is the only record that
     * the import ever happened, which is why {@code discarded_at}/{@code _by} are
     * columns rather than a deletion.</p>
     */
    @Transactional
    public ImportBatch markDiscarded(UUID batchId) {
        ImportBatch b = get(batchId);
        if (b.getStatus() != ImportBatchStatus.DRAFT && b.getStatus() != ImportBatchStatus.REVERSED) {
            throw new BusinessRuleViolationException(
                    "Import batch is " + b.getStatus() + "; only a DRAFT or REVERSED batch can be discarded"
                            + (b.getStatus() == ImportBatchStatus.POSTED
                            ? ". Reverse it first — its contracts are on the books." : "."));
        }
        b.setStatus(ImportBatchStatus.DISCARDED);
        b.setDiscardedAt(Instant.now());
        b.setDiscardedBy(currentUserId());
        return batches.save(b);
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
     * <p>{@code PostingService.reverse} is the internal posting API, not
     * {@code JournalService.reverse}: the HTTP-facing one only reverses MANUAL
     * journals and would refuse every entry in the batch by design.</p>
     */
    @Transactional
    public ImportBatch reverse(UUID batchId, LocalDate date, String reason) {
        ImportBatch b = lockForWrite(batchId);
        if (b.getStatus() != ImportBatchStatus.POSTED) {
            throw new BusinessRuleViolationException(
                    "Import batch is " + b.getStatus() + "; only a POSTED batch can be reversed");
        }
        if (date == null) throw new BusinessRuleViolationException("A reversal date is required");

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
        for (JournalEntry e : toReverse) {
            posting.reverse(e.getId(), date, reason == null || reason.isBlank() ? "Import batch reversed" : reason);
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

    /**
     * The batch row, locked for the length of this transaction.
     *
     * <p>Two clerks reversing one batch — or one clerk double-clicking — must take
     * it off once. The status check is read-then-act, so it is a guard only while
     * the row it read cannot move underneath it.</p>
     *
     * <p>{@code find} then {@code refresh(…, PESSIMISTIC_WRITE)} rather than a
     * {@code @Lock} finder, for the reason documented on
     * {@code VoucherService#lockForWrite}: a locking JPQL query hands back the
     * first-level-cache instance with its <em>stale</em> state, so the loser of the
     * race would take the lock and then decide on the pre-lock status — exactly the
     * bug the lock exists to prevent. {@code refresh} both takes the lock and
     * re-reads.</p>
     *
     * <p><b>The explicit tenant comparison below is the only guard on this path —
     * do not delete it as redundant.</b> {@code BaseTenantEntity} sets
     * {@code applyToLoadByKey = true}, but that only bites once the filter is
     * <em>enabled</em>, and {@code TenantAspect} enables it {@code @Before}
     * execution of {@code domain.repository..*} — nothing else. {@code reverse}
     * calls this first, so no repository method has run in the transaction yet and
     * the filter is off for both the {@code find} and the {@code refresh}.
     * {@code ImportBatchReverseIT#tenantBCannotReadPostMarkOrReverseAnotherTenantsBatch}
     * is what holds the line.</p>
     *
     * <p><b>Lock ordering</b> is <em>batch row → journal entry row → entry-number
     * sequence row</em>, matching the order every other ledger write path takes its
     * locks in ({@code PostingService.reverse} takes the entry, then the sequence).
     * {@code markPosted} deliberately takes no row lock: it is called at the end of
     * a bulk post that is already holding the sequence, and locking the batch there
     * would invert that order.</p>
     */
    private ImportBatch lockForWrite(UUID batchId) {
        ImportBatch b = entityManager.find(ImportBatch.class, batchId);
        if (b == null) throw new NotFoundException("Import batch not found");
        try {
            entityManager.refresh(b, LockModeType.PESSIMISTIC_WRITE);
        } catch (PessimisticLockingFailureException | PessimisticLockException | LockTimeoutException e) {
            // Three types for one event: an EntityManager call is not put through
            // Spring Data's exception translation, so JPA's own types get out.
            throw new RowLockedException("This import batch is being reversed right now; try again");
        }
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.equals(b.getTenantId())) {
            throw new NotFoundException("Import batch not found");
        }
        return b;
    }

    /** Same shape as {@code PostingService.currentUserId}: null for a system-run import. */
    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }
}
