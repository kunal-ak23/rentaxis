package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoterApiKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Promoter-side reads carry the tenant and the promoter in every signature. The
 * cross-tenant ones (key lookup by hash, admin lookup and listing, the last-used
 * touch) are native SQL on purpose: Hibernate's tenantFilter does not apply to native
 * queries, so they answer the same whatever tenant context the caller happens to have.
 */
@Repository
public interface PromoterApiKeyRepository extends JpaRepository<PromoterApiKey, UUID> {

    @Query("SELECT k FROM PromoterApiKey k WHERE k.tenantId = :tenantId AND k.userId = :userId "
            + "ORDER BY k.createdAt DESC, k.id DESC")
    List<PromoterApiKey> findForPromoter(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    @Query("SELECT k FROM PromoterApiKey k WHERE k.id = :id AND k.tenantId = :tenantId AND k.userId = :userId")
    Optional<PromoterApiKey> findOwned(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
                                       @Param("userId") UUID userId);

    @Query("SELECT COUNT(k) FROM PromoterApiKey k WHERE k.tenantId = :tenantId AND k.userId = :userId "
            + "AND k.revokedAt IS NULL AND k.expiresAt IS NULL")
    long countActive(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    @Query(value = "SELECT * FROM promoter_api_key WHERE key_hash = :hash", nativeQuery = true)
    Optional<PromoterApiKey> findAnyByKeyHash(@Param("hash") String hash);

    @Query(value = "SELECT * FROM promoter_api_key WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<PromoterApiKey> findAnyByIdForUpdate(@Param("id") UUID id);

    /** Sets last_used_at only if it is unset or older than {@code before}; 1 if it wrote. */
    @Modifying
    @Query(value = "UPDATE promoter_api_key SET last_used_at = :now WHERE id = :id "
            + "AND (last_used_at IS NULL OR last_used_at < :before)", nativeQuery = true)
    int touchLastUsed(@Param("id") UUID id, @Param("now") Instant now, @Param("before") Instant before);

    /** One row of the super-admin listing. */
    interface AdminRow {
        UUID getId();
        UUID getTenantId();
        String getOrgName();
        UUID getPromoterUserId();
        String getPromoterName();
        String getPromoterEmail();
        String getName();
        String getKeyPrefix();
        Instant getCreatedAt();
        Instant getLastUsedAt();
        Instant getExpiresAt();
        Instant getRevokedAt();
        String getRevokedReason();
    }

    String ADMIN_FROM = " FROM promoter_api_key k"
            + " JOIN users u ON u.id = k.user_id"
            + " JOIN landlord_org o ON o.id = k.tenant_id"
            + " WHERE (CAST(:q AS text) IS NULL"
            + "   OR lower(o.name) LIKE :q OR lower(u.name) LIKE :q OR lower(u.email) LIKE :q"
            + "   OR lower(k.name) LIKE :q OR lower(k.key_prefix) LIKE :q)"
            + " AND (CAST(:status AS text) IS NULL"
            + "   OR (:status = 'revoked' AND k.revoked_at IS NOT NULL)"
            + "   OR (:status = 'expired' AND k.revoked_at IS NULL AND k.expires_at <= :now)"
            + "   OR (:status = 'expiring' AND k.revoked_at IS NULL AND k.expires_at > :now)"
            + "   OR (:status = 'active' AND k.revoked_at IS NULL AND k.expires_at IS NULL))";

    /** Every org's keys, newest first. {@code q} is a lowercase LIKE pattern (or null). */
    @Query(value = "SELECT k.id AS id, k.tenant_id AS tenantId, o.name AS orgName, k.user_id AS promoterUserId,"
            + " u.name AS promoterName, u.email AS promoterEmail, k.name AS name, k.key_prefix AS keyPrefix,"
            + " k.created_at AS createdAt, k.last_used_at AS lastUsedAt, k.expires_at AS expiresAt,"
            + " k.revoked_at AS revokedAt, k.revoked_reason AS revokedReason"
            + ADMIN_FROM + " ORDER BY k.created_at DESC, k.id DESC",
            countQuery = "SELECT COUNT(*)" + ADMIN_FROM,
            nativeQuery = true)
    Page<AdminRow> findAllForAdmin(@Param("q") String q, @Param("status") String status,
                                   @Param("now") Instant now, Pageable pageable);
}
