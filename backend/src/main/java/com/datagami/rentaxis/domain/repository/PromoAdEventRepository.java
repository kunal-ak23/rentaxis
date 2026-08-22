package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAdEvent;
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
     * Per-ad event totals for a page of ads, as
     * {@code [adId, eventType, count, distinctRenters]}.
     *
     * <p>The distinct-renter column is what makes a tap rate meaningful.
     * Impressions are already deduped to one per renter per day, but clicks are
     * not — a renter may legitimately tap the same card several times — so
     * {@code clicks / impressions} is "taps per unique-renter-day" and can
     * exceed 1. Dividing distinct clickers by distinct viewers gives the figure
     * a client actually reads as a tap rate.
     *
     * <p>Two-column grouping keeps this to one query per page, not two per ad.
     */
    @Query("""
            SELECT e.adId, e.eventType, COUNT(e), COUNT(DISTINCT e.renterUserId)
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
     * This renter's own event counts for these ads today, as
     * {@code [adId, eventType, count]}.
     *
     * <p>Deliberately batched, and deliberately counting both event types in
     * one query, because the write path needs both numbers. This runs on every
     * home-screen load — the client flushes up to six impressions each time —
     * so a per-ad check would put six round trips on the renter hot path, all
     * day, forever. Served by {@code idx_pae_ad_day} plus the renter predicate.
     *
     * <p>Impressions are dropped at a count of 1 (the partial unique index
     * would otherwise fail the whole batch at commit). Clicks have no unique
     * index by design — a second tap is a real second tap — so this count is
     * also what bounds them; see {@code PromotionFeedService.MAX_CLICKS_PER_AD_PER_DAY}.
     */
    @Query("""
            SELECT e.adId, e.eventType, COUNT(e)
            FROM PromoAdEvent e
            WHERE e.tenantId = :tenantId
              AND e.adId IN :adIds
              AND e.renterUserId = :renterUserId
              AND e.day = :day
            GROUP BY e.adId, e.eventType
            """)
    List<Object[]> countTodaysEventsByAd(@Param("tenantId") UUID tenantId,
                                         @Param("adIds") Collection<UUID> adIds,
                                         @Param("renterUserId") UUID renterUserId,
                                         @Param("day") LocalDate day);
}
