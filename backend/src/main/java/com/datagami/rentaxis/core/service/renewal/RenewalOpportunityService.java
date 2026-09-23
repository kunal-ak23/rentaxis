package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import com.datagami.rentaxis.domain.entity.enums.RenewalOutcome;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RenewalOpportunityService {

    public static final int WINDOW_DAYS = 90;
    private static final List<RenewalStage> OPEN_STAGES =
            List.of(RenewalStage.OPEN, RenewalStage.INTENT_CAPTURED);

    private final LeaseRepository leaseRepository;
    private final RenewalOpportunityRepository opportunityRepository;
    private final LeaseAccessPolicy leaseAccessPolicy;

    /** Phase 1: open opportunities for ACTIVE leases entering the 90-day window. */
    @Transactional
    public int openOpportunitiesForCurrentTenant(LocalDate today) {
        LocalDate cutoff = today.plusDays(WINDOW_DAYS);
        List<Lease> leases = leaseRepository.findActiveLeasesEnteringRenewalWindow(cutoff);
        int opened = 0;
        for (Lease lease : leases) {
            RenewalOpportunity o = new RenewalOpportunity();
            o.setLease(lease);
            o.setTenantId(lease.getTenantId());
            o.setStage(RenewalStage.OPEN);
            o.setOpenedAt(Instant.now());
            opportunityRepository.save(o);
            opened++;
        }
        if (opened > 0) log.info("Opened {} renewal opportunities", opened);
        return opened;
    }

    /** Phase 3: close opportunities whose lease has resolved. Caller sets TenantContextHolder. */
    @Transactional
    public int closeStaleOpportunitiesForCurrentTenant() {
        List<RenewalOpportunity> open = opportunityRepository
                .findByTenantIdAndStageIn(TenantContextHolder.getTenantId(), OPEN_STAGES);
        int closed = 0;
        Instant now = Instant.now();
        for (RenewalOpportunity o : open) {
            LeaseStatus s = o.getLease().getStatus();
            if (s == LeaseStatus.ACTIVE) continue;

            // Every case that was not (EXPIRED + no intent) or (EXPIRED +
            // MOVE_OUT) used to fall into a final else and be written as
            // MOVED_OUT — including a renter who had answered RENEW, and any
            // terminated lease. The funnel therefore reported renters as having
            // moved out when they had asked to stay, and those are exactly the
            // ones worth chasing.
            RenewalOutcome outcome;
            if (s == LeaseStatus.TERMINATED || s == LeaseStatus.CLOSED) {
                outcome = RenewalOutcome.LEASE_TERMINATED;
            } else if (o.getIntent() == RenewalIntent.RENEW || o.getIntent() == RenewalIntent.DISCUSS) {
                // The renter wanted to stay; the lease lapsed before anyone
                // created the renewal. A lost renewal, not a departure.
                outcome = RenewalOutcome.RENEWAL_NOT_ACTIONED;
            } else if (o.getIntent() == RenewalIntent.MOVE_OUT) {
                outcome = RenewalOutcome.MOVED_OUT;
            } else {
                outcome = RenewalOutcome.EXPIRED_NO_RESPONSE;
            }
            o.setStage(RenewalStage.CLOSED_LOST);
            o.setOutcome(outcome);
            o.setClosedAt(now);
            opportunityRepository.save(o);
            closed++;
        }
        if (closed > 0) log.info("Closed {} stale renewal opportunities", closed);
        return closed;
    }

    /** Manual close from the PM via mark-renewed endpoint. */
    @Transactional
    public RenewalOpportunity markRenewed(UUID leaseId) {
        // A manager may close only the renewals of the buildings they manage
        // (audit B-F3). markRenewedIfOpen stays unchecked: it is the posting path's
        // internal close, reached only after the posting itself was authorised.
        leaseAccessPolicy.requireManageable(leaseRepository.findById(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found")));
        return markRenewedIfOpen(leaseId)
                .orElseThrow(() -> new NotFoundException("No open renewal opportunity for lease " + leaseId));
    }

    /**
     * The same close, for the caller that does not know whether an opportunity
     * exists — and must not be derailed if it does not.
     *
     * <p>This is what {@code LeasePostingService} calls when a renewal's successor
     * posts (spec §6.6): the funnel closes the moment the new contract is on the
     * books, not when the PM remembers to tick it. Most leases have an open
     * opportunity by then, because the scheduler opens one 90 days out — but a
     * lease renewed early, or one whose opportunity was already closed by hand, has
     * none, and that is not an error worth failing a posting over.</p>
     *
     * <p>It returns an empty {@link Optional} rather than throwing, and the
     * distinction is not cosmetic: this bean is a {@code @Transactional} proxy, so
     * a {@code NotFoundException} thrown out of {@link #markRenewed} marks the
     * <em>caller's</em> transaction rollback-only on its way through the
     * interceptor. A {@code try/catch} around it at the call site would swallow the
     * exception and still lose the whole post at commit time with "Transaction
     * silently rolled back" — the same trap {@code AccountResolver.resolveOrNull}
     * exists for.</p>
     */
    @Transactional
    public Optional<RenewalOpportunity> markRenewedIfOpen(UUID leaseId) {
        return opportunityRepository.findByLeaseIdAndStageIn(leaseId, OPEN_STAGES)
                .map(o -> {
                    o.setStage(RenewalStage.CLOSED_WON);
                    o.setOutcome(RenewalOutcome.RENEWED);
                    o.setClosedAt(Instant.now());
                    return opportunityRepository.save(o);
                });
    }
}
