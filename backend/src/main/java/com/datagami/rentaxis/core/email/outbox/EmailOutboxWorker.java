package com.datagami.rentaxis.core.email.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailOutboxWorker {

    private final EmailOutboxRepository repo;
    private final EmailOutboxService service;
    private final EmailOutboxRowProcessor processor;

    @Value("${rentaxis.email.outbox.batch-size:50}")
    private int batchSize;

    @Value("${rentaxis.email.outbox.enabled:true}")
    private boolean enabled = true;

    @Scheduled(fixedDelayString = "${rentaxis.email.outbox.tick-ms:30000}")
    @SchedulerLock(name = "email-outbox-worker", lockAtMostFor = "PT2M", lockAtLeastFor = "PT5S")
    public void tick() {
        if (!enabled) return;
        try {
            service.resetStuckSending();
        } catch (Exception e) {
            log.warn("email.outbox.sweeper_failed: {}", e.getMessage());
        }
        List<EmailOutbox> batch = repo.pickPending(batchSize);
        for (EmailOutbox row : batch) {
            processor.processOne(row);
        }
    }
}
