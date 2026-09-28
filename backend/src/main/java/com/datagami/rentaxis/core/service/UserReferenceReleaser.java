package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseInteractionRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import com.datagami.rentaxis.domain.repository.PromoAdEventRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Clears everything that points at a {@code users} row so the row itself can be
 * deleted.
 *
 * <p>Several tables reference {@code users(id)} with no {@code ON DELETE}
 * clause, so deleting a user without this first violates a foreign key. For a
 * renter that is not an edge case: {@code PromotionFeedService} writes one
 * impression row per ad per renter per Dubai day, so any renter who has ever
 * opened the app has {@code promo_ad_events} rows and could not be deleted at
 * all.</p>
 *
 * <p>This lived inline in {@link AccountDeletionService}, which is why the
 * in-app "delete my account" flow worked while the admin
 * {@code DELETE /api/admin/users/{id}} — three statements that went straight to
 * {@code deleteById} — returned a bare 500 for the same user. Extracted here so
 * there is exactly one description of how to delete a user safely, and both
 * callers get it.</p>
 *
 * <p>Two kinds of row are treated differently, and the distinction matters:</p>
 * <ul>
 *   <li><b>Personal activity is deleted</b> with the account — impressions and
 *       booking requests exist only because that person used the app.</li>
 *   <li><b>Records the business must keep survive with the personal link
 *       severed.</b> {@code 65-gate-pass.yaml} states this policy outright, and
 *       {@code 79-account-deletion-detach} made those columns nullable so the
 *       record can outlive the account rather than block its deletion. The
 *       building's access log stays intact; it just stops naming someone who no
 *       longer exists.</li>
 * </ul>
 */
@Component
public class UserReferenceReleaser {

    private final RenterRepository renterRepository;
    private final PromoAdEventRepository promoAdEventRepository;
    private final BookingRequestRepository bookingRequestRepository;
    private final GatePassRepository gatePassRepository;
    private final GatePassScanRepository gatePassScanRepository;
    private final LeaseInteractionRepository leaseInteractionRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final NotificationRepository notificationRepository;
    private final GuardPropertyAssignmentRepository guardPropertyAssignmentRepository;
    private final MaintenanceTicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final com.datagami.rentaxis.domain.repository.MeetingRepository meetingRepository;

    public UserReferenceReleaser(RenterRepository renterRepository,
            PromoAdEventRepository promoAdEventRepository,
            BookingRequestRepository bookingRequestRepository,
            GatePassRepository gatePassRepository,
            GatePassScanRepository gatePassScanRepository,
            LeaseInteractionRepository leaseInteractionRepository,
            DeviceTokenRepository deviceTokenRepository,
            NotificationRepository notificationRepository,
            GuardPropertyAssignmentRepository guardPropertyAssignmentRepository,
            MaintenanceTicketRepository ticketRepository,
            UserRepository userRepository,
            com.datagami.rentaxis.domain.repository.MeetingRepository meetingRepository) {
        this.meetingRepository = meetingRepository;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.renterRepository = renterRepository;
        this.promoAdEventRepository = promoAdEventRepository;
        this.bookingRequestRepository = bookingRequestRepository;
        this.gatePassRepository = gatePassRepository;
        this.gatePassScanRepository = gatePassScanRepository;
        this.leaseInteractionRepository = leaseInteractionRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.notificationRepository = notificationRepository;
        this.guardPropertyAssignmentRepository = guardPropertyAssignmentRepository;
    }

    /**
     * Must run inside the caller's transaction, immediately before the user row
     * is deleted.
     *
     * <p>The renter detach is first and is not optional: {@code renters.user_id}
     * is {@code ON DELETE CASCADE} (57-cascade-renters-user), so deleting the
     * user would cascade the renter row away and then trip the
     * {@code leases.renter_id} foreign key. Severing the link keeps the renter,
     * and therefore the lease and its payment history, intact.</p>
     */
    public void release(UUID userId) {
        renterRepository.findByUserId(userId).ifPresent(renter -> {
            renter.setUserId(null);
            renterRepository.save(renter);
        });

        deviceTokenRepository.deleteByUserId(userId);
        notificationRepository.deleteByUserIdUnfiltered(userId);
        guardPropertyAssignmentRepository.deleteByUserId(userId);

        // Personal activity goes with the account.
        promoAdEventRepository.deleteByRenterUserId(userId);
        bookingRequestRepository.deleteByRenterUserId(userId);

        // Break-it R3 ops3 F5: open work goes back to the queue. A ticket used to stay
        // ASSIGNED to nobody — "Assigned" badge, "Assigned To: Unassigned", in no one's
        // list. Each release is recorded in the ticket's history first.
        releaseOpenTickets(userId);

        // Business records survive, de-identified.
        gatePassRepository.detachCreatedBy(userId);
        gatePassScanRepository.detachScannedBy(userId);
        leaseInteractionRepository.detachCreatedBy(userId);
    }

    static final String TICKET_RELEASE_NOTE = "Assignee's account was deleted; ticket returned to the unassigned queue";

    private void releaseOpenTickets(UUID userId) {
        UUID performer = currentUserId();
        if (performer == null) performer = userId; // the user deleting their own account
        String performerName = userRepository.findDisplayNameById(performer).orElse(null);
        ticketRepository.recordReleaseOfOpenAssignedTo(userId, performer, performerName, TICKET_RELEASE_NOTE);
        ticketRepository.releaseOpenAssignedTo(userId);
    }

    private static UUID currentUserId() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof String s)) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Break-it R3 ops3 F5: what deleting this user will do to work they hold, for the
     * delete dialog. {@code openTickets} go back to the unassigned queue;
     * {@code meetings} (hosted or requested) keep their history and refuse the delete.
     */
    public record Impact(long openTickets, long meetings) {
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Impact impact(UUID userId) {
        return new Impact(ticketRepository.countOpenAssignedTo(userId),
                meetingRepository.countByParticipantUnfiltered(userId));
    }
}
