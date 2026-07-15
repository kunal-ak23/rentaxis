package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<GatePass> findByTenantIdAndPropertyIdInAndStatusAndValidFromLessThanEqualAndValidToGreaterThanEqual(
            UUID tenantId, Collection<UUID> propertyIds, GatePassStatus status, Instant windowEnd, Instant windowStart);

    List<GatePass> findByTenantIdAndStatusAndPropertyIdIn(UUID tenantId, GatePassStatus status, Collection<UUID> propertyIds);

    boolean existsByTenantIdAndNumericCodeAndStatusIn(UUID tenantId, String numericCode, Collection<GatePassStatus> statuses);
}
