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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * What this successor would carry forward, per deposit account, largest first
     * in the order the predecessor's lines name them.
     *
     * <p>Read-only, so the dry run can show the accountant the figure before they
     * commit to it — "carry the deposit forward" is a decision about an amount, and
     * the amount is not the one printed on last year's contract.</p>
     */
    public Map<UUID, BigDecimal> plan(Lease successor) {
        if (successor == null || !successor.isCarryDepositForward()
                || successor.getRenewedFromLeaseId() == null) {
            return Map.of();
        }
        Lease predecessor = leaseRepository.findByIdScopedToTenant(successor.getRenewedFromLeaseId()).orElse(null);
        if (predecessor == null) {
            return Map.of();
        }
        UUID tenantId = TenantContextHolder.getTenantId();

        // Distinct accounts, in line order. Two deposit lines crediting one leaf —
        // a security deposit and a key deposit that share an account — are one
        // balance, and asking twice would carry it forward twice.
        Map<UUID, BigDecimal> byAccount = new LinkedHashMap<>();
        for (LeaseLine line : leaseLineRepository.findByLease_IdOrderBySeqNoAsc(predecessor.getId())) {
            if (line.getChargeType() == null || line.getChargeType().getBehaviour() != ChargeBehaviour.DEPOSIT) {
                continue;
            }
            Account account = line.getCreditAccount();
            if (account == null || byAccount.containsKey(account.getId())) {
                continue;
            }
            BigDecimal held = journalLineRepository.creditBalanceForLease(tenantId, account.getId(), predecessor.getId());
            if (held == null || held.signum() <= 0) {
                // Nothing left to move: already refunded, forfeited, or never
                // collected because the predecessor predates the ledger.
                continue;
            }
            byAccount.put(account.getId(), held);
        }
        return byAccount;
    }

    /** Σ of {@link #plan}: the single figure the review screen shows. */
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
