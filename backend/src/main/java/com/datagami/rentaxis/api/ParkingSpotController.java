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

/** Admin inventory surface for parking spots. Same RBAC split as AmenityController. */
@RestController
@RequestMapping("/api/v1/parking-spots")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
public class ParkingSpotController {

    private final FacilityService facilityService;
    private final BookingService bookingService;
    private final BookingRequestRepository bookingRequestRepository;
    private final ParkingSpotBuildingScopeRepository spotScopeRepository;
    private final UserPropertyAssignmentRepository assignmentRepository;

    public ParkingSpotController(FacilityService facilityService,
                                 BookingService bookingService,
                                 BookingRequestRepository bookingRequestRepository,
                                 ParkingSpotBuildingScopeRepository spotScopeRepository,
                                 UserPropertyAssignmentRepository assignmentRepository) {
        this.facilityService = facilityService;
        this.bookingService = bookingService;
        this.bookingRequestRepository = bookingRequestRepository;
        this.spotScopeRepository = spotScopeRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping
    public ResponseEntity<Page<ParkingSpotDTO>> list(
            @RequestParam(required = false) UUID propertyId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        checkPropertyManagerAccess(propertyId);
        UUID tenantId = TenantContextHolder.getTenantId();
        Page<ParkingSpot> page = facilityService.listParkingSpots(tenantId, propertyId, pageable);

        List<UUID> ids = page.getContent().stream().map(ParkingSpot::getId).toList();
        Map<UUID, Long> pendingCounts = batchCounts(tenantId, ids, BookingRequestStatus.PENDING);
        Map<UUID, Long> approvedCounts = batchCounts(tenantId, ids, BookingRequestStatus.APPROVED);
        Map<UUID, List<UUID>> buildingIdsBySpot = batchBuildingIds(ids);

        return ResponseEntity.ok(page.map(s -> toDTO(s,
                buildingIdsBySpot.getOrDefault(s.getId(), List.of()),
                approvedCounts.getOrDefault(s.getId(), 0L) > 0,
                pendingCounts.getOrDefault(s.getId(), 0L))));
    }

    @PostMapping
    public ResponseEntity<ParkingSpotDTO> create(@Valid @RequestBody ParkingSpotCreateRequest req) {
        checkPropertyManagerAccess(req.propertyId());
        UUID tenantId = TenantContextHolder.getTenantId();
        ParkingSpot created = facilityService.createParkingSpot(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(created));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<ParkingSpotDTO>> bulkCreate(@Valid @RequestBody ParkingSpotBulkCreateRequest req) {
        checkPropertyManagerAccess(req.propertyId());
        UUID tenantId = TenantContextHolder.getTenantId();
        List<ParkingSpot> created = facilityService.bulkCreateParkingSpots(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(created.stream().map(this::toDTO).toList());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ParkingSpotDTO> update(@PathVariable UUID id,
                                                 @Valid @RequestBody ParkingSpotUpdateRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        checkPropertyManagerAccess(facilityService.getParkingSpot(tenantId, id).getPropertyId());
        return ResponseEntity.ok(toDTO(facilityService.updateParkingSpot(tenantId, id, req)));
    }

    /** Soft-deactivate — existing booking requests are untouched. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        UUID tenantId = TenantContextHolder.getTenantId();
        checkPropertyManagerAccess(facilityService.getParkingSpot(tenantId, id).getPropertyId());
        facilityService.deactivateParkingSpot(tenantId, id);
        return ResponseEntity.noContent().build();
    }

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
     * One query per status regardless of page size, not one per row. Guarded for
     * the empty-page case so a filtered list with no results skips the round-trip.
     */
    private Map<UUID, Long> batchCounts(UUID tenantId, List<UUID> spotIds, BookingRequestStatus status) {
        if (spotIds.isEmpty()) {
            return Map.of();
        }
        return bookingRequestRepository.countByParkingSpotIdIn(tenantId, spotIds, status).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
    }

    private Map<UUID, List<UUID>> batchBuildingIds(List<UUID> spotIds) {
        if (spotIds.isEmpty()) {
            return Map.of();
        }
        return spotScopeRepository.findByParkingSpotIdIn(spotIds).stream()
                .collect(Collectors.groupingBy(ParkingSpotBuildingScope::getParkingSpotId,
                        Collectors.mapping(ParkingSpotBuildingScope::getBuildingId, Collectors.toList())));
    }

    /** Single-resource mapping for create/update/delete/bulk responses — batch mapping is used for list(). */
    private ParkingSpotDTO toDTO(ParkingSpot s) {
        return toDTO(s, facilityService.parkingSpotBuildingIds(s.getId()),
                bookingService.spotHeld(s.getId()), bookingService.countPendingForSpot(s.getId()));
    }

    private ParkingSpotDTO toDTO(ParkingSpot s, List<UUID> buildingIds, boolean held, long pendingCount) {
        return new ParkingSpotDTO(s.getId(), s.getPropertyId(), s.getSpotNumber(), s.getLevel(),
                s.isCovered(), s.isActive(), buildingIds, held, pendingCount,
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
