package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoBusiness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromoBusinessRepository extends JpaRepository<PromoBusiness, UUID> {

    Page<PromoBusiness> findByTenantId(UUID tenantId, Pageable pageable);

    /** Addendum B: the admin list without (default) or with only ({@code ?archived=true}) archived businesses. */
    Page<PromoBusiness> findByTenantIdAndArchivedAtIsNull(UUID tenantId, Pageable pageable);

    Page<PromoBusiness> findByTenantIdAndArchivedAtIsNotNull(UUID tenantId, Pageable pageable);

    /** Businesses using an uploaded image as their logo (any tenant) — see the anonymous image route. */
    List<PromoBusiness> findByLogoUrl(String logoUrl);

    List<PromoBusiness> findByTenantIdOrderByCreatedAtAsc(UUID tenantId);

    /**
     * Tenant in the signature, not left to the ambient Hibernate filter. The
     * renter feed's privacy boundary should not rest on a thread-local that a
     * future caller without tenant context (a scheduler, a warmup job) could
     * silently bypass.
     */
    List<PromoBusiness> findByTenantIdAndIdIn(UUID tenantId, Collection<UUID> ids);

    /** Of these ids, the live (not archived) businesses of the tenant — ids only, no entities loaded. */
    @Query("SELECT b.id FROM PromoBusiness b WHERE b.tenantId = :tenantId AND b.id IN :ids AND b.archivedAt IS NULL")
    List<UUID> findLiveIds(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);

    /**
     * Review round 1, I3: the business row, locked for a promoter's business edit
     * (FOR UPDATE) ...
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM PromoBusiness b WHERE b.tenantId = :tenantId AND b.id = :id")
    Optional<PromoBusiness> findForUpdate(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /**
     * ... and share-locked (FOR SHARE) by an approval of one of its ads, so the two
     * serialise: an approval in flight holds the edit back until it commits (the edit
     * then re-submits the freshly approved ad), and an edit in flight holds the
     * approval back until it commits (the approval then meets the new submittedAt).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT b FROM PromoBusiness b WHERE b.tenantId = :tenantId AND b.id = :id")
    Optional<PromoBusiness> findForShare(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
