package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Locking counterpart of {@link #findByQrToken} used by the scan path, where the
     * read decides a state transition. The lock must be taken by the query that first
     * loads the entity: an unlocked read followed by a locking re-read would return
     * the already-managed (stale) instance from the persistence context.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GatePass p where p.qrToken = :qrToken")
    Optional<GatePass> findByQrTokenForUpdate(@Param("qrToken") String qrToken);

    /** Locking counterpart of {@link #findByTenantIdAndNumericCodeAndStatusIn}. See {@link #findByQrTokenForUpdate}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from GatePass p where p.tenantId = :tenantId and p.numericCode = :numericCode and p.status in :statuses")
    Optional<GatePass> findByNumericCodeForUpdate(@Param("tenantId") UUID tenantId, @Param("numericCode") String numericCode,
            @Param("statuses") Collection<GatePassStatus> statuses);

    List<GatePass> findByTenantIdAndCreatedByUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId);

    @Query("select p from GatePass p where p.tenantId = :tenantId and p.propertyId in :propertyIds and p.status = :status and p.validFrom <= :windowEnd and p.validTo >= :windowStart")
    List<GatePass> findActiveOverlapping(@Param("tenantId") UUID tenantId, @Param("propertyIds") Collection<UUID> propertyIds,
            @Param("status") GatePassStatus status, @Param("windowStart") Instant windowStart, @Param("windowEnd") Instant windowEnd);

    List<GatePass> findByTenantIdAndStatusAndPropertyIdIn(UUID tenantId, GatePassStatus status, Collection<UUID> propertyIds);

    boolean existsByTenantIdAndNumericCodeAndStatusIn(UUID tenantId, String numericCode, Collection<GatePassStatus> statuses);
}
