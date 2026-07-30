package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GateVisitorUnitRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GateVisitorUnitRegistrationRepository
        extends JpaRepository<GateVisitorUnitRegistration, UUID> {

    @Query("""
        select r from GateVisitorUnitRegistration r
        where r.tenantId = :tenantId
          and r.visitorProfileId = :profileId
          and r.unitId = :unitId
          and r.active = true
          and (r.validFrom is null or r.validFrom <= :now)
          and (r.validTo is null or r.validTo >= :now)
        """)
    Optional<GateVisitorUnitRegistration> findActiveRegistration(
            @Param("tenantId") UUID tenantId,
            @Param("profileId") UUID visitorProfileId,
            @Param("unitId") UUID unitId,
            @Param("now") Instant now);

    Optional<GateVisitorUnitRegistration> findByTenantIdAndVisitorProfileIdAndUnitId(
            UUID tenantId, UUID visitorProfileId, UUID unitId);
}
