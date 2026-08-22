package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAdEvent;
import com.datagami.rentaxis.domain.entity.enums.PromoEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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

    boolean existsByAdIdAndRenterUserIdAndDayAndEventType(
            UUID adId, UUID renterUserId, java.time.LocalDate day, PromoEventType eventType);
}
