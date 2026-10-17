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
        select l.status, count(l), coalesce(sum(coalesce(l.currentRentAmount, l.rentAmount)), 0)
        from Lease l
        where (:unrestricted = true or l.unit.property.id in :propertyIds)
        group by l.status
        """)
    List<Object[]> countAndRentByStatusInScope(@Param("unrestricted") boolean unrestricted,
                                               @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * F14-01: units whose tenancy covers {@code today} — a posted lease (ACTIVE,
     * NOTICE_GIVEN or RENEWED) with {@code start <= today <= end}, the end cut short
     * by a termination date. A posted lease that starts later does not occupy the
     * unit yet. Distinct units, within the caller's properties.
     */
    @Query("""
        select distinct l.unit.id from Lease l
        where l.status in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.NOTICE_GIVEN,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.RENEWED,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.TERMINATED)
          and (l.status <> com.datagami.rentaxis.domain.entity.enums.LeaseStatus.TERMINATED or l.terminatedOn is not null)
          and l.unit is not null
          and (l.startDate is null or l.startDate <= :today)
          and coalesce(l.terminatedOn, l.endDate) >= :today
          and (:unrestricted = true or l.unit.property.id in :propertyIds)
        """)
    List<UUID> unitIdsOccupiedOnInScope(@Param("today") LocalDate today,
                                        @Param("unrestricted") boolean unrestricted,
                                        @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * F14-01: units with a posted lease that starts after {@code today} — reserved
     * (upcoming), whether or not a current lease also covers today.
     */
    @Query("""
        select distinct l.unit.id from Lease l
        where l.status in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.NOTICE_GIVEN)
          and l.unit is not null
          and l.startDate > :today
          and (:unrestricted = true or l.unit.property.id in :propertyIds)
        """)
    List<UUID> unitIdsReservedAfterInScope(@Param("today") LocalDate today,
                                           @Param("unrestricted") boolean unrestricted,
                                           @Param("propertyIds") Collection<UUID> propertyIds);

    /** R2 N-1: posted live leases that start after {@code day} (they reserve their units). */
    @Query("""
        select l from Lease l join fetch l.unit left join fetch l.renter
        where l.status in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.NOTICE_GIVEN)
          and l.startDate > :day
        """)
    List<Lease> upcomingAfter(@Param("day") LocalDate day);

    /** P2-5: posted leases covering {@code day} (a terminated one through its termination date). */
    @Query("""
        select l from Lease l join fetch l.unit left join fetch l.renter
        where l.status in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.NOTICE_GIVEN,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.RENEWED,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.TERMINATED)
          and (l.status <> com.datagami.rentaxis.domain.entity.enums.LeaseStatus.TERMINATED or l.terminatedOn is not null)
          and (l.startDate is null or l.startDate <= :day)
          and coalesce(l.terminatedOn, l.endDate) >= :day
        """)
    List<Lease> coveringOn(@Param("day") LocalDate day);

    /** F14-01: the posted leases on these units that cover today or start later (unit lists). */
    @Query("""
        select l from Lease l join fetch l.renter
        where l.unit.id in :unitIds
          and l.status in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.NOTICE_GIVEN,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.RENEWED,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.TERMINATED)
          and (l.status <> com.datagami.rentaxis.domain.entity.enums.LeaseStatus.TERMINATED or l.terminatedOn is not null)
          and coalesce(l.terminatedOn, l.endDate) >= :today
        """)
    List<Lease> currentOrUpcomingOnUnits(@Param("unitIds") Collection<UUID> unitIds, @Param("today") LocalDate today);

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
          and (cast(:buildingId as java.util.UUID) is null or l.unit.building.id = :buildingId)
        """)
    Page<Lease> search(@Param("tenantId") UUID tenantId,
                       @Param("status") LeaseStatus status,
                       @Param("propertyId") UUID propertyId,
                       @Param("buildingId") UUID buildingId,
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
          and (cast(:buildingId as java.util.UUID) is null or l.unit.building.id = :buildingId)
        """)
    List<Lease> searchList(@Param("tenantId") UUID tenantId,
                           @Param("status") LeaseStatus status,
                           @Param("propertyId") UUID propertyId,
                           @Param("buildingId") UUID buildingId);

    /**
     * {@link #search} without the contracts in {@code excluded} (never empty), so a
     * picker that wants live contracts only (the Collection hub's "Scan cheques")
     * is paged and counted by the database rather than by loading the organisation's
     * every contract and dropping the closed ones in memory.
     */
    @Query("""
        select l from Lease l
        where l.tenantId = :tenantId
          and l.status not in :excluded
          and (cast(:status as string) is null or l.status = :status)
          and (cast(:propertyId as java.util.UUID) is null or l.unit.property.id = :propertyId)
          and (cast(:buildingId as java.util.UUID) is null or l.unit.building.id = :buildingId)
        """)
    Page<Lease> searchExcluding(@Param("tenantId") UUID tenantId,
                                @Param("status") LeaseStatus status,
                                @Param("propertyId") UUID propertyId,
                                @Param("buildingId") UUID buildingId,
                                @Param("excluded") Collection<LeaseStatus> excluded,
                                Pageable pageable);

    /** {@link #searchExcluding} unpaged, for the paths {@link #searchList} serves. */
    @Query("""
        select l from Lease l
        where l.tenantId = :tenantId
          and l.status not in :excluded
          and (cast(:status as string) is null or l.status = :status)
          and (cast(:propertyId as java.util.UUID) is null or l.unit.property.id = :propertyId)
          and (cast(:buildingId as java.util.UUID) is null or l.unit.building.id = :buildingId)
        """)
    List<Lease> searchListExcluding(@Param("tenantId") UUID tenantId,
                                    @Param("status") LeaseStatus status,
                                    @Param("propertyId") UUID propertyId,
                                    @Param("buildingId") UUID buildingId,
                                    @Param("excluded") Collection<LeaseStatus> excluded);

    /**
     * The contracts list's free-text search and property-manager scope, in the database
     * (scale follow-up to PR #366). Both used to run in memory over every contract with the
     * requested status: about 190 ms a keystroke at 2,000 contracts, growing linearly.
     *
     * <p>The term matches exactly what the in-memory filter matched on the mapped DTO, and
     * nothing more: the unit number, the renter's name, the property's name, the Ejari number
     * (each {@code lower(column)} containing the lower-cased term), or a status whose name
     * contains it ({@code statusHits}, resolved in Java from the enum). {@code pattern} is
     * {@code %term%} with {@code %}, {@code _} and {@code \} escaped, so they match literally,
     * as {@code String.contains} did. Each column side is a {@code lower(...)} expression the
     * pg_trgm GIN indexes of changesets 146/148/174 cover, and the joined tables are matched
     * through id subqueries so each can use its own index.</p>
     *
     * <p>{@code tenantId} is bound on the lease and on every subquery: the Hibernate tenant
     * filter is only on inside a transaction, and these must not rely on it.</p>
     *
     * <p>Parameters that cannot be empty or null in an {@code in} list are guarded by a flag:
     * {@code allowed} is every status not excluded (the caller returns an empty page itself
     * when that is none), {@code scoped}/{@code visible} is a property manager's assigned
     * properties (never empty when {@code scoped}: no assignments means an empty page before
     * the query), {@code hasStatusHits}/{@code statusHits} likewise.</p>
     *
     * <p>Renewal season (scale #16): {@code endFrom}/{@code endTo} bound the expiry date
     * (inclusive; {@code idx_leases_tenant_status_end}), {@code floorPrefix} is the unit-number
     * prefix a floor's units share — the same reading as {@code GET /units/paged?floor=}, with
     * {@code %}, {@code _} and {@code \} escaped — and {@code successor} is 0 for any, 1 for
     * "no renewal or transfer drafted from it yet", 2 for "one has been". The successor test is
     * {@code coalesce(renewed_from, transferred_from)}, the expression
     * {@code ux_leases_one_successor} indexes, and the one {@code LeaseRenewalService.renew}
     * refuses on. {@code mark} (scale PR C) is 0 for any, 1 for "no soft not-renewing mark", 2 for
     * "marked not renewing, or on notice".</p>
     */
    @Query("""
        select l from Lease l
        where l.tenantId = :tenantId
          and l.status in :allowed
          and (cast(:status as string) is null or l.status = :status)
          and (cast(:propertyId as java.util.UUID) is null or l.unit.property.id = :propertyId)
          and (cast(:buildingId as java.util.UUID) is null or l.unit.building.id = :buildingId)
          and (:scoped = false or l.unit.id in (
                select su.id from Unit su where su.tenantId = :tenantId and su.property.id in :visible))
          and (cast(:pattern as string) is null
               or l.unit.id in (select u.id from Unit u
                                where u.tenantId = :tenantId and lower(u.unitNumber) like :pattern escape '\\')
               or l.renter.id in (select r.id from Renter r
                                  where r.tenantId = :tenantId and lower(r.nameEn) like :pattern escape '\\')
               or l.unit.id in (select pu.id from Unit pu
                                where pu.tenantId = :tenantId and pu.property.id in (
                                    select p.id from Property p
                                    where p.tenantId = :tenantId and lower(p.nameEn) like :pattern escape '\\'))
               or lower(l.ejariNumber) like :pattern escape '\\'
               or (:hasStatusHits = true and l.status in :statusHits))
          and (cast(:endFrom as LocalDate) is null or l.endDate >= :endFrom)
          and (cast(:endTo as LocalDate) is null or l.endDate <= :endTo)
          and (cast(:floorPrefix as string) is null or l.unit.id in (select fu.id from Unit fu
                where fu.tenantId = :tenantId and lower(fu.unitNumber) like :floorPrefix escape '\\'))
          and (:successor = 0
               or (:successor = 1 and not exists (select s.id from Lease s where s.tenantId = :tenantId
                     and coalesce(s.renewedFromLeaseId, s.transferredFromLeaseId) = l.id))
               or (:successor = 2 and exists (select s.id from Lease s where s.tenantId = :tenantId
                     and coalesce(s.renewedFromLeaseId, s.transferredFromLeaseId) = l.id)))
          and (:mark = 0
               or (:mark = 1 and not exists (select o.id from RenewalOpportunity o where o.tenantId = :tenantId
                     and o.lease.id = l.id and o.notRenewingAt is not null))
               or (:mark = 2 and (l.status = com.datagami.rentaxis.domain.entity.enums.LeaseStatus.NOTICE_GIVEN
                     or exists (select o.id from RenewalOpportunity o where o.tenantId = :tenantId
                     and o.lease.id = l.id and o.notRenewingAt is not null))))
        """)
    Page<Lease> searchText(@Param("tenantId") UUID tenantId,
                           @Param("allowed") Collection<LeaseStatus> allowed,
                           @Param("status") LeaseStatus status,
                           @Param("propertyId") UUID propertyId,
                           @Param("buildingId") UUID buildingId,
                           @Param("scoped") boolean scoped,
                           @Param("visible") Collection<UUID> visible,
                           @Param("pattern") String pattern,
                           @Param("hasStatusHits") boolean hasStatusHits,
                           @Param("statusHits") Collection<LeaseStatus> statusHits,
                           @Param("endFrom") java.time.LocalDate endFrom,
                           @Param("endTo") java.time.LocalDate endTo,
                           @Param("floorPrefix") String floorPrefix,
                           @Param("successor") int successor,
                           @Param("mark") int mark,
                           Pageable pageable);

    /**
     * {@link #searchText} without the renewal-season filters (scale #16): no expiry window, no
     * floor, any successor state.
     */
    default Page<Lease> searchText(UUID tenantId, Collection<LeaseStatus> allowed, LeaseStatus status,
                                   UUID propertyId, UUID buildingId, boolean scoped, Collection<UUID> visible,
                                   String pattern, boolean hasStatusHits, Collection<LeaseStatus> statusHits,
                                   Pageable pageable) {
        return searchText(tenantId, allowed, status, propertyId, buildingId, scoped, visible, pattern,
                hasStatusHits, statusHits, null, null, null, 0, 0, pageable);
    }

    /**
     * The renewals drafted from a page of leases (scale #16: the renewal queue shows which
     * contracts already have one). {@code renewedFromLeaseId} only; a transfer successor is
     * {@link #findByTransferredFromLeaseIdIn}.
     */
    @Query("SELECT l FROM Lease l WHERE l.renewedFromLeaseId IN :leaseIds")
    List<Lease> findByRenewedFromLeaseIdIn(@Param("leaseIds") java.util.Collection<UUID> leaseIds);

    List<Lease> findByUnitId(UUID unitId);

    List<Lease> findByUnitIdAndStatus(UUID unitId, LeaseStatus status);

    /**
     * The leases that are <em>living on</em> a unit — the question every occupancy
     * rule asks (see {@code LeaseService.LIVE}).
     *
     * <p>A tenancy on notice is still a tenancy: the renter is still there, still
     * owes the remaining months and still has instruments on the register. Asking
     * this by a single status was the bug review I3 found — a unit could be let
     * twice, or vacated under a sitting renter, the day somebody recorded a
     * notice.</p>
     */
    List<Lease> findByUnitIdAndStatusIn(UUID unitId, Collection<LeaseStatus> statuses);

    /**
     * Leases already holding a PACT contract reference in this organisation.
     *
     * <p>Explicitly tenant-scoped rather than relying on the Hibernate filter: the
     * cut-over validator runs on the import executor's thread, and "is this
     * reference free?" answered across organisations would refuse one landlord's
     * contract because another landlord numbers theirs the same way.</p>
     */
    List<Lease> findByTenantIdAndExternalContractRef(UUID tenantId, String externalContractRef);

    List<Lease> findByRenterId(UUID renterId);

    /**
     * Leases with their unit and property already loaded — one query for a page of
     * rows that each need to say which building they belong to.
     *
     * <p>{@code join fetch}, not a projection, because the callers want the entity
     * graph they already work with; and inner joins, because {@code leases.unit_id}
     * is NOT NULL and a unit always has a property. The alternative is two lazy
     * loads per row, which on the month-end page is hundreds of queries to render
     * one grouped list.</p>
     */
    @Query("""
        select l from Lease l
        join fetch l.unit u
        join fetch u.property p
        where l.id in :ids
        """)
    List<Lease> findAllWithUnitAndPropertyByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * The nightly expiry sweep's candidates (spec §9): a running tenancy whose last
     * day has passed and which no termination has claimed.
     *
     * <p><b>{@code terminated_on IS NULL} is the belt to the status filter's
     * brace.</b> A row that is still ACTIVE and already carries a termination date
     * is a termination somebody is in the middle of — or one that left drift behind
     * — and expiring it would stamp a second ending on a contract that already has
     * one and release a unit the termination has not finished with. The status
     * filter alone would not see it.</p>
     *
     * <p>No tenant column: the Hibernate filter supplies it, which is why the
     * caller must have a tenant in context <em>and</em> a transaction for
     * {@code TenantAspect} to enable it in. {@link com.datagami.rentaxis.core.service.LeaseService#findLeasesToExpire}
     * is that caller.</p>
     */
    @Query("""
        SELECT l.id FROM Lease l
        WHERE l.status IN :statuses
          AND l.endDate < :date
          AND l.terminatedOn IS NULL
        """)
    List<UUID> findIdsToExpire(@Param("statuses") Collection<LeaseStatus> statuses,
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

    /** Spec §2: the successor on another unit, drafted or posted. */
    @Query("SELECT l FROM Lease l WHERE l.transferredFromLeaseId = :leaseId")
    List<Lease> findByTransferredFromLeaseId(@Param("leaseId") UUID leaseId);

    /** {@link #findByTransferredFromLeaseId} for a page of leases, the successor's unit fetched. */
    @Query("SELECT l FROM Lease l LEFT JOIN FETCH l.unit WHERE l.transferredFromLeaseId IN :leaseIds")
    List<Lease> findByTransferredFromLeaseIdIn(@Param("leaseIds") java.util.Collection<UUID> leaseIds);

    /**
     * One organisation's live contracts (ACTIVE or NOTICE_GIVEN) ending on {@code endDate},
     * renter fetched — the expiry reminder's list (scale P1-1), served by
     * {@code idx_leases_tenant_status_end}.
     */
    @Query("""
        SELECT l FROM Lease l LEFT JOIN FETCH l.renter
        WHERE l.tenantId = :tenantId AND l.endDate = :endDate
          AND l.status IN (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.NOTICE_GIVEN)""")
    List<Lease> findLiveEndingOn(@Param("tenantId") UUID tenantId, @Param("endDate") java.time.LocalDate endDate);

    /**
     * Break-it R3 portal3 F1: the renter user's <em>current</em> contracts — live
     * (ACTIVE, or ACTIVE with notice given: the renter still occupies the unit) and
     * with {@code today} inside the term — with unit and property loaded. What a
     * renter may raise a ticket (or anything else unit-bound) against.
     *
     * <p>Review r3C I1: RENEWED counts too. Posting a renewal early flips the running
     * lease to RENEWED at once while its term still runs (the successor starts later);
     * with today inside that term it is still the renter's current contract.
     */
    @Query("""
        select l from Lease l join fetch l.unit u join fetch u.property
        where l.tenantId = :tenantId
          and l.renter.userId = :userId
          and l.status in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.ACTIVE,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.NOTICE_GIVEN,
                           com.datagami.rentaxis.domain.entity.enums.LeaseStatus.RENEWED)
          and (l.startDate is null or l.startDate <= :today)
          and (l.endDate is null or l.endDate >= :today)
        """)
    List<Lease> findCurrentForRenterUser(@Param("tenantId") UUID tenantId,
                                         @Param("userId") UUID userId,
                                         @Param("today") java.time.LocalDate today);

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
     * The lease's status as the <em>database</em> has it, not as this transaction's
     * first-level cache has it.
     *
     * <p>A scalar projection on purpose (review M-1). Loading the entity — however
     * it is loaded, including through a locking finder — is answered from the
     * persistence context when the row is already managed, so it hands back the
     * status that was read the first time. {@code ChequeService}'s close hook is
     * exactly that case: the lease arrives through {@code cheque.getLease()}, which
     * may have been resolved before the transition began, and a lease read ACTIVE
     * for a row that is now TERMINATED with its settlement finalised skips a close
     * that should have happened. A scalar query is not resolved through the context,
     * so this always sees the committed row, and it costs one cheap select instead
     * of the row lock the hook only wants to take when the answer is interesting.</p>
     */
    @Query("SELECT l.status FROM Lease l WHERE l.id = :id")
    Optional<LeaseStatus> findStatusById(@Param("id") UUID id);

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
