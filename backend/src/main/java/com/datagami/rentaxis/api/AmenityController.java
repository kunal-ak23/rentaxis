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
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin inventory surface for property amenities. RBAC lives here, not in
 * {@link FacilityService} — same split as the gate-pass module. PROPERTY_MANAGER
 * callers are additionally checked against their property assignments.
 */
@RestController
@RequestMapping("/api/v1/amenities")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
public class AmenityController {

    private final FacilityService facilityService;
    private final BookingService bookingService;
    private final BookingRequestRepository bookingRequestRepository;
    private final AmenityBuildingScopeRepository amenityScopeRepository;
    private final UserPropertyAssignmentRepository assignmentRepository;

    public AmenityController(FacilityService facilityService,
                             BookingService bookingService,
                             BookingRequestRepository bookingRequestRepository,
                             AmenityBuildingScopeRepository amenityScopeRepository,
                             UserPropertyAssignmentRepository assignmentRepository) {
        this.facilityService = facilityService;
        this.bookingService = bookingService;
        this.bookingRequestRepository = bookingRequestRepository;
        this.amenityScopeRepository = amenityScopeRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping
    public ResponseEntity<Page<AmenityDTO>> list(
            @RequestParam(required = false) UUID propertyId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        checkPropertyManagerAccess(propertyId);
        UUID tenantId = TenantContextHolder.getTenantId();
        Page<PropertyAmenity> page = facilityService.listAmenities(tenantId, propertyId, pageable);

        List<UUID> ids = page.getContent().stream().map(PropertyAmenity::getId).toList();
        Map<UUID, Long> pendingCounts = batchPendingCounts(tenantId, ids);
        Map<UUID, List<UUID>> buildingIdsByAmenity = batchBuildingIds(ids);

        return ResponseEntity.ok(page.map(a -> toDTO(a,
                buildingIdsByAmenity.getOrDefault(a.getId(), List.of()),
                pendingCounts.getOrDefault(a.getId(), 0L))));
    }

    @PostMapping
    public ResponseEntity<AmenityDTO> create(@Valid @RequestBody AmenityCreateRequest req) {
        checkPropertyManagerAccess(req.propertyId());
        UUID tenantId = TenantContextHolder.getTenantId();
        PropertyAmenity created = facilityService.createAmenity(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AmenityDTO> update(@PathVariable UUID id,
                                             @Valid @RequestBody AmenityUpdateRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        checkPropertyManagerAccess(facilityService.getAmenity(tenantId, id).getPropertyId());
        return ResponseEntity.ok(toDTO(facilityService.updateAmenity(tenantId, id, req)));
    }

    /** Soft-deactivate — existing booking requests are untouched. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        UUID tenantId = TenantContextHolder.getTenantId();
        checkPropertyManagerAccess(facilityService.getAmenity(tenantId, id).getPropertyId());
        facilityService.deactivateAmenity(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * For PROPERTY_MANAGER callers, verifies the property is assigned to them
     * (UnitListingController.checkPropertyManagerAccess pattern). SUPER_ADMIN
     * and TENANT_ADMIN are unrestricted. A PM must always name a property.
     */
    private void checkPropertyManagerAccess(UUID propertyId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isPm = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PROPERTY_MANAGER"));
        if (!isPm) return;

        if (propertyId == null) {
            throw new AccessDeniedException("propertyId is required for property managers");
        }
        UUID userId = UUID.fromString(auth.getName());
        if (!assignmentRepository.existsByUserIdAndPropertyId(userId, propertyId)) {
            throw new AccessDeniedException("You are not assigned to this property");
        }
    }

    /**
     * Two queries total regardless of page size, not one per row. Guarded for the
     * empty-page case so a filtered list with no results skips both round-trips.
     */
    private Map<UUID, Long> batchPendingCounts(UUID tenantId, List<UUID> amenityIds) {
        if (amenityIds.isEmpty()) {
            return Map.of();
        }
        return bookingRequestRepository.countByAmenityIdIn(tenantId, amenityIds, BookingRequestStatus.PENDING)
                .stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    private Map<UUID, List<UUID>> batchBuildingIds(List<UUID> amenityIds) {
        if (amenityIds.isEmpty()) {
            return Map.of();
        }
        return amenityScopeRepository.findByAmenityIdIn(amenityIds).stream()
                .collect(Collectors.groupingBy(AmenityBuildingScope::getAmenityId,
                        Collectors.mapping(AmenityBuildingScope::getBuildingId, Collectors.toList())));
    }

    /** Single-resource mapping for create/update/delete responses — batch mapping is used for list(). */
    private AmenityDTO toDTO(PropertyAmenity a) {
        return toDTO(a, facilityService.amenityBuildingIds(a.getId()), bookingService.countPendingForAmenity(a.getId()));
    }

    private AmenityDTO toDTO(PropertyAmenity a, List<UUID> buildingIds, long pendingCount) {
        return new AmenityDTO(a.getId(), a.getPropertyId(), a.getNameEn(), a.getNameAr(),
                a.getDescription(), a.isBookable(), a.isActive(),
                buildingIds, pendingCount, a.getCreatedAt(), a.getUpdatedAt());
    }
}
