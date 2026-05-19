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
}
