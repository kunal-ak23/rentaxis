package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
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

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeaseRepository extends JpaRepository<Lease, UUID> {

    /**
     * Lease counts and contracted rent per status, within the caller's properties.
     *
     * <p>Returns {@code [LeaseStatus, Long count, BigDecimal rent]}. The dashboard
     * reads three tiles off it — active, draft and total rent revenue — which is why
     * it is one grouped aggregate rather than three counts.</p>
     *
     * <p>Joined through the unit to its property because that is where a lease's
     * building lives. The join is inner: {@code leases.unit_id} is NOT NULL, so no
     * lease can be lost by it.</p>
     */
    @Query("""
        select l.status, count(l), coalesce(sum(l.rentAmount), 0)
        from Lease l
        where (:unrestricted = true or l.unit.property.id in :propertyIds)
        group by l.status
        """)
    List<Object[]> countAndRentByStatusInScope(@Param("unrestricted") boolean unrestricted,
                                               @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * Live tenancies ending inside {@code [from, to]}, within the caller's
     * properties — the "expiring soon" tile.
     */
    @Query("""
        select count(l) from Lease l
        where l.status = com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE
          and l.endDate >= :from and l.endDate <= :to
          and (:unrestricted = true or l.unit.property.id in :propertyIds)
        """)
    long countExpiringInScope(@Param("from") LocalDate from,
                              @Param("to") LocalDate to,
                              @Param("unrestricted") boolean unrestricted,
                              @Param("propertyIds") Collection<UUID> propertyIds);

    List<Lease> findByTenantId(UUID tenantId);
    Page<Lease> findByTenantId(UUID tenantId, Pageable pageable);

    /**
     * The contracts list's filters, in the database rather than over a page.
     *
     * <p>The list screen used to take only a free-text term, so filtering by status
     * meant the client dropping rows out of the page it had been handed — a
     * paginator claiming 25 contracts while showing four, and page 2 showing rows
     * that belonged on page 1. Both filters therefore narrow the query, and the
     * count Spring Data derives is the count of what matched.</p>
     *
     * <p>The casts let Postgres infer a type for the bare {@code is null} tests,
     * which it otherwise rejects outright once a caller passes a non-null filter —
     * the same shape {@code ChequeRepository.search} uses.</p>
     *
     * <p>Property-manager scoping is deliberately <em>not</em> here. It is
     * {@code LeaseAccessPolicy}'s, and it is more than a property-id list (a renter
     * sees their own leases, an accountant sees the tenant); duplicating half of it
     * in JPQL is how the two come to disagree. A restricted caller goes through
     * {@link #searchList} and {@code filterReadable}, which is what it did before
     * these filters existed.</p>
     */
    @Query("""
        select l from Lease l
        where l.tenantId = :tenantId
          and (cast(:status as string) is null or l.status = :status)
          and (cast(:propertyId as java.util.UUID) is null or l.unit.property.id = :propertyId)
        """)
    Page<Lease> search(@Param("tenantId") UUID tenantId,
                       @Param("status") LeaseStatus status,
                       @Param("propertyId") UUID propertyId,
                       Pageable pageable);

    /**
     * {@link #search} unpaged, for the callers that still have to finish the job in
     * memory: a property manager, whose scope is the access policy's, and a free-text
     * term, which matches across the unit, renter and property a lease joins to.
     * Both narrow this result rather than the whole tenant's contracts, and both
     * page what is left <em>after</em> filtering, so the total is never a page's
     * worth of guesswork.
     */
    @Query("""
        select l from Lease l
        where l.tenantId = :tenantId
          and (cast(:status as string) is null or l.status = :status)
          and (cast(:propertyId as java.util.UUID) is null or l.unit.property.id = :propertyId)
        """)
    List<Lease> searchList(@Param("tenantId") UUID tenantId,
                           @Param("status") LeaseStatus status,
                           @Param("propertyId") UUID propertyId);

    List<Lease> findByUnitId(UUID unitId);

    List<Lease> findByUnitIdAndStatus(UUID unitId, LeaseStatus status);

    List<Lease> findByRenterId(UUID renterId);

    @Query("SELECT l FROM Lease l WHERE l.status IN :statuses AND l.endDate < :date")
    List<Lease> findByStatusInAndEndDateBefore(
            @Param("statuses") List<LeaseStatus> statuses,
            @Param("date") LocalDate date);

    @Query("SELECT l FROM Lease l WHERE l.status = :status AND l.endDate BETWEEN :from AND :to")
    List<Lease> findByStatusAndEndDateBetween(
            @Param("status") LeaseStatus status,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT l FROM Lease l WHERE l.unit.property.id = :propertyId")
    List<Lease> findByUnitPropertyId(@Param("propertyId") UUID propertyId);

    List<Lease> findByStatus(LeaseStatus status);

    /**
     * The successors somebody has already drafted from this lease (spec §6.6).
     *
     * <p>Renewing twice is what this answers. Two drafts pointing at one
     * predecessor both claim the same unit and both expect to retire it on posting;
     * whichever posts second would find the predecessor already RENEWED and the
     * unit held by its sibling, and the refusal would arrive at posting time with
     * a grid already cut and cheques already collected.</p>
     */
    @Query("SELECT l FROM Lease l WHERE l.renewedFromLeaseId = :leaseId")
    List<Lease> findByRenewedFromLeaseId(@Param("leaseId") UUID leaseId);

    @Query("SELECT COALESCE(MAX(l.contractNumber), 0) FROM Lease l WHERE l.tenantId = :tenantId")
    Long findMaxContractNumberForTenant(@Param("tenantId") UUID tenantId);

    /**
     * Tenant-aware lookup by id. Unlike Spring Data's default {@code findById},
     * this goes through JPQL — which applies Hibernate {@code @Filter}
     * annotations. The default {@code findById} bypasses filters in Hibernate
     * 7 (unless {@code applyToLoadByKey=true} is set on the filter), which
     * would let a caller in tenant A operate on a lease id from tenant B.
     * Use this anywhere a caller-supplied lease id must be scoped to the
     * current tenant.
     */
    @Query("SELECT l FROM Lease l WHERE l.id = :id")
    Optional<Lease> findByIdScopedToTenant(@Param("id") UUID id);

    /**
     * Pessimistic write lock on the lease row, taken before posting reads its
     * status. Two accountants hitting <em>Post</em> on the same contract at the
     * same moment both read {@code DRAFT}, both write a TCO and a full set of
     * PDRs, and the renter is billed twice for one tenancy — the lease's
     * {@code @Version} would fail the second commit, but only after it had burnt
     * a TCO number and written journals it then has to roll back. Locking first
     * turns the loser into a clean 400 that never touches the ledger.
     *
     * <p>NOWAIT: a posting that is waiting on a network call must not park every
     * other caller on the shared connection pool. Callers catch
     * {@link org.springframework.dao.PessimisticLockingFailureException} — the type
     * Spring Data's exception translation actually throws for SQLSTATE 55P03 — and
     * surface a "try again" rather than a 500. The same shape as
     * {@code ChequeRepository.findByIdForUpdate}.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("SELECT l FROM Lease l WHERE l.id = :id")
    Optional<Lease> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
        SELECT l FROM Lease l
        WHERE l.status = com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE
          AND l.endDate <= :cutoff
          AND NOT EXISTS (
            SELECT 1 FROM RenewalOpportunity o
            WHERE o.lease.id = l.id
              AND o.stage IN (com.datagami.rentaxis.domain.entity.enums.RenewalStage.OPEN,
                              com.datagami.rentaxis.domain.entity.enums.RenewalStage.INTENT_CAPTURED)
          )
    """)
    List<Lease> findActiveLeasesEnteringRenewalWindow(@Param("cutoff") LocalDate cutoff);

    /**
     * Properties a renter currently holds an active lease in. Used by the
     * promotions feed to resolve ad targeting; returns ids only so the feed
     * never materialises whole Lease graphs on a home-screen load.
     */
    @Query("""
            SELECT DISTINCT l.unit.property.id FROM Lease l
            WHERE l.tenantId = :tenantId
              AND l.renter.userId = :userId
              AND l.status = com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE
            """)
    List<UUID> findActivePropertyIdsForRenterUser(@Param("tenantId") UUID tenantId,
                                                  @Param("userId") UUID userId);
}
