package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A promoter's secret key for the read-only partner API (changeset 187). The secret
 * itself is never stored: {@code keyHash} is its SHA-256, {@code keyPrefix} its first
 * 12 characters for display. See {@code PromoterApiKeyService} for the lifecycle.
 */
@Entity
@Table(name = "promoter_api_key")
@Getter
@Setter
public class PromoterApiKey extends BaseTenantEntity {

    public static final String ACTIVE = "active";
    public static final String EXPIRING = "expiring";
    public static final String EXPIRED = "expired";
    public static final String REVOKED = "revoked";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "key_prefix", nullable = false, length = 16, updatable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, length = 64, updatable = false)
    private String keyHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoked_reason", length = 500)
    private String revokedReason;

    /** This key's status at {@code now}; see {@link #statusAt(Instant, Instant, Instant)}. */
    public String statusAt(Instant now) {
        return statusAt(now, revokedAt, expiresAt);
    }

    /** revoked → {@code revoked}; expires_at ≤ now → {@code expired}; expires_at > now → {@code expiring}; else {@code active}. */
    public static String statusAt(Instant now, Instant revokedAt, Instant expiresAt) {
        if (revokedAt != null) {
            return REVOKED;
        }
        if (expiresAt == null) {
            return ACTIVE;
        }
        return expiresAt.isAfter(now) ? EXPIRING : EXPIRED;
    }
}
