package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.DashboardSummaryDTO;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gap #60: the activity feed printed "Payment #2 bounced - Unit G-01 … (AED
 * 31500.00)": the instalment's position rather than the cheque number an
 * accountant searches for, and an ungrouped amount. Each item now carries the
 * facts it is built from so the web renders it in the viewer's language, and
 * the English fallback names the cheque and groups the amount.
 */
class DashboardServiceRecentActivityTest {

    private ChequeRepository chequeRepository;
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
        when(chequeRepository.totalsByStatus(any(), anyBoolean(), any())).thenReturn(List.of());
        when(chequeRepository.sumClearedBetween(any(), any(), any(), anyBoolean(), any()))
                .thenReturn(BigDecimal.ZERO);
        LeaseAccessPolicy leaseAccessPolicy = mock(LeaseAccessPolicy.class);
        when(leaseAccessPolicy.visiblePropertyIds()).thenReturn(null);
        service = new DashboardService(propertyRepository, unitRepository, leaseRepository,
                chequeRepository, leaseAccessPolicy);
    }

    private static Cheque cheque(int seqNo, String number) {
        Property property = new Property();
        property.setNameEn("Miftah Residences");
        Unit unit = new Unit();
        unit.setUnitNumber("G-01");
        Cheque c = new Cheque();
        c.setSeqNo(seqNo);
        c.setChequeNumber(number);
        c.setStatus(ChequeStatus.BOUNCED);
        c.setAmount(new BigDecimal("31500.00"));
        c.setProperty(property);
        c.setUnit(unit);
        c.setStatusChangedAt(Instant.parse("2026-09-20T08:00:00Z"));
        return c;
    }

    @Test
    void anItemNamesTheChequeNumberAndCarriesItsFacts() {
        when(chequeRepository.findRecentlyChanged(anyBoolean(), any(), any()))
                .thenReturn(List.of(cheque(2, "700102")));

        DashboardSummaryDTO.RecentActivityItem item = service.getSummary().getRecentActivity().getFirst();

        assertThat(item.getDescription())
                .isEqualTo("Cheque 700102 bounced - Unit G-01, Miftah Residences (AED 31,500.00)");
        assertThat(item.getChequeStatus()).isEqualTo("BOUNCED");
        assertThat(item.getChequeNumber()).isEqualTo("700102");
        assertThat(item.getSeqNo()).isEqualTo(2);
        assertThat(item.getUnitNumber()).isEqualTo("G-01");
        assertThat(item.getPropertyName()).isEqualTo("Miftah Residences");
        assertThat(item.getAmount()).isEqualByComparingTo("31500");
    }

    @Test
    void aChequeWithNoNumberFallsBackToItsPosition() {
        when(chequeRepository.findRecentlyChanged(anyBoolean(), any(), any()))
                .thenReturn(List.of(cheque(2, "  ")));

        DashboardSummaryDTO.RecentActivityItem item = service.getSummary().getRecentActivity().getFirst();

        assertThat(item.getDescription())
                .isEqualTo("Payment #2 bounced - Unit G-01, Miftah Residences (AED 31,500.00)");
        assertThat(item.getChequeNumber()).isNull();
    }
}
