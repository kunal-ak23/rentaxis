package com.datagami.rentaxis.core.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Only visitor/gate events need an immediate device alert in this release. */
@Component
@RequiredArgsConstructor
public class PushNotificationListener {

    private final FirebasePushService firebasePushService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void deliver(PushNotificationEvent event) {
        if (event.type() != null && event.type().startsWith("GATE_")) {
            firebasePushService.send(event);
        }
    }
}
