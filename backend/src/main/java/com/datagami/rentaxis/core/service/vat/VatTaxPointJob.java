package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.vat.VatTaxPointRunResult;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The nightly VAT tax point run (spec 2026-09-24 §1): every organisation's PLANNED
 * tax points dated today or earlier, posted as {@code VTP}s with their tax invoices.
 *
 * <p>{@code RevenueRecognitionJob}'s pattern, point for point, and for its reasons:</p>
 * <ul>
 *   <li><b>Per tenant, context set and cleared each time</b> — the Hibernate tenant
 *       filter reads {@code TenantContextHolder}, and a job has no request to fill
 *       it. {@code VatTaxPointService.runTo} refuses an empty context.</li>
 *   <li><b>One organisation's bad night is its own</b> — every tenant is wrapped,
 *       and inside a tenant every point posts in its own transaction.</li>
 *   <li><b>Not transactional</b> at any level — a short read, then one transaction
 *       per point.</li>
 *   <li><b>ShedLock</b>, so several replicas run it once; the poster's row lock makes
 *       a double run harmless anyway.</li>
 *   <li><b>A kill switch</b> on the nightly trigger only
 *       ({@code rentaxis.vat.tax-point-job.enabled}, default true); {@link #runFor}
 *       stays available to an operator.</li>
 *   <li><b>Interruption stops the loop</b> between tenants.</li>
 * </ul>
 *
 * <p>00:40, ten minutes after recognition, so the two nightly writers do not queue
 * behind each other on the same tenants' sequence rows.</p>
 */
@Component
public class VatTaxPointJob {

    private static final Logger log = LoggerFactory.getLogger(VatTaxPointJob.class);

    private final LandlordOrgRepository orgs;
    private final VatTaxPointService vatTaxPoints;
    private final Clock clock;

    @Value("${rentaxis.vat.tax-point-job.enabled:true}")
    private boolean enabled;

    public VatTaxPointJob(LandlordOrgRepository orgs, VatTaxPointService vatTaxPoints, Clock clock) {
        this.orgs = orgs;
        this.vatTaxPoints = vatTaxPoints;
        this.clock = clock;
    }

    @Scheduled(cron = "0 40 0 * * *")
    @SchedulerLock(name = "vat-tax-points", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        if (!enabled) {
            log.info("VAT tax point run is disabled (rentaxis.vat.tax-point-job.enabled=false); skipping tonight's pass");
            return;
        }
        runFor(LocalDate.now(clock));
    }

    /** The same pass for an explicit date; not gated by the kill switch. */
    public void runFor(LocalDate today) {
        if (Thread.currentThread().isInterrupted()) {
            log.warn("VAT tax point run not started for {}: the thread is already interrupted", today);
            return;
        }
        log.info("VAT tax point run starting for {}", today);
        TenantContextHolder.clear();
        int tenants = 0;
        int posted = 0;
        try {
            List<LandlordOrg> all = orgs.findAll();
            for (LandlordOrg org : all) {
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("VAT tax point run interrupted for {} after {} tenants, {} points posted",
                            today, tenants, posted);
                    return;
                }
                tenants++;
                posted += runTenant(org.getId(), today);
            }
        } finally {
            TenantContextHolder.clear();
        }
        log.info("VAT tax point run finished for {}: {} tenants, {} points posted", today, tenants, posted);
    }

    private int runTenant(UUID tenantId, LocalDate today) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            VatTaxPointRunResult result = vatTaxPoints.runTo(today, false);
            if (result.wouldPost() > 0 || result.skippedLocked() > 0 || !result.errors().isEmpty()) {
                log.info("vat-tax-points tenant_id={} posted={} vat={} skipped_locked={} failed={}",
                        tenantId, result.posted(), result.vatAmount(), result.skippedLocked(), result.errors().size());
            }
            result.errors().forEach(e -> log.warn("vat-tax-points tenant_id={} refused: {}", tenantId, e));
            return result.posted();
        } catch (Exception e) {
            if (wasInterrupted(e)) {
                Thread.currentThread().interrupt();
                log.warn("VAT tax point run interrupted while running tenant {}", tenantId);
                return 0;
            }
            log.error("VAT tax point run failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return 0;
        } finally {
            TenantContextHolder.clear();
        }
    }

    static boolean wasInterrupted(Throwable t) {
        Throwable c = t;
        for (int depth = 0; c != null && depth < 16; depth++, c = c.getCause()) {
            if (c instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }
}
