package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.api.exception.NotFoundException;
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

            RenewalOutcome outcome;
            if (s == LeaseStatus.EXPIRED && o.getIntent() == null) {
                outcome = RenewalOutcome.EXPIRED_NO_RESPONSE;
            } else if (s == LeaseStatus.EXPIRED && o.getIntent() == RenewalIntent.MOVE_OUT) {
                outcome = RenewalOutcome.MOVED_OUT;
            } else {
                outcome = RenewalOutcome.MOVED_OUT;
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
        RenewalOpportunity o = opportunityRepository
                .findByLeaseIdAndStageIn(leaseId, OPEN_STAGES)
                .orElseThrow(() -> new NotFoundException("No open renewal opportunity for lease " + leaseId));
        o.setStage(RenewalStage.CLOSED_WON);
        o.setOutcome(RenewalOutcome.RENEWED);
        o.setClosedAt(Instant.now());
        return opportunityRepository.save(o);
    }
}
