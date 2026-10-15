package com.datagami.rentaxis.core.service.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Person-level "forgot password" links (changeset 167): keyed by the normalised
 * email, never by a users row, and stored only as a SHA-256 of the token. Plain
 * JDBC on purpose — the table is not tenant-scoped and must never pass through
 * the tenant filter.
 */
@Repository
public class PasswordResetTokenStore {

    public record Link(String email, Instant expiresAt, Instant usedAt) {
    }

    private final JdbcTemplate jdbc;

    public PasswordResetTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void issue(String email, String token, Instant expiresAt) {
        // Housekeeping: links a day past expiry are of no use to anyone.
        jdbc.update("DELETE FROM password_reset_tokens WHERE expires_at < ?",
                Timestamp.from(Instant.now().minusSeconds(86_400)));
        jdbc.update("INSERT INTO password_reset_tokens (email, token_hash, expires_at) VALUES (?, ?, ?)",
                email, hash(token), Timestamp.from(expiresAt));
    }

    public Optional<Link> find(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        List<Link> rows = jdbc.query(
                "SELECT email, expires_at, used_at FROM password_reset_tokens WHERE token_hash = ?",
                (rs, n) -> new Link(rs.getString(1), rs.getTimestamp(2).toInstant(),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()),
                hash(token));
        return rows.stream().findFirst();
    }

    /** Marks the link used; 0 when it was already used or has expired (a concurrent redeem lost). */
    public int consume(String token, Instant now) {
        return jdbc.update("UPDATE password_reset_tokens SET used_at = ? "
                        + "WHERE token_hash = ? AND used_at IS NULL AND expires_at > ?",
                Timestamp.from(now), hash(token), Timestamp.from(now));
    }

    /** Once one link set the password, the person's other outstanding links stop working. */
    public void retireAll(String email, Instant now) {
        jdbc.update("UPDATE password_reset_tokens SET used_at = ? WHERE email = ? AND used_at IS NULL",
                Timestamp.from(now), email);
    }
}
