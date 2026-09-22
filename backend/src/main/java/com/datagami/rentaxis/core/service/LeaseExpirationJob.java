package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A tenancy whose end date has passed becomes EXPIRED, and its unit becomes
 * available again (spec §9).
 *
 * <p><b>It does not touch the cheque register.</b> The job used to cancel every
 * uncollected instalment on the way past, and that was wrong in the one direction
 * that costs the landlord money: a held, undeposited cheque for the final month
 * is still an instrument against a debt the renter genuinely owes, and a lease
 * reaching its end date does not settle it. Deciding what happens to uncleared
 * paper — returned, banked, or carried into a renewal — is the termination and
 * settlement flow's job, where a human is looking at the contract. Recognition is
 * untouched for the mirror-image reason: the schedule's remaining rows are rent
 * that was earned, and the month-end close posts them on their own. Expiry is a
 * calendar fact and posts nothing.</p>
 *
 * <p><b>Per tenant, in a loop, with the context set each time.</b> The Hibernate
 * tenant filter is switched on by {@code TenantAspect} when a transaction opens,
 * reading {@code TenantContextHolder} — and this job has no HTTP request behind it
 * to have populated it. The candidate query has no tenant column of its own, so a
 * pass made without setting the context would hand one landlord's overdue leases
 * to whichever tenant happened to be current: a status change, a lease event and a
 * unit release written into books nobody asked it to touch. The context is set per
 * organisation and cleared in a {@code finally}, so a thrown tenant cannot leave
 * its id behind for the next one. Same shape as {@code RevenueRecognitionJob},
 * deliberately.</p>
 *
 * <p><b>One organisation's bad night is its own</b>, and inside an organisation
 * one lease's is its own too: both loops carry on past a failure, because a unit
 * whose listing sync throws must not stop the other thirty-nine landlords — or the
 * other nine leases — from ending their tenancies.</p>
 *
 * <p><b>ShedLock</b> because the API serves several instances behind Caddy and a
 * midnight sweep must happen once, not once per replica. A second pass is a no-op
 * (the leases it would find are already EXPIRED), but "harmless" is not a reason
 * to do the work twice.</p>
 */
@Service
public class LeaseExpirationJob {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpirationJob.class);

    private final LandlordOrgRepository orgs;
    private final LeaseService leaseService;

    /**
     * "Today" from the bean, never {@code LocalDate.now()}: a job whose date is
     * unfixable can only be tested by waiting for tomorrow.
     */
    private final Clock clock;

    public LeaseExpirationJob(LandlordOrgRepository orgs, LeaseService leaseService, Clock clock) {
        this.orgs = orgs;
        this.leaseService = leaseService;
        this.clock = clock;
    }

    /**
     * What one sweep did, so an operator — and a test — can tell "nothing was due"
     * from "everything was refused".
     *
     * @param tenants   organisations visited.
     * @param expired   leases moved to EXPIRED.
     * @param skipped   candidates the flip declined: renewed, terminated or extended
     *                  between the query and the update. Persistently non-zero means
     *                  the candidate query and the flip's precondition disagree.
     * @param failed    candidates whose flip threw.
     */
    public record ExpiryRun(int tenants, int expired, int skipped, int failed) {
    }

    /** Midnight daily: "today" has just turned over, so a term ending yesterday is over. */
    @Scheduled(cron = "0 0 0 * * ?")
    @SchedulerLock(name = "lease-expiration", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void evaluateExpiredLeases() {
        runFor(LocalDate.now(clock));
    }

    /**
     * The same sweep for an explicit date, so a support request or a catch-up after
     * an outage can be driven without moving the clock.
     *
     * <p><b>Interruption stops the loop.</b> A pass over every organisation is
     * minutes of work and a shutdown must not wait for the fortieth tenant. The flag
     * is checked between tenants, so whichever organisation is mid-sweep finishes
     * its own lease and none is left half-expired — each lease commits on its own
     * anyway.</p>
     */
    public ExpiryRun runFor(LocalDate today) {
        if (Thread.currentThread().isInterrupted()) {
            // Nothing has been read yet, so there is nothing to unwind. Refuse to
            // start rather than open transactions a shutdown is about to tear down.
            log.warn("Lease expiry not started for {}: the thread is already interrupted", today);
            return new ExpiryRun(0, 0, 0, 0);
        }
        log.info("Lease expiry starting for {}", today);
        // A previous caller on this thread — a test, an ops endpoint — may have left
        // one set, and the loop must not silently sweep tenant A under tenant B's id.
        TenantContextHolder.clear();
        int tenants = 0;
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        try {
            for (LandlordOrg org : orgs.findAll()) {
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Lease expiry interrupted for {} after {} tenants, {} leases expired",
                            today, tenants, expired);
                    return new ExpiryRun(tenants, expired, skipped, failed);
                }
                tenants++;
                ExpiryRun one = sweep(org.getId(), today);
                expired += one.expired();
                skipped += one.skipped();
                failed += one.failed();
            }
        } finally {
            TenantContextHolder.clear();
        }
        log.info("Lease expiry finished for {}: {} tenants, {} expired, {} skipped, {} failed",
                today, tenants, expired, skipped, failed);
        return new ExpiryRun(tenants, expired, skipped, failed);
    }

    /**
     * One organisation's sweep, under its own tenant context.
     *
     * <p>Package-private so the isolation guarantee can be asserted directly — "this
     * landlord's sweep left the other landlord alone" is not a statement a test can
     * make about a loop over every tenant.</p>
     *
     * @return how many leases this tenant expired; 0 if its sweep failed outright.
     */
    int runTenant(UUID tenantId, LocalDate today) {
        return sweep(tenantId, today).expired();
    }

    private ExpiryRun sweep(UUID tenantId, LocalDate today) {
        TenantContextHolder.setTenantId(tenantId);
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        try {
            List<UUID> candidates = leaseService.findLeasesToExpire(today);
            for (UUID leaseId : candidates) {
                try {
                    if (leaseService.markExpired(leaseId, today)) {
                        expired++;
                        log.info("Lease {} marked as EXPIRED", leaseId);
                    } else {
                        // Renewed, terminated or extended since the query above.
                        skipped++;
                    }
                } catch (Exception e) {
                    // One contract's listing sync or unit release is not the other
                    // nine's problem.
                    failed++;
                    log.error("Lease {} could not be expired: {}", leaseId, e.getMessage(), e);
                }
            }
            log.info("lease expiry tenant_id={} candidates={} expired={} skipped={} failed={}",
                    tenantId, candidates.size(), expired, skipped, failed);
        } catch (Exception e) {
            if (wasInterrupted(e)) {
                // Catching the exception cleared the flag; the loop above reads it to
                // decide whether to carry on, so putting it back is what actually
                // stops the pass.
                Thread.currentThread().interrupt();
                log.warn("Lease expiry interrupted while sweeping tenant {}", tenantId);
                return new ExpiryRun(1, expired, skipped, failed);
            }
            // One organisation's bad night is its own.
            log.error("Lease expiry failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return new ExpiryRun(1, expired, skipped, failed + 1);
        } finally {
            TenantContextHolder.clear();
        }
        return new ExpiryRun(1, expired, skipped, failed);
    }

    /**
     * Whether this failure is really a shutdown signal — the cause chain, not just
     * the exception, because nothing on this path declares
     * {@code InterruptedException} and it arrives wrapped. Depth-bounded: a self-
     * or mutually-referencing cause is rare but real, and a shutdown path is the
     * last place to spin forever.
     */
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
