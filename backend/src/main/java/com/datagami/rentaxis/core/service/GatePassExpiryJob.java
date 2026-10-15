package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.repository.GatePassRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;

/**
 * Stores EXPIRED on gate passes whose visit window has closed.
 *
 * <p>EXPIRED used to be written only when a guard scanned the pass, so a pass nobody
 * scanned stayed ACTIVE in the database forever: the resident's list showed it as
 * Active (with Cancel) days after the visit, and every report filtered by status
 * counted it as live. Readers already derive the status ({@code GatePass#effectiveStatus});
 * this job makes the stored value agree within a few minutes, so status filters and
 * exports do too.</p>
 *
 * <p>One bulk UPDATE across organisations rather than a per-tenant loop: the change is
 * the same calendar fact for every row, reads nothing back and writes nothing that
 * belongs to another row, so there is no tenant context to get wrong. ShedLock so
 * several API replicas do not each run it.</p>
 */
@Service
public class GatePassExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(GatePassExpiryJob.class);
    private static final EnumSet<GatePassStatus> LIVE =
            EnumSet.of(GatePassStatus.ACTIVE, GatePassStatus.PENDING_APPROVAL);

    private final GatePassRepository gatePassRepository;
    private final Clock clock;

    public GatePassExpiryJob(GatePassRepository gatePassRepository, Clock clock) {
        this.gatePassRepository = gatePassRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "${rentaxis.gatepass-expiry.cron:0 */10 * * * *}")
    @SchedulerLock(name = "gatepass-expiry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void expireEndedPasses() {
        runAt(Instant.now(clock));
    }

    /**
     * The sweep for an explicit instant, so a test need not wait for a window to close.
     * The repository method carries its own transaction: this is reached by a
     * self-call from the scheduled method, which no proxy sees.
     */
    public int runAt(Instant now) {
        int expired = gatePassRepository.expireEndedPasses(now, LIVE);
        if (expired > 0) log.info("Gate pass expiry: {} passes past their window marked EXPIRED", expired);
        return expired;
    }
}
