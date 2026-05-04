package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
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
 * Unit tests for the M7 update to {@link PenaltyService#waivePenalty(UUID, String, UUID)}:
 * waiving must set {@code clearedAt} (so the daily accrual job stops accruing),
 * fire {@code PENALTY_WAIVED}, and reject re-waivers / already-cleared penalties.
 */
@ExtendWith(MockitoExtension.class)
class PenaltyServiceWaiveTest {

    @Mock PaymentPenaltyRepository paymentPenaltyRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock PenaltyProcessingService penaltyProcessingService;
    @Mock NotificationService notificationService;

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 3);
    private final Clock fixedClock = Clock.fixed(
            TODAY.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));

    private PenaltyService service;
    private UUID tenantId;
    private UUID penaltyId;
    private UUID waivedBy;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        penaltyId = UUID.randomUUID();
        waivedBy = UUID.randomUUID();
        TenantContextHolder.setTenantId(tenantId);

        service = new PenaltyService(
                paymentPenaltyRepository,
                leaseRepository,
                penaltyProcessingService,
                notificationService,
                fixedClock);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void waive_setsClearedAtAndWaived_andFiresPenaltyWaivedNotification() {
        PaymentPenalty p = openPenalty();
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));
        when(paymentPenaltyRepository.save(any(PaymentPenalty.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentPenalty result = service.waivePenalty(penaltyId, "Goodwill", waivedBy);

        assertThat(result.isWaived()).isTrue();
        assertThat(result.getWaivedBy()).isEqualTo(waivedBy);
        assertThat(result.getWaivedReason()).isEqualTo("Goodwill");
        assertThat(result.getWaivedAt()).isEqualTo(LocalDateTime.now(fixedClock));
        assertThat(result.getClearedAt()).isEqualTo(LocalDateTime.now(fixedClock));

        verify(notificationService, times(1)).sendPenaltyWaived(eq(p), eq("Goodwill"));
    }

    @Test
    void waive_alreadyCleared_throws() {
        PaymentPenalty p = openPenalty();
        p.setClearedAt(LocalDateTime.now(fixedClock));
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.waivePenalty(penaltyId, "n", waivedBy))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already cleared");

        verify(paymentPenaltyRepository, never()).save(any());
        verify(notificationService, never()).sendPenaltyWaived(any(), any());
    }

    @Test
    void waive_alreadyWaived_throws() {
        PaymentPenalty p = openPenalty();
        p.setWaived(true);
        when(paymentPenaltyRepository.findById(penaltyId)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.waivePenalty(penaltyId, "n", waivedBy))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already waived");

        verify(paymentPenaltyRepository, never()).save(any());
        verify(notificationService, never()).sendPenaltyWaived(any(), any());
    }

    private PaymentPenalty openPenalty() {
        PaymentPenalty p = new PaymentPenalty();
        p.setId(penaltyId);
        p.setTenantId(tenantId);
        p.setPaymentScheduleId(UUID.randomUUID());
        p.setLeaseId(UUID.randomUUID());
        p.setPenaltyType("CHEQUE_FAILURE");
        p.setPenaltyAmount(new BigDecimal("500"));
        p.setDaysOverdue(0);
        return p;
    }
}
