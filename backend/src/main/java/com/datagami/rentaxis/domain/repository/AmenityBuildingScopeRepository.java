package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.AmenityBuildingScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AmenityBuildingScopeRepository extends JpaRepository<AmenityBuildingScope, UUID> {

    List<AmenityBuildingScope> findByAmenityId(UUID amenityId);

    List<AmenityBuildingScope> findByAmenityIdIn(Collection<UUID> amenityIds);

    void deleteByTenantIdAndAmenityId(UUID tenantId, UUID amenityId);
}
