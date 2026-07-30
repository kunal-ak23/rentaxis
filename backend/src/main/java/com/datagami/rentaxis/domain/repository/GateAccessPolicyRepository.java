package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GateAccessPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GateAccessPolicyRepository extends JpaRepository<GateAccessPolicy, UUID> {
    Optional<GateAccessPolicy> findByTenantIdAndPropertyIdAndBuildingId(
            UUID tenantId, UUID propertyId, UUID buildingId);
    Optional<GateAccessPolicy> findByTenantIdAndPropertyIdAndBuildingIdIsNull(
            UUID tenantId, UUID propertyId);
    List<GateAccessPolicy> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId);
}
