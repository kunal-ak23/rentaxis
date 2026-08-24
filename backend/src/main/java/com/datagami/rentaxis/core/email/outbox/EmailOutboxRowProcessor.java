package com.datagami.rentaxis.core.email.outbox;

import com.datagami.rentaxis.core.email.send.EmailSender;
import com.datagami.rentaxis.core.email.send.SendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Processes one outbox row without holding a database transaction across the
 * external provider call. Claim and finalization are separate short
 * transactions in {@link EmailOutboxService}; this prevents a slow provider
 * from retaining row locks and blocking tenant cleanup or other workers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailOutboxRowProcessor {

    private final EmailSender sender;
    private final EmailOutboxService service;

    public void processOne(EmailOutbox row) {
        var claimed = service.claimForSending(row.getId());
        if (claimed.isEmpty()) {
            log.debug("email.outbox.send.skip_unclaimed id={}", row.getId());
            return;
        }
        EmailOutbox sending = claimed.get();
        try {
            SendResult result = sender.send(sending);
            if (service.markSent(sending.getId(), result)) {
                log.info("email.outbox.send.success id={} azure_id={} event={}",
                        sending.getId(), result.azureMessageId(), sending.getEventType());
            } else {
                log.info("email.outbox.send.result_discarded id={} reason=row_deleted", sending.getId());
            }
        } catch (Exception e) {
            var failed = service.markFailedAttempt(sending.getId(), e.getMessage());
            if (failed.isEmpty()) {
                log.info("email.outbox.send.failure_discarded id={} reason=row_deleted", sending.getId());
            } else if (failed.get().getStatus() == EmailOutbox.Status.FAILED) {
                log.error("email.outbox.send.fail_final id={} attempts={} error={}",
                        sending.getId(), failed.get().getAttempts(), e.getMessage());
            } else {
                log.warn("email.outbox.send.fail_retry id={} attempts={} backoff_to={} error={}",
                        sending.getId(), failed.get().getAttempts(), failed.get().getScheduledAt(), e.getMessage());
            }
        }
    }
}
