package com.datagami.rentaxis.core.service.cheque;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR #400 review P3-3: one file, one stored scan; a failed attempt is forgotten. */
class ChequeExtractionIdempotencyTest {

    private final ChequeExtractionIdempotency once = new ChequeExtractionIdempotency();
    private final UUID tenant = UUID.randomUUID();

    @Test
    void theSameKeyRunsOnceAndAnswersTheSame() {
        AtomicInteger runs = new AtomicInteger();
        String a = once.once(tenant, "k1", () -> "stored-" + runs.incrementAndGet());
        String b = once.once(tenant, "k1", () -> "stored-" + runs.incrementAndGet());
        assertThat(a).isEqualTo("stored-1").isEqualTo(b);
        assertThat(runs).hasValue(1);
    }

    @Test
    void keysAreTenantScopedAndABlankKeyAlwaysRuns() {
        AtomicInteger runs = new AtomicInteger();
        once.once(tenant, "k1", runs::incrementAndGet);
        once.once(UUID.randomUUID(), "k1", runs::incrementAndGet);
        once.once(tenant, null, runs::incrementAndGet);
        once.once(tenant, "", runs::incrementAndGet);
        assertThat(runs).hasValue(4);
    }

    @Test
    void aFailedAttemptIsForgottenSoTheRetryRuns() {
        assertThatThrownBy(() -> once.once(tenant, "k2", () -> { throw new IllegalStateException("storage down"); }))
                .hasMessage("storage down");
        assertThat(once.once(tenant, "k2", () -> "ok")).isEqualTo("ok");
    }
}
