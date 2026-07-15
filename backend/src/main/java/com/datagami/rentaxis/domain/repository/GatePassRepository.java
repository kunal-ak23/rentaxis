package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GatePassRepository extends JpaRepository<GatePass, UUID> {

    /**
     * Locking lookup by QR token used by the scan path, where the
     * read decides a state transition. The lock must be taken by the query that first
     * loads the entity: an unlocked read followed by a locking re-read would return
     * the already-managed (stale) instance from the persistence context.
     *
     * <p>NOWAIT (via {@code lock.timeout = 0}): without a bound, a stuck holder
     * (crashed connection, GC pause) would leave every other guard scanning this pass
     * waiting forever on the shared connection pool. Fails immediately (Postgres
     * SQLSTATE 55P03) instead. A bounded {@code lock_timeout} is deliberately not used
     * — it is not honored by this stack for row-lock waits under a locking select; see
     * {@link PaymentScheduleRepository#findAllByIdForUpdate}.
     * Callers must catch {@link org.springframework.dao.PessimisticLockingFailureException}
     * (what Spring Data's exception translation actually throws — not the raw
     * {@link jakarta.persistence.PessimisticLockException}) and surface a "retry"
     * outcome rather than let it escape as a 500.
     *
     * <p>Note the emitted clause is {@code FOR NO KEY UPDATE ... NOWAIT}, which is how
     * Hibernate's Postgres dialect renders {@code PESSIMISTIC_WRITE}. That is the
     * intended strength: two {@code FOR NO KEY UPDATE} requests on the same row still
     * block each other (so concurrent scans of one pass serialize), while still
     * allowing the {@code FOR KEY SHARE} that FK checks take. Asserted on the real SQL
     * in {@code GatePassScanConcurrencyIT}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select p from GatePass p where p.qrToken = :qrToken")
    Optional<GatePass> findByQrTokenForUpdate(@Param("qrToken") String qrToken);

    /**
     * Locking numeric-code resolution for the scan path, newest row first — pass
     * {@code PageRequest.of(0, 1)} so Postgres receives a single
     * {@code ORDER BY created_at DESC ... FETCH FIRST 1 ROWS ONLY FOR NO KEY UPDATE NOWAIT},
     * locking exactly the newest matching row (the lock applies after the limit).
     *
     * <p>Deliberately returns a {@code List} and does NOT filter by status. Numeric codes
     * are recycled: {@code uq_gate_pass_numeric_active} and
     * {@code GatePassService.uniqueNumericCode} only consider PENDING_APPROVAL/ACTIVE, so a
     * terminal pass's code is reissued to a new pass. A status-filtered single-result
     * {@code Optional} finder therefore blows up with
     * {@code IncorrectResultSizeDataAccessException} (a 500 at the gate) the moment a code is
     * held by both an old USED row and a new live one.
     *
     * <p>Ordering by {@code createdAt desc} and taking the first row is what makes this
     * deterministic AND correct: because the partial unique index forbids two *live* passes
     * sharing a code, a newer row can only have been issued a code no live row held — so the
     * live pass with a given code, if there is one, is always the newest row with that code.
     * Taking the newest therefore yields the live pass when one exists, and otherwise the most
     * recent terminal one (which is what lets the scan report "already used" / "expired" /
     * "cancelled" rather than a misleading "not found").
     *
     * <p>NOWAIT — see {@link #findByQrTokenForUpdate} for why.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select p from GatePass p where p.tenantId = :tenantId and p.numericCode = :numericCode order by p.createdAt desc")
    List<GatePass> findByNumericCodeForUpdate(@Param("tenantId") UUID tenantId, @Param("numericCode") String numericCode,
            Pageable pageable);

    List<GatePass> findByTenantIdAndCreatedByUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId);

    @Query("select p from GatePass p where p.tenantId = :tenantId and p.propertyId in :propertyIds and p.status = :status and p.validFrom <= :windowEnd and p.validTo >= :windowStart")
    List<GatePass> findActiveOverlapping(@Param("tenantId") UUID tenantId, @Param("propertyIds") Collection<UUID> propertyIds,
            @Param("status") GatePassStatus status, @Param("windowStart") Instant windowStart, @Param("windowEnd") Instant windowEnd);

    List<GatePass> findByTenantIdAndStatusAndPropertyIdIn(UUID tenantId, GatePassStatus status, Collection<UUID> propertyIds);

    /**
     * Tenant-wide status listing, used for the manager view of the approvals queue.
     * The guard view of the same queue is property-scoped and uses
     * {@link #findByTenantIdAndStatusAndPropertyIdIn} instead.
     */
    List<GatePass> findByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, GatePassStatus status);

    boolean existsByTenantIdAndNumericCodeAndStatusIn(UUID tenantId, String numericCode, Collection<GatePassStatus> statuses);
}
