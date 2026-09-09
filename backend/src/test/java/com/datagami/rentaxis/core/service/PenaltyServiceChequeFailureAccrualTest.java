package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PenaltyServiceChequeFailureAccrualTest {

    @Mock PaymentPenaltyRepository paymentPenaltyRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock PenaltyProcessingService penaltyProcessingService;
    @Mock NotificationService notificationService;

    private Clock fixedClock;
    private PenaltyService service;

    private static final LocalDate CREATED = LocalDate.of(2026, 5, 1);

    @BeforeEach
    void setUp() {
        // Default: today is exactly the createdAt date.
        setToday(CREATED);
    }

    private void setToday(LocalDate today) {
        fixedClock = Clock.fixed(today.atStartOfDay(ZoneId.of("UTC")).toInstant(), ZoneId.of("UTC"));
        service = new PenaltyService(paymentPenaltyRepository, mock(PenaltyPaymentService.class), leaseRepository, penaltyProcessingService, notificationService, fixedClock);
    }

    @Test
    void accrual_dayZero_daysOverdueIsZero() {
        // today = createdAt; graceDays=7 -> daysOverdue=0.
        setToday(CREATED);
        PaymentPenalty p = openChequeFailurePenalty(CREATED, 7, BigDecimal.valueOf(25));
        when(paymentPenaltyRepository.findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE")).thenReturn(List.of(p));

        service.processChequeFailureAccruals();

        assertThat(p.getDaysOverdue()).isEqualTo(0);
        assertThat(p.getLastCalculatedAt()).isNotNull();
        verify(paymentPenaltyRepository).saveAll(anyList());
    }

    @Test
    void accrual_dayOfGrace_stillZero() {
        setToday(CREATED.plusDays(7)); // today = createdAt + 7
        PaymentPenalty p = openChequeFailurePenalty(CREATED, 7, BigDecimal.valueOf(25));
        when(paymentPenaltyRepository.findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE")).thenReturn(List.of(p));
        service.processChequeFailureAccruals();
        assertThat(p.getDaysOverdue()).isEqualTo(0);
    }

    @Test
    void accrual_oneDayPastGrace_daysOverdueIsOne() {
        setToday(CREATED.plusDays(8)); // 1 day past
        PaymentPenalty p = openChequeFailurePenalty(CREATED, 7, BigDecimal.valueOf(25));
        when(paymentPenaltyRepository.findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE")).thenReturn(List.of(p));
        service.processChequeFailureAccruals();
        assertThat(p.getDaysOverdue()).isEqualTo(1);
    }

    @Test
    void accrual_thirtyDays_daysOverdueIsTwentyThree() {
        setToday(CREATED.plusDays(30));  // grace 7 -> 23 days past
        PaymentPenalty p = openChequeFailurePenalty(CREATED, 7, BigDecimal.valueOf(25));
        when(paymentPenaltyRepository.findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE")).thenReturn(List.of(p));
        service.processChequeFailureAccruals();
        assertThat(p.getDaysOverdue()).isEqualTo(23);
    }

    @Test
    void accrual_idempotentSameDay() {
        setToday(CREATED.plusDays(15));
        PaymentPenalty p = openChequeFailurePenalty(CREATED, 7, BigDecimal.valueOf(25));
        when(paymentPenaltyRepository.findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE")).thenReturn(List.of(p));

        service.processChequeFailureAccruals();
        int first = p.getDaysOverdue();
        service.processChequeFailureAccruals();
        assertThat(p.getDaysOverdue()).isEqualTo(first);
        assertThat(p.getDaysOverdue()).isEqualTo(8);
    }

    @Test
    void accrual_clearedPenalty_isSkippedByQuery() {
        // The repo query returns ONLY clearedAt-null rows; nothing is updated.
        setToday(CREATED.plusDays(30));
        when(paymentPenaltyRepository.findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE")).thenReturn(List.of());
        service.processChequeFailureAccruals();
        verify(paymentPenaltyRepository).findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE");
        // saveAll may be called with empty list (no-op) — verify nothing was mutated.
        verify(paymentPenaltyRepository, atMost(1)).saveAll(argThat(arg -> !arg.iterator().hasNext()));
    }

    @Test
    void accrual_multiplePenalties_eachUpdatedIndependently() {
        setToday(CREATED.plusDays(20));
        PaymentPenalty p1 = openChequeFailurePenalty(CREATED, 7, BigDecimal.valueOf(25));            // 13 days past
        PaymentPenalty p2 = openChequeFailurePenalty(CREATED.minusDays(5), 7, BigDecimal.valueOf(25)); // 18 days past
        when(paymentPenaltyRepository.findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE"))
                .thenReturn(List.of(p1, p2));
        service.processChequeFailureAccruals();
        assertThat(p1.getDaysOverdue()).isEqualTo(13);
        assertThat(p2.getDaysOverdue()).isEqualTo(18);
    }

    @Test
    void calculateDailyPenalties_invokesChequeFailurePass() {
        setToday(LocalDate.of(2026, 5, 4));
        when(leaseRepository.findByStatus(any())).thenReturn(List.of());
        when(paymentPenaltyRepository.findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE")).thenReturn(List.of());

        service.calculateDailyPenalties();

        verify(paymentPenaltyRepository).findByPenaltyTypeAndClearedAtIsNull("CHEQUE_FAILURE");
    }

    private PaymentPenalty openChequeFailurePenalty(LocalDate createdAt, int graceDays, BigDecimal perDayRate) {
        PaymentPenalty p = new PaymentPenalty();
        p.setPaymentScheduleId(UUID.randomUUID());
        p.setLeaseId(UUID.randomUUID());
        p.setPenaltyType("CHEQUE_FAILURE");
        p.setPenaltyAmount(BigDecimal.valueOf(500));
        p.setDaysOverdue(0);
        p.setFineGraceDays(graceDays);
        p.setFinePerDayRate(perDayRate);
        p.setCreatedAt(createdAt.atStartOfDay());
        return p;
    }
}
