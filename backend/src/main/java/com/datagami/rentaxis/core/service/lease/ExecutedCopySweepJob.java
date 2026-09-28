package com.datagami.rentaxis.core.service.lease;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly retry of executed contract copies missed at posting (review R5),
 * its own job with its own lock, apart from lease expiry: a slow or failing sweep
 * must never hold up, or be held up by, the midnight expiry.
 *
 * <p>Only a retry: {@link ExecutedContractCopyService#sweepMissing} considers leases
 * posted in the last 7 days, after the organisation's current stamp was set, that
 * have not failed {@link ExecutedContractCopyService#MAX_ATTEMPTS} times already.
 * ShedLock so several API replicas sweep once.</p>
 */
@Component
@Slf4j
public class ExecutedCopySweepJob {

    private final ExecutedContractCopyService executedCopies;

    /** Kill switch, same style as the other nightly jobs. */
    @Value("${rentaxis.executed-copy-sweep.job.enabled:true}")
    private boolean enabled = true;

    public ExecutedCopySweepJob(ExecutedContractCopyService executedCopies) {
        this.executedCopies = executedCopies;
    }

    @Scheduled(cron = "${rentaxis.executed-copy-sweep.cron:0 30 1 * * *}")
    @SchedulerLock(name = "executed-copy-sweep", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void sweep() {
        if (!enabled) {
            log.info("Executed copy sweep is disabled (rentaxis.executed-copy-sweep.job.enabled=false); skipping");
            return;
        }
        try {
            executedCopies.sweepMissing(ExecutedContractCopyService.SWEEP_MAX,
                    ExecutedContractCopyService.SWEEP_MAX_PER_ORG);
        } catch (RuntimeException e) {
            log.error("Executed copy sweep failed", e);
        }
    }
}
