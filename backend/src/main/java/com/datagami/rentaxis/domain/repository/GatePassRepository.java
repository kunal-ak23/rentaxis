package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatePassRepository extends JpaRepository<GatePass, UUID> {

    Optional<GatePass> findByQrToken(String qrToken);

    Optional<GatePass> findByTenantIdAndNumericCodeAndStatusIn(UUID tenantId, String numericCode, Collection<GatePassStatus> statuses);

    List<GatePass> findByTenantIdAndCreatedByUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId);

    @Query("select p from GatePass p where p.tenantId = :tenantId and p.propertyId in :propertyIds and p.status = :status and p.validFrom <= :windowEnd and p.validTo >= :windowStart")
    List<GatePass> findActiveOverlapping(@Param("tenantId") UUID tenantId, @Param("propertyIds") Collection<UUID> propertyIds,
            @Param("status") GatePassStatus status, @Param("windowStart") Instant windowStart, @Param("windowEnd") Instant windowEnd);

    List<GatePass> findByTenantIdAndStatusAndPropertyIdIn(UUID tenantId, GatePassStatus status, Collection<UUID> propertyIds);

    boolean existsByTenantIdAndNumericCodeAndStatusIn(UUID tenantId, String numericCode, Collection<GatePassStatus> statuses);
}
