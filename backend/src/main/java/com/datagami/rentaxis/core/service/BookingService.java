package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Booking request lifecycle. No role logic — RBAC and the renter's
 * active-lease ownership check live in {@code BookingController}, same split
 * as the gate-pass module. The {@code unit} passed to {@link #create} must
 * already be verified as leased by the caller. Cross-tenant and
 * not-visible-to-caller lookups throw {@link NotFoundException} (404, never
 * 403) so ids cannot be probed. The partial unique indexes
 * uq_booking_spot_active / uq_booking_pending_renter_* (migration 69) are the
 * DB backstops behind the conflict and idempotency checks here.
 *
 * <p><b>Concurrency.</b> Every status transition (approve/reject/cancel/
 * release) loads its row under a {@code PESSIMISTIC_WRITE} lock via
 * {@link BookingRequestRepository#findByIdForUpdate}, taken by the query that
 * first loads the entity in the transaction — same reasoning as
 * {@code GatePassScanService}: an unlocked read followed by a locking re-read
 * would hand back the already-managed (stale) instance from the persistence
 * context, and the lock would do nothing. The lock is NOWAIT, so a decision
 * racing a concurrent one fails fast with {@link PessimisticLockingFailureException}
 * (Spring's translated type — not the raw JPA exception) rather than queueing;
 * that is translated here into a {@link SlotConflictException} retry hint.
 */
@Service
@Transactional
public class BookingService {

    /** Migration 69's constraint/index names — kept in sync with the translation below. */
    static final String UQ_BOOKING_PENDING_RENTER_AMENITY = "uq_booking_pending_renter_amenity";
    static final String UQ_BOOKING_PENDING_RENTER_SPOT = "uq_booking_pending_renter_spot";
    static final String UQ_BOOKING_SPOT_ACTIVE = "uq_booking_spot_active";

    private static final List<BookingRequestStatus> OPEN_STATUSES =
            List.of(BookingRequestStatus.PENDING, BookingRequestStatus.APPROVED);

    private final BookingRequestRepository bookingRepository;
    private final FacilityService facilityService;
    private final ApplicationEventPublisher eventPublisher;

    public BookingService(BookingRequestRepository bookingRepository,
                          FacilityService facilityService,
                          ApplicationEventPublisher eventPublisher) {
        this.bookingRepository = bookingRepository;
        this.facilityService = facilityService;
        this.eventPublisher = eventPublisher;
    }

    public BookingRequest create(UUID tenantId, UUID renterUserId, Unit unit, BookingCreateRequest req) {
        if (req.resourceType() == null) {
            throw new BusinessRuleViolationException("resourceType is required");
        }
        if (req.resourceId() == null) {
            throw new BusinessRuleViolationException("resourceId is required");
        }
        validateRequestedTimeWindow(req);

        BookingRequest booking = new BookingRequest();
        booking.setTenantId(tenantId);
        booking.setUnitId(unit.getId());
        booking.setRenterUserId(renterUserId);
        booking.setResourceType(req.resourceType());
        booking.setNote(req.note());
        booking.setPreferredDate(req.preferredDate());
        booking.setPreferredEndDate(req.preferredEndDate());
        booking.setPreferredStartTime(req.preferredStartTime());
        booking.setPreferredEndTime(req.preferredEndTime());
        booking.setStatus(BookingRequestStatus.PENDING);

        if (req.resourceType() == BookingResourceType.AMENITY) {
            PropertyAmenity amenity = facilityService.getAmenity(tenantId, req.resourceId());
            // Inactive or out-of-scope resources are indistinguishable from missing ones.
            if (!amenity.isActive() || !facilityService.amenityVisibleToUnit(amenity, unit)) {
                throw new NotFoundException("Amenity not found");
            }
            if (!amenity.isBookable()) {
                throw new BusinessRuleViolationException("This amenity is not bookable");
            }
            Optional<BookingRequest> existing =
                    bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                            tenantId, renterUserId, amenity.getId(), BookingRequestStatus.PENDING);
            if (existing.isPresent()) {
                return existing.get(); // idempotent, like InterestService.addInterest
            }
            booking.setAmenityId(amenity.getId());
            // propertyId always derives from the resource, never from the client.
            booking.setPropertyId(amenity.getPropertyId());
            // F14-50: the fee the renter was shown, fixed now.
            booking.setFeeAmount(BookingFeeService.quote(amenity, booking));
        } else {
            ParkingSpot spot = facilityService.getParkingSpot(tenantId, req.resourceId());
            if (!spot.isActive() || !facilityService.parkingSpotVisibleToUnit(spot, unit)) {
                throw new NotFoundException("Parking spot not found");
            }
            Optional<BookingRequest> existing =
                    bookingRepository.findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
                            tenantId, renterUserId, spot.getId(), BookingRequestStatus.PENDING);
            if (existing.isPresent()) {
                return existing.get();
            }
            if (bookingRepository.existsByParkingSpotIdAndStatus(spot.getId(), BookingRequestStatus.APPROVED)) {
                throw new SlotConflictException("Parking spot is already assigned", null);
            }
            booking.setParkingSpotId(spot.getId());
            booking.setPropertyId(spot.getPropertyId());
            booking.setFeeAmount(BookingFeeService.quote(spot, booking));
        }

        return saveNewBooking(tenantId, booking);
    }

    private void validateRequestedTimeWindow(BookingCreateRequest req) {
        if (req.resourceType() == BookingResourceType.PARKING_SPOT) {
            if (req.preferredStartTime() != null || req.preferredEndTime() != null) {
                throw new BusinessRuleViolationException(
                        "Parking requests use a start and end date, not hourly time slots");
            }
            // Existing API clients can still create legacy date-less parking
            // requests. The renter app requires both dates; the server only
            // needs to reject a malformed half-range.
            if (req.preferredEndDate() != null && req.preferredDate() == null) {
                throw new BusinessRuleViolationException(
                        "Parking end date requires a start date");
            }
            if (req.preferredDate() != null && req.preferredEndDate() != null
                    && req.preferredEndDate().isBefore(req.preferredDate())) {
                throw new BusinessRuleViolationException(
                        "Parking end date must be on or after the start date");
            }
            return;
        }
        if (req.preferredEndDate() != null) {
            throw new BusinessRuleViolationException(
                    "Amenity requests use a time slot, not a date range");
        }
        LocalTime start = req.preferredStartTime();
        LocalTime end = req.preferredEndTime();
        if ((start == null) != (end == null)) {
            throw new BusinessRuleViolationException(
                    "Both preferredStartTime and preferredEndTime are required for a time slot");
        }
        if (start != null && req.preferredDate() == null) {
            throw new BusinessRuleViolationException("preferredDate is required for a time slot");
        }
        if (start != null && !start.isBefore(end)) {
            throw new BusinessRuleViolationException(
                    "preferredEndTime must be after preferredStartTime");
        }
    }

    /**
     * saveAndFlush (not save) so the INSERT actually executes inside this try —
     * GenerationType.UUID assigns the id in memory, so Hibernate is otherwise
     * free to defer the INSERT past this catch (same reasoning as
     * FacilityService#saveSpot). The existsBy/findFirstBy pre-checks above are
     * happy-path guards, but they're TOCTOU against the DB-level partial unique
     * indexes from migration 69 — a concurrent request can still slip in
     * between the check and this save.
     */
    private BookingRequest saveNewBooking(UUID tenantId, BookingRequest booking) {
        BookingRequest saved;
        try {
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            throw translateCreateConstraintViolation(e);
        }
        eventPublisher.publishEvent(new BookingRequestedEvent(saved.getId(), tenantId));
        return saved;
    }

    /**
     * A concurrent create beat this one to the insert. uq_booking_pending_renter_*
     * means the renter already has a PENDING request for this exact resource —
     * surfaced as a retryable {@link SlotConflictException} rather than an
     * idempotent re-read: Postgres aborts the whole transaction on a
     * unique-constraint violation, so no further statement on this connection
     * (including a re-read) can succeed. The pre-flight findFirstBy check
     * earlier in {@link #create} is what actually delivers idempotency — it
     * runs in a fresh, non-aborted transaction, so a client that retries after
     * hitting this narrow race lands on that pre-flight and gets the existing
     * row back. uq_booking_spot_active means the spot is already APPROVED
     * elsewhere — surfaced as a conflict rather than silently handed back.
     * Anything else wasn't the violation being guarded against, so it is
     * rethrown rather than mislabeled — same shape as
     * FacilityService#translateConstraintViolation.
     */
    private static RuntimeException translateCreateConstraintViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String causeMessage = cause != null ? cause.getMessage() : null;
        if (causeMessage != null && (causeMessage.contains(UQ_BOOKING_PENDING_RENTER_AMENITY)
                || causeMessage.contains(UQ_BOOKING_PENDING_RENTER_SPOT))) {
            return new SlotConflictException(
                    "A request for this resource is already in flight, please retry", null);
        }
        if (causeMessage != null && causeMessage.contains(UQ_BOOKING_SPOT_ACTIVE)) {
            return new SlotConflictException("Parking spot is already assigned", null);
        }
        return e;
    }

    /** Unlocked read for display purposes (GET detail, otherRequests context) — not a transition. */
    @Transactional(readOnly = true)
    public BookingRequest get(UUID tenantId, UUID id) {
        BookingRequest booking = bookingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        if (!Objects.equals(booking.getTenantId(), tenantId)) {
            throw new NotFoundException("Booking not found");
        }
        return booking;
    }

    /**
     * Locked read for status transitions. Must be the first load of this row in
     * the transaction — see the class javadoc. findByIdForUpdate has no
     * tenantId param, so the tenant check happens explicitly after the load.
     */
    private BookingRequest getForUpdate(UUID tenantId, UUID id) {
        Optional<BookingRequest> found;
        try {
            found = bookingRepository.findByIdForUpdate(id);
        } catch (PessimisticLockingFailureException e) {
            // NOWAIT fired: another decision holds this row's lock. Better a 409
            // retry hint than a 500 or blocking the request thread.
            throw new SlotConflictException("A decision on this booking is already in progress, please retry", null);
        }
        BookingRequest booking = found.orElseThrow(() -> new NotFoundException("Booking not found"));
        if (!Objects.equals(booking.getTenantId(), tenantId)) {
            throw new NotFoundException("Booking not found");
        }
        return booking;
    }

    public BookingRequest approve(UUID tenantId, UUID id, UUID adminUserId, String adminNote) {
        BookingRequest booking = getForUpdate(tenantId, id);
        requirePending(booking);
        if (booking.getResourceType() == BookingResourceType.PARKING_SPOT
                && bookingRepository.existsByParkingSpotIdAndStatus(
                        booking.getParkingSpotId(), BookingRequestStatus.APPROVED)) {
            throw new SlotConflictException("Parking spot is already assigned to another renter", null);
        }
        // F14-50: the fee becomes a charge on the renter's lease, posted with the approval.
        if (bookingFees != null) bookingFees.chargeOnApproval(booking, resourceLabel(booking), java.time.LocalDate.now());
        return decide(booking, BookingRequestStatus.APPROVED, adminUserId, adminNote);
    }

    public BookingRequest reject(UUID tenantId, UUID id, UUID adminUserId, String adminNote) {
        BookingRequest booking = getForUpdate(tenantId, id);
        requirePending(booking);
        return decide(booking, BookingRequestStatus.REJECTED, adminUserId, adminNote);
    }

    /**
     * Renter withdraws their own PENDING request, or (F14-50) an APPROVED amenity
     * booking whose slot has not started — its fee is reversed. 404 on someone
     * else's — no probing.
     */
    public BookingRequest cancel(UUID tenantId, UUID id, UUID renterUserId) {
        BookingRequest booking = getForUpdate(tenantId, id);
        if (!renterUserId.equals(booking.getRenterUserId())) {
            throw new NotFoundException("Booking not found");
        }
        if (booking.getStatus() == BookingRequestStatus.APPROVED
                && booking.getResourceType() == BookingResourceType.AMENITY) {
            if (!BookingFeeService.beforeSlot(booking, java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Dubai")))) {
                throw new BusinessRuleViolationException("The booking's slot has started; it can no longer be cancelled.",
                        "booking.slotStarted", java.util.Map.of());
            }
            if (bookingFees != null) bookingFees.reverseOnCancel(booking, java.time.LocalDate.now());
        } else {
            requirePending(booking);
        }
        booking.setStatus(BookingRequestStatus.CANCELLED);
        return bookingRepository.saveAndFlush(booking);
    }

    private BookingFeeService bookingFees;

    /** Setter-injected: hand-built instances in unit tests need no new argument. */
    @org.springframework.beans.factory.annotation.Autowired
    public void setBookingFees(BookingFeeService bookingFees) {
        this.bookingFees = bookingFees;
    }

    private String resourceLabel(BookingRequest b) {
        // A label is a courtesy; the charge still posts. Looked up rather than caught:
        // a NotFoundException out of FacilityService would mark the approval's
        // transaction rollback-only, and the approval would fail at commit.
        if (b.getAmenityId() != null) {
            return facilityService.findAmenity(b.getTenantId(), b.getAmenityId())
                    .map(PropertyAmenity::getNameEn).orElse("booking");
        }
        if (b.getParkingSpotId() != null) {
            return facilityService.findParkingSpot(b.getTenantId(), b.getParkingSpotId())
                    .map(sp -> "parking " + sp.getSpotNumber()).orElse("booking");
        }
        return "booking";
    }

    /** APPROVED parking only. actorIsAdmin=false enforces the renter-owner rule. */
    public BookingRequest release(UUID tenantId, UUID id, UUID actorUserId, boolean actorIsAdmin) {
        BookingRequest booking = getForUpdate(tenantId, id);
        if (!actorIsAdmin && !actorUserId.equals(booking.getRenterUserId())) {
            throw new NotFoundException("Booking not found");
        }
        if (booking.getResourceType() != BookingResourceType.PARKING_SPOT) {
            throw new BusinessRuleViolationException("Only parking bookings can be released");
        }
        if (booking.getStatus() != BookingRequestStatus.APPROVED) {
            throw new BusinessRuleViolationException("Only approved bookings can be released");
        }
        booking.setStatus(BookingRequestStatus.RELEASED);
        booking.setDecidedByUserId(actorUserId);
        booking.setDecidedAt(Instant.now());
        BookingRequest saved = bookingRepository.saveAndFlush(booking);
        eventPublisher.publishEvent(new BookingDecidedEvent(
                saved.getId(), saved.getTenantId(), saved.getRenterUserId(), BookingRequestStatus.RELEASED));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<BookingRequest> search(UUID tenantId, UUID propertyId, BookingRequestStatus status,
                                       BookingResourceType resourceType, Pageable pageable) {
        return bookingRepository.search(tenantId, propertyId, status, resourceType, pageable);
    }

    @Transactional(readOnly = true)
    public Page<BookingRequest> searchAssignedProperties(
            UUID tenantId,
            List<UUID> propertyIds,
            BookingRequestStatus status,
            BookingResourceType resourceType,
            Pageable pageable) {
        if (propertyIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return bookingRepository.searchAssignedProperties(
                tenantId, propertyIds, status, resourceType, pageable);
    }

    @Transactional(readOnly = true)
    public List<BookingRequest> listMine(UUID tenantId, UUID renterUserId) {
        return bookingRepository.findByTenantIdAndRenterUserIdOrderByCreatedAtAsc(tenantId, renterUserId);
    }

    /** All other PENDING/APPROVED requests for the same resource — the admin's context. */
    @Transactional(readOnly = true)
    public List<BookingRequest> otherRequests(BookingRequest booking) {
        List<BookingRequest> siblings = booking.getResourceType() == BookingResourceType.AMENITY
                ? bookingRepository.findByAmenityIdAndStatusInOrderByCreatedAtAsc(
                        booking.getAmenityId(), OPEN_STATUSES)
                : bookingRepository.findByParkingSpotIdAndStatusInOrderByCreatedAtAsc(
                        booking.getParkingSpotId(), OPEN_STATUSES);
        return siblings.stream().filter(b -> !b.getId().equals(booking.getId())).toList();
    }

    @Transactional(readOnly = true)
    public long countPendingForAmenity(UUID amenityId) {
        return bookingRepository.countByAmenityIdAndStatus(amenityId, BookingRequestStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public long countPendingForSpot(UUID parkingSpotId) {
        return bookingRepository.countByParkingSpotIdAndStatus(parkingSpotId, BookingRequestStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public boolean spotHeld(UUID parkingSpotId) {
        return bookingRepository.existsByParkingSpotIdAndStatus(parkingSpotId, BookingRequestStatus.APPROVED);
    }

    /**
     * saveAndFlush for the same reason as {@link #saveNewBooking}: the write
     * must happen inside this try so a uq_booking_spot_active race on approve
     * (concurrent decisions on two different PENDING requests for the same
     * spot) is caught here rather than surfacing later as an unhandled 500.
     * The pre-flight existsByParkingSpotIdAndStatus check in {@link #approve}
     * is the happy-path guard; this is the DB backstop.
     */
    private BookingRequest decide(BookingRequest booking, BookingRequestStatus status,
                                  UUID adminUserId, String adminNote) {
        booking.setStatus(status);
        booking.setAdminNote(adminNote);
        booking.setDecidedByUserId(adminUserId);
        booking.setDecidedAt(Instant.now());
        BookingRequest saved;
        try {
            saved = bookingRepository.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            throw translateDecideConstraintViolation(e);
        }
        eventPublisher.publishEvent(new BookingDecidedEvent(
                saved.getId(), saved.getTenantId(), saved.getRenterUserId(), status));
        return saved;
    }

    private static RuntimeException translateDecideConstraintViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        String causeMessage = cause != null ? cause.getMessage() : null;
        if (causeMessage != null && causeMessage.contains(UQ_BOOKING_SPOT_ACTIVE)) {
            return new SlotConflictException("Parking spot is already assigned to another renter", null);
        }
        return e;
    }

    private static void requirePending(BookingRequest booking) {
        if (booking.getStatus() != BookingRequestStatus.PENDING) {
            throw new BusinessRuleViolationException("Booking is not pending");
        }
    }
}
