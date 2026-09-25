package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The dashboard's money tiles, read off the cheque register.
 *
 * <p>"Pending this month" is a date-bounded aggregate rather than a filter over
 * every row the database holds: the register is the biggest table a landlord has
 * and this is the first screen after login. The test therefore asserts that the
 * service asks the right <em>question</em> — the outstanding statuses, the
 * current month's window — and reports what comes back.</p>
 */
class DashboardServicePendingThisMonthTest {

    private ChequeRepository chequeRepository;
    private LeaseAccessPolicy leaseAccessPolicy;
    private DashboardService service;

    @org.junit.jupiter.api.AfterEach
    void clearTenant() {
        com.datagami.rentaxis.core.tenant.TenantContextHolder.clear();
    }

    @BeforeEach
    void setUp() {
        // The overdue tile's SQL binds the organisation; a caller always has one here.
        com.datagami.rentaxis.core.tenant.TenantContextHolder.setTenantId(java.util.UUID.randomUUID());
        PropertyRepository propertyRepository = mock(PropertyRepository.class);
        UnitRepository unitRepository = mock(UnitRepository.class);
        LeaseRepository leaseRepository = mock(LeaseRepository.class);
        chequeRepository = mock(ChequeRepository.class);
        when(propertyRepository.findAll()).thenReturn(List.of());
        when(unitRepository.findAll()).thenReturn(List.of());
        when(leaseRepository.findAll()).thenReturn(List.of());
        when(chequeRepository.findRecentlyChanged(anyBoolean(), any(), any())).thenReturn(List.of());
        when(chequeRepository.sumClearedBetween(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(BigDecimal.ZERO);
        // Unrestricted by default: the scoping itself is ChequeQueryServiceIT's and
        // DashboardServiceScopingIT's subject, not this test's.
        leaseAccessPolicy = mock(LeaseAccessPolicy.class);
        when(leaseAccessPolicy.visiblePropertyIds()).thenReturn(null);
        service = new DashboardService(propertyRepository, unitRepository, leaseRepository,
                chequeRepository, leaseAccessPolicy);
    }

    @Test
    void outstandingTilesComeFromTheRegistersStatusTotals() {
        when(chequeRepository.totalsByStatus(eq(null), eq(true), any())).thenReturn(List.<Object[]>of(
                new Object[]{ChequeStatus.REGISTERED, 3L, new BigDecimal("2500")},
                new Object[]{ChequeStatus.DEPOSITED, 1L, new BigDecimal("1000")},
                new Object[]{ChequeStatus.CLEARED, 2L, new BigDecimal("9999")},
                // Not outstanding: the paper is back with the tenant.
                new Object[]{ChequeStatus.RETURNED, 1L, new BigDecimal("4000")}));
        when(chequeRepository.sumByStatusInAndChequeDateBetween(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new BigDecimal("1000"));

        DashboardSummaryDTO summary = service.getSummary();

        assertThat(summary.getPendingThisMonthAmount()).isEqualByComparingTo("1000");
        assertThat(summary.getPendingAmount()).isEqualByComparingTo("3500");
        assertThat(summary.getCollectedAmount()).isEqualByComparingTo("9999");
    }

    /**
     * "This month" is the current calendar month, asked of the database as a
     * half-open window over the date on the paper.
     */
    @Test
    void pendingThisMonthAsksForTheCurrentMonthsOutstandingRows() {
        when(chequeRepository.totalsByStatus(eq(null), eq(true), any())).thenReturn(List.of());
        java.time.LocalDate monthStart = java.time.LocalDate.now().withDayOfMonth(1);
        when(chequeRepository.sumByStatusInAndChequeDateBetween(
                argThatContainsOutstanding(), eq(monthStart), eq(monthStart.plusMonths(1)),
                anyBoolean(), any()))
                .thenReturn(new BigDecimal("777"));

        assertThat(service.getSummary().getPendingThisMonthAmount()).isEqualByComparingTo("777");
    }

    private static Collection<ChequeStatus> argThatContainsOutstanding() {
        return org.mockito.ArgumentMatchers.argThat(statuses ->
                statuses != null
                        && statuses.contains(ChequeStatus.REGISTERED)
                        && statuses.contains(ChequeStatus.DEPOSITED)
                        && !statuses.contains(ChequeStatus.CLEARED));
    }
}
