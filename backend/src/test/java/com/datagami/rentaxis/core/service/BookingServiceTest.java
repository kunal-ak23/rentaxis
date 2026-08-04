package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingServiceTest {

    private BookingRequestRepository bookingRepository;
    private FacilityService facilityService;
    private ApplicationEventPublisher eventPublisher;
    private BookingService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterUserId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private Unit unit;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRequestRepository.class);
        facilityService = mock(FacilityService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new BookingService(bookingRepository, facilityService, eventPublisher);

        // Create and every locked transition write through saveAndFlush (not save) so the
        // constraint-race translation can catch DataIntegrityViolationException at the
        // point of insert/update — see BookingService#saveNewBooking / #decide.
        when(bookingRepository.saveAndFlush(any(BookingRequest.class))).thenAnswer(inv -> {
            BookingRequest b = inv.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            return b;
        });

        Property property = new Property();
        property.setId(propertyId);
        unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setProperty(property);
    }

    private PropertyAmenity amenity(boolean bookable, boolean active) {
        PropertyAmenity a = new PropertyAmenity();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setPropertyId(propertyId);
        a.setNameEn("Gym");
        a.setBookable(bookable);
        a.setActive(active);
        return a;
    }

    private ParkingSpot spot(boolean active) {
        ParkingSpot s = new ParkingSpot();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setPropertyId(propertyId);
        s.setSpotNumber("B1-07");
        s.setActive(active);
        return s;
    }

    private BookingRequest booking(BookingResourceType type, BookingRequestStatus status) {
        BookingRequest b = new BookingRequest();
        b.setId(UUID.randomUUID());
        b.setTenantId(tenantId);
        b.setPropertyId(propertyId);
        b.setUnitId(unit.getId());
        b.setRenterUserId(renterUserId);
        b.setResourceType(type);
        if (type == BookingResourceType.AMENITY) {
            b.setAmenityId(UUID.randomUUID());
        } else {
            b.setParkingSpotId(UUID.randomUUID());
        }
        b.setStatus(status);
        return b;
    }

    // ---- create ----

    @Test
    void create_amenity_savesPendingWithDerivedPropertyAndPublishesEvent() {
        PropertyAmenity a = amenity(true, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                tenantId, renterUserId, a.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        BookingRequest created = service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, "pls"));

        assertThat(created.getStatus()).isEqualTo(BookingRequestStatus.PENDING);
        assertThat(created.getPropertyId()).isEqualTo(propertyId);
        assertThat(created.getAmenityId()).isEqualTo(a.getId());
        verify(eventPublisher).publishEvent(any(BookingRequestedEvent.class));
    }

    @Test
    void create_nonBookableAmenity_throws400() {
        PropertyAmenity a = amenity(false, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void create_amenityNotVisibleToUnit_throwsNotFound() {
        PropertyAmenity a = amenity(true, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(false);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_inactiveAmenity_throwsNotFound() {
        PropertyAmenity a = amenity(true, false);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_existingPending_returnsItWithoutSavingOrEvent() {
        PropertyAmenity a = amenity(true, true);
        BookingRequest existing = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                tenantId, renterUserId, a.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.of(existing));

        BookingRequest result = service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null));

        assertThat(result).isSameAs(existing);
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void create_parkingSpotAlreadyApproved_throwsConflict() {
        ParkingSpot s = spot(true);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.parkingSpotVisibleToUnit(s, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
                tenantId, renterUserId, s.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsByParkingSpotIdAndStatus(s.getId(), BookingRequestStatus.APPROVED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(), null, null)))
                .isInstanceOf(SlotConflictException.class);
    }

    // ---- create: DB backstop / constraint-race translation (review hardening) ----

    @Test
    void create_amenityPendingRaceAtSave_reReadsAndReturnsExistingIdempotently() {
        // Happy-path pre-check found nothing, but a concurrent create for the same
        // renter+amenity won the race and committed first; the INSERT here trips
        // uq_booking_pending_renter_amenity. Must re-read and hand back that row
        // rather than surfacing a 500.
        PropertyAmenity a = amenity(true, true);
        BookingRequest winner = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                tenantId, renterUserId, a.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(bookingRepository.saveAndFlush(any(BookingRequest.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", new RuntimeException(
                        "duplicate key value violates unique constraint \"uq_booking_pending_renter_amenity\"")));

        BookingRequest result = service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null));

        assertThat(result).isSameAs(winner);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void create_parkingSpotPendingRaceAtSave_reReadsAndReturnsExistingIdempotently() {
        ParkingSpot s = spot(true);
        BookingRequest winner = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.PENDING);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.parkingSpotVisibleToUnit(s, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
                tenantId, renterUserId, s.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(bookingRepository.existsByParkingSpotIdAndStatus(s.getId(), BookingRequestStatus.APPROVED))
                .thenReturn(false);
        when(bookingRepository.saveAndFlush(any(BookingRequest.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", new RuntimeException(
                        "duplicate key value violates unique constraint \"uq_booking_pending_renter_spot\"")));

        BookingRequest result = service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(), null, null));

        assertThat(result).isSameAs(winner);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void create_spotActiveRaceAtSave_throwsConflict() {
        ParkingSpot s = spot(true);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.parkingSpotVisibleToUnit(s, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
                tenantId, renterUserId, s.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsByParkingSpotIdAndStatus(s.getId(), BookingRequestStatus.APPROVED))
                .thenReturn(false);
        when(bookingRepository.saveAndFlush(any(BookingRequest.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", new RuntimeException(
                        "duplicate key value violates unique constraint \"uq_booking_spot_active\"")));

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(), null, null)))
                .isInstanceOf(SlotConflictException.class);
    }

    @Test
    void create_unrecognizedConstraintViolation_rethrowsOriginal() {
        PropertyAmenity a = amenity(true, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                tenantId, renterUserId, a.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty());
        DataIntegrityViolationException original = new DataIntegrityViolationException("insert failed",
                new RuntimeException("duplicate key value violates unique constraint \"some_other_constraint\""));
        when(bookingRepository.saveAndFlush(any(BookingRequest.class))).thenThrow(original);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)))
                .isSameAs(original);
    }

    // ---- transitions ----

    @Test
    void approve_pending_setsDecisionFieldsAndPublishes() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        UUID adminId = UUID.randomUUID();
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        BookingRequest approved = service.approve(tenantId, b.getId(), adminId, "ok");

        assertThat(approved.getStatus()).isEqualTo(BookingRequestStatus.APPROVED);
        assertThat(approved.getDecidedByUserId()).isEqualTo(adminId);
        assertThat(approved.getDecidedAt()).isNotNull();
        assertThat(approved.getAdminNote()).isEqualTo("ok");
        verify(eventPublisher).publishEvent(any(BookingDecidedEvent.class));
    }

    @Test
    void approve_parkingWhenSpotHeldElsewhere_throwsConflict() {
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.PENDING);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        when(bookingRepository.existsByParkingSpotIdAndStatus(b.getParkingSpotId(),
                BookingRequestStatus.APPROVED)).thenReturn(true);

        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .isInstanceOf(SlotConflictException.class);
    }

    @Test
    void approve_spotActiveRaceAtSave_throwsConflict() {
        // Pre-flight existsByParkingSpotIdAndStatus passed (false), but a concurrent
        // approval of a sibling request for the same spot committed first — the
        // UPDATE here trips uq_booking_spot_active. The DB backstop behind the
        // pre-flight check.
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.PENDING);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        when(bookingRepository.existsByParkingSpotIdAndStatus(b.getParkingSpotId(),
                BookingRequestStatus.APPROVED)).thenReturn(false);
        when(bookingRepository.saveAndFlush(any(BookingRequest.class)))
                .thenThrow(new DataIntegrityViolationException("update failed", new RuntimeException(
                        "duplicate key value violates unique constraint \"uq_booking_spot_active\"")));

        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .isInstanceOf(SlotConflictException.class);
    }

    @Test
    void approve_nonPending_throws400() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.REJECTED);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void reject_pending_setsRejectedAndPublishes() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        BookingRequest rejected = service.reject(tenantId, b.getId(), UUID.randomUUID(), "no");

        assertThat(rejected.getStatus()).isEqualTo(BookingRequestStatus.REJECTED);
        verify(eventPublisher).publishEvent(any(BookingDecidedEvent.class));
    }

    @Test
    void cancel_othersBooking_throwsNotFound() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.cancel(tenantId, b.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancel_ownPending_setsCancelledWithoutEvent() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        BookingRequest cancelled = service.cancel(tenantId, b.getId(), renterUserId);

        assertThat(cancelled.getStatus()).isEqualTo(BookingRequestStatus.CANCELLED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void release_amenity_throws400() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.APPROVED);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.release(tenantId, b.getId(), renterUserId, false))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void release_renterNotOwner_throwsNotFound() {
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.APPROVED);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.release(tenantId, b.getId(), UUID.randomUUID(), false))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void release_adminOnApprovedParking_setsReleasedAndPublishes() {
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.APPROVED);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        BookingRequest released = service.release(tenantId, b.getId(), UUID.randomUUID(), true);

        assertThat(released.getStatus()).isEqualTo(BookingRequestStatus.RELEASED);
        verify(eventPublisher).publishEvent(any(BookingDecidedEvent.class));
    }

    // ---- locked-load failure (review hardening) ----

    @Test
    void lockConflictOnLockedLoad_translatesToConflict() {
        // Postgres NOWAIT surfaces as Spring's translated PessimisticLockingFailureException
        // (never the raw jakarta.persistence.PessimisticLockException) once another
        // decision holds the row lock. Every locked transition must translate it to a
        // 409-style retry hint rather than let it escape as a 500.
        UUID id = UUID.randomUUID();
        when(bookingRepository.findByIdForUpdate(id))
                .thenThrow(new PessimisticLockingFailureException("could not obtain lock on row"));

        assertThatThrownBy(() -> service.approve(tenantId, id, UUID.randomUUID(), null))
                .isInstanceOf(SlotConflictException.class);
    }

    @Test
    void get_wrongTenant_throwsNotFound() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        b.setTenantId(UUID.randomUUID());
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.get(tenantId, b.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void otherRequests_excludesTheRequestItself() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        BookingRequest sibling = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        sibling.setAmenityId(b.getAmenityId());
        when(bookingRepository.findByAmenityIdAndStatusInOrderByCreatedAtAsc(
                eq(b.getAmenityId()), any())).thenReturn(List.of(b, sibling));

        assertThat(service.otherRequests(b)).containsExactly(sibling);
    }
}
