package com.datagami.rentaxis.core.email.outbox;

import com.datagami.rentaxis.core.email.send.EmailSender;
import com.datagami.rentaxis.core.email.send.SendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailOutboxWorker {

    private final EmailOutboxRepository repo;
    private final EmailSender sender;
    private final EmailOutboxService service;

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
            processOne(row);
        }
    }

    @Transactional
    public void processOne(EmailOutbox row) {
        row.setStatus(EmailOutbox.Status.SENDING);
        row.setLastAttemptAt(Instant.now());
        repo.saveAndFlush(row);
        try {
            SendResult r = sender.send(row);
            row.setAzureMessageId(r.azureMessageId());
            row.setAzureDeliveryStatus(r.azureDeliveryStatus());
            row.setStatus(EmailOutbox.Status.SENT);
            log.info("email.outbox.send.success id={} azure_id={} event={}",
                    row.getId(), r.azureMessageId(), row.getEventType());
        } catch (Exception e) {
            row.setAttempts(row.getAttempts() + 1);
            row.setLastError(truncate(e.getMessage()));
            if (row.getAttempts() >= row.getMaxAttempts()) {
                row.setStatus(EmailOutbox.Status.FAILED);
                log.error("email.outbox.send.fail_final id={} attempts={} error={}",
                        row.getId(), row.getAttempts(), e.getMessage());
            } else {
                row.setStatus(EmailOutbox.Status.PENDING);
                row.setScheduledAt(Instant.now().plus(service.backoff(row.getAttempts())));
                log.warn("email.outbox.send.fail_retry id={} attempts={} backoff_to={} error={}",
                        row.getId(), row.getAttempts(), row.getScheduledAt(), e.getMessage());
            }
        }
        repo.save(row);
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
