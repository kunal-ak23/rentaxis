package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ParkingSpot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ParkingSpotRepository extends JpaRepository<ParkingSpot, UUID> {

    Page<ParkingSpot> findByTenantId(UUID tenantId, Pageable pageable);

    Page<ParkingSpot> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId, Pageable pageable);

    List<ParkingSpot> findByTenantIdAndPropertyIdAndActiveTrue(UUID tenantId, UUID propertyId);

    List<ParkingSpot> findByTenantIdAndPropertyIdOrderByCreatedAtAsc(UUID tenantId, UUID propertyId);

    List<ParkingSpot> findByTenantIdAndPropertyIdAndActiveTrueOrderByCreatedAtAsc(UUID tenantId, UUID propertyId);

    boolean existsByTenantIdAndPropertyIdAndSpotNumber(UUID tenantId, UUID propertyId, String spotNumber);

    boolean existsByTenantIdAndPropertyIdAndSpotNumberAndIdNot(UUID tenantId, UUID propertyId, String spotNumber, UUID id);
}
