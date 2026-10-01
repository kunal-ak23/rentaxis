package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ParkingSpot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    long countByTenantIdAndPropertyIdAndActiveTrue(UUID tenantId, UUID propertyId);

    long countByTenantIdAndPropertyIdAndActiveFalse(UUID tenantId, UUID propertyId);

    /** Active spots of the property held by an APPROVED booking (the list's {@code held} flag), counted once each. */
    @Query("""
        select count(s) from ParkingSpot s
        where s.tenantId = :tenantId and s.propertyId = :propertyId and s.active = true
          and exists (select 1 from BookingRequest b
                      where b.tenantId = :tenantId and b.parkingSpotId = s.id
                        and b.status = com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus.APPROVED)
        """)
    long countHeldActive(@Param("tenantId") UUID tenantId, @Param("propertyId") UUID propertyId);

    boolean existsByTenantIdAndPropertyIdAndSpotNumber(UUID tenantId, UUID propertyId, String spotNumber);

    boolean existsByTenantIdAndPropertyIdAndSpotNumberAndIdNot(UUID tenantId, UUID propertyId, String spotNumber, UUID id);
}
