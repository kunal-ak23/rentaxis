package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PaymentSummaryDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LeaseChargeRepository;
import com.datagami.rentaxis.domain.repository.LeaseEventRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentScheduleService#getSummary}. The manager app's
 * status strip renders an amount under every status tile — including DEPOSITED
 * and BOUNCED — so the summary must accumulate depositedAmount/bouncedAmount
 * alongside the counts, not just the pending/collected/cleared amounts.
 */
class PaymentScheduleServiceSummaryTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private PaymentScheduleService service;

    @BeforeEach
    void setUp() {
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        service = new PaymentScheduleService(
                paymentScheduleRepository,
                mock(LeaseChargeRepository.class),
                mock(LeaseRepository.class),
                mock(AccountRepository.class),
                mock(RentCollectionSettingsRepository.class),
                mock(NotificationService.class),
                mock(FineConfigResolver.class),
                mock(PaymentPenaltyRepository.class),
                mock(LeaseEventRepository.class),
                mock(ApplicationEventPublisher.class),
                new ObjectMapper());
        TenantContextHolder.setTenantId(UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private PaymentSchedule payment(PaymentStatus status, String amount) {
        Lease lease = new Lease();
        lease.setStatus(LeaseStatus.ACTIVE);

        PaymentSchedule ps = new PaymentSchedule();
        ps.setLease(lease);
        ps.setStatus(status);
        ps.setAmount(new BigDecimal(amount));
        ps.setDueDate(LocalDate.now().plusMonths(1));
        return ps;
    }

    @Test
    void getSummary_accumulatesDepositedAndBouncedAmounts() {
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(
                payment(PaymentStatus.DEPOSITED, "1000"),
                payment(PaymentStatus.DEPOSITED, "2500"),
                payment(PaymentStatus.BOUNCED, "4000"),
                payment(PaymentStatus.PENDING, "700")));

        PaymentSummaryDTO summary = service.getSummary(null);

        assertThat(summary.getDepositedCount()).isEqualTo(2);
        assertThat(summary.getDepositedAmount()).isEqualByComparingTo("3500");
        assertThat(summary.getBouncedCount()).isEqualTo(1);
        assertThat(summary.getBouncedAmount()).isEqualByComparingTo("4000");
        assertThat(summary.getPendingAmount()).isEqualByComparingTo("700");
        assertThat(summary.getTotalAmount()).isEqualByComparingTo("8200");
    }

    @Test
    void getSummary_zeroesAmountsWhenNoDepositedOrBouncedPayments() {
        when(paymentScheduleRepository.findAll()).thenReturn(List.of(
                payment(PaymentStatus.CLEARED, "1200")));

        PaymentSummaryDTO summary = service.getSummary(null);

        assertThat(summary.getDepositedAmount()).isEqualByComparingTo("0");
        assertThat(summary.getBouncedAmount()).isEqualByComparingTo("0");
        assertThat(summary.getClearedAmount()).isEqualByComparingTo("1200");
    }
}
