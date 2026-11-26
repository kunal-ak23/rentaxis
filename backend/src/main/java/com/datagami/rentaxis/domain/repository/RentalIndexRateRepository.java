package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RentalIndexRate;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RentalIndexRateRepository extends JpaRepository<RentalIndexRate, UUID> {

    @Query("select r from RentalIndexRate r where r.id = :id and r.tenantId = :tenantId")
    Optional<RentalIndexRate> findForTenant(@Param("tenantId") UUID tenantId, @Param("id") UUID id);

    /** Ranges in force for a building and type, newest first (latest checked_on, then created_at). */
    @Query("select r from RentalIndexRate r where r.tenantId = :tenantId and r.propertyId = :propertyId"
            + " and r.buildingId = :buildingId and r.unitType = :unitType and r.revokedAt is null"
            + " and r.expiresOn >= :on order by r.checkedOn desc, r.createdAt desc")
    List<RentalIndexRate> inForceForBuilding(@Param("tenantId") UUID tenantId, @Param("propertyId") UUID propertyId,
                                             @Param("buildingId") UUID buildingId,
                                             @Param("unitType") UnitType unitType, @Param("on") LocalDate on,
                                             Pageable first);

    /** Property-level ranges (no building) in force for a type, newest first. */
    @Query("select r from RentalIndexRate r where r.tenantId = :tenantId and r.propertyId = :propertyId"
            + " and r.buildingId is null and r.unitType = :unitType and r.revokedAt is null"
            + " and r.expiresOn >= :on order by r.checkedOn desc, r.createdAt desc")
    List<RentalIndexRate> inForceForProperty(@Param("tenantId") UUID tenantId, @Param("propertyId") UUID propertyId,
                                             @Param("unitType") UnitType unitType, @Param("on") LocalDate on,
                                             Pageable first);

    @Query("select r from RentalIndexRate r where r.tenantId = :tenantId and r.propertyId in :propertyIds"
            + " order by r.propertyId, r.buildingId, r.unitType, r.checkedOn desc, r.createdAt desc")
    List<RentalIndexRate> findForProperties(@Param("tenantId") UUID tenantId,
                                            @Param("propertyIds") Collection<UUID> propertyIds);

    @Query("select r from RentalIndexRate r where r.tenantId = :tenantId"
            + " order by r.propertyId, r.buildingId, r.unitType, r.checkedOn desc, r.createdAt desc")
    List<RentalIndexRate> findAllForTenant(@Param("tenantId") UUID tenantId);
}
