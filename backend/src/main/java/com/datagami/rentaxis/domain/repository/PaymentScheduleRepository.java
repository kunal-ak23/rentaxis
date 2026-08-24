package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentScheduleRepository extends JpaRepository<PaymentSchedule, UUID> {

    List<PaymentSchedule> findByLeaseId(UUID leaseId);

    List<PaymentSchedule> findByPropertyId(UUID propertyId);

    List<PaymentSchedule> findByStatus(PaymentStatus status);

    List<PaymentSchedule> findByLeaseIdAndStatus(UUID leaseId, PaymentStatus status);

    /**
     * Pessimistic write lock on the targeted schedule rows so concurrent
     * bulk-attach callers serialize their check-then-update on
     * {@code status == PENDING}. Without this, two parallel callers can both
     * observe PENDING under {@code READ_COMMITTED} and both flip to
     * COLLECTED, blowing the invariant and emitting duplicate
     * CHEQUE_RECEIVED events.
     *
     * <p>NOWAIT: without a bound, a stuck holder (crashed connection, GC
     * pause) would leave every other caller touching these rows waiting
     * forever on the shared connection pool. Fails immediately (Postgres
     * SQLSTATE 55P03) rather than waiting at all — Postgres's connection-level
     * {@code lock_timeout} (the previous approach here, a bounded wait rather
     * than an immediate failure) turned out not to be honored by this stack
     * for row-lock waits under {@code SELECT ... FOR UPDATE} despite Hibernate
     * issuing {@code SET LOCAL lock_timeout}; NOWAIT is a query-level clause
     * Postgres enforces directly and doesn't depend on that connection-state
     * mechanism. Callers should catch {@link
     * org.springframework.dao.PessimisticLockingFailureException} (the type
     * Spring Data JPA's exception translation actually throws — not the raw
     * {@link jakarta.persistence.PessimisticLockException}) and surface a
     * "try again" error rather than let it escape as a 500.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT ps FROM PaymentSchedule ps WHERE ps.id IN :ids")
    List<PaymentSchedule> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);

    /**
     * Pessimistic write lock on a single schedule row so concurrent status
     * transitions (e.g. one caller clearing a cheque while another marks the
     * same cheque failed) serialize their check-then-update on the current
     * {@code status}. Without this, two parallel callers under
     * {@code READ_COMMITTED} can both observe {@code DEPOSITED} and both pass
     * their precondition guard, posting conflicting financial transactions
     * (a "Rental income" credit AND a "Cheque bounced" reversal) for the same
     * payment.
     *
     * <p>NOWAIT — see {@link #findAllByIdForUpdate} for why.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT ps FROM PaymentSchedule ps WHERE ps.id = :id")
    Optional<PaymentSchedule> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Returns the subset of {@code chequeNumbers} that already exist on
     * <em>other</em> schedules of {@code leaseId} (i.e. excluding the ids
     * being bulk-attached right now). Replaces the previous N+1 pattern of
     * loading every schedule on the lease and filtering in-memory.
     */
    @Query("""
        SELECT ps.chequeNumber FROM PaymentSchedule ps
        WHERE ps.lease.id = :leaseId
          AND ps.chequeNumber IN :chequeNumbers
          AND ps.id NOT IN :excludeIds
        """)
    List<String> findConflictingChequeNumbersOnLease(
            @Param("leaseId") UUID leaseId,
            @Param("chequeNumbers") Collection<String> chequeNumbers,
            @Param("excludeIds") Collection<UUID> excludeIds);

    @Query("SELECT ps FROM PaymentSchedule ps WHERE ps.status IN ('PENDING', 'COLLECTED') AND ps.dueDate < :date")
    List<PaymentSchedule> findOverdue(@Param("date") LocalDate date);

    List<PaymentSchedule> findByPropertyIdAndStatusIn(UUID propertyId, List<PaymentStatus> statuses);

    @Query("""
        SELECT ps
        FROM PaymentSchedule ps
        WHERE (:propertyId IS NULL OR ps.property.id = :propertyId)
          AND (:status IS NULL OR ps.status = :status)
        """)
    Page<PaymentSchedule> findFiltered(
            @Param("propertyId") UUID propertyId,
            @Param("status") PaymentStatus status,
            Pageable pageable);

    @Query("""
        SELECT ps
        FROM PaymentSchedule ps
        WHERE (:propertyId IS NULL OR ps.property.id = :propertyId)
          AND (:status IS NULL OR ps.status = :status)
        ORDER BY ps.dueDate DESC, ps.id DESC
        """)
    List<PaymentSchedule> findForRenterSearch(
            @Param("propertyId") UUID propertyId,
            @Param("status") PaymentStatus status);

    /**
     * Paged "overdue" listing. Mirrors the overdue predicate used by the
     * dashboard summary and {@link #findOverdue(LocalDate)}: a payment is
     * overdue when it is still PENDING or COLLECTED (i.e. not deposited/cleared
     * and not yet failed) and its due date is strictly before {@code today}.
     * Keeping this definition in one shape guarantees the payments page filter
     * returns exactly the population the dashboard "Overdue" card counts.
     */
    @Query("""
        SELECT ps
        FROM PaymentSchedule ps
        WHERE (:propertyId IS NULL OR ps.property.id = :propertyId)
          AND ps.status IN ('PENDING', 'COLLECTED', 'OVERDUE')
          AND ps.dueDate < :today
          AND ps.lease.status NOT IN ('DRAFT', 'PENDING_SIGNATURE')
        """)
    Page<PaymentSchedule> findOverdueFiltered(
            @Param("propertyId") UUID propertyId,
            @Param("today") LocalDate today,
            Pageable pageable);

    /** Unpaged overdue variant for the in-memory renter-name search path. */
    @Query("""
        SELECT ps
        FROM PaymentSchedule ps
        WHERE (:propertyId IS NULL OR ps.property.id = :propertyId)
          AND ps.status IN ('PENDING', 'COLLECTED', 'OVERDUE')
          AND ps.dueDate < :today
          AND ps.lease.status NOT IN ('DRAFT', 'PENDING_SIGNATURE')
        ORDER BY ps.dueDate DESC, ps.id DESC
        """)
    List<PaymentSchedule> findOverdueForRenterSearch(
            @Param("propertyId") UUID propertyId,
            @Param("today") LocalDate today);

    /**
     * Cheques in hand that are due (or overdue) for bank deposit: status is
     * COLLECTED (received from the renter, not yet deposited) and the post-dated
     * {@code chequeDate} has arrived (on/before {@code today}). Rows with a null
     * chequeDate are excluded — there is no banking date to act on. Ordering is
     * driven by the {@link Pageable} (controller defaults to chequeDate ASC, so
     * the most overdue-for-deposit cheques surface first) — mirrors
     * {@link #findOverdueFiltered} and avoids a redundant JPQL ORDER BY.
     */
    @Query("""
        SELECT ps
        FROM PaymentSchedule ps
        WHERE (:propertyId IS NULL OR ps.property.id = :propertyId)
          AND ps.status = 'COLLECTED'
          AND ps.chequeDate IS NOT NULL
          AND ps.chequeDate <= :today
        """)
    Page<PaymentSchedule> findChequesToDeposit(
            @Param("propertyId") UUID propertyId,
            @Param("today") LocalDate today,
            Pageable pageable);

    /**
     * Monthly "collection vs expected" aggregation for the dashboard chart.
     * Grouped by the due-date month: expected = all amounts due that month;
     * collected = the subset already received (COLLECTED / DEPOSITED / CLEARED).
     * JPQL (not native) so the tenant Hibernate filter still applies. Returns
     * rows only for months that have schedules — the service zero-fills gaps.
     * Each row is [ym(String "yyyy-MM"), expected(BigDecimal), collected(BigDecimal)].
     */
    @Query("""
        SELECT FUNCTION('to_char', ps.dueDate, 'YYYY-MM') AS ym,
               SUM(ps.amount) AS expected,
               SUM(CASE WHEN ps.status IN ('COLLECTED', 'DEPOSITED', 'CLEARED') THEN ps.amount ELSE 0 END) AS collected
        FROM PaymentSchedule ps
        WHERE ps.dueDate >= :from AND ps.dueDate < :to
          AND ps.lease.status NOT IN ('DRAFT', 'PENDING_SIGNATURE')
        GROUP BY FUNCTION('to_char', ps.dueDate, 'YYYY-MM')
        """)
    List<Object[]> aggregateMonthlyCollection(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * Cash actually received in a time window: sum of schedule amounts whose
     * latest status transition (collect/deposit/clear) happened inside
     * [from, to). Powers the dashboard "Collected this month" stat so it
     * matches the Transactions ledger view, independent of due dates.
     */
    @Query("""
        SELECT COALESCE(SUM(ps.amount), 0)
        FROM PaymentSchedule ps
        WHERE ps.status IN ('COLLECTED', 'DEPOSITED', 'CLEARED')
          AND ps.statusChangedAt >= :from AND ps.statusChangedAt < :to
          AND ps.lease.status NOT IN ('DRAFT', 'PENDING_SIGNATURE')
        """)
    BigDecimal sumReceivedBetween(
            @Param("from") Instant from,
            @Param("to") Instant to);

    @Query("""
        SELECT new com.datagami.rentaxis.domain.repository.ChequeImagePurgeRow(
            ps.id, ps.tenantId, ps.chequeImageBlobPath
        )
        FROM PaymentSchedule ps
        WHERE ps.chequeImageBlobPath IS NOT NULL
          AND ps.chequeDate < :cutoff
        """)
    List<ChequeImagePurgeRow> findChequeImagesOlderThan(@Param("cutoff") LocalDate cutoff);

    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE PaymentSchedule ps
        SET ps.chequeImageUrl = NULL,
            ps.chequeImageBlobPath = NULL,
            ps.chequeImageUploadedAt = NULL
        WHERE ps.id = :id
        """)
    void clearChequeImage(@Param("id") UUID id);

    /**
     * Search base for the command palette. JOIN FETCH the associations mapToDTO
     * dereferences (lease -> renter, unit, property): without them a scan of N
     * rows fires N lazy selects apiece, which on a large tenant is thousands of
     * round trips to return at most six hits.
     *
     * <p>Plain JPQL on a {@code BaseTenantEntity}, so Hibernate's tenantFilter
     * applies -- but TenantAspect only enables that filter when a tenant is
     * actually set, so callers MUST refuse to run this without an active tenant.
     */
    @Query("""
        SELECT ps
        FROM PaymentSchedule ps
        JOIN FETCH ps.lease l
        JOIN FETCH l.renter
        JOIN FETCH ps.unit
        JOIN FETCH ps.property
        ORDER BY ps.dueDate DESC, ps.id DESC
        """)
    List<PaymentSchedule> findAllForPaletteSearch();
}
