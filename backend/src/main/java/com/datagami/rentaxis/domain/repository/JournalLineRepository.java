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
             join accounts la on la.id = l.account_id
             left join accounts ca on ca.id = l.contra_account_id
        where l.tenant_id = :tenantId and l.account_id = :accountId
          and e.entry_date between :from and :to
          and (cast(:propertyId as uuid) is null
               or (case when :effective then coalesce(l.property_id, la.property_id) else l.property_id end) = :propertyId)
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
                             @Param("effective") boolean effective, @Param("limit") int limit);

    @Query(value = """
        select coalesce(sum(l.debit),0) - coalesce(sum(l.credit),0)
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
             join accounts la on la.id = l.account_id
        where l.tenant_id = :tenantId and l.account_id = :accountId and e.entry_date < :before
          and (cast(:propertyId as uuid) is null
               or (case when :effective then coalesce(l.property_id, la.property_id) else l.property_id end) = :propertyId)
          and (cast(:unitId as uuid) is null or l.unit_id = :unitId)
          and (cast(:leaseId as uuid) is null or l.lease_id = :leaseId)
          and (cast(:renterId as uuid) is null or l.renter_id = :renterId)
        """, nativeQuery = true)
    BigDecimal balanceBefore(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId,
                             @Param("before") LocalDate before, @Param("propertyId") UUID propertyId,
                             @Param("unitId") UUID unitId, @Param("leaseId") UUID leaseId, @Param("renterId") UUID renterId,
                             @Param("effective") boolean effective);

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
             join accounts la on la.id = l.account_id
        where l.tenant_id = :tenantId and e.entry_date between :from and :to
          and (cast(:propertyId as uuid) is null
               or (case when :effective then coalesce(l.property_id, la.property_id) else l.property_id end) = :propertyId)
          and (cast(:unitId as uuid) is null or l.unit_id = :unitId)
          and (cast(:leaseId as uuid) is null or l.lease_id = :leaseId)
          and (cast(:renterId as uuid) is null or l.renter_id = :renterId)
        """, nativeQuery = true)
    List<UUID> activeAccountIds(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                                @Param("propertyId") UUID propertyId, @Param("unitId") UUID unitId,
                                @Param("leaseId") UUID leaseId, @Param("renterId") UUID renterId,
                                @Param("effective") boolean effective);

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

    /** Σ(debit − credit) of one account over all time (PR #358 R1 P2-1). Native: binds the tenant. */
    @Query(value = """
        select coalesce(sum(l.debit),0) - coalesce(sum(l.credit),0) from journal_lines l
        where l.tenant_id = :tenantId and l.account_id = :accountId
        """, nativeQuery = true)
    BigDecimal accountBalance(@Param("tenantId") UUID tenantId, @Param("accountId") UUID accountId);

    /** {@link #balancesAsOf} without the year-end closing entries dated on {@code asOf} (spec 2026-09-24 §3). */
    @Query(value = """
        select l.account_id as accountId, coalesce(sum(l.debit),0) as debit, coalesce(sum(l.credit),0) as credit
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
        where l.tenant_id = :tenantId and e.entry_date <= :asOf
          and not (e.doc_type = 'YEC' and e.entry_date = :asOf)
          and (cast(:propertyId as uuid) is null or l.property_id = :propertyId)
        group by l.account_id
        having coalesce(sum(l.debit),0) <> 0 or coalesce(sum(l.credit),0) <> 0
        """, nativeQuery = true)
    List<BalanceRow> balancesAsOfBeforeClosing(@Param("tenantId") UUID tenantId, @Param("asOf") LocalDate asOf,
                                               @Param("propertyId") UUID propertyId);

    // ---- property P&L and statement pack (finance-ops spec §1) ----
    //
    // Property of a line in the company P&L and balance sheet = the line's own
    // property dimension, the per-property trial balance's rule (F15-15): the
    // account's property was a fallback for one side of an entry only (a Marina
    // Tower leaf without a dimension, against a tenant-level vendor), which left a
    // property column of the balance sheet off by the entry. No dimension is
    // Unassigned, for both sides alike. (The statement pack below still reads the
    // effective property.) Reversed entries
    // and their mirrors both count, as in the trial balance. YEC (the year-end close
    // entry, not built yet) is excluded so a closed year still shows its P&L. Every
    // query takes tenantId explicitly: native SQL bypasses the tenant filter.

    interface PnlCellRow {
        UUID getPropertyId(); UUID getAccountId(); BigDecimal getDebit(); BigDecimal getCredit(); long getMismatchLines();
    }

    @Query(value = """
        select l.property_id as propertyId, l.account_id as accountId,
               coalesce(sum(l.debit),0) as debit, coalesce(sum(l.credit),0) as credit,
               count(*) filter (where l.property_id is not null and a.property_id is not null
                                      and l.property_id <> a.property_id) as mismatchLines
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
             join accounts a on a.id = l.account_id
        where l.tenant_id = :tenantId and e.tenant_id = :tenantId
          and e.entry_date between :from and :to
          and a.account_type in ('INCOME', 'EXPENSE')
          and e.doc_type <> 'YEC'
        group by l.property_id, l.account_id
        """, nativeQuery = true)
    List<PnlCellRow> pnlCells(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /**
     * Net income-statement movement, tenant-wide, credit-positive: the P&L check
     * row. Written without the property grouping on purpose, so a mistake in
     * {@link #pnlCells} shows up as a difference rather than agreeing with itself.
     */
    @Query(value = """
        select coalesce(sum(l.credit),0) - coalesce(sum(l.debit),0)
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
             join accounts a on a.id = l.account_id
        where l.tenant_id = :tenantId and e.entry_date between :from and :to
          and a.account_type in ('INCOME', 'EXPENSE') and e.doc_type <> 'YEC'
        """, nativeQuery = true)
    BigDecimal pnlNetMovement(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    interface PnlLineRow {
        UUID getEntryId(); String getEntryNumber(); LocalDate getEntryDate(); String getDocType(); String getNarration();
        UUID getAccountId(); String getAccountCode(); String getAccountName(); String getAccountNameAr();
        BigDecimal getDebit(); BigDecimal getCredit(); UUID getLinePropertyId(); UUID getAccountPropertyId();
    }

    /**
     * The journal lines behind one P&L cell. {@code mode} is PROPERTY (effective
     * property = propertyId), UNASSIGNED (effective property null) or ALL.
     */
    @Query(value = """
        select e.id as entryId, e.entry_number as entryNumber, e.entry_date as entryDate, e.doc_type as docType,
               coalesce(l.narration, e.narration) as narration,
               a.id as accountId, a.code as accountCode, a.name as accountName, a.name_ar as accountNameAr,
               l.debit as debit, l.credit as credit, l.property_id as linePropertyId, a.property_id as accountPropertyId
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
             join accounts a on a.id = l.account_id
        where l.tenant_id = :tenantId and e.entry_date between :from and :to
          and a.account_type in ('INCOME', 'EXPENSE') and e.doc_type <> 'YEC'
          and (:allAccounts or l.account_id in (:accountIds))
          and (:mode = 'ALL'
               or (:mode = 'UNASSIGNED' and l.property_id is null)
               or (:mode = 'PROPERTY' and l.property_id = cast(:propertyId as uuid))
               or (:mode = 'SCOPE' and (l.property_id is null
                                        or l.property_id in (:scopeIds))))
        order by e.entry_date, e.created_at, e.entry_number, l.line_no
        limit :limit
        """, nativeQuery = true)
    List<PnlLineRow> pnlLines(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                              @Param("allAccounts") boolean allAccounts, @Param("accountIds") Collection<UUID> accountIds,
                              @Param("mode") String mode, @Param("propertyId") UUID propertyId,
                              @Param("scopeIds") Collection<UUID> scopeIds, @Param("limit") int limit);

    /**
     * The check row for a report narrowed to some properties: the same net
     * movement, over those properties plus Unassigned — exactly what its Total
     * column claims to add up. Independent of the fold that builds the columns.
     */
    @Query(value = """
        select coalesce(sum(l.credit),0) - coalesce(sum(l.debit),0)
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
             join accounts a on a.id = l.account_id
        where l.tenant_id = :tenantId and e.entry_date between :from and :to
          and a.account_type in ('INCOME', 'EXPENSE') and e.doc_type <> 'YEC'
          and (l.property_id is null
               or l.property_id in (:propertyIds))
        """, nativeQuery = true)
    BigDecimal pnlNetMovementFor(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to,
                                 @Param("propertyIds") Collection<UUID> propertyIds);

    /** Whether this tenant has any journal line whose effective property is this id (a deleted property's column). */
    @Query(value = """
        select exists (select 1 from journal_lines l join accounts a on a.id = l.account_id
                       where l.tenant_id = :tenantId and l.property_id = :propertyId)
        """, nativeQuery = true)
    boolean hasLinesForProperty(@Param("tenantId") UUID tenantId, @Param("propertyId") UUID propertyId);

    interface MovementRow { UUID getAccountId(); String getDocType(); String getChequeMode(); BigDecimal getDebit(); BigDecimal getCredit(); }

    /**
     * Movement on the given accounts for one effective property, by document type
     * and by the mode of the cheque row a line names (null when it names none).
     */
    @Query(value = """
        select l.account_id as accountId, e.doc_type as docType, c.mode as chequeMode,
               coalesce(sum(l.debit),0) as debit, coalesce(sum(l.credit),0) as credit
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
             join accounts a on a.id = l.account_id
             left join cheques c on c.id = l.cheque_id and c.tenant_id = l.tenant_id
        where l.tenant_id = :tenantId and l.account_id in (:accountIds)
          and coalesce(l.property_id, a.property_id) = :propertyId
          and e.entry_date between :from and :to
        group by l.account_id, e.doc_type, c.mode
        """, nativeQuery = true)
    List<MovementRow> movementForProperty(@Param("tenantId") UUID tenantId, @Param("accountIds") Collection<UUID> accountIds,
                                          @Param("propertyId") UUID propertyId,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    interface ExpenseEntryRow {
        UUID getEntryId(); String getEntryNumber(); LocalDate getEntryDate(); String getDocType();
        String getSourceType(); UUID getSourceId(); String getNarration(); BigDecimal getExpense(); BigDecimal getInputVat();
    }

    /**
     * Each entry's expense (debit-positive) for one effective property, with the
     * input VAT the same entry carries for it. {@code inputVatAccountIds} must not
     * be empty; pass a random id when the tenant has no input-VAT account.
     */
    @Query(value = """
        select e.id as entryId, e.entry_number as entryNumber, e.entry_date as entryDate, e.doc_type as docType,
               e.source_type as sourceType, e.source_id as sourceId, e.narration as narration,
               coalesce(sum(case when a.account_type = 'EXPENSE' then l.debit - l.credit end), 0) as expense,
               coalesce(sum(case when a.id in (:inputVatAccountIds) then l.debit - l.credit end), 0) as inputVat
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
             join accounts a on a.id = l.account_id
        where l.tenant_id = :tenantId and e.entry_date between :from and :to and e.doc_type <> 'YEC'
          and coalesce(l.property_id, a.property_id) = :propertyId
          and (a.account_type = 'EXPENSE' or a.id in (:inputVatAccountIds))
        group by e.id, e.entry_number, e.entry_date, e.doc_type, e.source_type, e.source_id, e.narration
        having coalesce(sum(case when a.account_type = 'EXPENSE' then l.debit - l.credit end), 0) <> 0
        order by e.entry_date, e.entry_number
        """, nativeQuery = true)
    List<ExpenseEntryRow> expenseEntriesForProperty(@Param("tenantId") UUID tenantId, @Param("propertyId") UUID propertyId,
                                                    @Param("from") LocalDate from, @Param("to") LocalDate to,
                                                    @Param("inputVatAccountIds") Collection<UUID> inputVatAccountIds);
    /**
     * F14-10: balances by effective property and account over entries dated
     * {@code from}..{@code to}, every account type, year-end closes included (the
     * balance sheet reads Retained Earnings after them and the unclosed result
     * before them).
     */
    @Query(value = """
        select l.property_id as propertyId, l.account_id as accountId,
               coalesce(sum(l.debit),0) as debit, coalesce(sum(l.credit),0) as credit, cast(0 as bigint) as mismatchLines
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
             join accounts a on a.id = l.account_id
        where l.tenant_id = :tenantId and e.tenant_id = :tenantId and a.tenant_id = :tenantId
          and e.entry_date between :from and :to
        group by l.property_id, l.account_id
        """, nativeQuery = true)
    List<PnlCellRow> balanceCells(@Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** F14-10: Σ debit − Σ credit over every line through a date; zero on a balanced ledger (the balance sheet's own check). */
    @Query(value = """
        select coalesce(sum(l.debit),0) - coalesce(sum(l.credit),0)
        from journal_lines l join journal_entries e on e.id = l.journal_entry_id
        where l.tenant_id = :tenantId and e.tenant_id = :tenantId and e.entry_date <= :asAt
        """, nativeQuery = true)
    BigDecimal ledgerImbalance(@Param("tenantId") UUID tenantId, @Param("asAt") LocalDate asAt);
}
