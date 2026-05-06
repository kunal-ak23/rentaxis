package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.repository.DeviceTokenRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.NotificationRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the three penalty notification helpers in {@link NotificationService}:
 * sendPenaltyIncurred, sendPenaltyCleared, sendPenaltyWaived.
 *
 * <p>Tests drive the real service (not a mock), stubbing its repository collaborators,
 * and verify that {@code notificationRepository.save(...)} is (or is not) called.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServicePenaltyHelpersTest {

    @Mock NotificationRepository notificationRepository;
    @Mock DeviceTokenRepository deviceTokenRepository;
    @Mock UserRepository userRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock org.springframework.context.ApplicationEventPublisher events;

    private NotificationService service;

    private UUID tenantId;
    private UUID renterUserId;

    @BeforeEach
    void setUp() {
        service = new NotificationService(
                notificationRepository, deviceTokenRepository, userRepository, leaseRepository, events);
        tenantId = UUID.randomUUID();
        renterUserId = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // ───────────────────────────── sendPenaltyIncurred ─────────────────────────────

    @Test
    void sendPenaltyIncurred_withSchedule_callsNotifyWithIncurredType() {
        UUID penaltyId = UUID.randomUUID();
        PaymentSchedule schedule = scheduleWithRenter(renterUserId);
        BigDecimal fineAmount = new BigDecimal("500");

        service.sendPenaltyIncurred(schedule, ChequeFailureReason.BOUNCE, fineAmount, penaltyId);

        ArgumentCaptor<com.datagami.rentaxis.domain.entity.Notification> captor =
                ArgumentCaptor.forClass(com.datagami.rentaxis.domain.entity.Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());

        com.datagami.rentaxis.domain.entity.Notification saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("PENALTY_INCURRED");
        assertThat(saved.getTitle()).isEqualTo("Penalty Incurred");
        assertThat(saved.getMessage()).contains("500");
        assertThat(saved.getMessage()).contains("BOUNCE");
        assertThat(saved.getReferenceType()).isEqualTo("PENALTY");
        assertThat(saved.getReferenceId()).isEqualTo(penaltyId);
        assertThat(saved.getUserId()).isEqualTo(renterUserId);
    }

    @Test
    void sendPenaltyIncurred_renterUserIdNull_silentlySkips() {
        UUID penaltyId = UUID.randomUUID();
        PaymentSchedule schedule = scheduleWithNullRenterUserId();

        service.sendPenaltyIncurred(schedule, ChequeFailureReason.BOUNCE, new BigDecimal("500"), penaltyId);

        verify(notificationRepository, never()).save(any());
    }

    // ───────────────────────────── sendPenaltyCleared ─────────────────────────────

    @Test
    void sendPenaltyCleared_withPayment_callsNotifyWithClearedType() {
        UUID leaseId = UUID.randomUUID();
        UUID penaltyId = UUID.randomUUID();

        Lease lease = leaseWithRenter(renterUserId);
        when(leaseRepository.findById(leaseId)).thenReturn(Optional.of(lease));

        PaymentPenalty penalty = penalty(penaltyId, leaseId, tenantId, new BigDecimal("750"));
        PenaltyPayment receipt = receipt(new BigDecimal("750"), "BANK_TRANSFER");

        service.sendPenaltyCleared(penalty, receipt);

        ArgumentCaptor<com.datagami.rentaxis.domain.entity.Notification> captor =
                ArgumentCaptor.forClass(com.datagami.rentaxis.domain.entity.Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());

        com.datagami.rentaxis.domain.entity.Notification saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("PENALTY_CLEARED");
        assertThat(saved.getTitle()).isEqualTo("Penalty Cleared");
        assertThat(saved.getMessage()).contains("750");
        assertThat(saved.getMessage()).contains("BANK_TRANSFER");
        assertThat(saved.getReferenceType()).isEqualTo("PENALTY");
        assertThat(saved.getReferenceId()).isEqualTo(penaltyId);
        assertThat(saved.getUserId()).isEqualTo(renterUserId);
    }

    @Test
    void sendPenaltyCleared_renterUserIdNull_silentlySkips() {
        UUID leaseId = UUID.randomUUID();
        UUID penaltyId = UUID.randomUUID();

        // Lease exists but renter has no userId
        Lease lease = leaseWithNullRenterUserId();
        when(leaseRepository.findById(leaseId)).thenReturn(Optional.of(lease));

        PaymentPenalty penalty = penalty(penaltyId, leaseId, tenantId, new BigDecimal("500"));
        PenaltyPayment receipt = receipt(new BigDecimal("500"), "CASH");

        service.sendPenaltyCleared(penalty, receipt);

        verify(notificationRepository, never()).save(any());
    }

    // ───────────────────────────── sendPenaltyWaived ───────────────────────────────

    @Test
    void sendPenaltyWaived_callsNotifyWithWaivedType_includesReasonInBody() {
        UUID leaseId = UUID.randomUUID();
        UUID penaltyId = UUID.randomUUID();

        Lease lease = leaseWithRenter(renterUserId);
        when(leaseRepository.findById(leaseId)).thenReturn(Optional.of(lease));

        PaymentPenalty penalty = penalty(penaltyId, leaseId, tenantId, new BigDecimal("300"));

        service.sendPenaltyWaived(penalty, "Goodwill gesture");

        ArgumentCaptor<com.datagami.rentaxis.domain.entity.Notification> captor =
                ArgumentCaptor.forClass(com.datagami.rentaxis.domain.entity.Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());

        com.datagami.rentaxis.domain.entity.Notification saved = captor.getValue();
        assertThat(saved.getType()).isEqualTo("PENALTY_WAIVED");
        assertThat(saved.getTitle()).isEqualTo("Penalty Waived");
        assertThat(saved.getMessage()).contains("300");
        assertThat(saved.getMessage()).contains("Goodwill gesture");
        assertThat(saved.getReferenceType()).isEqualTo("PENALTY");
        assertThat(saved.getReferenceId()).isEqualTo(penaltyId);
        assertThat(saved.getUserId()).isEqualTo(renterUserId);
    }

    @Test
    void sendPenaltyWaived_leaseMissing_silentlySkips() {
        UUID leaseId = UUID.randomUUID();
        UUID penaltyId = UUID.randomUUID();

        when(leaseRepository.findById(leaseId)).thenReturn(Optional.empty());

        PaymentPenalty penalty = penalty(penaltyId, leaseId, tenantId, new BigDecimal("400"));

        service.sendPenaltyWaived(penalty, "Some reason");

        verify(notificationRepository, never()).save(any());
    }

    // ─────────────────────────────── helpers ───────────────────────────────────────

    private PaymentSchedule scheduleWithRenter(UUID renterUserId) {
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setUserId(renterUserId);
        renter.setNameEn("Test Renter");

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setRenter(renter);

        PaymentSchedule schedule = new PaymentSchedule();
        schedule.setId(UUID.randomUUID());
        schedule.setLease(lease);
        schedule.setInstallmentNumber(3);
        schedule.setDueDate(LocalDate.now());
        return schedule;
    }

    private PaymentSchedule scheduleWithNullRenterUserId() {
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setUserId(null);  // no user id
        renter.setNameEn("No User Renter");

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setRenter(renter);

        PaymentSchedule schedule = new PaymentSchedule();
        schedule.setId(UUID.randomUUID());
        schedule.setLease(lease);
        schedule.setInstallmentNumber(1);
        schedule.setDueDate(LocalDate.now());
        return schedule;
    }

    private Lease leaseWithRenter(UUID renterUserId) {
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setUserId(renterUserId);
        renter.setNameEn("Test Renter");

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setRenter(renter);
        return lease;
    }

    private Lease leaseWithNullRenterUserId() {
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setUserId(null);
        renter.setNameEn("No User Renter");

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setRenter(renter);
        return lease;
    }

    private PaymentPenalty penalty(UUID id, UUID leaseId, UUID tenantId, BigDecimal amount) {
        PaymentPenalty p = new PaymentPenalty();
        p.setId(id);
        p.setLeaseId(leaseId);
        p.setTenantId(tenantId);
        p.setPenaltyAmount(amount);
        p.setPaymentScheduleId(UUID.randomUUID());
        p.setDaysOverdue(5);
        return p;
    }

    private PenaltyPayment receipt(BigDecimal amount, String method) {
        PenaltyPayment r = new PenaltyPayment();
        r.setId(UUID.randomUUID());
        r.setAmount(amount);
        r.setPaymentMethod(method);
        r.setReceivedAt(LocalDate.now());
        r.setReceivedBy(UUID.randomUUID());
        return r;
    }
}
