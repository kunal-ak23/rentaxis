package com.datagami.rentaxis.core.service.rating;

import com.datagami.rentaxis.core.service.TenantFeatureService;
import com.datagami.rentaxis.core.service.TenantFeatureToggledEvent;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Nightly recompute of every rated renter, per org with the feature on. Each run also writes the current
 * month's snapshot for renters that have none yet, so a month whose 1st was missed (job down, feature
 * enabled mid-month) still gets one.
 */
@Component
@Slf4j
public class RenterRatingJob {

    static final int BATCH_SIZE = 500;

    private final LandlordOrgRepository orgRepository;
    private final TenantFeatureService tenantFeatureService;
    private final RenterHistoryLoader loader;
    private final RenterRatingService service;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final Clock clock;

    @Value("${app.renter-rating.job.enabled:true}")
    private boolean enabled;

    public RenterRatingJob(LandlordOrgRepository orgRepository, TenantFeatureService tenantFeatureService,
                           RenterHistoryLoader loader, RenterRatingService service,
                           PlatformTransactionManager txManager,
                           @Qualifier("renterRatingExecutor") Executor executor, Clock clock) {
        this.orgRepository = orgRepository;
        this.tenantFeatureService = tenantFeatureService;
        this.loader = loader;
        this.service = service;
        this.tx = new TransactionTemplate(txManager);
        this.executor = executor;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "renter-rating", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void runDaily() {
        if (!enabled) {
            log.debug("Renter rating job disabled via flag");
            return;
        }
        runNow(LocalDate.now(clock));
    }

    public void runNow(LocalDate today) {
        log.info("Renter rating job starting for {}", today);
        TenantContextHolder.clear();
        try {
            List<LandlordOrg> orgs = orgRepository.findAll();
            for (LandlordOrg org : orgs) {
                if (!tenantFeatureService.isEnabled(org.getId(), TenantFeature.RENTER_RATING)) {
                    log.debug("rating.skipped reason=feature_disabled tenant_id={}", org.getId());
                    continue;
                }
                runNowForTenant(org.getId(), today);
            }
        } finally {
            TenantContextHolder.clear();
        }
        log.info("Renter rating job finished");
    }

    /** Recomputes one org (no feature check: callers decide). A failure is logged, never thrown. */
    public void runNowForTenant(UUID tenantId, LocalDate today) {
        UUID previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            LocalDate month = today.withDayOfMonth(1);
            UUID after = null;
            int written = 0;
            while (true) {
                List<UUID> ids = loader.ratedRenterIds(tenantId, after, BATCH_SIZE);
                if (ids.isEmpty()) break;
                try {
                    Integer n = tx.execute(status -> {
                        int w = service.recomputeAndStore(tenantId, ids, today);
                        service.snapshotMonth(tenantId, ids, month);
                        return w;
                    });
                    written += n == null ? 0 : n;
                } catch (Exception e) {
                    log.error("Renter rating tenant {} batch after {} failed: {}", tenantId, after, e.getMessage(), e);
                }
                after = ids.get(ids.size() - 1);
                if (ids.size() < BATCH_SIZE) break;
            }
            log.info("Renter rating tenant {}: written={}", tenantId, written);
        } catch (Exception e) {
            log.error("Renter rating tenant {} failed: {}", tenantId, e.getMessage(), e);
        } finally {
            if (previous == null) TenantContextHolder.clear(); else TenantContextHolder.setTenantId(previous);
        }
    }

    @EventListener
    public void onFeatureToggled(TenantFeatureToggledEvent e) {
        if (e.feature() == TenantFeature.RENTER_RATING && e.enabled()) {
            LocalDate today = LocalDate.now(clock);
            executor.execute(() -> runNowForTenant(e.tenantId(), today));
        }
    }
}
