package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface GatePassScanRepository extends JpaRepository<GatePassScan, UUID> {

    List<GatePassScan> findByGatePassIdOrderByScannedAtAsc(UUID gatePassId);

    boolean existsByGatePassIdAndDirectionAndResult(UUID gatePassId, ScanDirection direction, ScanResult result);

    List<GatePassScan> findByTenantIdAndScannedAtBetween(UUID tenantId, Instant from, Instant to);

    @Modifying
    @Query("UPDATE GatePassScan s SET s.scannedByUserId = NULL WHERE s.scannedByUserId = :userId")
    void detachScannedBy(@Param("userId") UUID userId);
}
