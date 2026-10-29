package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.PartnerApiKeyAuthIT.PartnerResp;
import com.datagami.rentaxis.core.security.ApiKeySecrets;
import com.datagami.rentaxis.core.service.PromoterApiKeyService;
import com.datagami.rentaxis.domain.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.api.PartnerApiKeyAuthIT.PING;
import static com.datagami.rentaxis.api.PartnerApiKeyAuthIT.freshIp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Promoter developer API, task 2: the partner rate limits, at low values in a context
 * of their own (distinct properties → distinct cached context). Named to end in
 * {@code PartnerApiKeyAuthIT} so {@code --tests '*PartnerApiKeyAuthIT'} runs it too.
 */
@TestPropertySource(properties = {
        "app.partner-api.failed-auth-per-minute=3",
        "app.partner-api.per-key-per-minute=5",
        "app.partner-api.per-key-burst=5"})
class RateLimitedPartnerApiKeyAuthIT extends AbstractPromoterIT {

    @Autowired PromoterApiKeyService keys;

    User promoter;

    @BeforeEach
    void setUp() {
        UUID org = org();
        promoter = promoter(org, false, List.of(), List.of());
    }

    @Test
    void failedAttemptsAreRateLimitedPerIp() {
        String ip = freshIp();
        for (int i = 0; i < 3; i++) {
            assertThat(call(ApiKeySecrets.generate(), ip).status()).as("attempt " + i).isEqualTo(401);
        }
        PartnerResp limited = call(ApiKeySecrets.generate(), ip);
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.text("error")).isEqualTo("rate_limited");
        assertThat(limited.retryAfter()).isNotBlank();
        assertThat(Long.parseLong(limited.retryAfter())).isPositive();

        // Throttled before the key is looked at: even a good key waits from that IP...
        String secret = keys.create(promoter.getId(), "Feed").secret();
        assertThat(call(secret, ip).status()).isEqualTo(429);
        // ...while another IP is unaffected.
        assertThat(call(secret, freshIp()).status()).isEqualTo(200);
    }

    @Test
    void successesDoNotSpendTheFailedAuthBudget() {
        String ip = freshIp();
        String secret = keys.create(promoter.getId(), "Feed").secret();
        for (int i = 0; i < 4; i++) {
            assertThat(call(secret, ip).status()).as("call " + i).isEqualTo(200);
        }
        assertThat(call(ApiKeySecrets.generate(), ip).status()).isEqualTo(401);
    }

    @Test
    void perKeyRateLimit() {
        String secret = keys.create(promoter.getId(), "Feed").secret();
        String other = keys.create(promoter.getId(), "Other").secret();
        for (int i = 0; i < 5; i++) {
            // A different IP each time: the budget is the key's, not the address's.
            assertThat(call(secret, freshIp()).status()).as("call " + i).isEqualTo(200);
        }
        PartnerResp limited = call(secret, freshIp());
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.text("error")).isEqualTo("rate_limited");
        assertThat(Long.parseLong(limited.retryAfter())).isPositive();
        // Per key: the promoter's other key has its own budget.
        assertThat(call(other, freshIp()).status()).isEqualTo(200);
    }

    private PartnerResp call(String secret, String ip) {
        return PartnerApiKeyAuthIT.partnerCall(port, json, secret, PING, Map.of(), ip);
    }
}
