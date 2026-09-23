package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RenewalTokenServiceTest {

    private final RenewalTokenService service =
            new RenewalTokenService("test-secret-at-least-32-bytes-long-please-x");

    @Test
    void round_trip_signs_and_verifies() throws Exception {
        UUID opp = UUID.randomUUID();
        String t = service.sign(opp, RenewalIntent.RENEW, LocalDate.now().plusDays(30));

        var v = service.verify(t);
        assertThat(v.opportunityId()).isEqualTo(opp);
        assertThat(v.intent()).isEqualTo(RenewalIntent.RENEW);
    }

    @Test
    void expired_token_rejected() {
        String t = service.sign(UUID.randomUUID(), RenewalIntent.RENEW, LocalDate.now().minusDays(30));
        assertThatThrownBy(() -> service.verify(t))
                .isInstanceOf(RenewalTokenService.TokenExpiredException.class);
    }

    @Test
    void tampered_signature_rejected() {
        String t = service.sign(UUID.randomUUID(), RenewalIntent.RENEW, LocalDate.now().plusDays(30));
        String tampered = t.substring(0, t.length() - 4) + "abcd";
        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(RenewalTokenService.TokenInvalidException.class);
    }

    // ---- audit B-F5: the prod profile refuses a missing or committed secret ----

    private static org.springframework.mock.env.MockEnvironment env(String... profiles) {
        org.springframework.mock.env.MockEnvironment e = new org.springframework.mock.env.MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @org.junit.jupiter.api.Test
    void prodRefusesTheCommittedDevSecretAndAMissingOne() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RenewalTokenService(
                        RenewalTokenService.COMMITTED_DEV_SECRET, env("prod"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RENEWAL_TOKEN_SECRET");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RenewalTokenService("", env("prod"), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RENEWAL_TOKEN_SECRET");
    }

    @org.junit.jupiter.api.Test
    void prodStartsWithARealSecretAndDevStartsWithTheDefault() {
        new RenewalTokenService("a-real-prod-secret-that-is-at-least-32-bytes-long", env("prod"), false);
        new RenewalTokenService(RenewalTokenService.COMMITTED_DEV_SECRET, env("dev"), false);
        new RenewalTokenService(RenewalTokenService.COMMITTED_DEV_SECRET, env(), false);
        // Break-glass, explicitly set.
        new RenewalTokenService(RenewalTokenService.COMMITTED_DEV_SECRET, env("prod"), true);
    }

    /** The literal the guard compares against is the one application.yml actually ships. */
    @org.junit.jupiter.api.Test
    void theGuardKnowsTheCommittedDefault() throws Exception {
        String yml = new String(getClass().getResourceAsStream("/application.yml").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(yml)
                .contains("${APP_RENEWAL_TOKEN_SECRET:" + RenewalTokenService.COMMITTED_DEV_SECRET + "}");
    }
}
