package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaseRenewalSchedulerTargetedTest {

    @Mock LandlordOrgRepository orgRepository;
    @Mock RenewalOpportunityRepository opportunityRepository;
    @Mock RenewalOpportunityService opportunityService;
    @Mock RenewalReminderService reminderService;
    @Mock TenantFeatureService tenantFeatureService;

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    @Test
    void runNowForTenantProcessesOnlyTheRequestedTenant() {
        UUID tenantId = UUID.randomUUID();
        LocalDate today = LocalDate.of(2026, 8, 23);
        LeaseRenewalScheduler scheduler = scheduler();
        when(orgRepository.existsById(tenantId)).thenReturn(true);
        when(tenantFeatureService.isEnabled(tenantId, TenantFeature.LEASE_RENEWALS))
                .thenReturn(true);
        when(opportunityRepository.findByTenantIdAndStageIn(tenantId, List.of(
                com.datagami.rentaxis.domain.entity.enums.RenewalStage.OPEN,
                com.datagami.rentaxis.domain.entity.enums.RenewalStage.INTENT_CAPTURED)))
                .thenReturn(List.of());

        scheduler.runNowForTenant(tenantId, today);

        verify(orgRepository, never()).findAll();
        verify(opportunityService).openOpportunitiesForCurrentTenant(today);
        verify(opportunityService).closeStaleOpportunitiesForCurrentTenant();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    void runNowForTenantRejectsUnknownTenantWithoutProcessingAnything() {
        UUID tenantId = UUID.randomUUID();
        LeaseRenewalScheduler scheduler = scheduler();
        when(orgRepository.existsById(tenantId)).thenReturn(false);

        assertThatThrownBy(() -> scheduler.runNowForTenant(tenantId, LocalDate.now()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Tenant not found");

        verify(tenantFeatureService, never())
                .isEnabled(tenantId, TenantFeature.LEASE_RENEWALS);
        verify(opportunityService, never()).openOpportunitiesForCurrentTenant(
                org.mockito.ArgumentMatchers.any());
    }

    private LeaseRenewalScheduler scheduler() {
        return new LeaseRenewalScheduler(
                orgRepository,
                opportunityRepository,
                opportunityService,
                reminderService,
                tenantFeatureService);
    }
}
