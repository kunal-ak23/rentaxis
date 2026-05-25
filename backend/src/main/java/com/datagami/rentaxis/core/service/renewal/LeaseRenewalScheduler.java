package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.RenewalOpportunity;
import com.datagami.rentaxis.domain.entity.enums.RenewalStage;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenewalOpportunityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class LeaseRenewalScheduler {

    private final LandlordOrgRepository orgRepository;
    private final RenewalOpportunityRepository opportunityRepository;
    private final RenewalOpportunityService opportunityService;
    private final RenewalReminderService reminderService;
    private final TenantFeatureService tenantFeatureService;

    @Value("${app.renewal.scheduler.enabled:false}")
    private boolean enabled;

    @Scheduled(cron = "0 0 8 * * *")
    public void runDaily() {
        if (!enabled) {
            log.debug("Renewal scheduler disabled via flag");
            return;
        }
        runNow(LocalDate.now());
    }

    /** Public entry point usable from a SUPER_ADMIN ops endpoint. */
    public void runNow(LocalDate today) {
        log.info("Lease renewal scheduler starting for {}", today);
        TenantContextHolder.clear();
        try {
            List<LandlordOrg> tenants = orgRepository.findAll();
            for (LandlordOrg org : tenants) {
                processTenant(org.getId(), today);
            }
        } finally {
            TenantContextHolder.clear();
        }
        log.info("Lease renewal scheduler finished");
    }

    private void processTenant(UUID tenantId, LocalDate today) {
        // Per-tenant gate: only process companies that have opted into renewals during phased
        // rollout. Mirrors the EMAIL_NOTIFICATIONS kill-switch so the org-wide run-now is safe.
        if (!tenantFeatureService.isEnabled(tenantId, TenantFeature.LEASE_RENEWALS)) {
            log.debug("renewal.skipped reason=feature_disabled tenant_id={}", tenantId);
            return;
        }
        TenantContextHolder.setTenantId(tenantId);
        try {
            int opened = opportunityService.openOpportunitiesForCurrentTenant(today);
            List<RenewalOpportunity> active = opportunityRepository
                    .findByTenantIdAndStageIn(tenantId, List.of(RenewalStage.OPEN, RenewalStage.INTENT_CAPTURED));
            int fired = 0;
            for (RenewalOpportunity o : active) {
                try {
                    reminderService.fireRemindersForOpportunity(o.getId(), today);
                    fired++;
                } catch (Exception e) {
                    log.error("Failed to process reminders for opportunity {}: {}", o.getId(), e.getMessage(), e);
                }
            }
            int closed = opportunityService.closeStaleOpportunitiesForCurrentTenant();
            log.info("Tenant {}: opened={}, fired={}, closed={}", tenantId, opened, fired, closed);
        } catch (Exception e) {
            log.error("Tenant {} processing failed: {}", tenantId, e.getMessage(), e);
        } finally {
            TenantContextHolder.clear();
        }
    }
}
