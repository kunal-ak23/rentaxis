package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The renewal season's soft "not renewing" mark (scale PR C ruling, 2026-10-17).
 *
 * <p>A staff-only note on the contract itself (changeset 174): who marked it, when, and why. It
 * gives <b>no notice</b> — the lease's status, its notice fields and its unit's marketplace
 * availability are untouched — and (re-review P2-1) it opens <b>no renewal opportunity</b>, so
 * nothing the renter sees (renewal banner, {@code /me/renewals}), nothing the reminder scheduler
 * sends and nothing the renewal funnel counts depends on it. Giving notice stays its own act.
 * Undo removes it; renewing, extending, transferring or terminating the contract clears it.</p>
 *
 * <p>Every method is {@code @Transactional}: the tenant filter is only on inside a transaction.
 * Setting and undoing require the lease to be manageable by the caller ({@code LeaseAccessPolicy}),
 * so a manager marks only their own buildings' contracts and another organisation's lease is
 * "Lease not found". The lease row's {@code @Version} turns two marks at once into a clean
 * conflict rather than a lost write.</p>
 */
@Service
public class RenewalMarkService {

    /** The contracts the mark makes sense on: still running or held over, not yet continued or ended. */
    private static final Set<LeaseStatus> MARKABLE =
            EnumSet.of(LeaseStatus.ACTIVE, LeaseStatus.NOTICE_GIVEN, LeaseStatus.EXPIRED);
    static final int MAX_NOTE = 500;

    private final LeaseRepository leaseRepository;
    private final LeaseAccessPolicy access;
    private final LeaseService leaseService;

    public RenewalMarkService(LeaseRepository leaseRepository, LeaseAccessPolicy access, LeaseService leaseService) {
        this.leaseRepository = leaseRepository;
        this.access = access;
        this.leaseService = leaseService;
    }

    /** What the mark says, as the endpoints answer it. */
    public record Mark(UUID leaseId, boolean notRenewing, Instant at, UUID by, String note) {
    }

    /** Mark the contract "not renewing". Marking again replaces the note and the who/when. */
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
        lease.setNotRenewingAt(Instant.now());
        lease.setNotRenewingBy(byUser);
        lease.setNotRenewingNote(clean);
        leaseRepository.save(lease);
        leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(),
                "Marked not renewing" + (clean == null ? "" : ": " + clean));
        return new Mark(leaseId, true, lease.getNotRenewingAt(), byUser, clean);
    }

    /** Undo: the contract is back in the season's "to do". A contract with no mark is left as it is. */
    @Transactional
    public Mark undo(UUID leaseId) {
        Lease lease = manageable(leaseId);
        if (lease.clearNotRenewing()) {
            leaseRepository.save(lease);
            leaseService.recordLeaseEvent(lease, lease.getStatus(), lease.getStatus(), "Not-renewing mark removed");
        }
        return new Mark(leaseId, false, null, null, null);
    }

    /**
     * The tenancy continues (a renewal was drafted, or the contract was extended): the mark no
     * longer says anything true. Called from inside those acts, whose own checks authorised
     * them, on the lease row they already hold; never throws.
     */
    @Transactional
    public void clearOnContinuation(UUID leaseId) {
        leaseRepository.findByIdScopedToTenant(leaseId).ifPresent(Lease::clearNotRenewing);
    }

    private Lease manageable(UUID leaseId) {
        Lease lease = leaseRepository.findByIdScopedToTenant(leaseId)
                .orElseThrow(() -> new NotFoundException("Lease not found"));
        access.requireManageable(lease);
        return lease;
    }
}
