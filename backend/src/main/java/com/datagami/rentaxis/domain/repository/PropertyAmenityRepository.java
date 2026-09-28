package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PropertyAmenityRepository extends JpaRepository<PropertyAmenity, UUID> {

    Page<PropertyAmenity> findByTenantId(UUID tenantId, Pageable pageable);

    Page<PropertyAmenity> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId, Pageable pageable);

    List<PropertyAmenity> findByTenantIdAndPropertyIdAndActiveTrue(UUID tenantId, UUID propertyId);

    List<PropertyAmenity> findByTenantIdAndPropertyIdOrderByCreatedAtAsc(UUID tenantId, UUID propertyId);

    List<PropertyAmenity> findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(UUID tenantId, UUID propertyId);

    /**
     * Break-it R3 ops3 F7: the amenity row locked for a booking approval, so two
     * approvals for the same amenity serialise their overlap check. Waits (no
     * NOWAIT) — the competing approval is a short transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PropertyAmenity a where a.id = :id and a.tenantId = :tenantId")
    Optional<PropertyAmenity> findByIdForUpdate(@Param("tenantId") UUID tenantId, @Param("id") UUID id);
}
