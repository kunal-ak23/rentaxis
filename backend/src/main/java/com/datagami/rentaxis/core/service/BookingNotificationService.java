package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Listens for {@link BookingRequestedEvent} / {@link BookingDecidedEvent}
 * published by {@code BookingService} and turns them into in-app
 * {@link com.datagami.rentaxis.domain.entity.Notification} rows. Modeled on
 * {@link ListingNotificationService}: {@code AFTER_COMMIT} so a listener only
 * runs once the booking transaction has already committed, and
 * {@code REQUIRES_NEW} so the listener body runs in its own transaction
 * rather than piggybacking on one that is already gone.
 *
 * <p><b>Per-recipient failure isolation.</b> The obvious approach —
 * per-recipient try/catch around {@link NotificationService#notify} — does
 * NOT isolate failures: {@code notify} is {@code @Transactional} with default
 * ({@code REQUIRED}) propagation, so each call simply joins this listener's
 * one {@code REQUIRES_NEW} transaction instead of opening its own. A failure
 * for one recipient marks that shared transaction rollback-only; the
 * try/catch swallows the exception and the loop continues, but at method
 * exit Spring finds the rollback-only flag set and throws
 * {@link org.springframework.transaction.UnexpectedRollbackException} instead
 * of committing — losing every recipient's row, not just the failing one
 * (rows are only queued in the persistence context until the shared
 * transaction commits/flushes, so "earlier" successes were never actually
 * durable). This was verified against real Postgres. The fix is
 * {@link NotificationService#notifyInAppInNewTx}, whose javadoc exists for
 * exactly this hazard: its own {@code REQUIRES_NEW} suspends the listener's
 * transaction and opens an independent one <em>per call</em>, which commits
 * (or rolls back) on its own before the next recipient is processed. A
 * failure there rolls back only that one recipient's row; every other
 * recipient's row — already committed or not yet attempted — is unaffected.
 *
 * <p>{@code BOOKING_REQUESTED}/{@code BOOKING_APPROVED}/{@code BOOKING_REJECTED}/
 * {@code BOOKING_RELEASED} have no case in {@link NotificationService#mapLegacyType}
 * (verified by reading it), so {@link NotificationService#notify} would publish no
 * {@code EmailEvent} for these types anyway — {@code notifyInAppInNewTx} (which never
 * publishes one) is therefore behaviorally identical to {@code notify} here, on top of
 * fixing the transaction hazard above. Only the in-app row is written, matching the
 * spec that booking notifications are in-app only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingNotificationService {

    private final BookingRequestRepository bookingRepository;
    private final PropertyAmenityRepository amenityRepository;
    private final ParkingSpotRepository parkingSpotRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** New request → in-app row for every TENANT_ADMIN and PROPERTY_MANAGER of the tenant. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingRequested(BookingRequestedEvent event) {
        Optional<BookingRequest> bookingOpt = bookingRepository.findById(event.bookingId());
        if (bookingOpt.isEmpty()) {
            log.warn("BookingRequestedEvent for missing booking {}", event.bookingId());
            return;
        }
        String resourceName = resourceName(bookingOpt.get());

        Set<UserRole> notifyRoles = Set.of(UserRole.TENANT_ADMIN, UserRole.PROPERTY_MANAGER);
        List<User> recipients = userRepository.findByTenantId(event.tenantId()).stream()
                .filter(u -> notifyRoles.contains(u.getRole()))
                .toList();
        for (User recipient : recipients) {
            try {
                notificationService.notifyInAppInNewTx(
                        event.tenantId(),
                        recipient.getId(),
                        "BOOKING_REQUESTED",
                        "New booking request",
                        "A renter has requested " + resourceName + ".",
                        "BOOKING",
                        event.bookingId());
            } catch (Exception ex) {
                log.error("Failed to notify user {} for booking {} — {}",
                        recipient.getId(), event.bookingId(), ex.getMessage());
                // Continue — one failing recipient must not abort the rest
            }
        }
        log.info("BookingRequestedEvent processed: notified {} users for booking {}",
                recipients.size(), event.bookingId());
    }

    /** Decision → in-app row for the requesting renter. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingDecided(BookingDecidedEvent event) {
        String type = switch (event.status()) {
            case APPROVED -> "BOOKING_APPROVED";
            case REJECTED -> "BOOKING_REJECTED";
            case RELEASED -> "BOOKING_RELEASED";
            default -> null;
        };
        if (type == null) {
            return;
        }
        String resourceName = bookingRepository.findById(event.bookingId())
                .map(this::resourceName)
                .orElse("your booking");
        String message = switch (event.status()) {
            case APPROVED -> "Your booking request for " + resourceName + " has been approved.";
            case REJECTED -> "Your booking request for " + resourceName + " has been rejected.";
            default -> "Your parking booking for " + resourceName + " has been released.";
        };
        try {
            notificationService.notifyInAppInNewTx(event.tenantId(), event.renterUserId(), type,
                    "Booking update", message, "BOOKING", event.bookingId());
        } catch (Exception ex) {
            log.error("Failed to notify renter {} for booking {} — {}",
                    event.renterUserId(), event.bookingId(), ex.getMessage());
        }
    }

    private String resourceName(BookingRequest booking) {
        if (booking.getResourceType() == BookingResourceType.AMENITY) {
            return amenityRepository.findById(booking.getAmenityId())
                    .map(PropertyAmenity::getNameEn)
                    .orElse("an amenity");
        }
        return parkingSpotRepository.findById(booking.getParkingSpotId())
                .map(s -> "parking spot " + s.getSpotNumber())
                .orElse("a parking spot");
    }
}
