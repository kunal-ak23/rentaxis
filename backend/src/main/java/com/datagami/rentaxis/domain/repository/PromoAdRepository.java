package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromoAdRepository extends JpaRepository<PromoAd, UUID> {

    Page<PromoAd> findByTenantId(UUID tenantId, Pageable pageable);

    /** Ads showing an uploaded image (any tenant) — the anonymous image route decides which may be served. */
    List<PromoAd> findByBackgroundImageUrl(String backgroundImageUrl);

    Page<PromoAd> findByTenantIdAndBusinessId(UUID tenantId, UUID businessId, Pageable pageable);

    long countByBusinessId(UUID businessId);

    /** A business's live (not archived) ads — the adCount its DTO shows (review W-M5). */
    long countByBusinessIdAndArchivedAtIsNull(UUID businessId);

    // ---- approval (changeset 183) ----

    /** The admins' queue: archived ads (addendum B) wait for nobody. */
    long countByTenantIdAndApprovalStatusAndArchivedAtIsNull(UUID tenantId, PromoApprovalStatus status);

    /** A promoter's ad list without archived ads (addendum B). */
    Page<PromoAd> findByTenantIdAndBusinessIdInAndArchivedAtIsNull(UUID tenantId, Collection<UUID> businessIds,
                                                                  Pageable pageable);

    Page<PromoAd> findByTenantIdAndBusinessIdAndArchivedAtIsNull(UUID tenantId, UUID businessId, Pageable pageable);

    // ---- the admin ad list: live (default) or archived only, by business and/or approval state ----

    Page<PromoAd> findByTenantIdAndArchivedAtIsNull(UUID tenantId, Pageable pageable);

    Page<PromoAd> findByTenantIdAndArchivedAtIsNotNull(UUID tenantId, Pageable pageable);

    Page<PromoAd> findByTenantIdAndBusinessIdAndArchivedAtIsNotNull(UUID tenantId, UUID businessId, Pageable pageable);

    Page<PromoAd> findByTenantIdAndApprovalStatusAndArchivedAtIsNull(UUID tenantId, PromoApprovalStatus status,
                                                                    Pageable pageable);

    Page<PromoAd> findByTenantIdAndApprovalStatusAndArchivedAtIsNotNull(UUID tenantId, PromoApprovalStatus status,
                                                                       Pageable pageable);

    Page<PromoAd> findByTenantIdAndBusinessIdAndApprovalStatusAndArchivedAtIsNull(
            UUID tenantId, UUID businessId, PromoApprovalStatus status, Pageable pageable);

    Page<PromoAd> findByTenantIdAndBusinessIdAndApprovalStatusAndArchivedAtIsNotNull(
            UUID tenantId, UUID businessId, PromoApprovalStatus status, Pageable pageable);

    /** Review M6: whether the business has an approved, live (not archived) ad. */
    boolean existsByTenantIdAndBusinessIdAndApprovalStatusAndArchivedAtIsNull(UUID tenantId, UUID businessId,
                                                                             PromoApprovalStatus status);

    /** A business's ads not yet archived — what archiving the business archives with it. */
    List<PromoAd> findByTenantIdAndBusinessIdAndArchivedAtIsNull(UUID tenantId, UUID businessId);

    /** The ad's business id without loading the ad, so a later locked read loads it fresh. */
    @Query("SELECT a.businessId FROM PromoAd a WHERE a.tenantId = :tenantId AND a.id = :id")
    Optional<UUID> findBusinessId(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** As {@link #findBusinessId}, for an ad that is not archived (empty otherwise). */
    @Query("SELECT a.businessId FROM PromoAd a WHERE a.tenantId = :tenantId AND a.id = :id AND a.archivedAt IS NULL")
    Optional<UUID> findBusinessIdOfLiveAd(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /**
     * The ad, row-locked, for an approval transition or a promoter's edit: the two
     * serialize, so an approval can neither overwrite an edit that landed after it
     * read the row nor be lost under one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PromoAd a WHERE a.tenantId = :tenantId AND a.id = :id")
    Optional<PromoAd> findForUpdate(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /**
     * A business's ads in these approval states, row-locked: a promoter's business edit
     * sends the APPROVED ones back and re-submits the PENDING ones (review round 1, I2).
     * Archived ads included (review M1): one restored later must not come back APPROVED
     * with business changes nobody reviewed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a FROM PromoAd a
            WHERE a.tenantId = :tenantId AND a.businessId = :businessId AND a.approvalStatus IN :statuses
            ORDER BY a.id
            """)
    List<PromoAd> findForUpdateByBusinessAndStatusIn(@Param("tenantId") UUID tenantId,
                                                     @Param("businessId") UUID businessId,
                                                     @Param("statuses") Collection<PromoApprovalStatus> statuses);

    /**
     * Ad counts for a page of businesses, as {@code [businessId, count]}.
     *
     * <p>One query per page. The admin business list would otherwise issue a
     * COUNT per row, and Spring Data's default max page size is 2000 — so a
     * single request could fire 2001 queries. Same shape as
     * {@code BookingRequestRepository.countByAmenityIdIn}. Live ads only: an archived
     * ad is not one of the business's ads to an admin or a promoter (review W-M5).
     */
    @Query("""
            SELECT a.businessId, COUNT(a) FROM PromoAd a
            WHERE a.tenantId = :tenantId AND a.businessId IN :businessIds AND a.archivedAt IS NULL
            GROUP BY a.businessId
            """)
    List<Object[]> countByBusinessIdIn(@Param("tenantId") UUID tenantId,
                                       @Param("businessIds") Collection<UUID> businessIds);

    /**
     * Every ad this renter is eligible to see right now.
     *
     * <p>An ad with no rows in promo_ad_property targets every property — the
     * NOT EXISTS arm — otherwise one of its targeted properties must be a
     * property the renter holds an active lease in. Both arms are evaluated in
     * SQL so the home screen never loads the full ad table.
     *
     * <p>Placement is passed as a list rather than an equality check so one
     * query serves both the home slate (HOME_AND_OFFERS only) and the offers
     * screen (both values).
     *
     * <p>The ordering is load-bearing: {@code offers()} returns this order
     * straight to the client. {@code createdAt} is not unique, so {@code id}
     * breaks ties and keeps paging stable when a seed or bulk import creates
     * several ads in the same instant.
     *
     * <p>Archived ads and businesses (addendum B) never show, on top of
     * {@code active} (archiving also deactivates; this is belt and braces).
     *
     * <p><b>Promoter rules (changeset 183).</b> Only APPROVED ads — nothing a
     * promoter writes reaches a renter before an admin approves it. And an ad a
     * promoter wrote ({@code promoterUserId} set) shows only at a property it
     * targets that is also, right now, in that promoter's {@code promoter_property}
     * set, matched within this tenant: removing a property from a promoter takes
     * its ads off that property at once, and "zero targeting rows = every property"
     * can never apply to a promoter's ad (the EXISTS needs a targeting row).
     * An ad a promoter once wrote whose promoter is gone ({@code promoterUserId}
     * nulled by the login's deletion, {@code writtenByPromoter} still true) shows
     * nowhere (review M4) — it never falls back to the admin rules.
     * Every renter-facing read goes through here — home slate, offers, and the
     * impression/click endpoint, which re-derives its allowed ad ids from it.
     */
    @Query("""
            SELECT a FROM PromoAd a
            WHERE a.tenantId = :tenantId
              AND a.active = true
              AND a.archivedAt IS NULL
              AND a.approvalStatus = com.datagami.rentaxis.domain.entity.enums.PromoApprovalStatus.APPROVED
              AND a.placement IN :placements
              AND (a.startsAt IS NULL OR a.startsAt <= :now)
              AND (a.endsAt IS NULL OR a.endsAt > :now)
              AND EXISTS (SELECT 1 FROM PromoBusiness b
                          WHERE b.id = a.businessId AND b.active = true AND b.archivedAt IS NULL)
              AND (NOT EXISTS (SELECT 1 FROM PromoAdProperty p WHERE p.adId = a.id)
                   OR EXISTS (SELECT 1 FROM PromoAdProperty p
                              WHERE p.adId = a.id AND p.propertyId IN :propertyIds))
              AND ((a.promoterUserId IS NULL AND a.writtenByPromoter = false)
                   OR EXISTS (SELECT 1 FROM PromoAdProperty p, PromoterProperty pp
                              WHERE p.adId = a.id
                                AND pp.tenantId = :tenantId
                                AND pp.userId = a.promoterUserId
                                AND pp.propertyId = p.propertyId
                                AND p.propertyId IN :propertyIds))
            ORDER BY a.createdAt ASC, a.id ASC
            """)
    List<PromoAd> findEligible(@Param("tenantId") UUID tenantId,
                               @Param("now") Instant now,
                               @Param("placements") List<PromoPlacement> placements,
                               @Param("propertyIds") List<UUID> propertyIds);
}
