package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TenantFeatureEntity;
import com.datagami.rentaxis.domain.entity.enums.TenantFeature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantFeatureRepository extends JpaRepository<TenantFeatureEntity, UUID> {

    Optional<TenantFeatureEntity> findByTenantIdAndFeature(UUID tenantId, TenantFeature feature);

    List<TenantFeatureEntity> findByTenantId(UUID tenantId);

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO tenant_feature (tenant_id, feature, enabled, updated_at)
        VALUES (:tenantId, :feature, :enabled, now())
        ON CONFLICT (tenant_id, feature) DO UPDATE
            SET enabled = EXCLUDED.enabled, updated_at = now()
        """, nativeQuery = true)
    void upsert(UUID tenantId, String feature, boolean enabled);
}
