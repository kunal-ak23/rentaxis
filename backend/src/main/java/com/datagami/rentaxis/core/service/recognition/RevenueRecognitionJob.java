package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.core.service.recognition.RecognitionService.RecognitionRunResult;
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
 *
 * <p><b>{@code rentaxis.recognition.job.enabled}</b> (default true) stops the
 * nightly trigger without a deploy. It gates {@link #run()} only — see the field.</p>
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

    /**
     * The kill switch, on the nightly trigger only.
     *
     * <p>This is the one job in the module that writes journals with nobody
     * watching. If a close starts producing bad {@code CIL}s at 00:30 the fix has
     * to be available in the time it takes to set an environment variable and
     * restart, not in the time it takes to cut a release — which is why both
     * sibling schedulers carry one ({@code app.renewal.scheduler.enabled},
     * {@code rentaxis.tenant-artifact-cleanup.enabled}).</p>
     *
     * <p><b>Default true.</b> Recognition is not an optional feature being rolled
     * out; a deployment that forgets the variable must still close its months.
     * {@link #runFor(LocalDate)} is deliberately <em>not</em> gated: with the
     * nightly pass off, the manual close and a cut-over catch-up are exactly how an
     * operator finishes the month by hand.</p>
     */
    @Value("${rentaxis.recognition.job.enabled:true}")
    private boolean enabled;

    private final RecognitionRunLog runLog;

    /** The nightly slot, matching {@link #run()}'s cron. */
    static final java.time.LocalTime NIGHTLY_AT = java.time.LocalTime.of(0, 30);

    /** F14-63: how long after {@link #NIGHTLY_AT} the catch-up waits for the nightly pass before running it. */
    @Value("${rentaxis.recognition.job.catch-up-grace-minutes:30}")
    private long catchUpGraceMinutes = 30;

    /** Off in the test suite (src/test/resources), whose shared database must not be closed behind a test's back. */
    @Value("${rentaxis.recognition.job.catch-up-enabled:true}")
    private boolean catchUpEnabled;

    public RevenueRecognitionJob(LandlordOrgRepository orgs, RecognitionService recognition, Clock clock,
                                 RecognitionRunLog runLog) {
        this.orgs = orgs;
        this.recognition = recognition;
        this.clock = clock;
        this.runLog = runLog;
    }

    /**
     * F14-27: the catch-up. The nightly trigger fires once, at 00:30 app time
     * (Asia/Dubai, the JVM default zone), and a pass that falls inside a deploy's
     * restart window is simply lost: nothing ran it again until the next night.
     * The production deploy of 23/09 ran 20:25–20:33 UTC, which spans 00:30 Dubai
     * on 24/09 — the most likely reason leases posted on 23/09 kept every ended
     * period PLANNED all of 24/09 (no application log was available to confirm).
     *
     * <p>Every hour (first check a few minutes after start-up) this asks whether the
     * last recorded nightly pass was for an earlier day and, once the nightly hour
     * has passed, runs the pass for today under the same ShedLock name. The pass is
     * idempotent: it only posts PLANNED rows whose period has ended.</p>
     *
     * <p>F14-63: an organisation with <em>no</em> pass recorded is behind too, so
     * the first night after a deploy is caught up like any other: once 00:30 plus
     * {@code rentaxis.recognition.job.catch-up-grace-minutes} (default 30) has
     * passed and some organisation has no pass recorded for today, today's pass
     * runs.</p>
     */
    @Scheduled(initialDelayString = "${rentaxis.recognition.job.catch-up-initial-delay-ms:300000}",
            fixedDelayString = "${rentaxis.recognition.job.catch-up-interval-ms:3600000}")
    @SchedulerLock(name = "revenue-recognition", lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void catchUp() {
        if (!enabled || !catchUpEnabled) return;
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        // F14-63: only once the nightly slot (00:30) plus a grace has passed, so the
        // catch-up never races tonight's own pass.
        if (now.toLocalTime().isBefore(NIGHTLY_AT.plusMinutes(catchUpGraceMinutes))) return;
        // F14-63: "no pass recorded" is itself behind — the first night after the
        // deploy that created recognition_runs, or an organisation added since.
        if (!runLog.anyBehind(today)) return;
        log.warn("Revenue recognition: today's pass has not run (oldest recorded pass: {}); catching up for {}",
                runLog.oldestLastRunFor(), today);
        runFor(today);
    }

    /**
     * 00:30 daily — after midnight, so "today" has turned over and a period ending
     * yesterday is closed, and off the hour so it does not queue behind the other
     * midnight jobs.
     */
    @Scheduled(cron = "0 30 0 * * *")
    @SchedulerLock(name = "revenue-recognition", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        if (!enabled) {
            // One line, at INFO: an operator who turned this off wants to see in the
            // log that it stayed off, not silence they have to distinguish from a
            // scheduler that never fired.
            log.info("Revenue recognition is disabled (rentaxis.recognition.job.enabled=false); skipping tonight's pass");
            return;
        }
        runFor(LocalDate.now(clock));
    }

    /**
     * The same pass for an explicit date, so a cut-over catch-up or a support
     * request can be driven without moving the clock.
     *
     * <p>Unlike the HTTP endpoint this does not refuse a future date — it is not
     * reachable from the network, and the caller is the scheduler passing today.
     * Unlike {@link #run()} it is not gated by the kill switch: turning the nightly
     * pass off is how an operator takes the close back into their own hands, not
     * how they lose it.</p>
     *
     * <p><b>Interruption stops the loop.</b> A pass over every organisation is
     * minutes of work; a shutdown that asks it to stop must not have to wait for
     * the fortieth tenant. The flag is checked between tenants, so whichever
     * organisation is mid-close finishes its own entry and no tenant is left
     * half-posted (each entry commits on its own anyway — see
     * {@link RecognitionPoster}).</p>
     */
    public void runFor(LocalDate today) {
        if (Thread.currentThread().isInterrupted()) {
            // Nothing has been read yet, so there is nothing to unwind. Refuse to
            // start rather than open transactions a shutdown is about to tear down.
            log.warn("Revenue recognition not started for {}: the thread is already interrupted", today);
            return;
        }
        log.info("Revenue recognition starting for {}", today);
        // A previous caller on this thread — a test, an ops endpoint — may have left
        // one set, and the loop must not silently run tenant A under tenant B's id.
        TenantContextHolder.clear();
        int tenants = 0;
        int posted = 0;
        try {
            List<LandlordOrg> all = orgs.findAll();
            for (LandlordOrg org : all) {
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Revenue recognition interrupted for {} after {} tenants, {} entries posted",
                            today, tenants, posted);
                    return;
                }
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
            // F14-27: a row that failed is not only a WARN line: the recognition
            // screen reads what the last pass could not post.
            runLog.record(tenantId, today, result.posted(), result.errors());
            log.info("recognition tenant_id={} posted={} amount={} skipped_locked={} failed={}",
                    tenantId, result.posted(), result.amount(), result.skippedLocked(), result.errors().size());
            result.errors().forEach(e -> log.warn("recognition tenant_id={} refused: {}", tenantId, e));
            return result.posted();
        } catch (Exception e) {
            if (wasInterrupted(e)) {
                // Catching the exception cleared the flag; the loop above reads it to
                // decide whether to carry on, so putting it back is what actually
                // stops the pass. A shutdown is not a tenant's mapping gap and is not
                // logged as one.
                Thread.currentThread().interrupt();
                log.warn("Revenue recognition interrupted while closing tenant {}", tenantId);
                return 0;
            }
            // One organisation's mapping gap is not the other forty's problem.
            log.error("Revenue recognition failed for tenant {}: {}", tenantId, e.getMessage(), e);
            try {
                runLog.record(tenantId, today, 0, List.of("The pass failed: " + e.getMessage()));
            } catch (RuntimeException ignored) {
                // The log line above is the record of last resort.
            }
            return 0;
        } finally {
            TenantContextHolder.clear();
        }
    }

    /**
     * Whether this failure is really a shutdown signal.
     *
     * <p>The cause chain, not just the exception: nothing on the posting path
     * declares {@code InterruptedException}, so when a pass is interrupted it
     * arrives wrapped — Hikari's connection wait and Hibernate both surface it
     * inside a {@code RuntimeException}. Testing only the outer type is the same
     * bug as not testing at all.</p>
     *
     * <p>Package-private so the chain walk can be asserted as a table, rather than
     * by contriving a real shutdown mid-pass.</p>
     */
    static boolean wasInterrupted(Throwable t) {
        // Depth-bounded rather than following the chain to its end: a self- or
        // mutually-referencing cause is rare but real, and a shutdown path is the
        // last place to spin forever.
        Throwable c = t;
        for (int depth = 0; c != null && depth < 16; depth++, c = c.getCause()) {
            if (c instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }
}
