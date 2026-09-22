package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Pair;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moving a renter's deposit from the contract that ends to the one that follows
 * it (spec §6.6).
 *
 * <p>The renter is staying and their money is staying with them. Refunding the
 * deposit on the old lease and collecting it again on the new one would be two
 * cheques neither party writes, so instead one {@code JV} moves the liability:
 * {@code Dr SECURITY_DEPOSIT} on the predecessor's dimension,
 * {@code Cr SECURITY_DEPOSIT} on the successor's. The account is the same leaf on
 * both sides — the tenant's deposit liability does not change, only whose tenancy
 * it is held against.</p>
 *
 * <p><b>The amount is the ledger balance, never the line's nominal figure.</b>
 * A deposit that was partly forfeited for a damaged door, or partly refunded
 * during the term, is no longer worth what the contract charged for it. Carrying
 * the nominal amount forward would credit the new lease with money the landlord
 * is not holding, and leave the old lease's deposit account sitting at the
 * difference forever — a balance on a retired contract that nothing will ever
 * clear. So the figure comes from {@code journal_lines}: Σcredit − Σdebit for that
 * account on the predecessor's lease dimension, which is exactly what is left.</p>
 *
 * <p>Both halves of that — which deposit accounts the chain names, and what each
 * still holds on the predecessor — are {@link LeaseDepositLedger}'s answer, which
 * is also the settlement preview's. Two callers asking "how much deposit is
 * held?" and getting different numbers is how a renewal and a termination end up
 * disagreeing about the same money.</p>
 *
 * <p>Every public method is {@code @Transactional}: the loads here are JPQL and
 * depend on the Hibernate tenant filter, which {@code TenantAspect} only enables
 * inside a transaction. {@link #carry} calls {@link #plan} directly rather than
 * through the proxy, which is harmless — it is already inside the transaction
 * {@code carry} joined.</p>
 *
 * <p><b>It happens on post, not on renew.</b> The draft records the accountant's
 * decision ({@code lease.carryDepositForward}); the journal is written when the
 * successor goes on the books, because until then there is no successor contract
 * for the liability to belong to. A renewal that is drafted and abandoned moves
 * nobody's deposit.</p>
 *
 * <p>A collaborator rather than another method on {@code LeasePostingService}:
 * the posting service's job is the contract's own journals, and it calls this once
 * with the successor lease. The period lock is enforced by {@code PostingService}
 * on the {@code JV} exactly as it is on the {@code TCO}, and both are inside the
 * post's transaction, so a locked period refuses the whole renewal rather than
 * posting a contract whose deposit never arrived.</p>
 */
@Component
public class DepositCarryForward {

    private final LeaseRepository leaseRepository;
    private final LeaseDepositLedger depositLedger;
    private final PostingService postingService;

    public DepositCarryForward(LeaseRepository leaseRepository,
                               LeaseDepositLedger depositLedger,
                               PostingService postingService) {
        this.leaseRepository = leaseRepository;
        this.depositLedger = depositLedger;
        this.postingService = postingService;
    }

    /**
     * What this successor would carry forward, per deposit account.
     *
     * <p><b>Which accounts and how much</b> are both {@link LeaseDepositLedger}'s
     * answer, asked of the <em>immediate predecessor</em>: the accounts come from
     * walking the renewal chain back from it, the balances are read on it. Asking
     * the chain head instead would double-count, because each hop has already moved
     * the balance onto the lease before it; asking only the predecessor's own lines
     * for the accounts would strand the money on the second renewal in a row, since
     * a lease created with {@code carryDepositForward} has no DEPOSIT line of its
     * own. The same collaborator answers the settlement preview, so a renewal and a
     * termination can never disagree about what is held.</p>
     *
     * <p>Read-only, so the dry run can show the accountant the figure before they
     * commit to it — "carry the deposit forward" is a decision about an amount, and
     * the amount is not the one printed on any contract.</p>
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> plan(Lease successor) {
        if (successor == null || !successor.isCarryDepositForward()
                || successor.getRenewedFromLeaseId() == null) {
            return Map.of();
        }
        // Checked here and not only inside LeaseDepositLedger: without a tenant the
        // predecessor load below runs with the Hibernate filter off, and a miss
        // there would return an empty plan — "nothing to move" for a lease that is
        // holding a deposit, which posts, succeeds, and loses the money.
        if (TenantContextHolder.getTenantId() == null) {
            throw new IllegalStateException(
                    "No tenant in context; a deposit cannot be carried forward without one");
        }
        Lease predecessor = leaseRepository.findByIdScopedToTenant(successor.getRenewedFromLeaseId()).orElse(null);
        if (predecessor == null) {
            return Map.of();
        }
        return depositLedger.heldByAccount(predecessor);
    }

    /** Σ of {@link #plan}: the single figure the review screen shows. */
    @Transactional(readOnly = true)
    public BigDecimal total(Lease successor) {
        return plan(successor).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Write the carry-forward, if there is one.
     *
     * <p>Dated the successor's contract date, so the deposit arrives on the new
     * lease in the same period the new lease was raised in — a JV dated today
     * against a contract posted in March would leave the deposit on neither lease
     * for the months in between.</p>
     *
     * @return the {@code JV}, or null when the lease carries nothing forward.
     */
    @Transactional
    public JournalEntry carry(Lease successor) {
        return carry(successor, null);
    }

    /**
     * The same, as part of a cut-over import batch (R4).
     *
     * <p>Unreachable today — Task 10 gives every imported contract a fresh chain, so
     * {@code renewedFromLeaseId} is null and {@link #plan} returns nothing — but the
     * day an import understands renewals this {@code JV} is a journal of the batch
     * like any other: without the id it would be refused by the period lock (a
     * cut-over is dated into a closed month), and if the date happened to be open it
     * would go on the books outside the batch and survive "Reverse batch". Threaded
     * rather than left as a null with a comment, because the comment is what stops
     * being true.</p>
     */
    @Transactional
    public JournalEntry carry(Lease successor, UUID importBatchId) {
        Map<UUID, BigDecimal> amounts = plan(successor);
        if (amounts.isEmpty()) {
            return null;
        }
        Lease predecessor = leaseRepository.findByIdScopedToTenant(successor.getRenewedFromLeaseId()).orElseThrow();
        String narration = "Security deposit carried forward from " + contractLabel(predecessor);

        PostingRequest.Dimensions from = LeaseChequeRegistrar.dimensions(predecessor, null);
        PostingRequest.Dimensions to = LeaseChequeRegistrar.dimensions(successor, null);

        List<Pair> pairs = new ArrayList<>(amounts.size());
        for (Map.Entry<UUID, BigDecimal> e : amounts.entrySet()) {
            pairs.add(PostingRequest.pair(
                    PostingRequest.dr(e.getKey(), e.getValue()).withDims(from).withNarration(narration),
                    PostingRequest.cr(e.getKey(), e.getValue()).withDims(to).withNarration(narration)));
        }

        // Header dimensions are the successor's: the entry belongs to the lease it
        // is being posted with. The predecessor's side carries its own line dims,
        // which is what makes the old lease's deposit ledger net to zero.
        return postingService.post(PostingRequest.ofPairs(
                JournalDocType.JV,
                successor.getContractDate(),
                narration,
                to,
                JournalSourceType.LEASE,
                successor.getId(),
                importBatchId,
                pairs));
    }

    /** How the narration names the contract left behind: its number, else its id. */
    private static String contractLabel(Lease lease) {
        return lease.getContractNumber() != null
                ? String.valueOf(lease.getContractNumber())
                : String.valueOf(lease.getId());
    }
}
