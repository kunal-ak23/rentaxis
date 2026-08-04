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
 * {@link ListingNotificationService}: {@code AFTER_COMMIT} so a listener
 * failure can never roll back the booking transaction, {@code REQUIRES_NEW}
 * so each listener runs its own transaction rather than piggybacking on one
 * that has already committed, and per-recipient try/catch so one bad
 * notification (e.g. a stale user row) does not stop the rest of the batch.
 *
 * <p>{@code BOOKING_REQUESTED}/{@code BOOKING_APPROVED}/{@code BOOKING_REJECTED}/
 * {@code BOOKING_RELEASED} have no case in {@link NotificationService#mapLegacyType}
 * (verified by reading it), so {@link NotificationService#notify} publishes no
 * {@code EmailEvent} for these types — only the in-app row is written, matching
 * the spec that booking notifications are in-app only.
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
                notificationService.notify(
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
            notificationService.notify(event.tenantId(), event.renterUserId(), type,
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
