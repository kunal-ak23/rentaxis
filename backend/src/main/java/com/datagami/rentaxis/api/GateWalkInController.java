package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.GatePassDtos.ApprovalDecision;
import com.datagami.rentaxis.api.dto.GateWalkInDtos.*;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.GatePassService;
import com.datagami.rentaxis.core.service.GatePassScanService;
import com.datagami.rentaxis.core.service.GateWalkInService;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.GatePassOrigin;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GateVisitorType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/gatepass")
@RequiredArgsConstructor
@Slf4j
public class GateWalkInController {

    private final GateWalkInService walkInService;
    private final GatePassService gatePassService;
    private final GatePassScanService gatePassScanService;
    private final GatePassRepository passRepository;
    private final BlobStorageService blobStorageService;
    private final GateAccessPolicyRepository policyRepository;
    private final GuardPropertyAssignmentRepository assignmentRepository;
    private final PropertyRepository propertyRepository;
    private final BuildingRepository buildingRepository;
    private final UnitRepository unitRepository;
    private final RenterRepository renterRepository;
    private final LeaseRepository leaseRepository;
    private final PropertyScope propertyScope;

    @GetMapping("/walk-in/destinations")
    @PreAuthorize("hasRole('SECURITY_GUARD')")
    @Transactional(readOnly = true)   // maps each unit's building (OSIV is off)
    public List<Destination> destinations(@RequestParam UUID propertyId) {
        requireAssignedProperty(propertyId);
        return unitRepository.findByPropertyId(propertyId).stream()
                .map(unit -> new Destination(unit.getId(), unit.getUnitNumber(), propertyId,
                        unit.getBuilding() == null ? null : unit.getBuilding().getId(),
                        unit.getBuilding() == null ? null : unit.getBuilding().getNameEn()))
                .sorted(Comparator.comparing(Destination::buildingName,
                                Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                        .thenComparing(Destination::unitNumber, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @GetMapping("/walk-in/visitor")
    @PreAuthorize("hasRole('SECURITY_GUARD')")
    public VisitorLookupResponse lookup(@RequestParam UUID propertyId,
                                        @RequestParam String phone,
                                        @RequestParam(required = false) UUID unitId) {
        requireAssignedProperty(propertyId);
        if (unitId != null) requireUnitAtProperty(unitId, propertyId);
        return walkInService.lookup(tenantId(), propertyId, phone, unitId)
                .map(VisitorLookupResponse::of)
                .orElseThrow(() -> new NotFoundException("No previous visitor found"));
    }

    @PostMapping(value = "/walk-in", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('SECURITY_GUARD')")
    public WalkInPass create(@RequestParam UUID propertyId,
                             @RequestParam UUID unitId,
                             @RequestParam String name,
                             @RequestParam String phone,
                             @RequestParam GateVisitorType visitorType,
                             @RequestParam(required = false) String purpose,
                             @RequestParam(required = false) String vehicleNumber,
                             @RequestPart(value = "photo", required = false) MultipartFile photo) {
        requireAssignedProperty(propertyId);
        Unit unit = requireUnitAtProperty(unitId, propertyId);
        GatePass pass = walkInService.create(tenantId(), currentUserId(), unit, name, phone,
                visitorType, purpose, vehicleNumber, photo);
        return WalkInPass.of(pass, unit.getUnitNumber());
    }

    @GetMapping("/walk-in/{id}/status")
    @PreAuthorize("hasRole('SECURITY_GUARD')")
    public WalkInPass status(@PathVariable UUID id) {
        GatePass pass = requireWalkIn(id);
        requireAssignedProperty(pass.getPropertyId());
        String unitNumber = unitRepository.findById(pass.getUnitId())
                .map(Unit::getUnitNumber).orElse(null);
        return WalkInPass.of(pass, unitNumber);
    }

    @GetMapping("/walk-in/{id}/photo")
    @PreAuthorize("hasAnyRole('RENTER','SECURITY_GUARD','SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<byte[]> photo(@PathVariable UUID id) {
        GatePass pass = requireWalkIn(id);
        if (hasRole("RENTER") && !residentUnitIds().contains(pass.getUnitId())) {
            throw new NotFoundException("Visitor photo not found");
        }
        if (hasRole("SECURITY_GUARD")) {
            requireAssignedProperty(pass.getPropertyId());
        }
        // A property manager sees visitor photos of their own buildings only (audit P1-6).
        propertyScope.requireCanAccessProperty(pass.getPropertyId(), "Visitor photo not found");
        if (pass.getGuestPhotoBlobPath() == null) {
            throw new NotFoundException("Visitor photo not found");
        }
        BlobStorageService.DownloadResult download =
                blobStorageService.download(tenantId(), pass.getGuestPhotoBlobPath());
        return ResponseEntity.ok()
                .header("Content-Type", download.contentType())
                .header("Cache-Control", "private, max-age=300")
                .body(download.bytes());
    }

    @PostMapping("/walk-in/{id}/admit")
    @PreAuthorize("hasRole('SECURITY_GUARD')")
    public WalkInPass admit(@PathVariable UUID id) {
        GatePass pass = requireWalkIn(id);
        requireAssignedProperty(pass.getPropertyId());
        GatePassScanService.ScanOutcome outcome = gatePassScanService.scan(
                tenantId(), currentUserId(), pass.getQrToken(), null, ScanDirection.ENTRY);
        if (outcome.result() != ScanResult.ALLOWED) {
            throw new BusinessRuleViolationException(
                    outcome.reason() == null ? "Visitor cannot be admitted" : outcome.reason());
        }
        try {
            walkInService.notifyRegisteredAdmission(tenantId(), outcome.pass());
        } catch (RuntimeException notificationFailure) {
            // Admission is already committed by GatePassScanService. A notification
            // outage must not turn that successful entry into a retry that will be
            // rejected as "already used".
            log.warn("Registered walk-in admitted but resident notification failed for pass {}",
                    pass.getId(), notificationFailure);
        }
        String unitNumber = unitRepository.findById(pass.getUnitId())
                .map(Unit::getUnitNumber).orElse(null);
        return WalkInPass.of(outcome.pass(), unitNumber);
    }

    @GetMapping("/resident-approvals")
    @PreAuthorize("hasRole('RENTER')")
    public List<WalkInPass> residentApprovals() {
        Set<UUID> unitIds = residentUnitIds();
        if (unitIds.isEmpty()) return List.of();
        return passRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(
                        tenantId(), GatePassStatus.PENDING_APPROVAL).stream()
                .filter(pass -> pass.getOrigin() == GatePassOrigin.GUARD_WALK_IN)
                .filter(pass -> unitIds.contains(pass.getUnitId()))
                .filter(pass -> pass.getValidTo().isAfter(Instant.now()))
                .map(pass -> WalkInPass.of(pass, unitRepository.findById(pass.getUnitId())
                        .map(Unit::getUnitNumber).orElse(null)))
                .toList();
    }

    @PostMapping("/resident-approvals/{id}")
    @PreAuthorize("hasRole('RENTER')")
    public WalkInPass decideAsResident(@PathVariable UUID id,
                                       @RequestBody ApprovalDecision decision) {
        GatePass pass = requireWalkIn(id);
        if (!residentUnitIds().contains(pass.getUnitId())) {
            throw new NotFoundException("Visitor request not found");
        }
        GatePass decided = gatePassService.approve(tenantId(), id, currentUserId(), decision.approved());
        String unitNumber = unitRepository.findById(decided.getUnitId())
                .map(Unit::getUnitNumber).orElse(null);
        return WalkInPass.of(decided, unitNumber);
    }

    @GetMapping("/policies/effective")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public PolicyResponse effectivePolicy(@RequestParam UUID propertyId,
                                          @RequestParam(required = false) UUID buildingId) {
        requirePropertyAndBuilding(propertyId, buildingId);
        return PolicyResponse.effective(propertyId, buildingId,
                walkInService.effectivePolicy(tenantId(), propertyId, buildingId));
    }

    @PutMapping("/policies")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    @Transactional
    public PolicyResponse setPolicy(@RequestParam UUID propertyId,
                                    @RequestParam(required = false) UUID buildingId,
                                    @RequestBody PolicyRequest request) {
        requirePropertyAndBuilding(propertyId, buildingId);
        if (request.approvalTimeoutMinutes() < 1 || request.approvalTimeoutMinutes() > 1440) {
            throw new BusinessRuleViolationException("Approval timeout must be between 1 and 1440 minutes");
        }
        GateAccessPolicy policy = buildingId == null
                ? policyRepository.findByTenantIdAndPropertyIdAndBuildingIdIsNull(
                        tenantId(), propertyId).orElseGet(GateAccessPolicy::new)
                : policyRepository.findByTenantIdAndPropertyIdAndBuildingId(
                        tenantId(), propertyId, buildingId).orElseGet(GateAccessPolicy::new);
        policy.setTenantId(tenantId());
        policy.setPropertyId(propertyId);
        policy.setBuildingId(buildingId);
        policy.setRequireUnregisteredApproval(request.requireUnregisteredApproval());
        policy.setRequireRegisteredApproval(request.requireRegisteredApproval());
        policy.setNotifyRegisteredEntry(request.notifyRegisteredEntry());
        policy.setRequireFreshPhoto(request.requireFreshPhoto());
        policy.setApprovalTimeoutMinutes(request.approvalTimeoutMinutes());
        return PolicyResponse.explicit(policyRepository.save(policy));
    }

    @PutMapping("/visitors/{profileId}/registration")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public void registerVisitor(@PathVariable UUID profileId,
                                @RequestBody RegistrationRequest request) {
        Unit unit = unitRepository.findById(request.unitId())
                .filter(u -> tenantId().equals(u.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Unit not found"));
        propertyScope.requireCanAccessUnit(unit, "Unit not found");
        walkInService.registerForUnit(tenantId(), profileId, unit.getId(),
                request.validFrom(), request.validTo(), request.active());
    }

    @PostMapping("/visitors/registration")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public VisitorLookupResponse createRegistration(
            @RequestBody ManagedRegistrationRequest request) {
        if (!propertyRepository.existsByIdAndTenantId(request.propertyId(), tenantId())) {
            throw new NotFoundException("Property not found");
        }
        propertyScope.requireCanAccessProperty(request.propertyId());
        GateVisitorProfile profile = walkInService.upsertRegisteredVisitor(
                tenantId(), request.propertyId(), request.unitId(), request.name(), request.phone(),
                request.visitorType(), request.validFrom(), request.validTo(), request.active());
        return walkInService.lookup(tenantId(), request.propertyId(),
                        profile.getPhoneNormalized(), request.unitId())
                .map(VisitorLookupResponse::of)
                .orElseThrow(() -> new IllegalStateException("Registered visitor could not be reloaded"));
    }

    private GatePass requireWalkIn(UUID id) {
        GatePass pass = passRepository.findById(id)
                .filter(candidate -> tenantId().equals(candidate.getTenantId())
                        && candidate.getOrigin() == GatePassOrigin.GUARD_WALK_IN)
                .orElseThrow(() -> new NotFoundException("Visitor request not found"));
        if (pass.getStatus() == GatePassStatus.PENDING_APPROVAL
                && !pass.getValidTo().isAfter(Instant.now())) {
            pass.setStatus(GatePassStatus.EXPIRED);
            return passRepository.save(pass);
        }
        return pass;
    }

    private Unit requireUnitAtProperty(UUID unitId, UUID propertyId) {
        return unitRepository.findById(unitId)
                .filter(unit -> tenantId().equals(unit.getTenantId())
                        && propertyId.equals(unit.getProperty().getId()))
                .orElseThrow(() -> new NotFoundException("Unit not found at assigned property"));
    }

    private void requireAssignedProperty(UUID propertyId) {
        boolean assigned = assignmentRepository.findByUserId(currentUserId()).stream()
                .anyMatch(row -> tenantId().equals(row.getTenantId())
                        && propertyId.equals(row.getPropertyId()));
        if (!assigned) throw new NotFoundException("Assigned property not found");
    }

    private void requirePropertyAndBuilding(UUID propertyId, UUID buildingId) {
        if (!propertyRepository.existsByIdAndTenantId(propertyId, tenantId())) {
            throw new NotFoundException("Property not found");
        }
        // Gate policy is a building's security setting: a property manager changes
        // (or reads) it only where they manage (audit P1-6).
        propertyScope.requireCanAccessProperty(propertyId);
        if (buildingId != null) {
            Building building = buildingRepository.findById(buildingId)
                    .orElseThrow(() -> new NotFoundException("Tower not found"));
            if (!tenantId().equals(building.getTenantId())
                    || !propertyId.equals(building.getProperty().getId())) {
                throw new NotFoundException("Tower not found");
            }
        }
    }

    private Set<UUID> residentUnitIds() {
        Renter renter = renterRepository.findByUserId(currentUserId())
                .filter(r -> tenantId().equals(r.getTenantId()))
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        Set<UUID> ids = new HashSet<>();
        for (Lease lease : leaseRepository.findByRenterId(renter.getId())) {
            if (lease.getStatus() == LeaseStatus.ACTIVE && tenantId().equals(lease.getTenantId())) {
                ids.add(lease.getUnit().getId());
            }
        }
        return ids;
    }

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    private boolean hasRole(String role) {
        String authority = "ROLE_" + role;
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private UUID tenantId() {
        UUID id = TenantContextHolder.getTenantId();
        if (id == null) throw new BusinessRuleViolationException("No tenant context on this request");
        return id;
    }
}
