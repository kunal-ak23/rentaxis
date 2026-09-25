package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How much of a lease's deposit the landlord is actually still holding.
 *
 * <p><b>The question is asked of the ledger, never of the contract.</b>
 * {@code leases.deposit_amount} is what the lease <em>charged</em>. A deposit
 * partly forfeited for a damaged door, partly refunded mid-term, or carried
 * forward into a renewal is no longer worth that figure, and two different
 * callers used to answer it two different ways: the carry-forward read
 * {@code journal_lines} and got it right, while the settlement preview read the
 * contract column and offered the renter a refund of money the landlord no
 * longer had. One collaborator, one answer.</p>
 *
 * <p><b>Which accounts comes from the whole renewal chain, not from one lease.</b>
 * A deposit is <em>charged</em> once, on the contract that first collected it;
 * every renewal that carries it forward deliberately has no DEPOSIT line of its
 * own. Asking a single lease for its deposit accounts therefore works exactly
 * once — on the second renewal in a row it finds nothing and reports zero. So the
 * walk follows {@code renewedFromLeaseId} back to the head of the chain to learn
 * <em>which</em> accounts, and reads each one's balance on the lease it was
 * <em>asked about</em> to learn <em>how much</em>. The two halves come from
 * different places because they are different questions.</p>
 *
 * <p>That split is also why a carried-forward predecessor answers zero rather
 * than "no accounts": the chain still names its deposit account, and the
 * carry-forward JV has already debited it flat on that lease's dimension. A
 * settlement on the old contract correctly finds nothing left to refund.</p>
 *
 * <p>Every public method is {@code @Transactional}: the loads are JPQL and depend
 * on the Hibernate tenant filter, which {@code TenantAspect} only enables inside
 * a transaction.</p>
 */
@Component
public class LeaseDepositLedger {

    /**
     * How far back the chain is walked before we assume it is malformed. A renter
     * renewing annually for fifty years is not a case this needs to serve
     * perfectly; a cycle written by a bad migration is a case it must not hang on.
     */
    private static final int MAX_CHAIN_DEPTH = 50;

    private final LeaseRepository leaseRepository;
    private final LeaseLineRepository leaseLineRepository;
    private final JournalLineRepository journalLineRepository;

    public LeaseDepositLedger(LeaseRepository leaseRepository,
                              LeaseLineRepository leaseLineRepository,
                              JournalLineRepository journalLineRepository) {
        this.leaseRepository = leaseRepository;
        this.leaseLineRepository = leaseLineRepository;
        this.journalLineRepository = journalLineRepository;
    }

    /**
     * What this lease still holds, per deposit account, in chain order (the
     * lease's own deposit accounts first). Accounts whose balance here is zero or
     * negative — refunded, forfeited, carried forward, or never collected — are
     * left out rather than reported as zero rows.
     *
     * <p>Read-only, so a dry run or a preview can show the figure before anything
     * commits to it.</p>
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> heldByAccount(Lease lease) {
        if (lease == null) {
            return Map.of();
        }
        // Not a silent zero: every load below is JPQL and relies on the Hibernate
        // tenant filter, which TenantAspect only enables when a tenant is set. With
        // none, the reads would cross tenants and this would answer "nothing held"
        // for a lease that is holding a deposit.
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "No tenant in context; a lease's deposit balance cannot be read without one");
        }

        Map<UUID, BigDecimal> byAccount = new LinkedHashMap<>();
        for (UUID accountId : depositAccountsAlongChain(lease)) {
            BigDecimal held = journalLineRepository.creditBalanceForLease(tenantId, accountId, lease.getId());
            if (held == null || held.signum() <= 0) {
                continue;
            }
            byAccount.put(accountId, held);
        }
        return byAccount;
    }

    /** Σ of {@link #heldByAccount}: the single figure a preview or a review screen shows. */
    @Transactional(readOnly = true)
    public BigDecimal depositHeld(Lease lease) {
        return heldByAccount(lease).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Every account a DEPOSIT line has ever credited in this chain, newest lease
     * first, de-duplicated.
     *
     * <p>Newest first because a later lease may have topped the deposit up with a
     * DEPOSIT line of its own, and that account is the more relevant one to name
     * first. De-duplicated because two deposit lines crediting one leaf — a
     * security deposit and a key deposit sharing an account — are one balance, and
     * asking twice would count it twice.</p>
     *
     * <p>The walk stops at a lease with no predecessor, at {@link #MAX_CHAIN_DEPTH},
     * or at a lease it has already seen. The visited set is not defensive
     * programming for its own sake: {@code renewed_from_lease_id} is a plain column
     * with no constraint forbidding a cycle, and a loop here would hang a posting
     * transaction holding a row lock.</p>
     */
    private List<UUID> depositAccountsAlongChain(Lease from) {
        // LinkedHashSet, so it is the de-duplication and the ordering at once.
        Set<UUID> accounts = new LinkedHashSet<>();
        Set<UUID> visited = new HashSet<>();

        Lease lease = from;
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
            // Spec §2: the chain runs through transfers as well as renewals.
            UUID previousId = lease.predecessorId();
            lease = previousId == null ? null
                    : leaseRepository.findByIdScopedToTenant(previousId).orElse(null);
        }
        return List.copyOf(accounts);
    }
}
