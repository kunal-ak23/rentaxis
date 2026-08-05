package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ParkingSpotBuildingScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ParkingSpotBuildingScopeRepository extends JpaRepository<ParkingSpotBuildingScope, UUID> {

    List<ParkingSpotBuildingScope> findByParkingSpotId(UUID parkingSpotId);

    List<ParkingSpotBuildingScope> findByParkingSpotIdIn(Collection<UUID> parkingSpotIds);

    void deleteByTenantIdAndParkingSpotId(UUID tenantId, UUID parkingSpotId);
}
