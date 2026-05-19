package com.datagami.rentaxis.core.service.renewal;

import com.datagami.rentaxis.domain.entity.enums.RenewalIntent;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

@Service
@Slf4j
public class RenewalTokenService {

    private final SecretKey key;

    public RenewalTokenService(@Value("${app.renewal.token-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("app.renewal.token-secret must be >= 32 bytes");
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    public String sign(UUID opportunityId, RenewalIntent intent, LocalDate leaseEndDate) {
        Date exp = Date.from(leaseEndDate.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant());
        return Jwts.builder()
                .claim("oid", opportunityId.toString())
                .claim("int", intent.name())
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    public record VerifiedToken(UUID opportunityId, RenewalIntent intent) {}

    public VerifiedToken verify(String token) throws TokenInvalidException, TokenExpiredException {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            if (claims.getExpiration() != null && claims.getExpiration().toInstant().isBefore(Instant.now())) {
                throw new TokenExpiredException();
            }
            UUID oid = UUID.fromString(claims.get("oid", String.class));
            RenewalIntent intent = RenewalIntent.valueOf(claims.get("int", String.class));
            return new VerifiedToken(oid, intent);
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            throw new TokenExpiredException();
        } catch (JwtException | IllegalArgumentException e) {
            throw new TokenInvalidException(e.getMessage());
        }
    }

    public static class TokenInvalidException extends Exception {
        public TokenInvalidException(String m) { super(m); }
    }
    public static class TokenExpiredException extends Exception {}
}
