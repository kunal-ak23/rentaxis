package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoAdProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PromoAdPropertyRepository extends JpaRepository<PromoAdProperty, UUID> {

    List<PromoAdProperty> findByAdId(UUID adId);

    /** Batch fetch for list responses — one query per page, not one per row. */
    List<PromoAdProperty> findByAdIdIn(List<UUID> adIds);

    @Modifying
    @Query("DELETE FROM PromoAdProperty p WHERE p.adId = :adId")
    void deleteByAdId(@Param("adId") UUID adId);
}
