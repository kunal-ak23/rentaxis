package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GatePassScan;
import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
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

    /**
     * One page of the gate-pass report (scale #15): each scan of the window with its
     * pass, newest first. The rules are those of {@code GET /gatepass/report}: the
     * window is inclusive at both ends ({@code Between}), a scan belongs to its pass's
     * {@code propertyId}, a scan whose pass is not found is left out (inner join), and
     * {@code propertyId} / a property manager's buildings narrow by that property —
     * here in SQL rather than after loading the window. Both tables bind the tenant.
     *
     * <p>Row: {@code [GatePassScan, GatePass]}.
     */
    @Query(value = """
        select s, p from GatePassScan s join GatePass p on p.id = s.gatePassId
        where s.tenantId = :tenantId and p.tenantId = :tenantId
          and s.scannedAt between :from and :to
          and (:propertyId is null or p.propertyId = :propertyId)
          and (:unrestricted = true or p.propertyId in :propertyIds)
        order by s.scannedAt desc, s.id
        """,
        countQuery = """
        select count(s) from GatePassScan s join GatePass p on p.id = s.gatePassId
        where s.tenantId = :tenantId and p.tenantId = :tenantId
          and s.scannedAt between :from and :to
          and (:propertyId is null or p.propertyId = :propertyId)
          and (:unrestricted = true or p.propertyId in :propertyIds)
        """)
    Page<Object[]> findReportPage(@Param("tenantId") UUID tenantId,
                                  @Param("from") Instant from,
                                  @Param("to") Instant to,
                                  @Param("propertyId") UUID propertyId,
                                  @Param("unrestricted") boolean unrestricted,
                                  @Param("propertyIds") Collection<UUID> propertyIds,
                                  Pageable pageable);

    @Modifying
    @Query("UPDATE GatePassScan s SET s.scannedByUserId = NULL WHERE s.scannedByUserId = :userId")
    void detachScannedBy(@Param("userId") UUID userId);
}
