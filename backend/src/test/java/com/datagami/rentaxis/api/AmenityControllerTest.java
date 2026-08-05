package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.AmenityDTO;
import com.datagami.rentaxis.api.dto.AmenityUpdateRequest;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.AmenityBuildingScope;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.repository.AmenityBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
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
 * NOTE: departs from the plan's original list_returns200WithMappedDTO test — that
 * version stubbed facilityService.amenityBuildingIds/bookingService.countPendingForAmenity
 * per row, which is exactly the N+1 shape the controller must NOT use for list
 * responses (see AmenityController.list). The list tests here instead stub the batch
 * repositories (BookingRequestRepository.countByAmenityIdIn, AmenityBuildingScopeRepository
 * .findByAmenityIdIn) and assert the singular per-row methods are never invoked.
 */
@ExtendWith(MockitoExtension.class)
class AmenityControllerTest {

    @Mock
    FacilityService facilityService;

    @Mock
    BookingService bookingService;

    @Mock
    BookingRequestRepository bookingRequestRepository;

    @Mock
    AmenityBuildingScopeRepository amenityScopeRepository;

    @Mock
    UserPropertyAssignmentRepository assignmentRepository;

    @InjectMocks
    AmenityController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID pmUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        authenticateAs(adminUserId, "ROLE_TENANT_ADMIN");
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

    private PropertyAmenity amenity() {
        PropertyAmenity a = new PropertyAmenity();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setPropertyId(propertyId);
        a.setNameEn("Gym");
        return a;
    }

    @Test
    void list_returns200WithBatchMappedCounts() {
        PropertyAmenity a1 = amenity();
        PropertyAmenity a2 = amenity();
        // Page 1 of 2, 45 total — deliberately different from the 2-item content list,
        // so a rewrite that rebuilds the page as `new PageImpl<>(dtoList)` (dropping
        // pageable/total) rather than `page.map(...)` (preserving them) fails loudly.
        Pageable requestedPageable = PageRequest.of(1, 2);
        Page<PropertyAmenity> sourcePage = new PageImpl<>(List.of(a1, a2), requestedPageable, 45L);
        when(facilityService.listAmenities(eq(tenantId), eq(propertyId), any(Pageable.class)))
                .thenReturn(sourcePage);
        when(bookingRequestRepository.countByAmenityIdIn(eq(tenantId), any(), eq(BookingRequestStatus.PENDING)))
                .thenReturn(List.of(new Object[]{a1.getId(), 2L}, new Object[]{a2.getId(), 0L}));
        AmenityBuildingScope scope = new AmenityBuildingScope();
        scope.setAmenityId(a1.getId());
        UUID buildingId = UUID.randomUUID();
        scope.setBuildingId(buildingId);
        when(amenityScopeRepository.findByAmenityIdIn(any())).thenReturn(List.of(scope));

        ResponseEntity<Page<AmenityDTO>> response =
                controller.list(propertyId, requestedPageable);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Page<AmenityDTO> body = response.getBody();
        List<AmenityDTO> dtos = body.getContent();
        assertThat(dtos).extracting(AmenityDTO::nameEn).containsExactly("Gym", "Gym");
        assertThat(dtos).extracting(AmenityDTO::pendingCount).containsExactly(2L, 0L);
        assertThat(dtos.get(0).buildingIds()).containsExactly(buildingId);
        assertThat(dtos.get(1).buildingIds()).isEmpty();
        assertThat(body.getTotalElements()).isEqualTo(45L);
        assertThat(body.getNumber()).isEqualTo(1);
        assertThat(body.getSize()).isEqualTo(2);

        // N+1 guard: exactly one batch call for the whole page, never the singular per-row methods.
        verify(bookingRequestRepository, times(1))
                .countByAmenityIdIn(eq(tenantId), any(), eq(BookingRequestStatus.PENDING));
        verify(amenityScopeRepository, times(1)).findByAmenityIdIn(any());
        verify(bookingService, never()).countPendingForAmenity(any());
        verify(facilityService, never()).amenityBuildingIds(any());
    }

    @Test
    void list_emptyPage_skipsBatchQueries() {
        when(facilityService.listAmenities(eq(tenantId), eq(propertyId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<Page<AmenityDTO>> response =
                controller.list(propertyId, PageRequest.of(0, 20));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getContent()).isEmpty();
        verify(bookingRequestRepository, never()).countByAmenityIdIn(any(), any(), any());
        verify(amenityScopeRepository, never()).findByAmenityIdIn(any());
    }

    @Test
    void create_returns201() {
        PropertyAmenity a = amenity();
        when(facilityService.createAmenity(eq(tenantId), any())).thenReturn(a);
        when(facilityService.amenityBuildingIds(a.getId())).thenReturn(List.of());
        when(bookingService.countPendingForAmenity(a.getId())).thenReturn(0L);

        ResponseEntity<AmenityDTO> response = controller.create(
                new AmenityCreateRequest(propertyId, "Gym", null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void create_pmWithoutAssignment_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.create(
                new AmenityCreateRequest(propertyId, "Gym", null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void create_pmWithAssignment_allowed() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(true);
        PropertyAmenity a = amenity();
        when(facilityService.createAmenity(eq(tenantId), any())).thenReturn(a);
        when(facilityService.amenityBuildingIds(a.getId())).thenReturn(List.of());
        when(bookingService.countPendingForAmenity(a.getId())).thenReturn(0L);

        assertThat(controller.create(new AmenityCreateRequest(propertyId, "Gym", null, null, null, null))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void list_pmWithoutPropertyId_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");

        assertThatThrownBy(() -> controller.list(null, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void update_returns200() {
        PropertyAmenity a = amenity();
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.updateAmenity(eq(tenantId), eq(a.getId()), any())).thenReturn(a);
        when(facilityService.amenityBuildingIds(a.getId())).thenReturn(List.of());
        when(bookingService.countPendingForAmenity(a.getId())).thenReturn(0L);

        ResponseEntity<AmenityDTO> response = controller.update(a.getId(),
                new AmenityUpdateRequest("Gym 2", null, null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void update_pmWithoutAssignment_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        PropertyAmenity a = amenity();
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.update(a.getId(),
                new AmenityUpdateRequest("Gym 2", null, null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_returns204AndDelegates() {
        PropertyAmenity a = amenity();
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);

        ResponseEntity<Void> response = controller.deactivate(a.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(facilityService).deactivateAmenity(tenantId, a.getId());
    }

    @Test
    void delete_pmWithoutAssignment_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        PropertyAmenity a = amenity();
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.deactivate(a.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
