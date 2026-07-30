package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.GatePassOrigin;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import com.datagami.rentaxis.domain.repository.GatePassScanRepository;
import com.datagami.rentaxis.domain.repository.GuardPropertyAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
 * decision, allowed or not. The two exceptions are the outcomes that have no
 * pass to point a row at ({@code gate_pass_scans.gate_pass_id} is a NOT NULL
 * FK): an unresolvable code, and a lock conflict. Both are logged at WARN
 * instead, so they are still auditable.
 *
 * <p><b>Concurrency.</b> Two guards scanning the same SINGLE_USE pass at once
 * would otherwise both read ACTIVE and both allow entry (a lost update — see
 * {@code GatePassScanConcurrencyIT}, which reproduces exactly that when the
 * lock is removed). The pass is therefore loaded under a
 * {@code PESSIMISTIC_WRITE} lock taken by the resolving query itself
 * ({@code find*ForUpdate}), which serializes concurrent scans of the same pass
 * so it is consumed exactly once. A pessimistic lock is chosen over an
 * {@code @Version} column because the latter needs a schema change, and because
 * scans are low-volume and the locked section is short. The lock must be
 * acquired by the query that first loads the entity — an unlocked read followed
 * by a locking re-read would hand back the stale instance already managed in the
 * persistence context. The lock is NOWAIT, so a scan that collides with one in
 * flight is rejected with a retry hint rather than blocking a request thread.
 *
 * <p><b>Resolution.</b> A QR token identifies a pass globally (tokens are
 * unique across tenants); a numeric code is per-tenant and recycled across
 * passes, so it resolves to the newest row bearing it. Both paths accept a pass
 * in any status — the status check below is what rejects it, which is what lets
 * a guard be told "expired" rather than "not found", and lets a guest whose
 * pass expired while they were inside still have their EXIT logged.
 *
 * <p>Guard RBAC ({@code @PreAuthorize}) and scan rate-limiting are the
 * controller's job (Task 7). Enforced here: tenant isolation, guard-to-property
 * assignment, status, validity window, single-use consumption, and the
 * entry-before-exit rule.
 */
@Service
public class GatePassScanService {

    private static final Logger log = LoggerFactory.getLogger(GatePassScanService.class);

    /**
     * Numeric-code resolution takes the newest row for the code and nothing else — see
     * {@link GatePassRepository#findByNumericCodeForUpdate} for why that is both
     * deterministic and correct despite code recycling.
     */
    private static final Pageable NEWEST_FIRST = PageRequest.of(0, 1);

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

    /**
     * The gate's verdict, plus the pass it was reached on.
     *
     * <p>{@code pass} is null when the caller must not be told anything about it, which
     * is not the same as "no pass was found". Three cases: the code resolved to nothing,
     * a lock conflict aborted the scan, and — the one that is a security decision rather
     * than an absence — a guard scanning at a property they are not assigned to. The
     * controller renders a null pass as a verdict with every guest field omitted, so
     * this field is what blinds that response; see the assignment check in {@link #scan}.
     */
    public record ScanOutcome(ScanResult result, String reason, GatePass pass) {}

    @Transactional
    public ScanOutcome scan(UUID tenantId, UUID guardUserId, String qrToken, String numericCode, ScanDirection direction) {
        // One clock reading for the whole decision: two Instant.now() calls could
        // straddle validTo, so the pass could pass the window check and then be stamped
        // with a scan time outside it.
        Instant now = Instant.now();
        String lookup = qrToken != null ? "QR" : "NUMERIC";
        Optional<GatePass> found;
        try {
            found = resolveForUpdate(tenantId, qrToken, numericCode);
        } catch (PessimisticLockingFailureException e) {
            // NOWAIT fired: another scan holds this pass's row lock. Better a "retry"
            // than a 500 at the gate — or than blocking the request thread until the
            // holder's transaction ends.
            log.warn("Gate pass scan aborted: row lock held by a concurrent scan. "
                    + "tenantId={} guardUserId={} direction={} lookup={}", tenantId, guardUserId, direction, lookup);
            return new ScanOutcome(ScanResult.REJECTED, "scan in progress, please retry", null);
        }

        // The query is already tenant-scoped by Hibernate's tenantFilter on
        // BaseTenantEntity (the real SQL includes "and gp1_0.tenant_id = ?"), so this
        // check is redundant in the normal case. It stays as deliberate defense in
        // depth in case that filter is ever disabled or bypassed. Reported as
        // "not found" so a guard cannot probe another tenant's passes for existence.
        GatePass pass = found.filter(p -> tenantId.equals(p.getTenantId())).orElse(null);
        if (pass == null) {
            // The only decision that leaves no gate_pass_scans row (the FK needs a pass),
            // so it is the one that has to be logged instead — otherwise token probing is
            // invisible. Never log the token/code itself: it is the credential.
            log.warn("Gate pass scan rejected: code did not resolve within tenant. "
                    + "tenantId={} guardUserId={} direction={} lookup={}", tenantId, guardUserId, direction, lookup);
            return new ScanOutcome(ScanResult.REJECTED, "not found", null);
        }

        Set<UUID> assignedProperties = guardPropertyAssignmentRepository.findByUserId(guardUserId).stream()
                .map(GuardPropertyAssignment::getPropertyId)
                .collect(Collectors.toSet());
        if (!assignedProperties.contains(pass.getPropertyId())) {
            // Audited with the pass (the FK needs it), but returned WITHOUT it. The
            // resolution above is scoped by tenant, not by property, so any code in the
            // tenant resolves for any guard in it — this check is the only thing standing
            // between a guard and the whole tenant's guest book, and it has to bound
            // reading as well as admitting. Every other rejection below keeps the pass:
            // those happen at a gate the guard is posted to, where they need the guest's
            // identity to explain the refusal to the person in front of them. This one
            // does not, so it carries the verdict and nothing else.
            record(tenantId, guardUserId, pass, direction, ScanResult.REJECTED,
                    "not authorized for this property", now);
            return new ScanOutcome(ScanResult.REJECTED, "not authorized for this property", null);
        }

        if (direction == ScanDirection.EXIT) {
            // Exit deliberately skips the entry gauntlet — status and window are about
            // admission. Whoever is inside must be able to leave; we only insist the
            // entry actually happened, so the log cannot record an exit without one.
            if (!gatePassScanRepository.existsByGatePassIdAndDirectionAndResult(
                    pass.getId(), ScanDirection.ENTRY, ScanResult.ALLOWED)) {
                return reject(tenantId, guardUserId, pass, direction, "no entry recorded", now);
            }
            record(tenantId, guardUserId, pass, ScanDirection.EXIT, ScanResult.ALLOWED, null, now);
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
            return reject(tenantId, guardUserId, pass, direction, statusReason, now);
        }

        if (now.isBefore(pass.getValidFrom())) {
            // Early, not expired — the pass must stay usable once the window opens.
            return reject(tenantId, guardUserId, pass, direction, "outside validity window", now);
        }
        if (now.isAfter(pass.getValidTo())) {
            pass.setStatus(GatePassStatus.EXPIRED);
            gatePassRepository.save(pass);
            return reject(tenantId, guardUserId, pass, direction, "outside validity window", now);
        }

        if (pass.getPassType() == GatePassType.SINGLE_USE) {
            pass.setStatus(GatePassStatus.USED);
            gatePassRepository.save(pass);
        }
        // RECURRING passes stay ACTIVE — they are admission rights for the whole window.

        record(tenantId, guardUserId, pass, ScanDirection.ENTRY, ScanResult.ALLOWED, null, now);

        // Runs inside the transaction, and therefore while still holding the pass's row
        // lock. That is deliberate: a pessimistic lock is held until commit no matter
        // where the call sits, so no reordering within this method shortens the hold —
        // only a post-commit hook would, and that would trade atomicity (an arrival
        // notification for a scan that rolled back, or a scan with no notification) for
        // one INSERT's worth of lock time. Atomicity wins.
        //
        // notifyInApp, not notify: NotificationService.mapLegacyType has no GATE_PASS_*
        // entry, so notify() would publish no EmailEvent anyway. Say what we mean.
        // Renter-created passes notify their creator. A walk-in's creator is the
        // guard standing at the gate, so sending "your guest has arrived" to that
        // guard is both noisy and wrong; its resident notification was created by
        // GateWalkInService when the request/registered arrival was recorded.
        if (pass.getOrigin() != GatePassOrigin.GUARD_WALK_IN) {
            notificationService.notifyInApp(tenantId, pass.getCreatedByUserId(), "GATE_PASS_ARRIVAL",
                    "Your guest has arrived", pass.getGuestName() + " was scanned in at the gate",
                    "GATE_PASS", pass.getId());
        }

        return new ScanOutcome(ScanResult.ALLOWED, null, pass);
    }

    /**
     * Loads the pass under the row lock. QR tokens are unique across all tenants so the
     * token alone identifies the pass; numeric codes are per-tenant and recycled, hence
     * the newest-first single-row resolution.
     */
    private Optional<GatePass> resolveForUpdate(UUID tenantId, String qrToken, String numericCode) {
        if (qrToken != null) {
            return gatePassRepository.findByQrTokenForUpdate(qrToken);
        }
        return gatePassRepository.findByNumericCodeForUpdate(tenantId, numericCode, NEWEST_FIRST)
                .stream().findFirst();
    }

    private ScanOutcome reject(UUID tenantId, UUID guardUserId, GatePass pass, ScanDirection direction,
                               String reason, Instant now) {
        record(tenantId, guardUserId, pass, direction, ScanResult.REJECTED, reason, now);
        return new ScanOutcome(ScanResult.REJECTED, reason, pass);
    }

    private void record(UUID tenantId, UUID guardUserId, GatePass pass, ScanDirection direction,
                        ScanResult result, String rejectionReason, Instant now) {
        GatePassScan scan = new GatePassScan();
        scan.setTenantId(tenantId);
        scan.setGatePassId(pass.getId());
        scan.setDirection(direction);
        scan.setScannedByUserId(guardUserId);
        scan.setScannedAt(now);
        scan.setResult(result);
        scan.setRejectionReason(rejectionReason);
        gatePassScanRepository.save(scan);
    }
}
