package com.datagami.rentaxis.core.email.event;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import java.time.Instant;
import java.util.UUID;

/**
 * One recorded renewal answer. {@code capturedAt} identifies this answer among the
 * renter's changes of mind (break-it R3 portal3 F3): the staff e-mail is deduplicated
 * per answer, so a replay of this event sends nothing new but a changed answer does.
 */
public record RenewalIntentCapturedEvent(
        UUID opportunityId,
        UUID leaseId,
        UUID tenantId,
        RenewalIntent intent,
        Instant capturedAt
) {
    /** The staff e-mail's outbox key: per opportunity, per answer, per time it was given. */
    public String emailDedupKey() {
        return "RENEWAL_INTENT_CAPTURED:" + opportunityId + ":" + intent
                + (capturedAt != null ? ":" + capturedAt.toEpochMilli() : "");
    }
}
