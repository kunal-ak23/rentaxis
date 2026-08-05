package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.dto.BookingDetailDTO;
import com.datagami.rentaxis.api.dto.BookingRequestDTO;
import com.datagami.rentaxis.api.dto.DecisionRequest;
import com.datagami.rentaxis.api.dto.MyFacilitiesDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock BookingService bookingService;
    @Mock FacilityService facilityService;
    @Mock RenterRepository renterRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock UnitRepository unitRepository;
    @Mock UserRepository userRepository;
    @Mock UserPropertyAssignmentRepository assignmentRepository;
    @Mock PropertyAmenityRepository amenityRepository;
    @Mock ParkingSpotRepository parkingSpotRepository;
    @Mock BookingRequestRepository bookingRequestRepository;

    @InjectMocks
    BookingController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterUserId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();

    private Renter renter;
    private Unit unit;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        authenticateAs(renterUserId, "ROLE_RENTER");

        Property property = new Property();
        property.setId(propertyId);
        property.setNameEn("Marina Heights");
        unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setUnitNumber("1204");
        unit.setProperty(property);

        renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setTenantId(tenantId);
        renter.setUserId(renterUserId);

        lenient().when(userRepository.findByTenantIdAndIdIn(eq(tenantId), any()))
                .thenReturn(List.of());
        lenient().when(unitRepository.findAllById(any())).thenReturn(List.of(unit));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID userId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null,
                        Collections.singletonList(new SimpleGrantedAuthority(authority))));
    }

    private Lease activeLease() {
        Lease lease = new Lease();
        lease.setTenantId(tenantId);
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setUnit(unit);
        lease.setRenter(renter);
        return lease;
    }

    private BookingRequest booking(BookingResourceType type) {
        BookingRequest b = new BookingRequest();
        b.setId(UUID.randomUUID());
        b.setTenantId(tenantId);
        b.setPropertyId(propertyId);
        b.setUnitId(unit.getId());
        b.setRenterUserId(renterUserId);
        b.setResourceType(type);
        if (type == BookingResourceType.AMENITY) b.setAmenityId(UUID.randomUUID());
        else b.setParkingSpotId(UUID.randomUUID());
        b.setStatus(BookingRequestStatus.PENDING);
        return b;
    }

    // ------------------------------------------------------------- create

    @Test
    void createBooking_unitNotOnCallerActiveLease_throwsNotFound() {
        when(renterRepository.findByUserId(renterUserId)).thenReturn(Optional.of(renter));
        when(leaseRepository.findByUnitIdAndStatus(unit.getId(), LeaseStatus.ACTIVE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> controller.create(new BookingCreateRequest(
                BookingResourceType.AMENITY, UUID.randomUUID(), unit.getId(), null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createBooking_activeLease_delegatesAndReturns201() {
        when(renterRepository.findByUserId(renterUserId)).thenReturn(Optional.of(renter));
        when(leaseRepository.findByUnitIdAndStatus(unit.getId(), LeaseStatus.ACTIVE))
                .thenReturn(List.of(activeLease()));
        BookingRequest saved = booking(BookingResourceType.AMENITY);
        when(bookingService.create(eq(tenantId), eq(renterUserId), eq(unit), any())).thenReturn(saved);
        when(amenityRepository.findAllById(List.of(saved.getAmenityId()))).thenReturn(List.of());

        ResponseEntity<BookingRequestDTO> response = controller.create(new BookingCreateRequest(
                BookingResourceType.AMENITY, saved.getAmenityId(), unit.getId(), null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().unitNumber()).isEqualTo("1204");
    }

    @Test
    void createBooking_missingUnitId_throwsBusinessRuleViolation() {
        assertThatThrownBy(() -> controller.create(new BookingCreateRequest(
                BookingResourceType.AMENITY, UUID.randomUUID(), null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    // ------------------------------------------------------------- release

    @Test
    void release_asRenter_callsServiceWithActorIsAdminFalse() {
        BookingRequest released = booking(BookingResourceType.PARKING_SPOT);
        released.setStatus(BookingRequestStatus.RELEASED);
        when(bookingService.release(tenantId, released.getId(), renterUserId, false)).thenReturn(released);
        when(parkingSpotRepository.findAllById(List.of(released.getParkingSpotId()))).thenReturn(List.of());

        ResponseEntity<BookingRequestDTO> response = controller.release(released.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(BookingRequestStatus.RELEASED);
    }

    @Test
    void release_asAdmin_callsServiceWithActorIsAdminTrue() {
        UUID adminId = UUID.randomUUID();
        authenticateAs(adminId, "ROLE_TENANT_ADMIN");
        BookingRequest approved = booking(BookingResourceType.PARKING_SPOT);
        approved.setStatus(BookingRequestStatus.APPROVED);
        when(bookingService.get(tenantId, approved.getId())).thenReturn(approved);
        BookingRequest released = booking(BookingResourceType.PARKING_SPOT);
        released.setStatus(BookingRequestStatus.RELEASED);
        when(bookingService.release(tenantId, approved.getId(), adminId, true)).thenReturn(released);
        when(parkingSpotRepository.findAllById(List.of(released.getParkingSpotId()))).thenReturn(List.of());

        assertThat(controller.release(approved.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------- admin get/list

    @Test
    void adminGet_pmWithoutAssignment_throwsAccessDenied() {
        UUID pmId = UUID.randomUUID();
        authenticateAs(pmId, "ROLE_PROPERTY_MANAGER");
        BookingRequest b = booking(BookingResourceType.AMENITY);
        when(bookingService.get(tenantId, b.getId())).thenReturn(b);
        when(assignmentRepository.existsByUserIdAndPropertyId(pmId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.get(b.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminGet_returnsDetailWithOtherRequestsAndRenterIdentities() {
        UUID adminId = UUID.randomUUID();
        authenticateAs(adminId, "ROLE_TENANT_ADMIN");
        BookingRequest b = booking(BookingResourceType.AMENITY);
        BookingRequest sibling = booking(BookingResourceType.AMENITY);
        sibling.setAmenityId(b.getAmenityId());
        when(bookingService.get(tenantId, b.getId())).thenReturn(b);
        when(bookingService.otherRequests(b)).thenReturn(List.of(sibling));
        User renterUser = new User();
        renterUser.setId(sibling.getRenterUserId());
        renterUser.setName("Jane Renter");
        renterUser.setEmail("jane@example.com");
        renterUser.setPhoneNumber("+971500000000");
        when(userRepository.findByTenantIdAndIdIn(eq(tenantId), any()))
                .thenReturn(List.of(renterUser));

        ResponseEntity<BookingDetailDTO> response = controller.get(b.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        BookingDetailDTO body = response.getBody();
        assertThat(body.request().id()).isEqualTo(b.getId());
        assertThat(body.otherRequests()).hasSize(1);
        assertThat(body.otherRequests().getFirst().renterName()).isEqualTo("Jane Renter");
        assertThat(body.otherRequests().getFirst().renterEmail()).isEqualTo("jane@example.com");
    }

    @Test
    void adminList_pmWithoutPropertyId_throwsAccessDenied() {
        UUID pmId = UUID.randomUUID();
        authenticateAs(pmId, "ROLE_PROPERTY_MANAGER");
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "createdAt"));

        assertThatThrownBy(() -> controller.list(null, null, null, pageable))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminList_delegatesToSearchAndMapsBatched() {
        UUID adminId = UUID.randomUUID();
        authenticateAs(adminId, "ROLE_TENANT_ADMIN");
        BookingRequest b = booking(BookingResourceType.AMENITY);
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<BookingRequest> page = new PageImpl<>(List.of(b));
        when(bookingService.search(tenantId, null, null, null, pageable)).thenReturn(page);

        ResponseEntity<Page<BookingRequestDTO>> response = controller.list(null, null, null, pageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getContent()).hasSize(1);
        verify(unitRepository, org.mockito.Mockito.times(1)).findAllById(any());
        verify(userRepository, org.mockito.Mockito.times(1)).findByTenantIdAndIdIn(any(), any());
    }

    // ------------------------------------------------------------- approve/reject

    @Test
    void approve_delegatesAndReturns200() {
        UUID adminId = UUID.randomUUID();
        authenticateAs(adminId, "ROLE_TENANT_ADMIN");
        BookingRequest pending = booking(BookingResourceType.AMENITY);
        BookingRequest approved = booking(BookingResourceType.AMENITY);
        approved.setStatus(BookingRequestStatus.APPROVED);
        when(bookingService.get(tenantId, pending.getId())).thenReturn(pending);
        when(bookingService.approve(tenantId, pending.getId(), adminId, "ok")).thenReturn(approved);

        ResponseEntity<BookingRequestDTO> response = controller.approve(pending.getId(), new DecisionRequest("ok"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(BookingRequestStatus.APPROVED);
    }

    @Test
    void approve_pmWithoutAssignment_throwsAccessDenied() {
        UUID pmId = UUID.randomUUID();
        authenticateAs(pmId, "ROLE_PROPERTY_MANAGER");
        BookingRequest pending = booking(BookingResourceType.AMENITY);
        when(bookingService.get(tenantId, pending.getId())).thenReturn(pending);
        when(assignmentRepository.existsByUserIdAndPropertyId(pmId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.approve(pending.getId(), null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void reject_delegatesAndReturns200() {
        UUID adminId = UUID.randomUUID();
        authenticateAs(adminId, "ROLE_TENANT_ADMIN");
        BookingRequest pending = booking(BookingResourceType.PARKING_SPOT);
        BookingRequest rejected = booking(BookingResourceType.PARKING_SPOT);
        rejected.setStatus(BookingRequestStatus.REJECTED);
        when(bookingService.get(tenantId, pending.getId())).thenReturn(pending);
        when(bookingService.reject(tenantId, pending.getId(), adminId, null)).thenReturn(rejected);

        ResponseEntity<BookingRequestDTO> response = controller.reject(pending.getId(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(BookingRequestStatus.REJECTED);
    }

    // ------------------------------------------------------------- renter my endpoints

    @Test
    void mine_returnsBatchedList() {
        BookingRequest b = booking(BookingResourceType.AMENITY);
        when(bookingService.listMine(tenantId, renterUserId)).thenReturn(List.of(b));

        List<BookingRequestDTO> result = controller.mine();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(b.getId());
    }

    @Test
    void cancel_delegatesWithCurrentUserId() {
        BookingRequest cancelled = booking(BookingResourceType.AMENITY);
        cancelled.setStatus(BookingRequestStatus.CANCELLED);
        when(bookingService.cancel(tenantId, cancelled.getId(), renterUserId)).thenReturn(cancelled);
        when(amenityRepository.findAllById(List.of(cancelled.getAmenityId()))).thenReturn(List.of());

        assertThat(controller.cancel(cancelled.getId()).getBody().status())
                .isEqualTo(BookingRequestStatus.CANCELLED);
    }

    @Test
    void myFacilities_mapsCountsHeldAndPropertyNameViaBatchQueries() {
        when(renterRepository.findByUserId(renterUserId)).thenReturn(Optional.of(renter));
        Lease lease = activeLease();
        when(leaseRepository.findByRenterId(renter.getId())).thenReturn(List.of(lease));
        PropertyAmenity a = new PropertyAmenity();
        a.setId(UUID.randomUUID());
        a.setPropertyId(propertyId);
        a.setNameEn("Gym");
        a.setBookable(true);
        ParkingSpot s = new ParkingSpot();
        s.setId(UUID.randomUUID());
        s.setPropertyId(propertyId);
        s.setSpotNumber("B1-07");
        s.setCovered(true);
        when(facilityService.visibleFacilities(tenantId, unit))
                .thenReturn(new FacilityService.VisibleFacilities(List.of(a), List.of(s)));
        when(bookingRequestRepository.countByAmenityIdIn(tenantId, List.of(a.getId()), BookingRequestStatus.PENDING))
                .thenReturn(List.<Object[]>of(new Object[]{a.getId(), 3L}));
        when(bookingRequestRepository.countByParkingSpotIdIn(tenantId, List.of(s.getId()), BookingRequestStatus.PENDING))
                .thenReturn(List.<Object[]>of(new Object[]{s.getId(), 1L}));
        when(bookingRequestRepository.countByParkingSpotIdIn(tenantId, List.of(s.getId()), BookingRequestStatus.APPROVED))
                .thenReturn(List.<Object[]>of(new Object[]{s.getId(), 1L}));

        ResponseEntity<MyFacilitiesDTO> response = controller.myFacilities();

        MyFacilitiesDTO body = response.getBody();
        assertThat(body.amenities()).hasSize(1);
        assertThat(body.amenities().getFirst().propertyName()).isEqualTo("Marina Heights");
        assertThat(body.amenities().getFirst().pendingCount()).isEqualTo(3L);
        assertThat(body.parkingSpots().getFirst().held()).isTrue();
        assertThat(body.parkingSpots().getFirst().pendingCount()).isEqualTo(1L);
        // Deviation from the plan: pendingCount/held must come from the batch
        // repository queries above, never from per-resource BookingService calls.
        verify(bookingService, never()).countPendingForAmenity(any());
        verify(bookingService, never()).countPendingForSpot(any());
        verify(bookingService, never()).spotHeld(any());
    }

    @Test
    void myFacilities_noActiveLeases_returnsEmptyCollectionsWithoutBatchQueries() {
        when(renterRepository.findByUserId(renterUserId)).thenReturn(Optional.of(renter));
        when(leaseRepository.findByRenterId(renter.getId())).thenReturn(List.of());

        ResponseEntity<MyFacilitiesDTO> response = controller.myFacilities();

        assertThat(response.getBody().amenities()).isEmpty();
        assertThat(response.getBody().parkingSpots()).isEmpty();
        verify(bookingRequestRepository, never()).countByAmenityIdIn(any(), any(), any());
        verify(bookingRequestRepository, never()).countByParkingSpotIdIn(any(), any(), any());
    }
}
