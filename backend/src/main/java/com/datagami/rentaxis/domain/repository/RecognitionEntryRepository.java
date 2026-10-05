package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.RecognitionEntry;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecognitionEntryRepository extends JpaRepository<RecognitionEntry, UUID> {

    /**
     * The row, locked for update — how a run claims an entry before posting it.
     *
     * <p>Re-reading the status inside the poster's own transaction is not enough
     * on its own. Under READ COMMITTED the nightly job and a hand-run month-end
     * close can both {@code SELECT} the same row while it is still {@code PLANNED},
     * both write a {@code CIL}, and the loser's {@code UPDATE} merely overwrites
     * {@code journal_id} — rent recognised twice, {@code ADVANCE_RENT}
     * over-released, one orphaned journal nothing points at, and a trial balance
     * that still balances so nobody notices. With {@code FOR UPDATE} the loser
     * blocks, then re-reads the committed row, sees {@code POSTED} and refuses
     * before writing anything. Same shape as
     * {@code JournalEntryRepository.lockById}, which {@code PostingService.reverse}
     * uses against the identical race.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from RecognitionEntry e where e.id = :id")
    Optional<RecognitionEntry> lockById(@Param("id") UUID id);

    /** The lease's whole schedule, in the order the lease page prints it. */
    List<RecognitionEntry> findByLease_IdOrderByPeriodStartAsc(UUID leaseId);

    List<RecognitionEntry> findByLease_IdAndStatusInOrderByPeriodStartAsc(
            UUID leaseId, Collection<RecognitionStatus> statuses);

    /** One segment's rows — what a termination re-slices. */
    List<RecognitionEntry> findBySegment_IdOrderByPeriodStartAsc(UUID segmentId);

    /**
     * Everything due to be recognised by {@code to}, oldest period first — the
     * nightly run's candidate list (spec §8.4).
     *
     * <p>The tenant is a parameter rather than an ambient filter on purpose. The
     * job walks the tenants one at a time and sets the context itself, and
     * {@code TenantAspect} only enables the Hibernate filter around a repository
     * call made inside a transaction; a query that depended on the filter alone
     * would quietly return every tenant's rows the first time somebody called it
     * from outside one. With the id in the where clause the two agree, and if the
     * filter is on as well it narrows the same rows a second time.</p>
     */
    List<RecognitionEntry> findByTenantIdAndStatusAndPeriodEndLessThanEqualOrderByPeriodEndAsc(
            UUID tenantId, RecognitionStatus status, LocalDate to);

    /**
     * The earliest entry of {@code tenantId} in {@code status} whose period ends on or
     * before {@code to} — the question a lock asks before it covers that date
     * ({@code TenantFiscalSettingsService.setBooksStartDate}, review of R4-B I3).
     */
    Optional<RecognitionEntry> findFirstByTenantIdAndStatusAndPeriodEndLessThanEqualOrderByPeriodEndAsc(
            UUID tenantId, RecognitionStatus status, LocalDate to);

    /**
     * The distinct period ends still PLANNED after {@code after} and on or before {@code through},
     * oldest first — what moving the period lock from {@code after} to {@code through} would strand.
     * Rows already inside the lock are not counted: the run cannot post them, the locked-months
     * catch-up does (PR #400 review P2-1).
     */
    @Query("""
            select distinct e.periodEnd from RecognitionEntry e
            where e.tenantId = :tenantId and e.status = com.datagami.rentaxis.domain.entity.enums.RecognitionStatus.PLANNED
              and e.periodEnd > :after and e.periodEnd <= :through
            order by e.periodEnd""")
    List<LocalDate> findPlannedPeriodEndsBetween(@Param("tenantId") UUID tenantId, @Param("after") LocalDate after,
                                                 @Param("through") LocalDate through);

    long countByTenantIdAndStatusAndPeriodEndGreaterThanAndPeriodEndLessThanEqual(
            UUID tenantId, RecognitionStatus status, LocalDate after, LocalDate through);

    /** Leases holding a PLANNED row inside the period lock — what the locked-months catch-up visits. */
    @Query("""
            select distinct e.lease.id from RecognitionEntry e
            where e.tenantId = :tenantId and e.status = com.datagami.rentaxis.domain.entity.enums.RecognitionStatus.PLANNED
              and e.periodEnd <= :lockedThrough""")
    List<UUID> findLeaseIdsWithPlannedThrough(@Param("tenantId") UUID tenantId, @Param("lockedThrough") LocalDate lockedThrough);

    /** PLANNED rows inside the period lock: how many, for the recognition screen. */
    @Query("""
            select count(e), coalesce(sum(e.amount), 0) from RecognitionEntry e
            where e.tenantId = :tenantId and e.status = com.datagami.rentaxis.domain.entity.enums.RecognitionStatus.PLANNED
              and e.periodEnd <= :lockedThrough""")
    List<Object[]> plannedInsideLock(@Param("tenantId") UUID tenantId, @Param("lockedThrough") LocalDate lockedThrough);
}
