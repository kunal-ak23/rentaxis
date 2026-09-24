package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The cheque register's read side.
 *
 * <p>Every register query excludes cheques whose lease is still {@code DRAFT} or
 * {@code PENDING_SIGNATURE}: those rows are a proposal the renter has not agreed
 * to, so counting them would overstate what the landlord is owed. The filter is
 * on the lease's status rather than the cheque's because a draft lease's cheques
 * are legitimately {@code REGISTERED} in the UI sense — they exist, they are just
 * not yet receivable.</p>
 *
 * <p>Enum comparisons bind parameters or name fully-qualified literals rather
 * than bare strings: Hibernate 7 resolves a bare {@code 'REGISTERED'} against an
 * enum-typed path inconsistently, and a literal that silently fails to match
 * would empty the day's deposit run without any error.</p>
 */
@Repository
public interface ChequeRepository extends JpaRepository<Cheque, UUID> {

    /**
     * What makes a row part of the <em>register</em> rather than of a lease's grid.
     *
     * <p>A {@code DRAFT} cheque is a proposal: it has no {@code PDR} behind it and
     * nobody has handed the paper over. It is read and edited through the grid
     * ({@code GET/PUT /api/v1/leases/&#123;id&#125;/cheques}) and it is invisible to
     * every query below — {@code search}, the summary totals, the activity feed and
     * the per-lease stats all exclude it, and so does {@code get} on a single id.</p>
     *
     * <p>Filtering on the lease's status alone was not enough. An imported lease is
     * created DRAFT, given its grid and then moved to ACTIVE without posting, so its
     * rows are DRAFT cheques on a non-draft lease — the exact combination that used
     * to appear on the register as instruments with no journal behind them.</p>
     *
     * <p>Not a constant Java can share (JPQL is a string literal per method), so it
     * is written out in each query and named here so the rule has one home.</p>
     */
    String REGISTER_ROW = """
        c.status <> ChequeStatus.DRAFT
          and c.lease.status not in (LeaseStatus.DRAFT, LeaseStatus.PENDING_SIGNATURE)""";

    /** The lease's instalment schedule in schedule order. */
    List<Cheque> findByLease_IdOrderBySeqNoAsc(UUID leaseId);

    /**
     * Drop a lease's cheques in one status. Used with {@code DRAFT} when a draft
     * lease's lines change: the proposed instalments were cut from amounts that
     * no longer exist, and there is nothing on a DRAFT cheque worth preserving.
     * Scoped by status rather than by lease alone so this can never reach a
     * cheque that has been registered, banked or cleared.
     */
    @Modifying
    void deleteByLease_IdAndStatus(UUID leaseId, ChequeStatus status);

    /**
     * How many of the lease's cheques have moved past {@code DRAFT}. Deleting a
     * draft lease refuses on a non-zero count: those rows are paper in hand or
     * money in transit, and the FK on {@code cheques.lease_id} would otherwise
     * fail as an opaque 500.
     */
    long countByLease_IdAndStatusNot(UUID leaseId, ChequeStatus status);

    /**
     * Pessimistic write lock on a single cheque so concurrent lifecycle transitions
     * (one caller clearing it while another marks it bounced) serialize their
     * check-then-update on {@code status}. Without it, two callers under
     * {@code READ_COMMITTED} both observe {@code DEPOSITED}, both pass their guard,
     * and the ledger gets a clearing entry and a bounce reversal for the same money.
     *
     * <p>NOWAIT: a stuck holder must not park every other caller on the shared
     * connection pool. Callers catch
     * {@link org.springframework.dao.PessimisticLockingFailureException} — the type
     * Spring Data's exception translation actually throws — and surface a "try
     * again" rather than a 500.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select c from Cheque c where c.id = :id")
    Optional<Cheque> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Batch form of {@link #findByIdForUpdate}, for bulk deposit and bulk clear
     * runs. All-or-nothing: one contended row fails the whole call, so a caller
     * never gets a half-locked set it then mutates.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("select c from Cheque c where c.id in :ids")
    List<Cheque> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);

    /** The renter's own view of what they still owe, oldest instrument first. */
    List<Cheque> findByRenter_IdAndStatusInOrderByChequeDateAsc(UUID renterId, Collection<ChequeStatus> statuses);

    /**
     * The register screen. Every filter is optional; the casts let Postgres infer a
     * type for the bare {@code is null} test, which it otherwise rejects outright
     * once a caller passes a non-null filter.
     *
     * <p>{@code search} is matched with {@code like} against lowercased columns, so
     * the <em>caller</em> passes an already-lowercased, already-wildcarded term —
     * {@code "%" + term.toLowerCase() + "%"} — not a bare word.</p>
     *
     * <p>{@code unit} is joined explicitly with a LEFT JOIN because the column is
     * nullable: dereferencing {@code c.unit.unitNumber} inline would make Hibernate
     * emit an INNER join and silently drop every cheque that has no unit from the
     * register <em>and</em> from its total count, even when no search term was
     * given. {@code c.lease} and {@code c.lease.renter} are non-null, so their
     * implicit joins are safe.</p>
     *
     * <p>{@code unrestricted}/{@code propertyIds} are how a property manager is kept
     * inside their own buildings, the same shape {@code PenaltyAssessmentRepository}
     * uses: the caller passes {@code false} with the ids they were assigned, and a
     * manager assigned to nothing is answered without a query at all. Filtering a
     * page the database has already counted would report totals covering buildings
     * the caller may not see and hand back short pages.</p>
     *
     * <p>{@code DRAFT} rows are excluded — see {@link #REGISTER_ROW}. A caller that
     * passes {@code status = DRAFT} therefore gets nothing, which is correct: the
     * grid is read through {@code GET /api/v1/leases/&#123;id&#125;/cheques}.</p>
     */
    @Query("""
        select c from Cheque c
        left join c.unit u
        where (cast(:propertyId as java.util.UUID) is null or c.property.id = :propertyId)
          and c.status <> com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DRAFT
          and (cast(:status as string) is null or c.status = :status)
          and (cast(:mode as string) is null or c.mode = :mode)
          and (cast(:from as LocalDate) is null or c.chequeDate >= :from)
          and (cast(:to as LocalDate) is null or c.chequeDate <= :to)
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (:unrestricted = true or c.property.id in :propertyIds)
          and (cast(:search as string) is null
               or lower(c.lease.renter.nameEn) like :search
               or lower(c.chequeNumber) like :search
               or lower(u.unitNumber) like :search)
        """)
    Page<Cheque> search(@Param("propertyId") UUID propertyId,
                        @Param("status") ChequeStatus status,
                        @Param("mode") ChequeMode mode,
                        @Param("from") LocalDate from,
                        @Param("to") LocalDate to,
                        @Param("search") String search,
                        @Param("unrestricted") boolean unrestricted,
                        @Param("propertyIds") Collection<UUID> propertyIds,
                        Pageable pageable);

    /**
     * Matured and still unpaid: what the landlord should be chasing today.
     *
     * <p>This is {@link com.datagami.rentaxis.core.service.cheque.ChequeDueRules#due}
     * expressed in SQL, and it has to stay that way — the register screen, the
     * reminder job and the aging report all read this and then ask the rule for the
     * per-row flag, so a row the query returns and the rule calls not-due (or the
     * reverse) is a count that disagrees with the list under it.</p>
     *
     * <p>Hence {@code DEPOSITED} (at the bank, but the money has not landed),
     * {@code ONLINE_PENDING} (a gateway session is open and an authorisation is not
     * money; an abandoned one has no expiry sweep behind it) and {@code BOUNCED}
     * <em>whatever its date</em>: a returned cheque already failed, so the debt is
     * live from that moment and does not wait for a calendar date.</p>
     *
     * <p>{@code ChequeRepositoryIT.theDuePredicateAndTheDueQueriesAgreeOnEveryStatus}
     * walks every status on both sides of its date and asserts set equality against
     * the rule, so a status added to one and not the other fails rather than
     * quietly dropping money off a screen.</p>
     */
    @Query("""
        select c from Cheque c
        where ((c.status in (com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REGISTERED,
                             com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DEPOSITED,
                             com.datagami.rentaxis.domain.entity.enums.ChequeStatus.ONLINE_PENDING)
                and c.chequeDate <= :today)
               or c.status = com.datagami.rentaxis.domain.entity.enums.ChequeStatus.BOUNCED)
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (cast(:propertyId as java.util.UUID) is null or c.property.id = :propertyId)
          and (:unrestricted = true or c.property.id in :propertyIds)
        """)
    Page<Cheque> findDue(@Param("propertyId") UUID propertyId,
                         @Param("today") LocalDate today,
                         @Param("unrestricted") boolean unrestricted,
                         @Param("propertyIds") Collection<UUID> propertyIds,
                         Pageable pageable);

    /**
     * {@link #findDue} for one lease — what the renter still owes on this contract
     * today, which is what a settlement deducts from their deposit.
     *
     * <p>Same predicate as {@link #findDue}, word for word, and it has to stay that
     * way: the register screen and the settlement preview disagreeing about whether
     * a bounced cheque is owed is a number the accountant cannot reconcile against
     * any screen. Not expressed as {@code findDue} with a lease filter because that
     * query is paged and property-scoped for a caller; a settlement is scoped by the
     * lease itself and wants every row, not a page of them.</p>
     *
     * <p>DRAFT rows cannot appear: they are neither REGISTERED, DEPOSITED nor
     * BOUNCED. The lease-status exclusion still earns its place — a draft lease's
     * grid can carry REGISTERED rows after an import — though a lease being settled
     * is past that point by definition.</p>
     */
    @Query("""
        select c from Cheque c
        where c.lease.id = :leaseId
          and ((c.status in (com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REGISTERED,
                             com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DEPOSITED,
                             com.datagami.rentaxis.domain.entity.enums.ChequeStatus.ONLINE_PENDING)
                and c.chequeDate <= :today)
               or c.status = com.datagami.rentaxis.domain.entity.enums.ChequeStatus.BOUNCED)
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
        order by c.seqNo asc
        """)
    List<Cheque> findDueForLease(@Param("leaseId") UUID leaseId, @Param("today") LocalDate today);

    /**
     * The day's deposit run: paper the landlord physically walks to the bank. Only
     * {@code PDC} rows — cash and transfers never were paper — and only ones not
     * already banked.
     */
    @Query("""
        select c from Cheque c
        where c.status = com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REGISTERED
          and c.mode = com.datagami.rentaxis.domain.entity.enums.ChequeMode.PDC
          and c.chequeDate <= :today
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (cast(:propertyId as java.util.UUID) is null or c.property.id = :propertyId)
          and (:unrestricted = true or c.property.id in :propertyIds)
        """)
    Page<Cheque> findToDeposit(@Param("propertyId") UUID propertyId,
                               @Param("today") LocalDate today,
                               @Param("unrestricted") boolean unrestricted,
                               @Param("propertyIds") Collection<UUID> propertyIds,
                               Pageable pageable);

    /**
     * The post-dated book for a month: what matures between {@code from} and
     * {@code to} and has not been settled yet, in maturity order (spec §7.4).
     *
     * <p>Uncleared instruments only — {@code REGISTERED}, {@code DEPOSITED} and
     * {@code ONLINE_PENDING}, which is a session in flight over an instalment
     * nothing has settled. A cleared row is money already in, a bounced one belongs
     * on the due list rather than the forward book, and a cancelled or returned one
     * is paper nobody holds.</p>
     */
    @Query("""
        select c from Cheque c
        where c.status in (com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REGISTERED,
                           com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DEPOSITED,
                           com.datagami.rentaxis.domain.entity.enums.ChequeStatus.ONLINE_PENDING)
          and c.chequeDate >= :from and c.chequeDate <= :to
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (cast(:propertyId as java.util.UUID) is null or c.property.id = :propertyId)
          and (:unrestricted = true or c.property.id in :propertyIds)
        order by c.chequeDate asc, c.seqNo asc
        """)
    List<Cheque> findPostDated(@Param("propertyId") UUID propertyId,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to,
                               @Param("unrestricted") boolean unrestricted,
                               @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * Count and value per status for the register's summary tiles, in one pass over
     * the index rather than one query per tile.
     *
     * <p>Returns {@code [ChequeStatus, Long count, BigDecimal amount]}. Statuses
     * with no rows are simply absent — the caller zero-fills, which is cheaper than
     * making Postgres invent them. There is never a {@code DRAFT} bucket: a grid row
     * is a proposal, and a tile counting it would tell the landlord they hold paper
     * nobody has handed over.</p>
     */
    @Query("""
        select c.status, count(c), coalesce(sum(c.amount), 0) from Cheque c
        where c.status <> com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DRAFT
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (cast(:propertyId as java.util.UUID) is null or c.property.id = :propertyId)
          and (:unrestricted = true or c.property.id in :propertyIds)
        group by c.status
        """)
    List<Object[]> totalsByStatus(@Param("propertyId") UUID propertyId,
                                  @Param("unrestricted") boolean unrestricted,
                                  @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * The named leases' <em>register</em> rows, in schedule order — the input to
     * per-lease stats.
     *
     * <p>A query rather than the derived {@code findByLease_IdIn…}: that one had no
     * status filter of any kind, so a draft grid on an imported lease counted
     * towards the lease's totals as instruments the renter had handed over.</p>
     */
    @Query("""
        select c from Cheque c
        where c.lease.id in :leaseIds
          and c.status <> com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DRAFT
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
        order by c.lease.id asc, c.seqNo asc
        """)
    List<Cheque> findRegisterRowsForLeases(@Param("leaseIds") Collection<UUID> leaseIds);

    /**
     * Value of the rows in these statuses maturing in {@code [from, toExclusive)} —
     * what the dashboard means by "expected this month".
     */
    @Query("""
        select coalesce(sum(c.amount), 0) from Cheque c
        where c.status in :statuses
          and c.status <> com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DRAFT
          and c.chequeDate >= :from and c.chequeDate < :toExclusive
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (:unrestricted = true or c.property.id in :propertyIds)
        """)
    BigDecimal sumByStatusInAndChequeDateBetween(@Param("statuses") Collection<ChequeStatus> statuses,
                                                 @Param("from") LocalDate from,
                                                 @Param("toExclusive") LocalDate toExclusive,
                                                 @Param("unrestricted") boolean unrestricted,
                                                 @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * The register's most recent movements, newest first — the dashboard's activity
     * feed. Paged by the caller so the query stops at ten rather than sorting the
     * whole table into memory, and scoped to the caller's properties so a manager's
     * feed cannot narrate another building's collections.
     */
    @Query("""
        select c from Cheque c
        where c.statusChangedAt is not null
          and c.status <> com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DRAFT
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (:unrestricted = true or c.property.id in :propertyIds)
        order by c.statusChangedAt desc
        """)
    List<Cheque> findRecentlyChanged(@Param("unrestricted") boolean unrestricted,
                                     @Param("propertyIds") Collection<UUID> propertyIds,
                                     Pageable pageable);

    long countByLease_IdAndStatusIn(UUID leaseId, Collection<ChequeStatus> statuses);

    /** How many times this lease has bounced — the input to the penalty threshold. */
    long countByLease_IdAndBouncedAtIsNotNull(UUID leaseId);

    /**
     * The retention purge's worklist: id, tenant and blob path only.
     *
     * <p>A projection rather than the entity because the job deletes a blob and
     * blanks three columns — it has no use for the lease, the renter or the
     * journals a managed {@code Cheque} would drag behind it, and the purge runs
     * across every tenant in one pass.</p>
     *
     * <p>Paged so one night's backlog cannot load an unbounded result set into a
     * scheduled job's heap; the job takes a bounded batch per run and the next run
     * takes the next one, because the rows it purged no longer match.</p>
     */
    @Query("""
        select new com.datagami.rentaxis.domain.repository.ChequeImagePurgeRow(
                   c.id, c.tenantId, c.imageBlobPath)
        from Cheque c
        where c.imageBlobPath is not null and c.chequeDate < :cutoff
        order by c.chequeDate asc
        """)
    List<ChequeImagePurgeRow> findImagePurgeBatch(@Param("cutoff") LocalDate cutoff, Pageable pageable);

    /**
     * Forget one purged image. A modifying query rather than a load-mutate-save so
     * the job never has a managed entity whose tenant filter it would have to
     * arrange; the id came from {@link #findImagePurgeBatch}, which is the scope.
     */
    @Modifying
    @Query("""
        update Cheque c set c.imageUrl = null, c.imageBlobPath = null, c.imageUploadedAt = null
        where c.id = :id
        """)
    void clearImage(@Param("id") UUID id);

    /**
     * Collections actually banked in a window — {@code [from, to)}, upper bound
     * exclusive so consecutive periods neither double-count nor drop a day.
     */
    @Query("""
        select coalesce(sum(c.amount), 0) from Cheque c
        where c.status = com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CLEARED
          and c.clearedAt >= :from and c.clearedAt < :to
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (cast(:propertyId as java.util.UUID) is null or c.property.id = :propertyId)
          and (:unrestricted = true or c.property.id in :propertyIds)
        """)
    BigDecimal sumClearedBetween(@Param("from") LocalDate from,
                                 @Param("to") LocalDate to,
                                 @Param("propertyId") UUID propertyId,
                                 @Param("unrestricted") boolean unrestricted,
                                 @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * What cleared in {@code [from, to)}, split by when it was due: before the
     * window (arrears), inside it, and after it (advance) — the dashboard's
     * collection tile (gap #59).
     *
     * <p>Returns one row {@code [BigDecimal dueBefore, BigDecimal dueWithin,
     * BigDecimal dueAfter]}. The where clause is {@link #sumClearedBetween}'s
     * exactly, so the three columns always add up to what that method reports for
     * the same window and scope. A cleared row with no cheque date counts as due
     * within the window — it cannot be arrears or advance of anything.</p>
     */
    @Query("""
        select coalesce(sum(case when c.chequeDate < :from then c.amount else 0 end), 0),
               coalesce(sum(case when c.chequeDate is null
                                  or (c.chequeDate >= :from and c.chequeDate < :to)
                                 then c.amount else 0 end), 0),
               coalesce(sum(case when c.chequeDate >= :to then c.amount else 0 end), 0)
        from Cheque c
        where c.status = com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CLEARED
          and c.clearedAt >= :from and c.clearedAt < :to
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (:unrestricted = true or c.property.id in :propertyIds)
        """)
    List<Object[]> sumClearedBetweenByDueWindow(@Param("from") LocalDate from,
                                                @Param("to") LocalDate to,
                                                @Param("unrestricted") boolean unrestricted,
                                                @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * The register grouped by the month a cheque matures in, expected against
     * collected — the dashboard's twelve-month chart in one query.
     *
     * <p>Returns {@code [String yyyy-MM, BigDecimal expected, BigDecimal collected]}.
     * "Expected" is every live instrument dated in the month; "collected" is the
     * subset that cleared. Cancelled, returned and superseded rows are excluded
     * from both: a replaced cheque and its replacement are the same money, and
     * counting each would double the month.</p>
     */
    @Query("""
        select function('to_char', c.chequeDate, 'YYYY-MM'),
               coalesce(sum(c.amount), 0),
               coalesce(sum(case when c.status = com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CLEARED
                                 then c.amount else 0 end), 0)
        from Cheque c
        where c.chequeDate >= :from and c.chequeDate < :toExclusive
          and c.status not in (com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DRAFT,
                               com.datagami.rentaxis.domain.entity.enums.ChequeStatus.CANCELLED,
                               com.datagami.rentaxis.domain.entity.enums.ChequeStatus.RETURNED,
                               com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REPLACED)
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (:unrestricted = true or c.property.id in :propertyIds)
        group by function('to_char', c.chequeDate, 'YYYY-MM')
        """)
    List<Object[]> aggregateMonthly(@Param("from") LocalDate from,
                                    @Param("toExclusive") LocalDate toExclusive,
                                    @Param("unrestricted") boolean unrestricted,
                                    @Param("propertyIds") Collection<UUID> propertyIds);

    /**
     * The reminder job's list: live instruments maturing on one date. Bounded by
     * date rather than scanned, because the job runs across every tenant.
     */
    @Query("""
        select c from Cheque c
        where c.status = com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REGISTERED
          and c.chequeDate = :on
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
        """)
    List<Cheque> findRegisteredMaturingOn(@Param("on") LocalDate on);

    /**
     * Every cheque due across every tenant — what the overdue reminder job walks.
     * Unscoped by property on purpose: the job has no caller to be restricted to,
     * and the tenant filter is off because a scheduled run has no tenant context.
     */
    @Query("""
        select c from Cheque c
        where ((c.status in (com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REGISTERED,
                             com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DEPOSITED,
                             com.datagami.rentaxis.domain.entity.enums.ChequeStatus.ONLINE_PENDING)
                and c.chequeDate <= :today)
               or c.status = com.datagami.rentaxis.domain.entity.enums.ChequeStatus.BOUNCED)
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
        """)
    List<Cheque> findAllDue(@Param("today") LocalDate today);
}
