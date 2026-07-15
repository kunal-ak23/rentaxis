package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.GatePassDtos.ApprovalDecision;
import com.datagami.rentaxis.api.dto.GatePassDtos.CreateGatePassRequest;
import com.datagami.rentaxis.api.dto.GatePassDtos.GatePassReportRow;
import com.datagami.rentaxis.api.dto.GatePassDtos.GatePassResponse;
import com.datagami.rentaxis.api.dto.GatePassDtos.GatePassSummary;
import com.datagami.rentaxis.api.dto.GatePassDtos.ScanRequest;
import com.datagami.rentaxis.api.dto.GatePassDtos.ScanResponse;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.GatePassScanService;
import com.datagami.rentaxis.core.service.GatePassScanService.ScanOutcome;
import com.datagami.rentaxis.core.service.GatePassService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * HTTP surface for the gate-pass module.
 *
 * <p><b>This class is where gate-pass RBAC lives.</b> {@code GatePassService} and
 * {@code GatePassScanService} deliberately implement no role checks — they enforce
 * tenant isolation, status transitions, guard-to-property assignment and the
 * creator-only rule on cancel, and leave "who may call this" to the annotations
 * here. Two authorization checks exist nowhere else and must not be removed:
 *
 * <ul>
 *   <li>{@link #requireUnitOnActiveLease} — a renter may only raise a pass for a
 *       unit on one of their own ACTIVE leases. {@code GatePassService.create}
 *       trusts its {@code unitId}/{@code propertyId} arguments completely, so
 *       without this a renter could issue a working pass into any unit in the
 *       tenant.</li>
 *   <li>{@link #scan} — exactly one of qrToken/numericCode. The scan service reads
 *       {@code qrToken != null ? QR : NUMERIC}, so both-null resolves nothing and
 *       reports a misleading "not found" rather than the client error it is.</li>
 * </ul>
 *
 * <p>The credential split is equally load-bearing: {@link GatePassResponse} carries
 * {@code qrToken}/{@code numericCode} and is returned only to a pass's creator;
 * every other audience gets {@link GatePassSummary}. See {@code GatePassDtos}.
 *
 * <p>Rate limiting for {@code POST /scan} is in {@code PublicRateLimitFilter}, not
 * here — it must run ahead of authentication.
 */
@RestController
@RequestMapping("/api/v1/gatepass")
public class GatePassController {

    private static final ZoneId UAE_ZONE = ZoneId.of("Asia/Dubai");

    private static final String GUARD_AUTHORITY = "ROLE_SECURITY_GUARD";

    private final GatePassService gatePassService;
    private final GatePassScanService gatePassScanService;
    private final GatePassRepository gatePassRepository;
    private final GatePassScanRepository gatePassScanRepository;
    private final GuardPropertyAssignmentRepository guardPropertyAssignmentRepository;
    private final UnitRepository unitRepository;
    private final PropertyRepository propertyRepository;
    private final RenterRepository renterRepository;
    private final LeaseRepository leaseRepository;
    private final UserRepository userRepository;

    public GatePassController(GatePassService gatePassService,
                              GatePassScanService gatePassScanService,
                              GatePassRepository gatePassRepository,
                              GatePassScanRepository gatePassScanRepository,
                              GuardPropertyAssignmentRepository guardPropertyAssignmentRepository,
                              UnitRepository unitRepository,
                              PropertyRepository propertyRepository,
                              RenterRepository renterRepository,
                              LeaseRepository leaseRepository,
                              UserRepository userRepository) {
        this.gatePassService = gatePassService;
        this.gatePassScanService = gatePassScanService;
        this.gatePassRepository = gatePassRepository;
        this.gatePassScanRepository = gatePassScanRepository;
        this.guardPropertyAssignmentRepository = guardPropertyAssignmentRepository;
        this.unitRepository = unitRepository;
        this.propertyRepository = propertyRepository;
        this.renterRepository = renterRepository;
        this.leaseRepository = leaseRepository;
        this.userRepository = userRepository;
    }

    // ---------------------------------------------------------------- renter

    @PostMapping
    @PreAuthorize("hasRole('RENTER')")
    public GatePassResponse create(@Valid @RequestBody CreateGatePassRequest request) {
        UUID tenantId = tenantId();
        UUID userId = currentUserId();

        Unit unit = requireUnitOnActiveLease(tenantId, userId, request.unitId());
        // propertyId comes from the unit's own row, never from the client: a renter who
        // could name the property could otherwise point a pass at a property they have
        // no lease in, and the guard-assignment check would then admit their guest there.
        UUID propertyId = unit.getProperty().getId();

        GatePass pass = gatePassService.create(tenantId, userId, propertyId, unit.getId(),
                request.guestName(), request.guestPhone(), request.purpose(), request.vehicleNumber(),
                request.passType(), request.validFrom(), request.validTo());
        return toResponse(pass);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('RENTER')")
    public List<GatePassResponse> mine() {
        return gatePassRepository
                .findByTenantIdAndCreatedByUserIdOrderByCreatedAtDesc(tenantId(), currentUserId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('RENTER')")
    public GatePassResponse get(@PathVariable UUID id) {
        return toResponse(requireOwnPass(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('RENTER')")
    public GatePassResponse cancel(@PathVariable UUID id) {
        // The service re-checks creator-only; no need to duplicate it here.
        return toResponse(gatePassService.cancel(tenantId(), id, currentUserId()));
    }

    // ----------------------------------------------------------------- guard

    @PostMapping("/scan")
    @PreAuthorize("hasRole('SECURITY_GUARD')")
    public ScanResponse scan(@RequestBody ScanRequest request) {
        // Blank is not "absent" to the service — it would take the QR branch on ""
        // and resolve nothing — so normalize before deciding.
        String qrToken = trimToNull(request.qrToken());
        String numericCode = trimToNull(request.numericCode());
        if ((qrToken == null) == (numericCode == null)) {
            throw new BusinessRuleViolationException("Provide exactly one of qrToken or numericCode");
        }
        if (request.direction() == null) {
            throw new BusinessRuleViolationException("direction is required");
        }

        ScanOutcome outcome = gatePassScanService.scan(tenantId(), currentUserId(), qrToken, numericCode,
                request.direction());
        return toScanResponse(outcome);
    }

    @GetMapping("/expected-today")
    @PreAuthorize("hasRole('SECURITY_GUARD')")
    public List<GatePassSummary> expectedToday() {
        List<UUID> propertyIds = assignedPropertyIds(currentUserId());
        // An unassigned guard sees nothing. Falling back to "all properties" here would
        // hand a guard with no posting the whole tenant's expected guests.
        if (propertyIds.isEmpty()) {
            return List.of();
        }

        ZonedDateTime dayStart = ZonedDateTime.now(UAE_ZONE).toLocalDate().atStartOfDay(UAE_ZONE);
        List<GatePass> passes = gatePassRepository.findActiveOverlapping(tenantId(), propertyIds,
                GatePassStatus.ACTIVE, dayStart.toInstant(), dayStart.plusDays(1).toInstant());
        return toSummaries(passes);
    }

    // ------------------------------------------------- approvals (shared)

    /**
     * The approvals queue. Same endpoint for guards and managers, different scope:
     * a guard sees only passes awaiting approval at properties they are assigned to,
     * a manager sees the whole tenant's.
     */
    @GetMapping("/approvals")
    @PreAuthorize("hasAnyRole('SECURITY_GUARD','TENANT_ADMIN','PROPERTY_MANAGER')")
    public List<GatePassSummary> approvals() {
        UUID tenantId = tenantId();
        if (!isGuard()) {
            return toSummaries(gatePassRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(
                    tenantId, GatePassStatus.PENDING_APPROVAL));
        }

        List<UUID> propertyIds = assignedPropertyIds(currentUserId());
        if (propertyIds.isEmpty()) {
            return List.of();
        }
        return toSummaries(gatePassRepository.findByTenantIdAndStatusAndPropertyIdInOrderByCreatedAtDesc(
                tenantId, GatePassStatus.PENDING_APPROVAL, propertyIds));
    }

    @PostMapping("/{id}/approval")
    @PreAuthorize("hasAnyRole('SECURITY_GUARD','TENANT_ADMIN','PROPERTY_MANAGER')")
    public GatePassSummary decideApproval(@PathVariable UUID id, @RequestBody ApprovalDecision decision) {
        UUID tenantId = tenantId();
        if (isGuard()) {
            // Mirror the read scope: a guard may only decide passes they can see. Without
            // this a guard could approve a pass for any property in the tenant by id.
            GatePass pass = gatePassRepository.findById(id)
                    .orElseThrow(() -> new NotFoundException("Gate pass not found"));
            if (!tenantId.equals(pass.getTenantId())
                    || !assignedPropertyIds(currentUserId()).contains(pass.getPropertyId())) {
                // 404, not 403 — a guard must not be able to probe for passes at
                // properties they are not posted to.
                throw new NotFoundException("Gate pass not found");
            }
        }
        return toSummary(gatePassService.approve(tenantId, id, currentUserId(), decision.approved()));
    }

    // --------------------------------------------------------------- manager

    /**
     * Gate traffic over a window, one row per scan joined to its pass — the web client
     * turns this into a CSV.
     */
    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER')")
    public List<GatePassReportRow> report(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID propertyId) {

        if (to.isBefore(from)) {
            throw new BusinessRuleViolationException("'to' must not be before 'from'");
        }

        List<GatePassScan> scans = gatePassScanRepository.findByTenantIdAndScannedAtBetween(tenantId(), from, to);
        if (scans.isEmpty()) {
            return List.of();
        }

        // Batch the two joins rather than resolving per row — a month of gate traffic is
        // thousands of scans, and per-row lookups would be an N+1 on both tables.
        Map<UUID, GatePass> passes = gatePassRepository.findAllById(
                        scans.stream().map(GatePassScan::getGatePassId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(GatePass::getId, p -> p));
        Map<UUID, String> unitNumbers = unitNumbers(passes.values().stream().map(GatePass::getUnitId).toList());

        List<GatePassReportRow> rows = new ArrayList<>();
        for (GatePassScan scan : scans) {
            GatePass pass = passes.get(scan.getGatePassId());
            if (pass == null) {
                continue; // pass outside the tenant filter's reach — skip rather than leak a null row
            }
            if (propertyId != null && !propertyId.equals(pass.getPropertyId())) {
                continue;
            }
            rows.add(new GatePassReportRow(scan.getId(), scan.getScannedAt(), scan.getDirection(), scan.getResult(),
                    scan.getRejectionReason(), scan.getScannedByUserId(), pass.getId(), pass.getPropertyId(),
                    unitNumbers.get(pass.getUnitId()), pass.getGuestName(), pass.getGuestPhone(),
                    pass.getVehicleNumber(), pass.getPurpose(), pass.getPassType()));
        }
        rows.sort(Comparator.comparing(GatePassReportRow::scannedAt));
        return rows;
    }

    @GetMapping("/guards/{userId}/properties")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER')")
    public List<UUID> guardProperties(@PathVariable UUID userId) {
        requireGuardInTenant(tenantId(), userId);
        return assignedPropertyIds(userId);
    }

    /**
     * Replace-all: the submitted list becomes the guard's complete posting.
     *
     * <p>Note this does NOT create the guard's {@code user_tenant_memberships} row.
     * Provisioning a guard without one leaves {@code AuthResponse.tenantIds} empty and
     * the guard app with no tenant to send, so the membership must be created where the
     * guard user is created — creating it as a side effect of a property assignment
     * would hide that requirement and let a guard exist in a half-provisioned state
     * whenever this endpoint happens not to be called.
     */
    @PutMapping("/guards/{userId}/properties")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER')")
    @Transactional
    public List<UUID> setGuardProperties(@PathVariable UUID userId, @RequestBody List<UUID> propertyIds) {
        UUID tenantId = tenantId();
        requireGuardInTenant(tenantId, userId);

        List<UUID> requested = propertyIds == null ? List.of() : propertyIds.stream().distinct().toList();
        for (UUID propertyId : requested) {
            // Not the Hibernate filter's job: an explicit tenant-scoped existence check,
            // so a manager cannot post their guard to another tenant's property.
            if (!propertyRepository.existsByIdAndTenantId(propertyId, tenantId)) {
                throw new BusinessRuleViolationException("Property is not in this tenant: " + propertyId);
            }
        }

        guardPropertyAssignmentRepository.deleteAll(guardPropertyAssignmentRepository.findByUserId(userId));
        // uq_gpa_user_property is checked per-statement, so without a flush the re-insert
        // of a property the guard already had would collide with its own pending delete.
        guardPropertyAssignmentRepository.flush();

        for (UUID propertyId : requested) {
            GuardPropertyAssignment assignment = new GuardPropertyAssignment();
            assignment.setTenantId(tenantId);
            assignment.setUserId(userId);
            assignment.setPropertyId(propertyId);
            guardPropertyAssignmentRepository.save(assignment);
        }
        return requested;
    }

    // -------------------------------------------------------------- helpers

    /**
     * The renter-side authorization check for {@code POST /gatepass}: the caller must
     * hold an ACTIVE lease on the unit they are raising a pass for. Rejects with 404
     * rather than 403 so a renter cannot use the status code to discover which unit
     * ids exist.
     */
    private Unit requireUnitOnActiveLease(UUID tenantId, UUID userId, UUID unitId) {
        if (unitId == null) {
            throw new BusinessRuleViolationException("unitId is required");
        }
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        if (!tenantId.equals(renter.getTenantId())) {
            throw new NotFoundException("No renter profile linked to this user");
        }

        Lease lease = leaseRepository.findByUnitIdAndStatus(unitId, LeaseStatus.ACTIVE).stream()
                .filter(l -> tenantId.equals(l.getTenantId()))
                .filter(l -> l.getRenter().getId().equals(renter.getId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Unit is not on an active lease of yours"));
        return lease.getUnit();
    }

    /** Creator-only read. 404 on a foreign pass so a renter cannot probe for pass ids. */
    private GatePass requireOwnPass(UUID id) {
        GatePass pass = gatePassRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Gate pass not found"));
        if (!tenantId().equals(pass.getTenantId()) || !currentUserId().equals(pass.getCreatedByUserId())) {
            throw new NotFoundException("Gate pass not found");
        }
        return pass;
    }

    private User requireGuardInTenant(UUID tenantId, UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        // The tenant filter already hides other tenants' users; belt and braces, and it
        // keeps the rule readable at the point it matters.
        if (!tenantId.equals(user.getTenantId())) {
            throw new NotFoundException("User not found");
        }
        if (user.getRole() != UserRole.SECURITY_GUARD) {
            throw new BusinessRuleViolationException("User is not a security guard");
        }
        return user;
    }

    private List<UUID> assignedPropertyIds(UUID userId) {
        return guardPropertyAssignmentRepository.findByUserId(userId).stream()
                .map(GuardPropertyAssignment::getPropertyId)
                .distinct()
                .toList();
    }

    private boolean isGuard() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(GUARD_AUTHORITY::equals);
    }

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    private UUID tenantId() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new BusinessRuleViolationException("No tenant context on this request");
        }
        return tenantId;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Batch unit-number lookup for display; missing units simply have no number. */
    private Map<UUID, String> unitNumbers(List<UUID> unitIds) {
        List<UUID> distinct = unitIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> byId = new HashMap<>();
        for (Unit unit : unitRepository.findAllById(distinct)) {
            byId.put(unit.getId(), unit.getUnitNumber());
        }
        return byId;
    }

    private GatePassResponse toResponse(GatePass pass) {
        return new GatePassResponse(pass.getId(), pass.getPropertyId(), pass.getUnitId(), pass.getGuestName(),
                pass.getGuestPhone(), pass.getPurpose(), pass.getVehicleNumber(), pass.getPassType(),
                pass.getValidFrom(), pass.getValidTo(), pass.getStatus(), pass.getQrToken(), pass.getNumericCode(),
                pass.getCreatedAt());
    }

    private List<GatePassSummary> toSummaries(List<GatePass> passes) {
        Map<UUID, String> unitNumbers = unitNumbers(passes.stream().map(GatePass::getUnitId).toList());
        return passes.stream().map(p -> toSummary(p, unitNumbers)).toList();
    }

    private GatePassSummary toSummary(GatePass pass, Map<UUID, String> unitNumbers) {
        // A miss means the unit row is gone or filtered out; the display name is simply
        // absent. Re-querying here would only turn a miss into an N+1 for the same null.
        String unitNumber = unitNumbers.get(pass.getUnitId());
        return new GatePassSummary(pass.getId(), pass.getPropertyId(), pass.getUnitId(), unitNumber,
                pass.getGuestName(), pass.getGuestPhone(), pass.getPurpose(), pass.getVehicleNumber(),
                pass.getPassType(), pass.getValidFrom(), pass.getValidTo(), pass.getStatus(), pass.getCreatedAt());
    }

    /** Single-pass variant, for the paths that map exactly one pass. */
    private GatePassSummary toSummary(GatePass pass) {
        return toSummary(pass, unitNumbers(List.of(pass.getUnitId())));
    }

    /**
     * Renders the verdict. A null pass means the scan service has decided this caller
     * gets no guest details — either because nothing resolved, or because the guard is
     * not posted to the pass's property (see {@code GatePassScanService.scan}). This
     * method must keep branching on {@code pass == null} and never on
     * {@code outcome.result()}: most REJECTED outcomes are at the guard's own gate and
     * do carry the guest, so blinding on "rejected" would be both wrong and, in the
     * other direction, a leak waiting for a new null-pass case to be added.
     *
     * <p>The {@code reason} does still separate "not found" from "not authorized for
     * this property", which is a mild existence oracle: a guard learns a code is live
     * somewhere in the tenant. That is accepted deliberately — the guard has to be told
     * whether to send the guest to another gate or turn them away — and it is bounded to
     * one bit. It reveals no guest, and the scan rate limit bounds how fast it can be
     * asked. Do not collapse the two reasons into one without also revisiting that
     * trade-off in {@code PublicRateLimitFilter.createScanBucket}.
     */
    private ScanResponse toScanResponse(ScanOutcome outcome) {
        GatePass pass = outcome.pass();
        if (pass == null) {
            return new ScanResponse(outcome.result(), outcome.reason(), null, null, null, null, null, null, null,
                    null);
        }
        String unitNumber = unitRepository.findById(pass.getUnitId()).map(Unit::getUnitNumber).orElse(null);
        return new ScanResponse(outcome.result(), outcome.reason(), pass.getGuestName(), pass.getGuestPhone(),
                pass.getVehicleNumber(), pass.getPurpose(), unitNumber, pass.getPassType(), pass.getValidFrom(),
                pass.getValidTo());
    }
}
