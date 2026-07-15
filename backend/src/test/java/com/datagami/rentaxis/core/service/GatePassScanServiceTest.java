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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GatePassScanService}: the gate-side validation matrix
 * (guard authorization, status, validity window, single-use consumption,
 * entry-before-exit ordering) plus the audit trail every rejection must leave.
 * Guard RBAC and scan rate-limiting are the controller's job (Task 7) and are
 * intentionally not exercised here.
 */
@ExtendWith(MockitoExtension.class)
class GatePassScanServiceTest {

    @Mock GatePassRepository gatePassRepository;
    @Mock GatePassScanRepository gatePassScanRepository;
    @Mock GuardPropertyAssignmentRepository guardPropertyAssignmentRepository;
    @Mock NotificationService notificationService;

    private GatePassScanService service;

    private UUID tenantId;
    private UUID guardUserId;
    private UUID createdBy;
    private UUID propertyId;
    private UUID unitId;
    private UUID passId;
    private String qrToken;

    @BeforeEach
    void setUp() {
        service = new GatePassScanService(gatePassRepository, gatePassScanRepository,
                guardPropertyAssignmentRepository, notificationService);
        tenantId = UUID.randomUUID();
        guardUserId = UUID.randomUUID();
        createdBy = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        unitId = UUID.randomUUID();
        passId = UUID.randomUUID();
        qrToken = UUID.randomUUID().toString().replace("-", "") + "0123456789abcdef";
    }

    @Test
    void activeSingleUseInsideWindowAllowsEntry() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.ALLOWED);
        assertThat(outcome.pass()).isSameAs(pass);
        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.USED);
        verify(gatePassRepository).save(pass);

        GatePassScan scan = capturedScan();
        assertThat(scan.getTenantId()).isEqualTo(tenantId);
        assertThat(scan.getGatePassId()).isEqualTo(passId);
        assertThat(scan.getScannedByUserId()).isEqualTo(guardUserId);
        assertThat(scan.getDirection()).isEqualTo(ScanDirection.ENTRY);
        assertThat(scan.getResult()).isEqualTo(ScanResult.ALLOWED);
        assertThat(scan.getRejectionReason()).isNull();

        verify(notificationService).notifyInApp(eq(tenantId), eq(createdBy), eq("GATE_PASS_ARRIVAL"),
                any(), any(), eq("GATE_PASS"), eq(passId));
    }

    @Test
    void secondEntryOnUsedSingleUseIsRejected() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        pass.setStatus(GatePassStatus.USED);
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        assertThat(outcome.reason()).isEqualTo("already used");
        assertRejectedScanRecorded("already used", ScanDirection.ENTRY);
        verifyNoInteractions(notificationService);
    }

    @Test
    void recurringPassAllowsMultipleEntries() {
        GatePass pass = activePass(GatePassType.RECURRING);
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.ALLOWED);
        // A RECURRING pass is never consumed — it must stay scannable for the next visit.
        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.ACTIVE);
        verify(gatePassRepository, never()).save(any());
        assertThat(capturedScan().getResult()).isEqualTo(ScanResult.ALLOWED);
    }

    @Test
    void entryBeforeWindowIsRejected() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        pass.setValidFrom(Instant.now().plus(1, ChronoUnit.HOURS));
        pass.setValidTo(Instant.now().plus(2, ChronoUnit.HOURS));
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        assertThat(outcome.reason()).isEqualTo("outside validity window");
        // Not yet valid is not the same as expired — the pass must remain usable later.
        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.ACTIVE);
        verify(gatePassRepository, never()).save(any());
        assertRejectedScanRecorded("outside validity window", ScanDirection.ENTRY);
        verifyNoInteractions(notificationService);
    }

    @Test
    void entryAfterWindowExpiresPassAndRejects() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        pass.setValidFrom(Instant.now().minus(2, ChronoUnit.HOURS));
        pass.setValidTo(Instant.now().minus(1, ChronoUnit.HOURS));
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        assertThat(outcome.reason()).isEqualTo("outside validity window");
        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.EXPIRED);
        verify(gatePassRepository).save(pass);
        assertRejectedScanRecorded("outside validity window", ScanDirection.ENTRY);
        verifyNoInteractions(notificationService);
    }

    @Test
    void pendingApprovalPassIsRejected() {
        GatePass pass = activePass(GatePassType.RECURRING);
        pass.setStatus(GatePassStatus.PENDING_APPROVAL);
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        assertThat(outcome.reason()).isEqualTo("pending approval");
        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.PENDING_APPROVAL);
        assertRejectedScanRecorded("pending approval", ScanDirection.ENTRY);
        verifyNoInteractions(notificationService);
    }

    @Test
    void cancelledPassIsRejected() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        pass.setStatus(GatePassStatus.CANCELLED);
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        assertThat(outcome.reason()).isEqualTo("cancelled");
        assertRejectedScanRecorded("cancelled", ScanDirection.ENTRY);
        verifyNoInteractions(notificationService);
    }

    @Test
    void unassignedGuardIsRejected() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        // Guard works a different property.
        guardIsAssignedTo(UUID.randomUUID());

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        assertThat(outcome.reason()).isEqualTo("not authorized for this property");
        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.ACTIVE);
        verify(gatePassRepository, never()).save(any());
        // The attempt is still auditable — a guard probing another property's passes
        // is exactly what the scan log exists to surface.
        assertRejectedScanRecorded("not authorized for this property", ScanDirection.ENTRY);
        verifyNoInteractions(notificationService);
    }

    @Test
    void unknownCodeIsRejectedWithoutScanRow() {
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.empty());

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        assertThat(outcome.reason()).isEqualTo("not found");
        assertThat(outcome.pass()).isNull();
        // gate_pass_scans.gate_pass_id is a NOT NULL FK — there is no row to point at.
        verify(gatePassScanRepository, never()).save(any());
        verifyNoInteractions(guardPropertyAssignmentRepository);
        verifyNoInteractions(notificationService);
    }

    @Test
    void crossTenantPassIsRejectedAsNotFound() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        pass.setTenantId(UUID.randomUUID()); // belongs to a different tenant
        // The qr_token lookup is global (the token is unique across tenants), so the
        // service — not the query — has to enforce isolation.
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        // "not found", never "not authorized": a guard must not be able to probe
        // another tenant's passes for existence.
        assertThat(outcome.reason()).isEqualTo("not found");
        assertThat(outcome.pass()).isNull();
        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.ACTIVE);
        verify(gatePassRepository, never()).save(any());
        verify(gatePassScanRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void exitAfterEntryIsAllowed() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        pass.setStatus(GatePassStatus.USED); // consumed by the entry scan
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);
        when(gatePassScanRepository.existsByGatePassIdAndDirectionAndResult(
                passId, ScanDirection.ENTRY, ScanResult.ALLOWED)).thenReturn(true);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.EXIT);

        assertThat(outcome.result()).isEqualTo(ScanResult.ALLOWED);
        // Exit never re-runs the entry gauntlet: a USED single-use pass must still let
        // the guest out.
        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.USED);
        verify(gatePassRepository, never()).save(any());

        GatePassScan scan = capturedScan();
        assertThat(scan.getDirection()).isEqualTo(ScanDirection.EXIT);
        assertThat(scan.getResult()).isEqualTo(ScanResult.ALLOWED);
        assertThat(scan.getRejectionReason()).isNull();
        // Arrival is announced on entry only.
        verifyNoInteractions(notificationService);
    }

    @Test
    void exitWithoutEntryIsRejected() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);
        when(gatePassScanRepository.existsByGatePassIdAndDirectionAndResult(
                passId, ScanDirection.ENTRY, ScanResult.ALLOWED)).thenReturn(false);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.EXIT);

        assertThat(outcome.result()).isEqualTo(ScanResult.REJECTED);
        assertThat(outcome.reason()).isEqualTo("no entry recorded");
        assertRejectedScanRecorded("no entry recorded", ScanDirection.EXIT);
        verifyNoInteractions(notificationService);
    }

    @Test
    void numericCodeLookupResolvesPass() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        when(gatePassRepository.findByNumericCodeForUpdate(eq(tenantId), eq("12345678"), any()))
                .thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);

        GatePassScanService.ScanOutcome outcome =
                service.scan(tenantId, guardUserId, null, "12345678", ScanDirection.ENTRY);

        assertThat(outcome.result()).isEqualTo(ScanResult.ALLOWED);
        assertThat(outcome.pass()).isSameAs(pass);
        verify(gatePassRepository, never()).findByQrTokenForUpdate(any());
        verify(gatePassRepository, never()).findByTenantIdAndNumericCodeAndStatusIn(any(), any(), any());
    }

    @Test
    void entryLoadsPassUnderPessimisticLock() {
        GatePass pass = activePass(GatePassType.SINGLE_USE);
        when(gatePassRepository.findByQrTokenForUpdate(qrToken)).thenReturn(Optional.of(pass));
        guardIsAssignedTo(propertyId);

        service.scan(tenantId, guardUserId, qrToken, null, ScanDirection.ENTRY);

        // The locking finder must be the query that first loads the pass. Reading it
        // unlocked and re-loading under a lock would not re-hydrate the already-managed
        // instance, so two guards could both see ACTIVE and both be let in.
        verify(gatePassRepository, times(1)).findByQrTokenForUpdate(qrToken);
        verify(gatePassRepository, never()).findByQrToken(any());
    }

    private GatePass activePass(GatePassType type) {
        GatePass pass = new GatePass();
        pass.setId(passId);
        pass.setTenantId(tenantId);
        pass.setPropertyId(propertyId);
        pass.setUnitId(unitId);
        pass.setCreatedByUserId(createdBy);
        pass.setGuestName("Guest Name");
        pass.setGuestPhone("+971500000000");
        pass.setPassType(type);
        pass.setValidFrom(Instant.now().minus(1, ChronoUnit.HOURS));
        pass.setValidTo(Instant.now().plus(1, ChronoUnit.HOURS));
        pass.setStatus(GatePassStatus.ACTIVE);
        pass.setQrToken(qrToken);
        pass.setNumericCode("12345678");
        return pass;
    }

    private void guardIsAssignedTo(UUID assignedPropertyId) {
        GuardPropertyAssignment assignment = new GuardPropertyAssignment();
        assignment.setTenantId(tenantId);
        assignment.setUserId(guardUserId);
        assignment.setPropertyId(assignedPropertyId);
        when(guardPropertyAssignmentRepository.findByUserId(guardUserId)).thenReturn(List.of(assignment));
    }

    private GatePassScan capturedScan() {
        ArgumentCaptor<GatePassScan> captor = ArgumentCaptor.forClass(GatePassScan.class);
        verify(gatePassScanRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertRejectedScanRecorded(String reason, ScanDirection direction) {
        GatePassScan scan = capturedScan();
        assertThat(scan.getTenantId()).isEqualTo(tenantId);
        assertThat(scan.getGatePassId()).isEqualTo(passId);
        assertThat(scan.getScannedByUserId()).isEqualTo(guardUserId);
        assertThat(scan.getDirection()).isEqualTo(direction);
        assertThat(scan.getResult()).isEqualTo(ScanResult.REJECTED);
        assertThat(scan.getRejectionReason()).isEqualTo(reason);
    }
}
