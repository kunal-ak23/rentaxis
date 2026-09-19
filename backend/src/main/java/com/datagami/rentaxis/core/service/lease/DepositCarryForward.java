package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Pair;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p><b>Which accounts comes from the whole chain, not from one lease.</b> A
 * deposit is <em>charged</em> once, on the contract that first collected it; every
 * renewal that carries it forward deliberately has no DEPOSIT line of its own. So
 * asking only the immediate predecessor for its deposit accounts works exactly
 * once — on the second renewal in a row it finds nothing, reports zero and strands
 * the money on the middle lease while telling the accountant all is well.
 * {@link #plan} therefore walks {@code renewedFromLeaseId} back to the head of the
 * chain to learn <em>which</em> accounts, and reads the balance of each on the
 * <em>immediate</em> predecessor to learn <em>how much</em>. The two halves come
 * from different places because they are different questions.</p>
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
    private final LeaseLineRepository leaseLineRepository;
    private final JournalLineRepository journalLineRepository;
    private final PostingService postingService;

    public DepositCarryForward(LeaseRepository leaseRepository,
                               LeaseLineRepository leaseLineRepository,
                               JournalLineRepository journalLineRepository,
                               PostingService postingService) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.journalLineRepository = journalLineRepository;
        this.postingService = postingService;
    }

    /**
     * How far back the chain is walked before we assume it is malformed. A renter
     * renewing annually for fifty years is not a case this needs to serve
     * perfectly; a cycle written by a bad migration is a case it must not hang on.
     */
    private static final int MAX_CHAIN_DEPTH = 50;

    /**
     * What this successor would carry forward, per deposit account.
     *
     * <p><b>Which accounts</b> comes from walking the renewal chain <em>back</em>
     * from the immediate predecessor, collecting every DEPOSIT line's credit
     * account on the way. It cannot come from the predecessor's own lines alone,
     * and that is the whole point: a lease that was itself created with
     * {@code carryDepositForward} has no DEPOSIT line — it was told not to charge
     * one — so on the second hop of A → B → C there would be no account to look at
     * and the deposit would silently strand on B, with the dry run reporting zero
     * and the post reporting success. The deposit is only ever <em>charged</em>
     * once, at the head of the chain, so that is where its account is named.</p>
     *
     * <p><b>How much</b> is always measured on the <em>immediate</em> predecessor's
     * lease dimension. Each hop has already moved the balance onto the lease before
     * it, so asking the chain head would double-count on every renewal after the
     * first. An account the chain names but whose balance on the predecessor is
     * zero — refunded, forfeited, or never collected — is skipped.</p>
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
        // Not a silent zero: every load below is JPQL and relies on the Hibernate
        // tenant filter, which TenantAspect only enables when a tenant is set. With
        // none, the reads would cross tenants and the carry-forward would answer
        // "nothing to move" for a lease that is holding a deposit.
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant in context; a deposit cannot be carried forward without one");
        }

        Lease predecessor = leaseRepository.findByIdScopedToTenant(successor.getRenewedFromLeaseId()).orElse(null);
        if (predecessor == null) {
            return Map.of();
        }

        Map<UUID, BigDecimal> byAccount = new LinkedHashMap<>();
        for (UUID accountId : depositAccountsAlongChain(predecessor)) {
            BigDecimal held = journalLineRepository.creditBalanceForLease(tenantId, accountId, predecessor.getId());
            if (held == null || held.signum() <= 0) {
                continue;
            }
            byAccount.put(accountId, held);
        }
        return byAccount;
    }

    /**
     * Every account a DEPOSIT line has ever credited in this chain, newest lease
     * first, de-duplicated.
     *
     * <p>Newest first because a later lease may have topped the deposit up with a
     * DEPOSIT line of its own, and that account is the more relevant one to name
     * first in the journal. De-duplicated because two deposit lines crediting one
     * leaf — a security deposit and a key deposit sharing an account — are one
     * balance, and asking twice would carry it forward twice.</p>
     *
     * <p>The walk stops at a lease with no predecessor, at {@link #MAX_CHAIN_DEPTH},
     * or at a lease it has already seen. The visited set is not defensive
     * programming for its own sake: {@code renewed_from_lease_id} is a plain column
     * with no constraint forbidding a cycle, and a loop here would hang a posting
     * transaction holding a row lock.</p>
     */
    private List<UUID> depositAccountsAlongChain(Lease predecessor) {
        // LinkedHashSet, so it is the de-duplication and the ordering at once.
        Set<UUID> accounts = new LinkedHashSet<>();
        Set<UUID> visited = new HashSet<>();

        Lease lease = predecessor;
        for (int depth = 0; lease != null && depth < MAX_CHAIN_DEPTH; depth++) {
            if (!visited.add(lease.getId())) {
                break;
            }
            for (LeaseLine line : leaseLineRepository.findByLease_IdOrderBySeqNoAsc(lease.getId())) {
                if (line.getChargeType() == null
                        || line.getChargeType().getBehaviour() != ChargeBehaviour.DEPOSIT) {
                    continue;
                }
                Account account = line.getCreditAccount();
                if (account != null) {
                    accounts.add(account.getId());
                }
            }
            UUID previousId = lease.getRenewedFromLeaseId();
            lease = previousId == null ? null
                    : leaseRepository.findByIdScopedToTenant(previousId).orElse(null);
        }
        return List.copyOf(accounts);
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
                null,
                pairs));
    }

    /** How the narration names the contract left behind: its number, else its id. */
    private static String contractLabel(Lease lease) {
        return lease.getContractNumber() != null
                ? String.valueOf(lease.getContractNumber())
                : String.valueOf(lease.getId());
    }
}
