package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit test for the dashboard "pending this month" calculation: only PENDING
 * schedules whose dueDate falls in the current calendar month count toward
 * pendingThisMonthAmount, while pendingAmount remains all-time PENDING.
 */
class DashboardServicePendingThisMonthTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private DashboardService service;

    @BeforeEach
    void setUp() {
        PropertyRepository propertyRepository = mock(PropertyRepository.class);
        UnitRepository unitRepository = mock(UnitRepository.class);
        LeaseRepository leaseRepository = mock(LeaseRepository.class);
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        when(propertyRepository.findAll()).thenReturn(List.of());
        when(unitRepository.findAll()).thenReturn(List.of());
        when(leaseRepository.findAll()).thenReturn(List.of());
        service = new DashboardService(propertyRepository, unitRepository, leaseRepository, paymentScheduleRepository);
    }

    @Test
    void pendingThisMonth_countsOnlyCurrentMonthPending() {
        LocalDate today = LocalDate.now();
        LocalDate thisMonth = today.withDayOfMonth(1).plusDays(4);
        LocalDate lastMonth = today.withDayOfMonth(1).minusDays(5);
        LocalDate nextMonth = today.withDayOfMonth(1).plusMonths(1).plusDays(3);

        when(paymentScheduleRepository.findAll()).thenReturn(List.of(
                pending(thisMonth, "1000"),   // counts in both
                pending(lastMonth, "2000"),   // all-time pending only (overdue bucket)
                pending(nextMonth, "500"),    // all-time pending only (future)
                cleared(thisMonth, "9999")    // not pending
        ));

        DashboardSummaryDTO summary = service.getSummary();

        assertThat(summary.getPendingThisMonthAmount()).isEqualByComparingTo("1000");
        assertThat(summary.getPendingAmount()).isEqualByComparingTo("3500");
        assertThat(summary.getCollectedAmount()).isEqualByComparingTo("9999");
    }

    private PaymentSchedule pending(LocalDate due, String amount) {
        return schedule(due, amount, PaymentStatus.PENDING);
    }

    private PaymentSchedule cleared(LocalDate due, String amount) {
        return schedule(due, amount, PaymentStatus.CLEARED);
    }

    private PaymentSchedule schedule(LocalDate due, String amount, PaymentStatus status) {
        PaymentSchedule s = new PaymentSchedule();
        s.setDueDate(due);
        s.setAmount(new BigDecimal(amount));
        s.setStatus(status);
        // statusChangedAt left null so it is skipped from recent-activity (no unit/property needed)
        return s;
    }
}
