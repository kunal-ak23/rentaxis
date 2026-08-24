package com.datagami.rentaxis.core.security;

import com.datagami.rentaxis.domain.entity.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues and verifies the HS256 bearer tokens that replace the spoofable
 * X-User-* header authentication (phase 1 of the header-trust fix).
 *
 * <p>Configuration contract — {@code app.auth.token-secret} (env
 * {@code APP_AUTH_TOKEN_SECRET}), with an <b>empty</b> default:
 * <ul>
 *   <li><b>blank</b> → the service is quietly disabled: {@link #enabled()} is
 *       {@code false}, {@link #issue} returns {@code null}, {@link #verify}
 *       accepts nothing. This keeps every environment byte-identical to the
 *       pre-token behaviour until the secret is deliberately configured.
 *       There is deliberately NO baked-in dev fallback — a fallback secret
 *       that reaches prod IS the vulnerability this service exists to fix.</li>
 *   <li><b>1..31 bytes</b> → {@link IllegalStateException} at startup. A
 *       half-configured secret is a config error someone must see, not a
 *       silent downgrade to header trust.</li>
 *   <li><b>&ge; 32 bytes</b> → enabled.</li>
 * </ul>
 *
 * <p>Claims: {@code sub} = user id, {@code role} = {@link UserRole} name,
 * {@code hti} = home tenant id (absent when null), {@code tids} = member
 * tenant ids. Expiry 30 days, issued-at set. The mobile apps store the compact
 * JWS opaquely and replay it as {@code Authorization: Bearer <token>}.
 */
@Service
@Slf4j
public class AuthTokenService {

    static final Duration TOKEN_TTL = Duration.ofDays(30);

    private final SecretKey key; // null <=> disabled

    public AuthTokenService(@Value("${app.auth.token-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("app.auth.token-secret is not configured: bearer-token auth is DISABLED; "
                    + "the API will keep trusting legacy X-User-* headers until it is set");
            this.key = null;
            return;
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "app.auth.token-secret must be >= 32 bytes (got " + bytes.length
                            + "); a half-configured secret is a config error, not a reason to fall back");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /** True when a usable secret is configured. When false the filter must use the legacy path. */
    public boolean enabled() {
        return key != null;
    }

    /**
     * Signs an identity token for an already-authenticated user, or returns
     * {@code null} when the service is disabled (clients treat an absent token
     * as "keep using legacy headers").
     */
    public String issue(UUID userId, UserRole role, UUID homeTenantId, List<UUID> tenantIds) {
        if (key == null) {
            return null;
        }
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .subject(userId.toString())
                .claim("role", role.name())
                .claim("tids", (tenantIds == null ? List.<UUID>of() : tenantIds)
                        .stream().map(UUID::toString).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)));
        if (homeTenantId != null) {
            builder.claim("hti", homeTenantId.toString());
        }
        return builder.signWith(key).compact();
    }

    /** Verified identity extracted from a valid token's claims. */
    public record VerifiedIdentity(UUID userId, UserRole role, UUID homeTenantId, List<UUID> tenantIds) {
    }

    /**
     * Verifies signature and expiry, then materialises the identity claims.
     * Any failure — including a well-signed token with malformed claims, or
     * calling this while disabled — is a typed exception the filter turns into
     * a 401. It must never fall through to header trust.
     */
    public VerifiedIdentity verify(String token) throws TokenInvalidException, TokenExpiredException {
        if (key == null) {
            throw new TokenInvalidException("token auth is disabled (no secret configured)");
        }
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (claims.getExpiration() == null) {
                // We always mint an expiry; a token without one is not ours.
                throw new TokenInvalidException("token has no expiration");
            }
            if (claims.getExpiration().toInstant().isBefore(Instant.now())) {
                throw new TokenExpiredException();
            }
            UUID userId = UUID.fromString(claims.getSubject());
            UserRole role = UserRole.valueOf(claims.get("role", String.class));
            String hti = claims.get("hti", String.class);
            UUID homeTenantId = (hti != null && !hti.isBlank()) ? UUID.fromString(hti) : null;
            List<?> rawTids = claims.get("tids", List.class);
            List<UUID> tenantIds = rawTids == null
                    ? List.of()
                    : rawTids.stream().map(t -> UUID.fromString(String.valueOf(t))).toList();
            return new VerifiedIdentity(userId, role, homeTenantId, tenantIds);
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new TokenExpiredException();
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            throw new TokenInvalidException(e.getMessage());
        }
    }

    public static class TokenInvalidException extends Exception {
        public TokenInvalidException(String m) {
            super(m);
        }
    }

    public static class TokenExpiredException extends Exception {
    }
}
