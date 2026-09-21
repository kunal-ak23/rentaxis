package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.core.service.recognition.RecognitionService.RecognitionRunResult;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The nightly close (spec §8.4): every organisation's planned recognition rows
 * whose period has ended, posted while nobody is looking.
 *
 * <p><b>Per tenant, in a loop, with the context set each time.</b> The Hibernate
 * tenant filter is switched on by {@code TenantAspect} when a transaction opens,
 * reading {@code TenantContextHolder} — and this job has no HTTP request behind
 * it to have populated it. A pass made without setting it would run every
 * organisation's rows through whichever tenant happened to be current, which is a
 * cross-tenant leak of the worst kind: journals in somebody else's ledger, under
 * somebody else's entry numbers. {@code RecognitionService} refuses outright when
 * the context is empty rather than answering "0 posted"; this class is what fills
 * it, and clears it again in a {@code finally} so a thrown tenant cannot leave its
 * id behind for the next one.</p>
 *
 * <p><b>One organisation's bad night is its own.</b> A tenant whose property lost
 * its {@code ADVANCE_RENT} mapping must not stop the other forty from closing, so
 * every tenant is wrapped: the failure is logged against its id and the loop goes
 * on. Inside a tenant the same is already true row by row — see
 * {@link RecognitionPoster}.</p>
 *
 * <p><b>Not transactional, at any level.</b> {@link RecognitionService#runTo}
 * takes a short read-only transaction for its candidates and then one per entry;
 * wrapping the loop would hold a second connection open for the length of the
 * whole run, per tenant.</p>
 *
 * <p><b>ShedLock</b> because the API serves several instances behind Caddy and a
 * nightly run must happen once, not once per replica. The row lock in
 * {@code RecognitionPoster} makes a double run harmless rather than
 * double-posting, but "harmless" is not a reason to do the work twice.</p>
 */
@Component
public class RevenueRecognitionJob {

    private static final Logger log = LoggerFactory.getLogger(RevenueRecognitionJob.class);

    private final LandlordOrgRepository orgs;
    private final RecognitionService recognition;

    /**
     * "Today" from the bean, never {@code LocalDate.now()}: a job whose date is
     * unfixable can only be tested by waiting for tomorrow.
     */
    private final Clock clock;

    public RevenueRecognitionJob(LandlordOrgRepository orgs, RecognitionService recognition, Clock clock) {
        this.orgs = orgs;
        this.recognition = recognition;
        this.clock = clock;
    }

    /**
     * 00:30 daily — after midnight, so "today" has turned over and a period ending
     * yesterday is closed, and off the hour so it does not queue behind the other
     * midnight jobs.
     */
    @Scheduled(cron = "0 30 0 * * *")
    @SchedulerLock(name = "revenue-recognition", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        runFor(LocalDate.now(clock));
    }

    /**
     * The same pass for an explicit date, so a cut-over catch-up or a support
     * request can be driven without moving the clock.
     *
     * <p>Unlike the HTTP endpoint this does not refuse a future date — it is not
     * reachable from the network, and the caller is the scheduler passing today.</p>
     */
    public void runFor(LocalDate today) {
        log.info("Revenue recognition starting for {}", today);
        // A previous caller on this thread — a test, an ops endpoint — may have left
        // one set, and the loop must not silently run tenant A under tenant B's id.
        TenantContextHolder.clear();
        int tenants = 0;
        int posted = 0;
        try {
            List<LandlordOrg> all = orgs.findAll();
            for (LandlordOrg org : all) {
                tenants++;
                posted += runTenant(org.getId(), today);
            }
        } finally {
            TenantContextHolder.clear();
        }
        log.info("Revenue recognition finished for {}: {} tenants, {} entries posted", today, tenants, posted);
    }

    /** @return how many entries this tenant posted; 0 if its run failed outright. */
    private int runTenant(UUID tenantId, LocalDate today) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            RecognitionRunResult result = recognition.runTo(today, false);
            log.info("recognition tenant_id={} posted={} amount={} skipped_locked={} failed={}",
                    tenantId, result.posted(), result.amount(), result.skippedLocked(), result.errors().size());
            result.errors().forEach(e -> log.warn("recognition tenant_id={} refused: {}", tenantId, e));
            return result.posted();
        } catch (Exception e) {
            // One organisation's mapping gap is not the other forty's problem.
            log.error("Revenue recognition failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return 0;
        } finally {
            TenantContextHolder.clear();
        }
    }
}
