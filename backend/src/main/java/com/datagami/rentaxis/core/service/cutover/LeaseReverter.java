package com.datagami.rentaxis.core.service.cutover;

import java.util.List;
import java.util.UUID;

/**
 * The one thing reversing an import batch needs from the lease module: put an
 * imported lease back into a clean DRAFT, so the corrected spreadsheet can be
 * re-imported over it.
 *
 * <p><b>Why a seam at all.</b> {@code ImportBatchService} owns the ledger half of
 * the undo and nothing else. Depending on {@code LeaseService} outright would make
 * the cut-over package depend on the whole lease module — lease lines, cheques,
 * recognition, settlement — and would have made batch reverse untestable until
 * that module was finished. Plan 4 Task 11 implements it as
 * {@code core.service.lease.ImportedLeaseReverter}; this is the only seam plan 4
 * introduces.</p>
 *
 * <h2>The contract (spec §10.3, controller ruling R12)</h2>
 *
 * <p>"Clean DRAFT" is not "status = DRAFT". By the time {@code revertToDraft}
 * returns, the lease must be indistinguishable from one the importer had just
 * written and had not yet posted:</p>
 * <ul>
 *   <li><b>The lease row</b> — {@code status = DRAFT}, with {@code postingJournalId},
 *       {@code postedAt}, {@code terminatedOn}, {@code terminationJournalId} and
 *       {@code terminationNotes} cleared. Its lines and its
 *       {@code externalContractRef} stay: those are the import's input, not its
 *       output.</li>
 *   <li><b>Its cheques</b> — every row back to {@code DRAFT}, with
 *       {@code pdrJournalId}, {@code crtJournalId} and {@code cbrJournalId} cleared
 *       (the journals they named have just been reversed and must not be cited by
 *       a live row), along with {@code depositedAt}, {@code clearedAt},
 *       {@code bouncedAt}, {@code returnedAt}, {@code failureReason},
 *       {@code statusChangedAt} and both ends of the {@code replacedBy} /
 *       {@code replaces} chain. {@code importedStatus} stays — it is what the
 *       spreadsheet asked for, and a re-post has to replay it again.</li>
 *   <li><b>Recognition</b> — every rent segment and recognition entry of the lease
 *       cancelled. The POSTED entries' CILs have already been reversed by
 *       {@code ImportBatchService.reverse} before this is called, so cancelling
 *       them here strands nothing.</li>
 *   <li><b>Settlement</b> — no settlement row survives; a settlement describes an
 *       ending that no longer happened.</li>
 *   <li><b>The unit</b> — released, so the corrected import can create a lease on it
 *       again without tripping {@code ux_leases_one_active_per_unit}.</li>
 * </ul>
 *
 * <h2>What it must not do</h2>
 * <ul>
 *   <li><b>Post nothing.</b> Every journal of the batch has already been reversed
 *       by the time this is called. An implementation that posted a TCR, an
 *       unearned-rent entry or a settlement would be reversing the reversal.
 *       Deliberately <em>not</em> {@code terminateLease}, which does exactly
 *       that.</li>
 *   <li><b>Never fail on an id it cannot find.</b> {@code import_batch_leases} has
 *       no foreign key on {@code lease_id} (changeset 88), so a link may outlive
 *       its lease. A lease id that no longer resolves — or that belongs to another
 *       tenant — is ignored, not an error: the rest of the batch still has to come
 *       off.</li>
 *   <li><b>Open no transaction of its own.</b> It is called inside
 *       {@code ImportBatchService.reverse}'s single transaction, and must join it:
 *       a reverse that half-succeeded would leave journals reversed and leases
 *       posted, which is the one state the books cannot explain. A
 *       {@code REQUIRES_NEW} here would break that atomicity.</li>
 * </ul>
 *
 * <p>Any other failure <em>should</em> propagate: it rolls the whole reverse back,
 * which is the correct answer — see
 * {@code ImportBatchReverseIT#aFailingLeaseRevertRollsTheWholeReverseBack}.</p>
 */
public interface LeaseReverter {

    /** ACTIVE (or any posted state) → a clean DRAFT, as specified on the interface. */
    void revertToDraft(UUID leaseId);

    /**
     * Everything about this lease that makes the undo above impossible — asked of
     * every lease of the batch <em>before</em> a single journal is reversed.
     *
     * <p><b>Why up front rather than as a failure part-way through.</b> The reverse
     * is one transaction, so a late refusal would roll back correctly; what it would
     * not do is tell the truth. "Reverse batch" is a button an accountant presses
     * after reading a list of six hundred contracts, and the answer they need is
     * <em>which</em> contract is in the way and why — not a rollback and a message
     * about the first one the loop happened to reach.</p>
     *
     * <p>The cases are all the same shape: something real happened to this contract
     * after the cut-over, and taking the import off the books would leave that
     * something with nothing behind it. A terminated, settled, amended, extended or
     * renewed lease; a cheque that has cleared, bounced, been replaced or been
     * handed back since; rent a month-end close has already recognised; a penalty
     * finance has raised. Each of those is a fact the batch does not own and cannot
     * undo, so the honest answer is to name it and refuse.</p>
     *
     * @param batchId the batch being reversed — what a journal must carry to count
     *                as this import's own work rather than as something that
     *                happened afterwards.
     * @return one sentence per blocker, naming the contract; empty when the lease
     *         can go back to a clean DRAFT. A lease id that no longer resolves, or
     *         that belongs to another tenant, blocks nothing: {@link #revertToDraft}
     *         ignores it too.
     */
    List<String> blockersAgainstRevert(UUID leaseId, UUID batchId);
}
