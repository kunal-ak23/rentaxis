package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAd;
import com.datagami.rentaxis.domain.entity.enums.PromoPlacement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromoAdRepository extends JpaRepository<PromoAd, UUID> {

    Page<PromoAd> findByTenantId(UUID tenantId, Pageable pageable);

    Page<PromoAd> findByTenantIdAndBusinessId(UUID tenantId, UUID businessId, Pageable pageable);

    long countByBusinessId(UUID businessId);

    /**
     * Ad counts for a page of businesses, as {@code [businessId, count]}.
     *
     * <p>One query per page. The admin business list would otherwise issue a
     * COUNT per row, and Spring Data's default max page size is 2000 — so a
     * single request could fire 2001 queries. Same shape as
     * {@code BookingRequestRepository.countByAmenityIdIn}.
     */
    @Query("""
            SELECT a.businessId, COUNT(a) FROM PromoAd a
            WHERE a.tenantId = :tenantId AND a.businessId IN :businessIds
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
     */
    @Query("""
            SELECT a FROM PromoAd a
            WHERE a.tenantId = :tenantId
              AND a.active = true
              AND a.placement IN :placements
              AND (a.startsAt IS NULL OR a.startsAt <= :now)
              AND (a.endsAt IS NULL OR a.endsAt > :now)
              AND EXISTS (SELECT 1 FROM PromoBusiness b
                          WHERE b.id = a.businessId AND b.active = true)
              AND (NOT EXISTS (SELECT 1 FROM PromoAdProperty p WHERE p.adId = a.id)
                   OR EXISTS (SELECT 1 FROM PromoAdProperty p
                              WHERE p.adId = a.id AND p.propertyId IN :propertyIds))
            ORDER BY a.createdAt ASC, a.id ASC
            """)
    List<PromoAd> findEligible(@Param("tenantId") UUID tenantId,
                               @Param("now") Instant now,
                               @Param("placements") List<PromoPlacement> placements,
                               @Param("propertyIds") List<UUID> propertyIds);
}
