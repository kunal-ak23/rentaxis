package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
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
        // Break-it R3 ops3 F8: "today" is pinned so dated fixtures stay in the future.
        service.setClock(Clock.fixed(LocalDate.of(2026, 8, 1).atStartOfDay(BookingService.DUBAI).toInstant(),
                BookingService.DUBAI));

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
        ArgumentCaptor<BookingRequestedEvent> captor = ArgumentCaptor.forClass(BookingRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().bookingId()).isEqualTo(created.getId());
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void create_parkingSpot_savesPendingWithDerivedPropertyAndPublishesEvent() {
        ParkingSpot s = spot(true);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.parkingSpotVisibleToUnit(s, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
                tenantId, renterUserId, s.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsByParkingSpotIdAndStatus(s.getId(), BookingRequestStatus.APPROVED))
                .thenReturn(false);

        BookingRequest created = service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(), null, null));

        assertThat(created.getStatus()).isEqualTo(BookingRequestStatus.PENDING);
        assertThat(created.getPropertyId()).isEqualTo(propertyId);
        assertThat(created.getParkingSpotId()).isEqualTo(s.getId());
        ArgumentCaptor<BookingRequestedEvent> captor = ArgumentCaptor.forClass(BookingRequestedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().bookingId()).isEqualTo(created.getId());
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void create_inactiveSpot_throwsNotFound() {
        ParkingSpot s = spot(false);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(), null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_spotNotVisibleToUnit_throwsNotFound() {
        ParkingSpot s = spot(true);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.parkingSpotVisibleToUnit(s, unit)).thenReturn(false);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(), null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_nullResourceType_throws400() {
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(null, UUID.randomUUID(), unit.getId(), null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void create_nullResourceId_throws400() {
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, null, unit.getId(), null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void create_timeSlot_requiresDateAndOrderedEndpoints() {
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, UUID.randomUUID(), unit.getId(),
                        null, LocalTime.of(10, 0), LocalTime.of(11, 0), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("preferredDate");

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, UUID.randomUUID(), unit.getId(),
                        LocalDate.now(), LocalTime.of(11, 0), LocalTime.of(10, 0), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("after");
    }

    @Test
    void create_amenity_persistsRequestedTimeSlot() {
        PropertyAmenity a = amenity(true, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                tenantId, renterUserId, a.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        BookingRequest created = service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(),
                        LocalDate.of(2026, 8, 29), LocalTime.of(10, 0), LocalTime.of(11, 0), null));

        assertThat(created.getPreferredDate()).isEqualTo(LocalDate.of(2026, 8, 29));
        assertThat(created.getPreferredStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(created.getPreferredEndTime()).isEqualTo(LocalTime.of(11, 0));
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
        when(bookingRepository.findByTenantIdAndRenterUserIdAndAmenityIdAndStatusIn(
                eq(tenantId), eq(renterUserId), eq(a.getId()), any()))
                .thenReturn(List.of(existing));

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
    void create_amenityPendingRaceAtSave_throwsRetryableConflict() {
        // Happy-path pre-check found nothing, but a concurrent create for the same
        // renter+amenity won the race and committed first; the INSERT here trips
        // uq_booking_pending_renter_amenity. Postgres aborts the whole transaction on
        // that violation, so no further statement (including a re-read) can succeed —
        // must surface a retryable conflict instead of attempting one.
        PropertyAmenity a = amenity(true, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                tenantId, renterUserId, a.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty());
        when(bookingRepository.saveAndFlush(any(BookingRequest.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed", new RuntimeException(
                        "duplicate key value violates unique constraint \"uq_booking_pending_renter_amenity\"")));

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)))
                .isInstanceOf(SlotConflictException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void create_parkingSpotPendingRaceAtSave_throwsRetryableConflict() {
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
                        "duplicate key value violates unique constraint \"uq_booking_pending_renter_spot\"")));

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(), null, null)))
                .isInstanceOf(SlotConflictException.class);
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
        ArgumentCaptor<BookingDecidedEvent> captor = ArgumentCaptor.forClass(BookingDecidedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(BookingRequestStatus.APPROVED);
        assertThat(captor.getValue().renterUserId()).isEqualTo(b.getRenterUserId());
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void approve_wrongTenant_throwsNotFound() {
        // Locked load returns the row regardless of tenant (findByIdForUpdate has no
        // tenantId param) — the explicit check after the load is what stands between a
        // cross-tenant caller and someone else's booking. P0 class: pin it directly.
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        b.setTenantId(UUID.randomUUID());
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .isInstanceOf(NotFoundException.class);
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
        ArgumentCaptor<BookingDecidedEvent> captor = ArgumentCaptor.forClass(BookingDecidedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(BookingRequestStatus.REJECTED);
        assertThat(captor.getValue().renterUserId()).isEqualTo(b.getRenterUserId());
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
    }

    @Test
    void reject_nonPending_throws400() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.APPROVED);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.reject(tenantId, b.getId(), UUID.randomUUID(), null))
                .isInstanceOf(BusinessRuleViolationException.class);
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
    void cancel_approved_throws400() {
        // requirePending guards cancel too — an APPROVED parking booking must not be
        // cancellable this way (that would silently free an assigned spot with no
        // BookingDecidedEvent for anyone downstream to react to). Only release() may
        // move a booking off APPROVED.
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.APPROVED);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.cancel(tenantId, b.getId(), renterUserId))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void release_amenity_throws400() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.APPROVED);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.release(tenantId, b.getId(), renterUserId, false))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void release_pendingParking_throws400() {
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.PENDING);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.release(tenantId, b.getId(), UUID.randomUUID(), true))
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
        ArgumentCaptor<BookingDecidedEvent> captor = ArgumentCaptor.forClass(BookingDecidedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(BookingRequestStatus.RELEASED);
        assertThat(captor.getValue().renterUserId()).isEqualTo(b.getRenterUserId());
        assertThat(captor.getValue().tenantId()).isEqualTo(tenantId);
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

    @Test
    void otherRequests_parkingSpot_excludesTheRequestItself() {
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.PENDING);
        BookingRequest sibling = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.PENDING);
        sibling.setParkingSpotId(b.getParkingSpotId());
        when(bookingRepository.findByParkingSpotIdAndStatusInOrderByCreatedAtAsc(
                eq(b.getParkingSpotId()), any())).thenReturn(List.of(b, sibling));

        assertThat(service.otherRequests(b)).containsExactly(sibling);
    }

    // ---- break-it R3 ops3 F7: one renter, one amenity, one slot ----

    private BookingRequest amenityBooking(UUID amenityId, BookingRequestStatus status, LocalDate day,
                                          LocalTime start, LocalTime end) {
        BookingRequest b = booking(BookingResourceType.AMENITY, status);
        b.setAmenityId(amenityId);
        b.setPreferredDate(day);
        b.setPreferredStartTime(start);
        b.setPreferredEndTime(end);
        return b;
    }

    private PropertyAmenity bookableAmenity() {
        PropertyAmenity a = amenity(true, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);
        return a;
    }

    private static String codeOf(Throwable e) {
        if (e instanceof BusinessRuleViolationException b) return b.getCode();
        if (e instanceof SlotConflictException c) return c.getCode();
        return null;
    }

    @Test
    void create_amenity_sameDayAsAnApprovedBooking_isRefused() {
        PropertyAmenity a = bookableAmenity();
        LocalDate day = LocalDate.of(2026, 10, 12);
        when(bookingRepository.findByTenantIdAndRenterUserIdAndAmenityIdAndStatusIn(
                eq(tenantId), eq(renterUserId), eq(a.getId()), any()))
                .thenReturn(List.of(amenityBooking(a.getId(), BookingRequestStatus.APPROVED, day, null, null)));

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), day, null)))
                .isInstanceOf(SlotConflictException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.alreadyBooked"));
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_amenity_nonOverlappingSlotSameDay_isAllowed() {
        PropertyAmenity a = bookableAmenity();
        LocalDate day = LocalDate.of(2026, 10, 12);
        when(bookingRepository.findByTenantIdAndRenterUserIdAndAmenityIdAndStatusIn(
                eq(tenantId), eq(renterUserId), eq(a.getId()), any()))
                .thenReturn(List.of(amenityBooking(a.getId(), BookingRequestStatus.APPROVED, day,
                        LocalTime.of(9, 0), LocalTime.of(10, 0))));

        BookingRequest created = service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), day,
                        LocalTime.of(10, 0), LocalTime.of(11, 0), null));
        assertThat(created.getStatus()).isEqualTo(BookingRequestStatus.PENDING);
    }

    @Test
    void create_amenity_pendingForAnotherSlot_isRefusedNotHandedBack() {
        PropertyAmenity a = bookableAmenity();
        when(bookingRepository.findByTenantIdAndRenterUserIdAndAmenityIdAndStatusIn(
                eq(tenantId), eq(renterUserId), eq(a.getId()), any()))
                .thenReturn(List.of(amenityBooking(a.getId(), BookingRequestStatus.PENDING,
                        LocalDate.of(2026, 10, 1), null, null)));

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(),
                        LocalDate.of(2026, 10, 5), null)))
                .isInstanceOf(SlotConflictException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.pendingExists"));
    }

    @Test
    void approve_amenity_overlappingAnApprovedBookingOfTheSameRenter_isRefusedUnderTheAmenityLock() {
        UUID amenityId = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 10, 12);
        BookingRequest b = amenityBooking(amenityId, BookingRequestStatus.PENDING, day, null, null);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        when(bookingRepository.findByTenantIdAndRenterUserIdAndAmenityIdAndStatusIn(
                eq(tenantId), eq(renterUserId), eq(amenityId), any()))
                .thenReturn(List.of(b, amenityBooking(amenityId, BookingRequestStatus.APPROVED, day,
                        LocalTime.of(18, 0), LocalTime.of(19, 0))));

        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .isInstanceOf(SlotConflictException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.renterAlreadyBooked"));
        verify(facilityService).lockAmenity(tenantId, amenityId);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void approve_amenity_otherDay_passes() {
        UUID amenityId = UUID.randomUUID();
        BookingRequest b = amenityBooking(amenityId, BookingRequestStatus.PENDING, LocalDate.of(2026, 10, 12), null, null);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        when(bookingRepository.findByTenantIdAndRenterUserIdAndAmenityIdAndStatusIn(
                eq(tenantId), eq(renterUserId), eq(amenityId), any()))
                .thenReturn(List.of(amenityBooking(amenityId, BookingRequestStatus.APPROVED,
                        LocalDate.of(2026, 10, 13), null, null)));

        assertThat(service.approve(tenantId, b.getId(), UUID.randomUUID(), null).getStatus())
                .isEqualTo(BookingRequestStatus.APPROVED);
    }

    @Test
    void slotsOverlap_rules() {
        UUID id = UUID.randomUUID();
        LocalDate d = LocalDate.of(2026, 10, 12);
        BookingRequest wholeDay = amenityBooking(id, BookingRequestStatus.APPROVED, d, null, null);
        BookingRequest nine = amenityBooking(id, BookingRequestStatus.APPROVED, d, LocalTime.of(9, 0), LocalTime.of(10, 0));
        BookingRequest nineThirty = amenityBooking(id, BookingRequestStatus.APPROVED, d, LocalTime.of(9, 30), LocalTime.of(11, 0));
        BookingRequest ten = amenityBooking(id, BookingRequestStatus.APPROVED, d, LocalTime.of(10, 0), LocalTime.of(11, 0));
        BookingRequest otherDay = amenityBooking(id, BookingRequestStatus.APPROVED, d.plusDays(1), null, null);
        BookingRequest undated = amenityBooking(id, BookingRequestStatus.APPROVED, null, null, null);
        assertThat(BookingService.slotsOverlap(wholeDay, nine)).isTrue();
        assertThat(BookingService.slotsOverlap(nine, nineThirty)).isTrue();
        assertThat(BookingService.slotsOverlap(nine, ten)).isFalse();   // back to back
        assertThat(BookingService.slotsOverlap(wholeDay, otherDay)).isFalse();
        assertThat(BookingService.slotsOverlap(undated, undated)).isFalse();
    }

    // ---- break-it R3 ops3 F8: dates on request and on approval ----

    @Test
    void create_dateBeforeToday_isRefused() {
        PropertyAmenity a = bookableAmenity();
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(),
                        LocalDate.of(2026, 7, 31), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.dateInPast"));
        // Today itself is fine.
        assertThat(service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(),
                        LocalDate.of(2026, 8, 1), null)).getStatus()).isEqualTo(BookingRequestStatus.PENDING);
    }

    @Test
    void create_parkingRangeOverTwoYears_isRefused() {
        ParkingSpot s = spot(true);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.parkingSpotVisibleToUnit(s, unit)).thenReturn(true);
        LocalDate from = LocalDate.of(2026, 9, 1);
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(),
                        from, from.plusYears(100), null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.rangeTooLong"));
        // Exactly two years is fine.
        assertThat(service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(),
                        from, from.plusYears(2), null, null, null)).getStatus()).isEqualTo(BookingRequestStatus.PENDING);
    }

    @Test
    void approve_requestLeftPendingPastItsDate_isRefused() {
        BookingRequest b = amenityBooking(UUID.randomUUID(), BookingRequestStatus.PENDING,
                LocalDate.of(2020, 1, 1), null, null);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.dateInPast"));
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    private LeaseRepository leaseOn(LocalDate start, LocalDate end) {
        LeaseRepository leases = mock(LeaseRepository.class);
        Renter renter = new Renter();
        renter.setUserId(renterUserId);
        Lease lease = new Lease();
        lease.setTenantId(tenantId);
        lease.setRenter(renter);
        lease.setUnit(unit);
        lease.setStartDate(start);
        lease.setEndDate(end);
        lease.setStatus(LeaseStatus.ACTIVE);
        when(leases.findByUnitIdAndStatusIn(eq(unit.getId()), any())).thenReturn(List.of(lease));
        service.setLeaseRepository(leases);
        return leases;
    }

    @Test
    void create_dateOutsideTheRentersContract_isRefused() {
        leaseOn(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31));
        PropertyAmenity a = bookableAmenity();
        // Before the contract starts: no fee dated before the lease.
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(),
                        LocalDate.of(2026, 8, 20), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.outsideLease"));
        // A parking range running past the contract end.
        ParkingSpot s = spot(true);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.parkingSpotVisibleToUnit(s, unit)).thenReturn(true);
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(),
                        LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 30), null, null, null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.outsideLease"));
        // Inside the contract.
        assertThat(service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(),
                        LocalDate.of(2026, 9, 1), null)).getStatus()).isEqualTo(BookingRequestStatus.PENDING);
    }

    @Test
    void approve_dateOutsideTheRentersContract_isRefused() {
        leaseOn(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31));
        BookingRequest b = amenityBooking(UUID.randomUUID(), BookingRequestStatus.PENDING,
                LocalDate.of(2026, 8, 15), null, null);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.outsideRenterLease"));
    }

    @Test
    void approve_renterWithNoCurrentContract_isRefused() {
        LeaseRepository leases = mock(LeaseRepository.class);
        when(leases.findByUnitIdAndStatusIn(any(), any())).thenReturn(List.of());
        service.setLeaseRepository(leases);
        BookingRequest b = amenityBooking(UUID.randomUUID(), BookingRequestStatus.PENDING,
                LocalDate.of(2026, 9, 15), null, null);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.noActiveLease"));
    }

    @Test
    void approve_alreadyDecided_carriesTheNotPendingCode() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.APPROVED);
        when(bookingRepository.findByIdForUpdate(b.getId())).thenReturn(Optional.of(b));
        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.notPending"));
    }

    // ---- review r3C I1: an early-posted renewal ----

    private Lease lease(LeaseStatus status, LocalDate start, LocalDate end) {
        Renter renter = new Renter();
        renter.setUserId(renterUserId);
        Lease l = new Lease();
        l.setTenantId(tenantId);
        l.setRenter(renter);
        l.setUnit(unit);
        l.setStartDate(start);
        l.setEndDate(end);
        l.setStatus(status);
        return l;
    }

    private void chain(Lease... leases) {
        LeaseRepository repo = mock(LeaseRepository.class);
        when(repo.findByUnitIdAndStatusIn(eq(unit.getId()), any())).thenReturn(List.of(leases));
        service.setLeaseRepository(repo);
    }

    @Test
    void renewedTermStillCounts_forRequestAndApproval() {
        chain(lease(LeaseStatus.RENEWED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                lease(LeaseStatus.ACTIVE, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)));
        PropertyAmenity a = bookableAmenity();
        // Before the renewal starts.
        assertThat(service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(),
                        LocalDate.of(2026, 10, 12), null)).getStatus()).isEqualTo(BookingRequestStatus.PENDING);
        // A parking range across the renewal boundary.
        ParkingSpot s = spot(true);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.parkingSpotVisibleToUnit(s, unit)).thenReturn(true);
        assertThat(service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(),
                        LocalDate.of(2026, 11, 1), LocalDate.of(2027, 3, 1), null, null, null)).getStatus())
                .isEqualTo(BookingRequestStatus.PENDING);
        // A request made before the renewal is still approvable.
        BookingRequest pending = amenityBooking(UUID.randomUUID(), BookingRequestStatus.PENDING,
                LocalDate.of(2026, 9, 20), null, null);
        when(bookingRepository.findByIdForUpdate(pending.getId())).thenReturn(Optional.of(pending));
        assertThat(service.approve(tenantId, pending.getId(), UUID.randomUUID(), null).getStatus())
                .isEqualTo(BookingRequestStatus.APPROVED);
        // Past the end of the chain is still refused.
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(),
                        LocalDate.of(2027, 6, 1), LocalDate.of(2028, 1, 15), null, null, null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.outsideLease"));
    }

    @Test
    void aGapBetweenTermsIsNotBridged_andRenewedAloneIsNotACurrentContract() {
        assertThat(BookingService.coveredByChain(List.of(
                lease(LeaseStatus.RENEWED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
                lease(LeaseStatus.ACTIVE, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 12, 31))),
                LocalDate.of(2026, 12, 20), LocalDate.of(2027, 2, 10))).isFalse();
        chain(lease(LeaseStatus.RENEWED, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        PropertyAmenity a = bookableAmenity();
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(),
                        LocalDate.of(2026, 10, 12), null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.noActiveLease"));
    }

    // ---- review r3C m1: a paid amenity needs a date ----

    @Test
    void create_paidAmenityWithoutADate_isRefused() {
        PropertyAmenity a = bookableAmenity();
        a.setFeeType("PER_BOOKING");
        a.setFeeAmount(new java.math.BigDecimal("100"));
        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("booking.dateRequired"));
        // A free amenity may still be requested without a date.
        a.setFeeType("FREE");
        assertThat(service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)).getStatus())
                .isEqualTo(BookingRequestStatus.PENDING);
    }
}
