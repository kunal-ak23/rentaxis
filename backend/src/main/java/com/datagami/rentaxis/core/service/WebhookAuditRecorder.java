package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.WebhookLog;
import com.datagami.rentaxis.domain.repository.WebhookLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one line of a webhook delivery that must survive whatever else happens to
 * it.
 *
 * <p>{@code WebhookService} records every delivery — verified, rejected, malformed
 * or broken — and that row is the only durable trace of a delivery that did not
 * work. It cannot be written in the handler's own transaction and be relied upon:
 * a delivery the handler answers with a 5xx (so the gateway redelivers it)
 * propagates an exception, the transaction rolls back, and the audit row goes with
 * it — losing the record of exactly the anomaly worth recording, on the path where
 * it matters most.</p>
 *
 * <p>{@code REQUIRES_NEW} is what makes it independent, and it is a separate bean
 * because a self-invocation would not go through the proxy and would silently join
 * the caller's transaction instead.</p>
 */
@Component
@RequiredArgsConstructor
public class WebhookAuditRecorder {

    private final WebhookLogRepository webhookLogRepository;

    /** Commit this row on its own, whatever the caller's transaction goes on to do. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(WebhookLog log) {
        webhookLogRepository.save(log);
    }
}
