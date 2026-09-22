package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {
    List<JournalLine> findByEntry_IdOrderByLineNoAsc(UUID entryId);
    boolean existsByAccount_Id(UUID accountId);

    /** An entry's amount — its debit side — without loading its lines, for list rows. */
    @Query("select coalesce(sum(l.debit), 0) from JournalLine l where l.entry.id = :entryId")
    BigDecimal totalDebit(@Param("entryId") UUID entryId);

    // ---- ledger reporting ----
    //
    // These are native because they aggregate across the entry/line join and need
    // Postgres' string_agg. Native SQL bypasses the Hibernate tenant filter, so every
    // one of them takes tenantId explicitly rather than trusting the session — leaving
    // it out would read every landlord's ledger. The `cast(:x as uuid) is null` form is
    // what Postgres needs for a nullable uuid parameter: a bare `:x is null` gives it
    // nothing to infer a type from and the statement is rejected outright.

    interface LineRow {
        UUID getEntryId(); String getEntryNumber(); LocalDate getEntryDate(); String getDocType(); String getEntryNarration();
        String getLineNarration(); BigDecimal getDebit(); BigDecimal getCredit(); String getContraAccountName();
        UUID getPropertyId(); UUID getUnitId(); UUID getLeaseId(); UUID getRenterId(); UUID getChequeId(); int getLineNo();
    }

    interface CounterRow { UUID getEntryId(); String getNames(); }

    interface BalanceRow { UUID getAccountId(); BigDecimal getDebit(); BigDecimal getCredit(); }

    @Query(value = """
        select e.id as entryId, e.entry_number as entryNumber, e.entry_date as entryDate, e.doc_type as docType,
               e.narration as entryNarration, l.narration as lineNarration, l.debit as debit, l.credit as credit,
               ca.name as contraAccountName,
               l.property_id as propertyId, l.unit_id as unitId, l.lease_id as leaseId, l.renter_id as renterId, l.cheque_id as chequeId, l.line_no as lineNo
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
             left join accounts ca on ca.id = l.contra_account_id
        where l.tenant_id = :tenantId and l.account_id = :accountId
          and e.entry_date between :from and :to
          and (cast(:propertyId as uuid) is null or l.property_id = :propertyId)
          and (cast(:unitId as uuid) is null or l.unit_id = :unitId)
          and (cast(:leaseId as uuid) is null or l.lease_id = :leaseId)
          and (cast(:renterId as uuid) is null or l.renter_id = :renterId)
        order by e.entry_date, e.created_at, e.entry_number, l.line_no
        limit :limit
        """, nativeQuery = true)
    List<LineRow> ledgerRows(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                             @Param("from") LocalDate from, @Param("to") LocalDate to,
                             @Param("propertyId") UUID propertyId, @Param("unitId") UUID unitId,
                             @Param("leaseId") UUID leaseId, @Param("renterId") UUID renterId,
                             @Param("limit") int limit);

    @Query(value = """
        select coalesce(sum(l.debit),0) - coalesce(sum(l.credit),0)
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
        where l.tenant_id = :tenantId and l.account_id = :accountId and e.entry_date < :before
          and (cast(:propertyId as uuid) is null or l.property_id = :propertyId)
          and (cast(:unitId as uuid) is null or l.unit_id = :unitId)
          and (cast(:leaseId as uuid) is null or l.lease_id = :leaseId)
          and (cast(:renterId as uuid) is null or l.renter_id = :renterId)
        """, nativeQuery = true)
    BigDecimal balanceBefore(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                             @Param("before") LocalDate before, @Param("propertyId") UUID propertyId,
                             @Param("unitId") UUID unitId, @Param("leaseId") UUID leaseId, @Param("renterId") UUID renterId);

    /**
     * Names of the OTHER accounts on each entry — the "Particular" column for lines the
     * posting left unpaired. The entry ids handed in are already tenant-scoped by
     * {@link #ledgerRows}, but a query does not get to rely on its caller for that.
     */
    @Query(value = """
        select l.journal_entry_id as entryId, string_agg(distinct a.name, ' / ' order by a.name) as names
        from journal_lines l join accounts a on a.id = l.account_id
        where l.tenant_id = :tenantId and l.journal_entry_id in (:entryIds) and l.account_id <> :accountId
        group by l.journal_entry_id
        """, nativeQuery = true)
    List<CounterRow> counterAccounts(@Param("tenantId") UUID tenantId, @Param("entryIds") Collection<UUID> entryIds,
                                     @Param("accountId") UUID accountId);

    @Query(value = """
        select distinct l.account_id as accountId
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
        where l.tenant_id = :tenantId and e.entry_date between :from and :to
          and (cast(:propertyId as uuid) is null or l.property_id = :propertyId)
          and (cast(:unitId as uuid) is null or l.unit_id = :unitId)
          and (cast(:leaseId as uuid) is null or l.lease_id = :leaseId)
          and (cast(:renterId as uuid) is null or l.renter_id = :renterId)
        """, nativeQuery = true)
    List<UUID> activeAccountIds(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                                @Param("propertyId") UUID propertyId, @Param("unitId") UUID unitId,
                                @Param("leaseId") UUID leaseId, @Param("renterId") UUID renterId);

    /**
     * What one account still owes on one lease: Σcredit − Σdebit, credit-positive.
     *
     * <p>Signed the other way round from {@link #balanceBefore} on purpose. It
     * exists for the deposit carry-forward (spec §6.6), and a deposit is a
     * liability: the question being asked is "how much of this deposit is still
     * held", which is a credit balance. Reporting it debit-positive would have
     * every caller negate it, and one of them eventually would not.</p>
     *
     * <p>No date bound: this is the balance <em>now</em>. A deposit partly refunded
     * last month carries forward at what is left of it, not at the figure the lease
     * originally charged — which is the whole reason the line's nominal amount is
     * not used.</p>
     */
    @Query(value = """
        select coalesce(sum(l.credit),0) - coalesce(sum(l.debit),0)
        from journal_lines l
        where l.tenant_id = :tenantId and l.account_id = :accountId and l.lease_id = :leaseId
        """, nativeQuery = true)
    BigDecimal creditBalanceForLease(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                                     @Param("leaseId") UUID leaseId);

    @Query(value = """
        select l.account_id as accountId, coalesce(sum(l.debit),0) as debit, coalesce(sum(l.credit),0) as credit
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
        where l.tenant_id = :tenantId and e.entry_date <= :asOf
          and (cast(:propertyId as uuid) is null or l.property_id = :propertyId)
        group by l.account_id
        having coalesce(sum(l.debit),0) <> 0 or coalesce(sum(l.credit),0) <> 0
        """, nativeQuery = true)
    List<BalanceRow> balancesAsOf(@Param("tenantId") UUID tenantId, @Param("asOf") LocalDate asOf, @Param("propertyId") UUID propertyId);
}
