package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PaymentScheduleDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentScheduleService#searchPayments}, which backs the
 * global command palette.
 *
 * <p>The behaviour that matters here is reach: the palette previously fetched
 * the newest 100 rows and filtered them in the browser, so anything older
 * rendered "No results" — a silent wrong answer. These tests pin that the
 * search scans the whole tenant and matches every field the palette displays.
 */
class PaymentScheduleServiceSearchTest {

    private PaymentScheduleRepository paymentScheduleRepository;
    private PaymentScheduleService service;

    private final Pageable pageable = PageRequest.of(0, 6);

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

    private static PaymentSchedule row(String chequeNumber, String renterName, String unitNumber,
            String propertyName, LocalDate dueDate) {
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setNameEn(renterName);

        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setRenter(renter);

        Unit unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setUnitNumber(unitNumber);

        Property property = new Property();
        property.setId(UUID.randomUUID());
        property.setNameEn(propertyName);

        PaymentSchedule ps = new PaymentSchedule();
        ps.setId(UUID.randomUUID());
        ps.setLease(lease);
        ps.setUnit(unit);
        ps.setProperty(property);
        ps.setChequeNumber(chequeNumber);
        ps.setDueDate(dueDate);
        return ps;
    }

    /**
     * The regression this endpoint exists for: a cheque far outside the newest
     * page must still be found. 150 newer rows sit in front of it — more than
     * the 100-row window the palette used to fetch.
     */
    @Test
    void findsAnOldChequeBuriedBehindMoreRowsThanTheOldClientWindow() {
        List<PaymentSchedule> rows = new ArrayList<>(IntStream.range(0, 150)
                .mapToObj(i -> row("CHQ-" + (9000 + i), "Noise Renter", "U-" + i, "Noise Tower",
                        LocalDate.of(2026, 1, 1).plusDays(i)))
                .toList());
        rows.add(row("CHQ-7788", "Ali Hassan", "U-101", "Marina Heights", LocalDate.of(2024, 6, 1)));
        when(paymentScheduleRepository.findAllForPaletteSearch()).thenReturn(rows);

        Page<PaymentScheduleDTO> page = service.searchPayments("CHQ-7788", pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).singleElement()
                .extracting(PaymentScheduleDTO::getChequeNumber).isEqualTo("CHQ-7788");
        // The search base is the whole tenant, not a page window.
        verify(paymentScheduleRepository).findAllForPaletteSearch();
    }

    @Test
    void matchesRenterUnitAndPropertyCaseInsensitively() {
        List<PaymentSchedule> rows = List.of(
                row("CHQ-1", "Ali Hassan", "U-101", "Marina Heights", LocalDate.of(2025, 1, 1)),
                row("CHQ-2", "Sara Khan", "U-202", "Palm Residences", LocalDate.of(2025, 2, 1)));
        when(paymentScheduleRepository.findAllForPaletteSearch()).thenReturn(rows);

        assertThat(service.searchPayments("ali hassan", pageable).getTotalElements()).isEqualTo(1);
        assertThat(service.searchPayments("U-202", pageable).getTotalElements()).isEqualTo(1);
        assertThat(service.searchPayments("PALM", pageable).getTotalElements()).isEqualTo(1);
        assertThat(service.searchPayments("marina", pageable).getTotalElements()).isEqualTo(1);
    }

    @Test
    void blankQueryReturnsEmptyWithoutTouchingTheRepository() {
        assertThat(service.searchPayments("   ", pageable).getTotalElements()).isZero();
        assertThat(service.searchPayments(null, pageable).getTotalElements()).isZero();
        verify(paymentScheduleRepository, org.mockito.Mockito.never()).findAllForPaletteSearch();
    }

    @Test
    void nullFieldsDoNotBreakMatching() {
        // Cash installments carry no cheque number; a null must be skipped, not NPE.
        List<PaymentSchedule> rows = List.of(
                row(null, "Ali Hassan", "U-101", "Marina Heights", LocalDate.of(2025, 1, 1)));
        when(paymentScheduleRepository.findAllForPaletteSearch()).thenReturn(rows);

        assertThat(service.searchPayments("ali", pageable).getTotalElements()).isEqualTo(1);
        assertThat(service.searchPayments("CHQ", pageable).getTotalElements()).isZero();
    }

    @Test
    void pagesResultsRatherThanReturningEverything() {
        List<PaymentSchedule> rows = IntStream.range(0, 20)
                .mapToObj(i -> row("CHQ-" + i, "Ali Hassan", "U-" + i, "Marina Heights",
                        LocalDate.of(2025, 1, 1).plusDays(i)))
                .toList();
        when(paymentScheduleRepository.findAllForPaletteSearch()).thenReturn(rows);

        Page<PaymentScheduleDTO> page = service.searchPayments("ali", pageable);

        assertThat(page.getTotalElements()).isEqualTo(20);
        assertThat(page.getContent()).hasSize(6);
    }
    /**
     * TenantAspect only enables Hibernate's tenantFilter when a tenant id is set,
     * so a scan run with no active tenant would cross tenant boundaries. A
     * SUPER_ADMIN who has not selected a tenant is exactly that state, and the
     * palette renders for SUPER_ADMIN.
     */
    @Test
    void withoutAnActiveTenantReturnsNothingAndNeverScans() {
        TenantContextHolder.clear();

        Page<PaymentScheduleDTO> page = service.searchPayments("ali", pageable);

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
        verify(paymentScheduleRepository, org.mockito.Mockito.never()).findAllForPaletteSearch();
    }
}