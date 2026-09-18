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
     */
    @Query("""
        select c from Cheque c
        left join c.unit u
        where (cast(:propertyId as java.util.UUID) is null or c.property.id = :propertyId)
          and (cast(:status as string) is null or c.status = :status)
          and (cast(:mode as string) is null or c.mode = :mode)
          and (cast(:from as LocalDate) is null or c.chequeDate >= :from)
          and (cast(:to as LocalDate) is null or c.chequeDate <= :to)
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
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
                        Pageable pageable);

    /**
     * Matured and still unpaid: what the landlord should be chasing today. Includes
     * cheques already at the bank ({@code DEPOSITED}) because the money has not
     * landed yet.
     */
    @Query("""
        select c from Cheque c
        where c.status in (com.datagami.rentaxis.domain.entity.enums.ChequeStatus.REGISTERED,
                           com.datagami.rentaxis.domain.entity.enums.ChequeStatus.DEPOSITED)
          and c.chequeDate <= :today
          and c.lease.status not in (com.datagami.rentaxis.domain.entity.enums.LeaseStatus.DRAFT,
                                     com.datagami.rentaxis.domain.entity.enums.LeaseStatus.PENDING_SIGNATURE)
          and (cast(:propertyId as java.util.UUID) is null or c.property.id = :propertyId)
        """)
    Page<Cheque> findDue(@Param("propertyId") UUID propertyId,
                         @Param("today") LocalDate today,
                         Pageable pageable);

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
          and (cast(:propertyId as java.util.UUID) is null or c.property.id = :propertyId)
        """)
    Page<Cheque> findToDeposit(@Param("propertyId") UUID propertyId,
                               @Param("today") LocalDate today,
                               Pageable pageable);

    long countByLease_IdAndStatusIn(UUID leaseId, Collection<ChequeStatus> statuses);

    /** How many times this lease has bounced — the input to the penalty threshold. */
    long countByLease_IdAndBouncedAtIsNotNull(UUID leaseId);

    /** Retention purge: scanned images whose cheque predates the cutoff. */
    @Query("select c from Cheque c where c.imageBlobPath is not null and c.chequeDate < :cutoff")
    List<Cheque> findImagesOlderThan(@Param("cutoff") LocalDate cutoff);

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
        """)
    BigDecimal sumClearedBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
