package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAdEvent;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromoAdEventRepository extends JpaRepository<PromoAdEvent, UUID> {

    /**
     * Impression and click totals for a page of ads, as {@code [adId, eventType, count]}
     * rows. Two-column grouping keeps this to one query per page rather than
     * two per ad.
     */
    @Query("""
            SELECT e.adId, e.eventType, COUNT(e)
            FROM PromoAdEvent e
            WHERE e.tenantId = :tenantId AND e.adId IN :adIds
            GROUP BY e.adId, e.eventType
            """)
    List<Object[]> countByAdIdIn(@Param("tenantId") UUID tenantId, @Param("adIds") List<UUID> adIds);

    /** Daily series for one ad's detail view. Rows are {@code [day, eventType, count]}. */
    @Query("""
            SELECT e.day, e.eventType, COUNT(e)
            FROM PromoAdEvent e
            WHERE e.tenantId = :tenantId AND e.adId = :adId
            GROUP BY e.day, e.eventType
            ORDER BY e.day ASC
            """)
    List<Object[]> dailySeries(@Param("tenantId") UUID tenantId, @Param("adId") UUID adId);

    /** Guards the hard-delete path in PromotionService.deleteAd. */
    long countByAdId(UUID adId);

    /**
     * Which of these ads this renter already has an impression for today.
     *
     * <p>Deliberately batched. This runs on every home-screen load — the client
     * flushes up to six impressions each time — so a per-ad `exists` check
     * would put six round trips on the renter hot path, all day, forever, for
     * a result that is `true` every time after the first load. Served by the
     * partial index `uq_promo_impression_per_day`.
     */
    @Query("""
            SELECT e.adId FROM PromoAdEvent e
            WHERE e.tenantId = :tenantId
              AND e.adId IN :adIds
              AND e.renterUserId = :renterUserId
              AND e.day = :day
              AND e.eventType = com.datagami.rentaxis.domain.entity.enums.PromoEventType.IMPRESSION
            """)
    List<UUID> findAdIdsWithImpressionOn(@Param("tenantId") UUID tenantId,
                                         @Param("adIds") Collection<UUID> adIds,
                                         @Param("renterUserId") UUID renterUserId,
                                         @Param("day") LocalDate day);
}
