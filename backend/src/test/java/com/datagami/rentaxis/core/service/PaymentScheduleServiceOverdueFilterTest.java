package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the overdue routing in
 * {@link PaymentScheduleService#getPaymentsForProperty}. The "overdue" flag must
 * route to the overdue-specific repository queries (with today's date) and must
 * take precedence over / ignore the {@code status} parameter.
 */
class PaymentScheduleServiceOverdueFilterTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private PaymentScheduleService service;

    private final UUID propertyId = UUID.randomUUID();
    private final Pageable pageable = PageRequest.of(0, 25);

    @BeforeEach
    void setUp() {
        paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        service = new PaymentScheduleService(
                paymentScheduleRepository,
                mock(LeaseChargeRepository.class),
                mock(LeaseRepository.class),
                mock(RentCollectionSettingsRepository.class),
                mock(NotificationService.class),
                mock(FineConfigResolver.class),
                mock(PaymentPenaltyRepository.class),
                mock(LeaseEventRepository.class),
                mock(ApplicationEventPublisher.class),
                new ObjectMapper());
        TenantContextHolder.setTenantId(UUID.randomUUID());

        Page<PaymentSchedule> empty = new PageImpl<>(List.of());
        when(paymentScheduleRepository.findFiltered(any(), any(), any())).thenReturn(empty);
        when(paymentScheduleRepository.findOverdueFiltered(any(), any(), any())).thenReturn(empty);
        when(paymentScheduleRepository.findFilteredWithSearch(any(), any(), any(), any(), any(), any())).thenReturn(empty);
        when(paymentScheduleRepository.findOverdueFilteredWithSearch(any(), any(), any(), any(), any(), any())).thenReturn(empty);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void overdueTrue_routesToFindOverdueFiltered_withTodayAndIgnoresStatus() {
        service.getPaymentsForProperty(propertyId, PaymentStatus.PENDING, null, true, pageable);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(paymentScheduleRepository)
                .findOverdueFiltered(eq(propertyId), dateCaptor.capture(), eq(pageable));
        assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now());
        // status must be ignored: the plain filtered query is never hit.
        verify(paymentScheduleRepository, never()).findFiltered(any(), any(), any());
    }

    @Test
    void overdueFalse_routesToFindFiltered_notOverdue() {
        service.getPaymentsForProperty(propertyId, PaymentStatus.PENDING, null, false, pageable);

        verify(paymentScheduleRepository).findFiltered(eq(propertyId), eq(PaymentStatus.PENDING), eq(pageable));
        verify(paymentScheduleRepository, never()).findOverdueFiltered(any(), any(), any());
    }

    @Test
    void overdueTrue_withSearch_routesToPagedOverdueSearch() {
        service.getPaymentsForProperty(propertyId, null, "ali", true, pageable);

        verify(paymentScheduleRepository).findOverdueFilteredWithSearch(
                eq(propertyId), any(LocalDate.class), eq("%ali%"), eq(null), eq(null), eq(pageable));
        verify(paymentScheduleRepository, never()).findFilteredWithSearch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void normalSearch_routesToPagedSearch_andParsesNumericAmountAndInstallment() {
        service.getPaymentsForProperty(propertyId, PaymentStatus.PENDING, "5,000", false, pageable);

        verify(paymentScheduleRepository).findFilteredWithSearch(
                eq(propertyId), eq(PaymentStatus.PENDING), eq("%5,000%"),
                eq(5000), eq(new BigDecimal("5000")), eq(pageable));
        verify(paymentScheduleRepository, never())
                .findOverdueFilteredWithSearch(any(), any(), any(), any(), any(), any());
    }
}
