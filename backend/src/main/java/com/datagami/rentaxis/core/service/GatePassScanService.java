package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Redemption of {@link GatePass}es at the gate: the guard scans a QR token or
 * keys the numeric code, and this service decides ALLOWED / REJECTED.
 *
 * <p>Unlike {@link GatePassService}, a failed scan is a normal outcome rather
 * than an exception — the guard's app must render a reason, and the SOW
 * requires every rejection to be audited. So {@link #scan} returns a
 * {@link ScanOutcome} and writes a {@code gate_pass_scans} row for every
 * decision, allowed or not. The single exception is an unresolvable code:
 * {@code gate_pass_scans.gate_pass_id} is a NOT NULL FK, so there is nothing
 * to point the row at.
 *
 * <p><b>Concurrency.</b> Two guards scanning the same SINGLE_USE pass at once
 * would otherwise both read ACTIVE and both allow entry (a lost update). The
 * pass is therefore loaded under a {@code PESSIMISTIC_WRITE} lock taken by the
 * resolving query itself ({@code find*ForUpdate}), which serializes concurrent
 * scans of the same pass so it is consumed exactly once. A pessimistic lock is
 * chosen over an {@code @Version} column because the latter needs a schema
 * change, and because scans are low-volume and the locked section is short.
 * The lock must be acquired by the query that first loads the entity — an
 * unlocked read followed by a locking re-read would hand back the stale
 * instance already managed in the persistence context.
 *
 * <p>Guard RBAC ({@code @PreAuthorize}) and scan rate-limiting are the
 * controller's job (Task 7). Enforced here: tenant isolation, guard-to-property
 * assignment, status, validity window, single-use consumption, and the
 * entry-before-exit rule.
 */
@Service
public class GatePassScanService {

    /**
     * Statuses a numeric code may resolve to. USED is included so a guest with a
     * consumed SINGLE_USE pass gets "already used" (and can still scan out) rather
     * than a misleading "not found". EXPIRED / CANCELLED passes release their code
     * back to the pool, so resolving them by code could hit another live pass.
     */
    private static final EnumSet<GatePassStatus> SCANNABLE =
            EnumSet.of(GatePassStatus.PENDING_APPROVAL, GatePassStatus.ACTIVE, GatePassStatus.USED);

    private final GatePassRepository gatePassRepository;
    private final GatePassScanRepository gatePassScanRepository;
    private final GuardPropertyAssignmentRepository guardPropertyAssignmentRepository;
    private final NotificationService notificationService;

    public GatePassScanService(GatePassRepository gatePassRepository,
                               GatePassScanRepository gatePassScanRepository,
                               GuardPropertyAssignmentRepository guardPropertyAssignmentRepository,
                               NotificationService notificationService) {
        this.gatePassRepository = gatePassRepository;
        this.gatePassScanRepository = gatePassScanRepository;
        this.guardPropertyAssignmentRepository = guardPropertyAssignmentRepository;
        this.notificationService = notificationService;
    }

    /** The gate's verdict, plus the pass it was reached on (null when the code resolved to nothing). */
    public record ScanOutcome(ScanResult result, String reason, GatePass pass) {}

    @Transactional
    public ScanOutcome scan(UUID tenantId, UUID guardUserId, String qrToken, String numericCode, ScanDirection direction) {
        Optional<GatePass> found = qrToken != null
                ? gatePassRepository.findByQrTokenForUpdate(qrToken)
                : gatePassRepository.findByNumericCodeForUpdate(tenantId, numericCode, SCANNABLE);

        // Defense in depth: the qr_token lookup is global (tokens are unique across
        // tenants), so isolation is enforced here rather than by the query. Reported as
        // "not found" so a guard cannot probe another tenant's passes for existence.
        GatePass pass = found.filter(p -> tenantId.equals(p.getTenantId())).orElse(null);
        if (pass == null) {
            return new ScanOutcome(ScanResult.REJECTED, "not found", null);
        }

        Set<UUID> assignedProperties = guardPropertyAssignmentRepository.findByUserId(guardUserId).stream()
                .map(GuardPropertyAssignment::getPropertyId)
                .collect(Collectors.toSet());
        if (!assignedProperties.contains(pass.getPropertyId())) {
            return reject(tenantId, guardUserId, pass, direction, "not authorized for this property");
        }

        if (direction == ScanDirection.EXIT) {
            // Exit deliberately skips the entry gauntlet — status and window are about
            // admission. Whoever is inside must be able to leave; we only insist the
            // entry actually happened, so the log cannot record an exit without one.
            if (!gatePassScanRepository.existsByGatePassIdAndDirectionAndResult(
                    pass.getId(), ScanDirection.ENTRY, ScanResult.ALLOWED)) {
                return reject(tenantId, guardUserId, pass, direction, "no entry recorded");
            }
            record(tenantId, guardUserId, pass, ScanDirection.EXIT, ScanResult.ALLOWED, null);
            return new ScanOutcome(ScanResult.ALLOWED, null, pass);
        }

        String statusReason = switch (pass.getStatus()) {
            case PENDING_APPROVAL -> "pending approval";
            case CANCELLED -> "cancelled";
            case EXPIRED -> "expired";
            case USED -> "already used";
            case ACTIVE -> null;
        };
        if (statusReason != null) {
            return reject(tenantId, guardUserId, pass, direction, statusReason);
        }

        Instant now = Instant.now();
        if (now.isBefore(pass.getValidFrom())) {
            // Early, not expired — the pass must stay usable once the window opens.
            return reject(tenantId, guardUserId, pass, direction, "outside validity window");
        }
        if (now.isAfter(pass.getValidTo())) {
            pass.setStatus(GatePassStatus.EXPIRED);
            gatePassRepository.save(pass);
            return reject(tenantId, guardUserId, pass, direction, "outside validity window");
        }

        if (pass.getPassType() == GatePassType.SINGLE_USE) {
            pass.setStatus(GatePassStatus.USED);
            gatePassRepository.save(pass);
        }
        // RECURRING passes stay ACTIVE — they are admission rights for the whole window.

        record(tenantId, guardUserId, pass, ScanDirection.ENTRY, ScanResult.ALLOWED, null);

        // notifyInApp, not notify: NotificationService.mapLegacyType has no GATE_PASS_*
        // entry, so notify() would publish no EmailEvent anyway. Say what we mean.
        notificationService.notifyInApp(tenantId, pass.getCreatedByUserId(), "GATE_PASS_ARRIVAL",
                "Your guest has arrived", pass.getGuestName() + " was scanned in at the gate",
                "GATE_PASS", pass.getId());

        return new ScanOutcome(ScanResult.ALLOWED, null, pass);
    }

    private ScanOutcome reject(UUID tenantId, UUID guardUserId, GatePass pass, ScanDirection direction, String reason) {
        record(tenantId, guardUserId, pass, direction, ScanResult.REJECTED, reason);
        return new ScanOutcome(ScanResult.REJECTED, reason, pass);
    }

    private void record(UUID tenantId, UUID guardUserId, GatePass pass, ScanDirection direction,
                        ScanResult result, String rejectionReason) {
        GatePassScan scan = new GatePassScan();
        scan.setTenantId(tenantId);
        scan.setGatePassId(pass.getId());
        scan.setDirection(direction);
        scan.setScannedByUserId(guardUserId);
        scan.setScannedAt(Instant.now());
        scan.setResult(result);
        scan.setRejectionReason(rejectionReason);
        gatePassScanRepository.save(scan);
    }
}
