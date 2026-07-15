package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GatePassService}: pass creation (codes + window
 * validation), approval/rejection of RECURRING passes, creator-only
 * cancellation, and tenant isolation on both approve and cancel.
 * Role-based access and the lease-ownership check are the controller's
 * job (Task 7) and are intentionally not exercised here.
 */
@ExtendWith(MockitoExtension.class)
class GatePassServiceTest {

    @Mock GatePassRepository gatePassRepository;
    @Mock NotificationService notificationService;

    private GatePassService service;

    private UUID tenantId;
    private UUID createdBy;
    private UUID propertyId;
    private UUID unitId;
    private Instant validFrom;
    private Instant validTo;

    @BeforeEach
    void setUp() {
        service = new GatePassService(gatePassRepository, notificationService);
        tenantId = UUID.randomUUID();
        createdBy = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        unitId = UUID.randomUUID();
        validFrom = Instant.now();
        validTo = validFrom.plus(1, ChronoUnit.DAYS);
    }

    @Test
    void singleUsePassIsActiveImmediatelyWithCodes() {
        when(gatePassRepository.existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any())).thenReturn(false);
        when(gatePassRepository.save(any(GatePass.class))).thenAnswer(inv -> inv.getArgument(0));

        GatePass pass = service.create(tenantId, createdBy, propertyId, unitId,
                "Guest Name", "+971500000000", "Delivery", "DXB-1234",
                GatePassType.SINGLE_USE, validFrom, validTo);

        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.ACTIVE);
        assertThat(pass.getQrToken()).matches("[0-9a-f]{48}");
        assertThat(pass.getNumericCode()).matches("\\d{8}");
    }

    @Test
    void recurringPassStartsPendingApprovalWithoutNotifying() {
        when(gatePassRepository.existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any())).thenReturn(false);
        when(gatePassRepository.save(any(GatePass.class))).thenAnswer(inv -> inv.getArgument(0));

        GatePass pass = service.create(tenantId, createdBy, propertyId, unitId,
                "Guest Name", "+971500000000", "Recurring visit", null,
                GatePassType.RECURRING, validFrom, validTo);

        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.PENDING_APPROVAL);
        // Manager notification is sent from the controller/report in a later task —
        // create() itself must not send an approval notification.
        verifyNoInteractions(notificationService);
    }

    @Test
    void numericCodeRegeneratedOnCollision() {
        when(gatePassRepository.existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any()))
                .thenReturn(true, false);
        when(gatePassRepository.save(any(GatePass.class))).thenAnswer(inv -> inv.getArgument(0));

        GatePass pass = service.create(tenantId, createdBy, propertyId, unitId,
                "Guest Name", "+971500000000", "Delivery", null,
                GatePassType.SINGLE_USE, validFrom, validTo);

        assertThat(pass.getNumericCode()).matches("\\d{8}");
        verify(gatePassRepository, times(2)).existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any());
        verify(gatePassRepository, times(1)).save(any(GatePass.class));
    }

    @Test
    void approveActivatesAndStampsApprover() {
        UUID passId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        GatePass pass = pendingPass(passId);
        when(gatePassRepository.findById(passId)).thenReturn(Optional.of(pass));
        when(gatePassRepository.save(any(GatePass.class))).thenAnswer(inv -> inv.getArgument(0));

        GatePass result = service.approve(tenantId, passId, approverId, true);

        assertThat(result.getStatus()).isEqualTo(GatePassStatus.ACTIVE);
        assertThat(result.getApprovedByUserId()).isEqualTo(approverId);
        assertThat(result.getApprovedAt()).isNotNull();
        verify(notificationService, times(1)).notifyInApp(eq(tenantId), eq(createdBy),
                eq("GATE_PASS_APPROVED"), any(), any(), eq("GATE_PASS"), eq(passId));
    }

    @Test
    void rejectCancelsAndNotifies() {
        UUID passId = UUID.randomUUID();
        UUID approverId = UUID.randomUUID();
        GatePass pass = pendingPass(passId);
        when(gatePassRepository.findById(passId)).thenReturn(Optional.of(pass));
        when(gatePassRepository.save(any(GatePass.class))).thenAnswer(inv -> inv.getArgument(0));

        GatePass result = service.approve(tenantId, passId, approverId, false);

        assertThat(result.getStatus()).isEqualTo(GatePassStatus.CANCELLED);
        verify(notificationService, times(1)).notifyInApp(eq(tenantId), eq(createdBy),
                eq("GATE_PASS_REJECTED"), any(), any(), eq("GATE_PASS"), eq(passId));
    }

    @Test
    void approveRejectsNonPendingPass() {
        UUID passId = UUID.randomUUID();
        GatePass pass = pendingPass(passId);
        pass.setStatus(GatePassStatus.ACTIVE);
        when(gatePassRepository.findById(passId)).thenReturn(Optional.of(pass));

        assertThatThrownBy(() -> service.approve(tenantId, passId, UUID.randomUUID(), true))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(gatePassRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void approveRejectsCrossTenantPass() {
        UUID passId = UUID.randomUUID();
        GatePass pass = pendingPass(passId);
        pass.setTenantId(UUID.randomUUID()); // belongs to a different tenant
        when(gatePassRepository.findById(passId)).thenReturn(Optional.of(pass));

        assertThatThrownBy(() -> service.approve(tenantId, passId, UUID.randomUUID(), true))
                .isInstanceOf(NotFoundException.class);

        verify(gatePassRepository, never()).save(any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void cancelRejectsAlreadyUsedPass() {
        UUID passId = UUID.randomUUID();
        GatePass pass = pendingPass(passId);
        pass.setStatus(GatePassStatus.USED);
        when(gatePassRepository.findById(passId)).thenReturn(Optional.of(pass));

        assertThatThrownBy(() -> service.cancel(tenantId, passId, createdBy))
                .isInstanceOf(BusinessRuleViolationException.class);

        verify(gatePassRepository, never()).save(any());
    }

    @Test
    void cancelRejectsCrossTenantPass() {
        UUID passId = UUID.randomUUID();
        GatePass pass = pendingPass(passId);
        pass.setTenantId(UUID.randomUUID()); // belongs to a different tenant
        pass.setStatus(GatePassStatus.ACTIVE);
        when(gatePassRepository.findById(passId)).thenReturn(Optional.of(pass));

        // Same creator id — only the tenant differs, so this isolates the tenant check.
        assertThatThrownBy(() -> service.cancel(tenantId, passId, createdBy))
                .isInstanceOf(NotFoundException.class);

        verify(gatePassRepository, never()).save(any());
    }

    @Test
    void cancelByNonCreatorFails() {
        UUID passId = UUID.randomUUID();
        GatePass pass = pendingPass(passId);
        pass.setStatus(GatePassStatus.ACTIVE);
        when(gatePassRepository.findById(passId)).thenReturn(Optional.of(pass));

        UUID otherUser = UUID.randomUUID();
        assertThatThrownBy(() -> service.cancel(tenantId, passId, otherUser))
                .isInstanceOf(NotFoundException.class);

        verify(gatePassRepository, never()).save(any());
    }

    @Test
    void numericCodeExhaustionAfterTenAttemptsThrows() {
        when(gatePassRepository.existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.create(tenantId, createdBy, propertyId, unitId,
                "Guest Name", "+971500000000", "Delivery", null,
                GatePassType.SINGLE_USE, validFrom, validTo))
                .isInstanceOf(IllegalStateException.class);

        verify(gatePassRepository, times(10)).existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any());
        verify(gatePassRepository, never()).save(any());
    }

    @Test
    void createRejectsInvertedWindow() {
        assertThatThrownBy(() -> service.create(tenantId, createdBy, propertyId, unitId,
                "Guest Name", "+971500000000", "Delivery", null,
                GatePassType.SINGLE_USE, validTo, validFrom))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(gatePassRepository);
    }

    private GatePass pendingPass(UUID passId) {
        GatePass pass = new GatePass();
        pass.setId(passId);
        pass.setTenantId(tenantId);
        pass.setPropertyId(propertyId);
        pass.setUnitId(unitId);
        pass.setCreatedByUserId(createdBy);
        pass.setGuestName("Guest Name");
        pass.setGuestPhone("+971500000000");
        pass.setPassType(GatePassType.RECURRING);
        pass.setValidFrom(validFrom);
        pass.setValidTo(validTo);
        pass.setStatus(GatePassStatus.PENDING_APPROVAL);
        pass.setQrToken(UUID.randomUUID().toString());
        pass.setNumericCode("12345678");
        return pass;
    }
}
