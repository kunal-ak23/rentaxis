package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The renewal season's soft "not renewing" mark (scale PR C ruling, 2026-10-17).
 *
 * <p>It is a note on the renewal opportunity and nothing else: who marked the contract, when,
 * and why. It gives <b>no notice</b> — the lease's status, its notice fields and its unit's
 * marketplace availability are untouched, because a renter who "probably won't renew" has not
 * served notice and may yet renew. Giving notice stays its own deliberate act. The mark is
 * reversible (Undo), and continuing the tenancy clears it: renewing (a draft is enough) or
 * extending the contract.</p>
 *
 * <p>Every method is {@code @Transactional}: the tenant filter is only on inside a transaction.
 * Setting and undoing require the lease to be manageable by the caller ({@code LeaseAccessPolicy}),
 * so a manager marks only their own buildings' contracts and another organisation's lease is
 * "Lease not found".</p>
 */
@Service
public class RenewalMarkService {

    /** The contracts the mark makes sense on: still running or held over, not yet continued or ended. */
    private static final Set<LeaseStatus> MARKABLE =
            EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN, LeaseStatus.EXPIRED);
    private static final List<RenewalStage> OPEN_STAGES = List.of(RenewalStage.OPEN, RenewalStage.INTENT_CAPTURED);
    static final int MAX_NOTE = 500;

    private final LeaseRepository leaseRepository;
    private final RenewalOpportunityRepository opportunities;
    private final LeaseAccessPolicy access;
    private final LeaseService leaseService;

    public RenewalMarkService(LeaseRepository leaseRepository, RenewalOpportunityRepository opportunities,
                              LeaseAccessPolicy access, LeaseService leaseService) {
        this.leaseRepository = leaseRepository;
        this.opportunities = opportunities;
        this.access = access;
        this.leaseService = leaseService;
    }

    /** What the mark says, as the lease row and the endpoints carry it. */
    public record Mark(UUID leaseId, boolean notRenewing, Instant at, UUID by, String note) {
    }

    /**
     * Mark the contract "not renewing". On the open opportunity when there is one; otherwise an
     * opportunity is opened for it (the scheduler opens one only 90 days out, and the season is
     * often worked earlier). Marking again replaces the note and the who/when.
     */
    @Transactional
    public Mark markNotRenewing(UUID leaseId, String note, UUID byUser) {
        Lease lease = manageable(leaseId);
        if (!MARKABLE.contains(lease.getStatus())) {
            throw new BusinessRuleViolationException("Only a running, on-notice or expired contract can be marked"
                    + " not renewing; this one is " + lease.getStatus() + ".", "lease.markNotRenewingStatus",
                    java.util.Map.of("status", lease.getStatus().name()));
        }
        if (!leaseRepository.findByRenewedFromLeaseId(leaseId).isEmpty()
                || !leaseRepository.findByTransferredFromLeaseId(leaseId).isEmpty()) {
            throw new BusinessRuleViolationException("This contract already has a renewal or transfer drafted;"
                    + " delete that draft first if it is not renewing.", "lease.markNotRenewingHasSuccessor",
                    java.util.Map.of());
        }
        String clean = note == null || note.isBlank() ? null : note.trim();
        if (clean != null && clean.length() > MAX_NOTE) {
            throw new BusinessRuleViolationException("Keep the note under " + MAX_NOTE + " characters.");
        }
        RenewalOpportunity o = opportunities.findByLeaseIdAndStageIn(leaseId, OPEN_STAGES).orElseGet(() -> {
            RenewalOpportunity created = new RenewalOpportunity();
            created.setLease(lease);
            created.setTenantId(lease.getTenantId());
            created.setStage(RenewalStage.OPEN);
            created.setOpenedAt(Instant.now());
            return created;
        });
        o.setNotRenewingAt(Instant.now());
        o.setNotRenewingBy(byUser);
        o.setNotRenewingNote(clean);
        opportunities.save(o);
        leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(),
                "Marked not renewing" + (clean == null ? "" : ": " + clean));
        return new Mark(leaseId, true, o.getNotRenewingAt(), byUser, clean);
    }

    /** Undo: the contract is back in the season's "to do". A contract with no mark is left as it is. */
    @Transactional
    public Mark undo(UUID leaseId) {
        Lease lease = manageable(leaseId);
        if (clearAll(leaseId) > 0) {
            leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(), "Not-renewing mark removed");
        }
        return new Mark(leaseId, false, null, null, null);
    }

    /**
     * The tenancy continues (a renewal was drafted, or the contract was extended): the mark no
     * longer says anything true. Called from inside those acts, whose own checks authorised
     * them; never throws, so it cannot derail them.
     */
    @Transactional
    public void clearOnContinuation(UUID leaseId) {
        clearAll(leaseId);
    }

    private int clearAll(UUID leaseId) {
        List<RenewalOpportunity> marked = opportunities.findMarkedNotRenewing(leaseId);
        for (RenewalOpportunity o : marked) {
            o.setNotRenewingAt(null);
            o.setNotRenewingBy(null);
            o.setNotRenewingNote(null);
        }
        opportunities.saveAll(marked);
        return marked.size();
    }

    private Lease manageable(UUID leaseId) {
        Lease lease = leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        access.requireManageable(lease);
        return lease;
    }
}
