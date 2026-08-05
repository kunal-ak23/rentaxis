package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ParkingSpotBulkCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotDTO;
import com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.ParkingSpotBuildingScope;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NOTE: adds a list_* test pair beyond the plan's original text — the plan didn't
 * cover ParkingSpotController#list, but the same N+1 (per-row FacilityService /
 * BookingService calls) vs. batch (BookingRequestRepository.countByParkingSpotIdIn,
 * ParkingSpotBuildingScopeRepository.findByParkingSpotIdIn) split applies here as it
 * does for AmenityController, so it needs equivalent coverage.
 */
@ExtendWith(MockitoExtension.class)
class ParkingSpotControllerTest {

    @Mock
    FacilityService facilityService;

    @Mock
    BookingService bookingService;

    @Mock
    BookingRequestRepository bookingRequestRepository;

    @Mock
    ParkingSpotBuildingScopeRepository spotScopeRepository;

    @Mock
    UserPropertyAssignmentRepository assignmentRepository;

    @InjectMocks
    ParkingSpotController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID pmUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminUserId.toString(), null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
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

    private ParkingSpot spot(String number) {
        ParkingSpot s = new ParkingSpot();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setPropertyId(propertyId);
        s.setSpotNumber(number);
        return s;
    }

    @Test
    void list_returns200WithBatchMappedCountsAndHeld() {
        ParkingSpot s1 = spot("B1-01");
        ParkingSpot s2 = spot("B1-02");
        // Page 1 of 2, 45 total — deliberately different from the 2-item content list,
        // so a rewrite that rebuilds the page as `new PageImpl<>(dtoList)` (dropping
        // pageable/total) rather than `page.map(...)` (preserving them) fails loudly.
        Pageable requestedPageable = PageRequest.of(1, 2);
        Page<ParkingSpot> sourcePage = new PageImpl<>(List.of(s1, s2), requestedPageable, 45L);
        when(facilityService.listParkingSpots(eq(tenantId), eq(propertyId), any(Pageable.class)))
                .thenReturn(sourcePage);
        when(bookingRequestRepository.countByParkingSpotIdIn(eq(tenantId), any(), eq(BookingRequestStatus.PENDING)))
                .thenReturn(List.<Object[]>of(new Object[]{s1.getId(), 1L}));
        when(bookingRequestRepository.countByParkingSpotIdIn(eq(tenantId), any(), eq(BookingRequestStatus.APPROVED)))
                .thenReturn(List.<Object[]>of(new Object[]{s2.getId(), 1L}));
        ParkingSpotBuildingScope scope = new ParkingSpotBuildingScope();
        scope.setParkingSpotId(s1.getId());
        UUID buildingId = UUID.randomUUID();
        scope.setBuildingId(buildingId);
        when(spotScopeRepository.findByParkingSpotIdIn(any())).thenReturn(List.of(scope));

        ResponseEntity<Page<ParkingSpotDTO>> response =
                controller.list(propertyId, requestedPageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Page<ParkingSpotDTO> body = response.getBody();
        List<ParkingSpotDTO> dtos = body.getContent();
        assertThat(dtos).extracting(ParkingSpotDTO::pendingCount).containsExactly(1L, 0L);
        assertThat(dtos).extracting(ParkingSpotDTO::held).containsExactly(false, true);
        assertThat(dtos.get(0).buildingIds()).containsExactly(buildingId);
        assertThat(body.getTotalElements()).isEqualTo(45L);
        assertThat(body.getNumber()).isEqualTo(1);
        assertThat(body.getSize()).isEqualTo(2);

        verify(bookingRequestRepository, times(1))
                .countByParkingSpotIdIn(eq(tenantId), any(), eq(BookingRequestStatus.PENDING));
        verify(bookingRequestRepository, times(1))
                .countByParkingSpotIdIn(eq(tenantId), any(), eq(BookingRequestStatus.APPROVED));
        verify(spotScopeRepository, times(1)).findByParkingSpotIdIn(any());
        verify(bookingService, never()).countPendingForSpot(any());
        verify(bookingService, never()).spotHeld(any());
        verify(facilityService, never()).parkingSpotBuildingIds(any());
    }

    @Test
    void list_emptyPage_skipsBatchQueries() {
        when(facilityService.listParkingSpots(eq(tenantId), eq(propertyId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<Page<ParkingSpotDTO>> response =
                controller.list(propertyId, PageRequest.of(0, 20));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getContent()).isEmpty();
        verify(bookingRequestRepository, never()).countByParkingSpotIdIn(any(), any(), any());
        verify(spotScopeRepository, never()).findByParkingSpotIdIn(any());
    }

    @Test
    void create_returns201WithHeldFlag() {
        ParkingSpot s = spot("B1-07");
        when(facilityService.createParkingSpot(eq(tenantId), any())).thenReturn(s);
        when(facilityService.parkingSpotBuildingIds(s.getId())).thenReturn(List.of());
        when(bookingService.countPendingForSpot(s.getId())).thenReturn(1L);
        when(bookingService.spotHeld(s.getId())).thenReturn(true);

        ResponseEntity<ParkingSpotDTO> response = controller.create(
                new ParkingSpotCreateRequest(propertyId, "B1-07", "B1", null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().held()).isTrue();
        assertThat(response.getBody().pendingCount()).isEqualTo(1L);
    }

    @Test
    void create_pmWithoutAssignment_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.create(
                new ParkingSpotCreateRequest(propertyId, "B1-07", "B1", null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void bulk_returns201WithAllSpots() {
        List<ParkingSpot> created = List.of(spot("B1-01"), spot("B1-02"));
        when(facilityService.bulkCreateParkingSpots(eq(tenantId), any())).thenReturn(created);
        // Batch stubs, not per-row: bulkCreate must map through the same
        // countByParkingSpotIdIn/findByParkingSpotIdIn helpers as list(), never
        // per-spot FacilityService/BookingService lookups (up to 1500 queries on a
        // 500-spot bulk otherwise).
        when(bookingRequestRepository.countByParkingSpotIdIn(eq(tenantId), any(), eq(BookingRequestStatus.PENDING)))
                .thenReturn(List.of());
        when(bookingRequestRepository.countByParkingSpotIdIn(eq(tenantId), any(), eq(BookingRequestStatus.APPROVED)))
                .thenReturn(List.of());
        when(spotScopeRepository.findByParkingSpotIdIn(any())).thenReturn(List.of());

        ResponseEntity<List<ParkingSpotDTO>> response = controller.bulkCreate(
                new ParkingSpotBulkCreateRequest(propertyId, List.of("B1-01", "B1-02"), null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody()).allSatisfy(dto -> {
            assertThat(dto.pendingCount()).isEqualTo(0L);
            assertThat(dto.held()).isFalse();
        });

        verify(bookingRequestRepository, times(1))
                .countByParkingSpotIdIn(eq(tenantId), any(), eq(BookingRequestStatus.PENDING));
        verify(bookingRequestRepository, times(1))
                .countByParkingSpotIdIn(eq(tenantId), any(), eq(BookingRequestStatus.APPROVED));
        verify(spotScopeRepository, times(1)).findByParkingSpotIdIn(any());
        verify(bookingService, never()).countPendingForSpot(any());
        verify(bookingService, never()).spotHeld(any());
        verify(facilityService, never()).parkingSpotBuildingIds(any());
    }

    @Test
    void bulk_pmWithoutAssignment_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.bulkCreate(
                new ParkingSpotBulkCreateRequest(propertyId, List.of("B1-01", "B1-02"), null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void bulk_pmWithAssignment_allowed() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(true);
        List<ParkingSpot> created = List.of(spot("B1-01"), spot("B1-02"));
        when(facilityService.bulkCreateParkingSpots(eq(tenantId), any())).thenReturn(created);
        when(bookingRequestRepository.countByParkingSpotIdIn(eq(tenantId), any(), any(BookingRequestStatus.class)))
                .thenReturn(List.of());
        when(spotScopeRepository.findByParkingSpotIdIn(any())).thenReturn(List.of());

        ResponseEntity<List<ParkingSpotDTO>> response = controller.bulkCreate(
                new ParkingSpotBulkCreateRequest(propertyId, List.of("B1-01", "B1-02"), null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void update_pmWithoutAssignmentOnSpotProperty_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        ParkingSpot s = spot("B1-07");
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.update(s.getId(),
                new ParkingSpotUpdateRequest(null, null, null, false, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void update_returns200() {
        ParkingSpot s = spot("B1-07");
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.updateParkingSpot(eq(tenantId), eq(s.getId()), any())).thenReturn(s);
        when(facilityService.parkingSpotBuildingIds(s.getId())).thenReturn(List.of());
        when(bookingService.countPendingForSpot(s.getId())).thenReturn(0L);
        when(bookingService.spotHeld(s.getId())).thenReturn(false);

        ResponseEntity<ParkingSpotDTO> response = controller.update(s.getId(),
                new ParkingSpotUpdateRequest("B1-08", null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void delete_returns204AndDelegates() {
        ParkingSpot s = spot("B1-07");
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);

        ResponseEntity<Void> response = controller.deactivate(s.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(facilityService).deactivateParkingSpot(tenantId, s.getId());
    }

    @Test
    void delete_pmWithoutAssignment_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        ParkingSpot s = spot("B1-07");
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.deactivate(s.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
