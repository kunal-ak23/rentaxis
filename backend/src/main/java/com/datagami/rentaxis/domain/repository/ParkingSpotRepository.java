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

    /**
     * Active, unattached spots of the property held on {@code day} by an APPROVED
     * booking whose dates cover it (the list's {@code held} flag), counted once each.
     * A null bound is an open end.
     */
    @Query("""
        select count(s) from ParkingSpot s
        where s.tenantId = :tenantId and s.propertyId = :propertyId and s.active = true
          and s.attachedUnitId is null
          and exists (select 1 from BookingRequest b
                      where b.tenantId = :tenantId and b.parkingSpotId = s.id
                        and b.status = com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus.APPROVED
                        and (b.preferredDate is null or b.preferredDate <= :day)
                        and (b.preferredEndDate is null or b.preferredEndDate >= :day))
        """)
    long countHeldActive(@Param("tenantId") UUID tenantId, @Param("propertyId") UUID propertyId,
                         @Param("day") java.time.LocalDate day);

    /** Active spots of the property attached to a unit (the Overview's "attached" tile). */
    long countByTenantIdAndPropertyIdAndActiveTrueAndAttachedUnitIdIsNotNull(UUID tenantId, UUID propertyId);

    /** Renter-facing: the property's active spots that are not attached to any unit — the bookable ones. */
    List<ParkingSpot> findByTenantIdAndPropertyIdAndActiveTrueAndAttachedUnitIdIsNullOrderByCreatedAtAsc(
            UUID tenantId, UUID propertyId);

    /** Active spots attached to any of these units — a renter's allocated parking. */
    @Query("""
        select s from ParkingSpot s
        where s.tenantId = :tenantId and s.active = true and s.attachedUnitId in :unitIds
        order by s.createdAt asc
        """)
    List<ParkingSpot> findActiveAttachedTo(@Param("tenantId") UUID tenantId,
                                           @Param("unitIds") java.util.Collection<UUID> unitIds);

    /**
     * Row-locked, tenant-scoped read: the serialisation point for a bay's booking
     * create/approve and its attach/detach, so an overlap or attachment check and the
     * write that follows it cannot interleave with another. Waits rather than NOWAIT —
     * every competing transaction is short.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ParkingSpot s where s.id = :id and s.tenantId = :tenantId")
    java.util.Optional<ParkingSpot> findByIdForUpdate(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** The property's bay with this exact number — the Parking sheet's re-import key. */
    java.util.Optional<ParkingSpot> findByTenantIdAndPropertyIdAndSpotNumber(UUID tenantId, UUID propertyId,
                                                                           String spotNumber);

    boolean existsByTenantIdAndPropertyIdAndSpotNumber(UUID tenantId, UUID propertyId, String spotNumber);

    boolean existsByTenantIdAndPropertyIdAndSpotNumberAndIdNot(UUID tenantId, UUID propertyId, String spotNumber, UUID id);
}
