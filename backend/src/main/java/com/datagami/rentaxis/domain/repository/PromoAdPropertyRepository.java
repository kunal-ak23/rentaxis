package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromoAdPropertyRepository extends JpaRepository<PromoAdProperty, UUID> {

    List<PromoAdProperty> findByAdId(UUID adId);

    /**
     * Batch fetch for list responses — one query per page, not one per row.
     * Tenant in the signature for the same reason
     * {@code PromoBusinessRepository.findByTenantIdAndIdIn} carries it: a
     * privacy boundary should not rest on an ambient thread-local that a
     * future caller without tenant context could bypass.
     */
    List<PromoAdProperty> findByTenantIdAndAdIdIn(UUID tenantId, Collection<UUID> adIds);

    /**
     * Bulk delete, so it executes immediately rather than deferring to flush —
     * which is what lets {@code PromotionService.replaceTargeting} delete then
     * re-insert in one transaction without tripping {@code uq_promo_ad_property}.
     * (The derived-delete form defers, which is why the analogous
     * {@code FacilityService.replaceAmenityScopes} has to call {@code flush()}.)
     * No {@code clearAutomatically}/{@code flushAutomatically} needed: nothing
     * mutates a loaded PromoAdProperty, so there is no stale-entity hazard.
     *
     * <p>Scoped by tenant explicitly. The Hibernate filter would cover it, but a
     * destructive statement should not lean on a single layer of defence.
     */
    @Modifying
    @Query("DELETE FROM PromoAdProperty p WHERE p.tenantId = :tenantId AND p.adId = :adId")
    void deleteByTenantIdAndAdId(@Param("tenantId") UUID tenantId, @Param("adId") UUID adId);
}
