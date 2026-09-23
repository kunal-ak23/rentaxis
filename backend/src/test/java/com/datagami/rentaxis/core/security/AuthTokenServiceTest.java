package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.domain.entity.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class AuthTokenServiceTest {

    private static final String SECRET = "auth-token-test-secret-at-least-32-bytes!";

    private final AuthTokenService service = new AuthTokenService(SECRET);

    private SecretKey rawKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void round_trip_preserves_every_identity_claim() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        String token = service.issue(userId, UserRole.TENANT_ADMIN, home, List.of(home, other));
        assertThat(token).isNotNull();

        var v = service.verify(token);
        assertThat(v.userId()).isEqualTo(userId);
        assertThat(v.role()).isEqualTo(UserRole.TENANT_ADMIN);
        assertThat(v.homeTenantId()).isEqualTo(home);
        assertThat(v.tenantIds()).containsExactly(home, other);
    }

    @Test
    void the_token_version_round_trips() throws Exception {
        String token = service.issue(UUID.randomUUID(), UserRole.RENTER, UUID.randomUUID(), List.of(), 7);
        assertThat(service.verify(token).tokenVersion()).isEqualTo(7);
    }

    @Test
    void a_token_without_a_version_is_invalid() {
        // Well signed, unexpired, but no "tv": it cannot be checked against the
        // user's current version, so it is not a token we accept (audit P1-2).
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", "TENANT_ADMIN")
                .claim("tids", List.of())
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofDays(1))))
                .signWith(rawKey())
                .compact();
        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(AuthTokenService.TokenInvalidException.class);
    }

    @Test
    void null_home_tenant_round_trips_as_null() throws Exception {
        String token = service.issue(UUID.randomUUID(), UserRole.SUPER_ADMIN, null, List.of());
        var v = service.verify(token);
        assertThat(v.homeTenantId()).isNull();
        assertThat(v.tenantIds()).isEmpty();
    }

    @Test
    void token_expires_in_30_days_with_issued_at_set() {
        String token = service.issue(UUID.randomUUID(), UserRole.RENTER, UUID.randomUUID(), List.of());
        Claims claims = Jwts.parser().verifyWith(rawKey()).build().parseSignedClaims(token).getPayload();

        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration().toInstant())
                .isCloseTo(Instant.now().plus(Duration.ofDays(30)), within(2, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    void expired_token_rejected_as_expired() {
        // Same key, expiry in the past — what a stale mobile install replays.
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", UserRole.RENTER.name())
                .claim("tids", List.of())
                .issuedAt(Date.from(Instant.now().minus(Duration.ofDays(31))))
                .expiration(Date.from(Instant.now().minus(Duration.ofDays(1))))
                .signWith(rawKey())
                .compact();

        assertThatThrownBy(() -> service.verify(expired))
                .isInstanceOf(AuthTokenService.TokenExpiredException.class);
    }

    @Test
    void tampered_signature_rejected() {
        String token = service.issue(UUID.randomUUID(), UserRole.RENTER, UUID.randomUUID(), List.of());
        String sig = token.substring(token.lastIndexOf('.') + 1);
        String flipped = sig.startsWith("a") ? "b" + sig.substring(1) : "a" + sig.substring(1);
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + flipped;

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(AuthTokenService.TokenInvalidException.class);
    }

    @Test
    void token_signed_with_a_different_secret_rejected() {
        AuthTokenService other = new AuthTokenService("a-completely-different-secret-32-bytes!!!");
        String foreign = other.issue(UUID.randomUUID(), UserRole.SUPER_ADMIN, null, List.of());

        assertThatThrownBy(() -> service.verify(foreign))
                .isInstanceOf(AuthTokenService.TokenInvalidException.class);
    }

    @Test
    void well_signed_token_with_unknown_role_rejected() {
        String badRole = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", "NOT_A_ROLE")
                .claim("tids", List.of())
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofDays(1))))
                .signWith(rawKey())
                .compact();

        assertThatThrownBy(() -> service.verify(badRole))
                .isInstanceOf(AuthTokenService.TokenInvalidException.class);
    }

    @Test
    void well_signed_token_without_expiry_rejected() {
        String noExp = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("role", UserRole.RENTER.name())
                .claim("tids", List.of())
                .signWith(rawKey())
                .compact();

        assertThatThrownBy(() -> service.verify(noExp))
                .isInstanceOf(AuthTokenService.TokenInvalidException.class);
    }

    @Test
    void blank_secret_disables_quietly() {
        AuthTokenService disabled = new AuthTokenService("");
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.issue(UUID.randomUUID(), UserRole.RENTER, null, List.of())).isNull();

        String realToken = service.issue(UUID.randomUUID(), UserRole.RENTER, null, List.of());
        assertThatThrownBy(() -> disabled.verify(realToken))
                .isInstanceOf(AuthTokenService.TokenInvalidException.class);
    }

    @Test
    void enabled_when_secret_configured() {
        assertThat(service.enabled()).isTrue();
    }

    @Test
    void short_secret_is_a_startup_error_not_a_silent_downgrade() {
        assertThatThrownBy(() -> new AuthTokenService("only-31-bytes-of-secret-here!!!"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
