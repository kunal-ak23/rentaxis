package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PromoterProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Tenant in every signature: the boundary is the caller's, never the row's. */
@Repository
public interface PromoterPropertyRepository extends JpaRepository<PromoterProperty, PromoterProperty.Key> {

    @Query("SELECT pp.propertyId FROM PromoterProperty pp WHERE pp.tenantId = :tenantId AND pp.userId = :userId")
    List<UUID> findPropertyIds(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    List<PromoterProperty> findByTenantIdAndUserIdIn(UUID tenantId, Collection<UUID> userIds);

    /** See {@code PromoterBusinessRepository.deleteByTenantIdAndUserId}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM PromoterProperty pp WHERE pp.tenantId = :tenantId AND pp.userId = :userId")
    void deleteByTenantIdAndUserId(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);
}
