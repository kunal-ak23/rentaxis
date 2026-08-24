package com.datagami.rentaxis.core.service;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers durable exact-object cleanup work after crashes or transient failures. */
@Component
@Slf4j
public class TenantArtifactCleanupRecoveryJob {

    private final TenantArtifactCleanupService cleanupService;

    @Value("${rentaxis.tenant-artifact-cleanup.enabled:true}")
    private boolean enabled;

    @Value("${rentaxis.tenant-artifact-cleanup.max-automatic-attempts:10}")
    private int maxAutomaticAttempts;

    @Value("${rentaxis.tenant-artifact-cleanup.batch-tenants:25}")
    private int batchTenants;

    public TenantArtifactCleanupRecoveryJob(TenantArtifactCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(
            initialDelayString = "${rentaxis.tenant-artifact-cleanup.initial-delay-ms:300000}",
            fixedDelayString = "${rentaxis.tenant-artifact-cleanup.tick-ms:300000}")
    @SchedulerLock(
            name = "tenant-artifact-cleanup-recovery",
            lockAtMostFor = "PT4M",
            lockAtLeastFor = "PT5S")
    public void recover() {
        if (!enabled) {
            return;
        }
        try {
            TenantArtifactCleanupService.CleanupReport report =
                    cleanupService.recoverUnfinished(maxAutomaticAttempts, batchTenants);
            if (report.attempted() > 0) {
                log.info("tenant_artifact_cleanup recovery attempted={} deleted={} skipped={} failed={}",
                        report.attempted(), report.deleted(),
                        report.skippedReferenced(), report.failed());
            }
        } catch (Exception e) {
            log.error("tenant_artifact_cleanup recovery tick failed", e);
        }
    }
}
