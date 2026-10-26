package com.datagami.rentaxis.core.service.report.tenantledger;

import com.datagami.rentaxis.api.dto.report.ReceivableAccountDTO;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.LeaseService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Tenant Ledger's read model (reports spec §2): native SQL over the journal, every table bound to the
 * caller's tenant, because the Hibernate tenant filter does not reach native queries and every id here arrives
 * from a request.
 *
 * <p>Two queries share one filter. {@link #groups} gives, per (account, renter), the opening before the window,
 * the window's debits and credits and its row count: the authoritative totals the report's writer cross-checks.
 * {@link #lines} gives the window's rows in the one ledger ordering ({@code entry_date, created_at,
 * entry_number, line_no}), each with its row number and running movement inside its group, so a page that
 * starts mid-group can carry the balance forward; a screen page's rows are cut in SQL ({@link PageBounds}), after
 * the window. A renter-less line is its own "No tenant" group, so an account's groups add up to the account's
 * General Ledger.</p>
 *
 * <p>Binding: an absent renter or lease is bound as {@link #NONE} beside a {@code :has…} flag (Postgres cannot
 * type a bare null parameter); an IN list is never empty (empty account ids short-circuit to an empty answer,
 * property ids come from {@link PropertyScope.Selection#sqlIds()}). Callers run these inside their read-only
 * transaction.</p>
 */
@Component
public class TenantLedgerQueries {

    /** Bound for an absent id beside its companion flag, and the one member of an IN list that has none. */
    static final UUID NONE = new UUID(0L, 0L);

    /** Entry ids per counter-name statement, keeping the IN list well inside the driver's parameter limit. */
    static final int CHUNK = 1_000;

    private final NamedParameterJdbcTemplate jdbc;

    public TenantLedgerQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What one run of the report reads.
     *
     * @param accountIds      the ledger accounts; empty selects nothing
     * @param renterId        one renter, or {@code null} for all (renter-less lines included)
     * @param leaseId         one contract, or {@code null} for all
     * @param properties      the caller's resolved property selection
     * @param onlyWithBalance keep only the groups whose closing at {@code to} is not zero
     * @param hideReversed    leave out both legs of every reversal whose two legs are on or before {@code to},
     *                        from the openings and the rows
     * @param arabic          an Arabic report: an account's tenants are ordered by the name the report prints, the
     *                        Arabic one when the renter has it
     */
    public record Scope(UUID tenantId, LocalDate from, LocalDate to, List<UUID> accountIds, UUID renterId, UUID leaseId,
                        PropertyScope.Selection properties, boolean onlyWithBalance, boolean hideReversed,
                        boolean arabic) {
        public Scope {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(properties, "properties");
            accountIds = accountIds == null ? List.of()
                    : accountIds.stream().filter(Objects::nonNull).distinct().toList();
        }

        /** An English report's scope. */
        public Scope(UUID tenantId, LocalDate from, LocalDate to, List<UUID> accountIds, UUID renterId, UUID leaseId,
                     PropertyScope.Selection properties, boolean onlyWithBalance, boolean hideReversed) {
            this(tenantId, from, to, accountIds, renterId, leaseId, properties, onlyWithBalance, hideReversed, false);
        }

        /** Nothing can match: no account, or a manager assigned to no property. */
        boolean selectsNothing() {
            return accountIds.isEmpty() || properties.empty();
        }
    }

    /** One group of the report: a ledger account and a renter ({@code null} = "No tenant"). */
    public record GroupKey(UUID accountId, UUID renterId) {}

    /**
     * Where a screen page starts and stops inside its groups: from row {@code firstRow} of {@code first} and up to
     * row {@code lastRow} of {@code last} (1-based {@code rn}, inclusive; one group when the page lies inside it).
     * The groups between the two are read whole.
     */
    public record PageBounds(GroupKey first, int firstRow, GroupKey last, int lastRow) {
        public PageBounds {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(last, "last");
        }
    }

    /** A group with its authoritative figures: {@code opening} before {@code from}, the window's sums and row count. */
    public record GroupRow(UUID accountId, UUID renterId, String accountCode, String accountName, String accountNameAr,
                           String renterName, String renterNameAr, BigDecimal opening, BigDecimal debit, BigDecimal credit,
                           int rowCount) {
        public GroupKey key() {
            return new GroupKey(accountId, renterId);
        }
    }

    /**
     * A journal line in the window. {@code rn} is its 1-based position in its group and {@code movement} the
     * group's running Σ(debit − credit) inside the window up to and including it (the opening not included).
     * The {@code cheque…}, {@code cbrJournalId}, {@code bouncedAt} and {@code pdrJournalId} fields are the
     * line's cheque, or {@code null} when it has none. {@code contraName} is the paired counter-account, or
     * {@code null} for an unpaired line (see {@link #counterNames}).
     */
    public record LineRow(UUID accountId, UUID renterId, int rn, BigDecimal movement, UUID entryId, String entryNumber,
                          String docType, LocalDate entryDate, BigDecimal debit, BigDecimal credit, UUID leaseId,
                          UUID chequeId, String contraName, String contraNameAr, String chequeNumber, String chequeMode,
                          String payeeBank, LocalDate chequeDate, UUID cbrJournalId, LocalDate bouncedAt,
                          UUID pdrJournalId) {
        public GroupKey key() {
            return new GroupKey(accountId, renterId);
        }
    }

    /** The other accounts of one entry, as seen from one of its accounts, joined {@code " / "} in English and Arabic. */
    public record CounterRow(UUID entryId, UUID accountId, String names, String namesAr) {}

    /** An id with its display name ({@code nameAr} {@code null} when there is none). */
    public record NamedRef(UUID id, String name, String nameAr) {}

    /**
     * The shared WHERE. "Hide reversed pairs" hides an entry only when its counterpart (the mirror of an
     * original, or the original of a mirror; a reversal cannot itself be reversed) is also on or before
     * {@code :to}: a pair inside the report nets to zero on every line, so hiding it leaves the closing at
     * {@code to} unchanged, whereas an original whose mirror falls after {@code to} is still open on that day
     * and stays, as the General Ledger at {@code to} has it.
     */
    private static final String FILTER = """
            l.tenant_id = :t
              and l.account_id in (:accountIds)
              and e.entry_date <= :to
              and (:hasRenter = false or l.renter_id = :renterId)
              and (:hasLease = false or l.lease_id = :leaseId)
              and (:unrestricted or coalesce(l.property_id, a.property_id) in (:propertyIds))
              and (:hideReversed = false
                   or (e.reversed_by_id is null and e.reversal_of_id is null)
                   or not exists (select 1 from journal_entries m
                                  where m.tenant_id = :t
                                    and m.id = coalesce(e.reversed_by_id, e.reversal_of_id)
                                    and m.entry_date <= :to))
            """;

    /**
     * The page's groups, applied inside {@code base} of {@link #LINES}: it keeps or drops whole
     * (account, renter) partitions, so a group's {@code rn}, {@code movement} and closing are the same
     * whichever groups are read, and a page no longer sorts the full history of every other group.
     */
    private static final String PAGE = """
              and (:allGroups or (l.account_id in (:pageAccounts)
                   and (l.renter_id in (:pageRenters) or (:pageNoTenant and l.renter_id is null))))
            """;

    /**
     * The page's rows, applied in the outer select of {@link #LINES}, after the window: {@code rn} and
     * {@code movement} still count over the whole group, but only the rows from {@code :firstRow} of the page's
     * first group and up to {@code :lastRow} of its last group leave the database. A group is matched on
     * {@code coalesce(renter_id, :none)}, so "No tenant" is the bound {@link #NONE}.
     */
    private static final String PAGE_ROWS = """
              and (:allRows
                   or ((s.account_id <> :firstAccount or coalesce(s.renter_id, :none) <> :firstRenter or s.rn >= :firstRow)
                       and (s.account_id <> :lastAccount or coalesce(s.renter_id, :none) <> :lastRenter or s.rn <= :lastRow)))
            """;

    private static final String GROUPS = """
            with base as (
                select l.account_id, l.renter_id, e.entry_date, l.debit, l.credit
                from journal_lines l
                join journal_entries e on e.id = l.journal_entry_id and e.tenant_id = :t
                join accounts a on a.id = l.account_id and a.tenant_id = :t
                where %s
            ), g as (
                select account_id, renter_id,
                       coalesce(sum(debit - credit) filter (where entry_date < :from), 0) as opening,
                       coalesce(sum(debit)  filter (where entry_date >= :from), 0)        as debit,
                       coalesce(sum(credit) filter (where entry_date >= :from), 0)        as credit,
                       count(*) filter (where entry_date >= :from)                         as row_count
                from base group by account_id, renter_id
            )
            select g.account_id, g.renter_id, g.opening, g.debit, g.credit, g.row_count,
                   a.code as account_code, a.name as account_name, a.name_ar as account_name_ar,
                   r.name_en as renter_name, r.name_ar as renter_name_ar
            from g
            join accounts a on a.id = g.account_id and a.tenant_id = :t
            left join renters r on r.id = g.renter_id and r.tenant_id = :t
            where (g.row_count > 0 or g.opening <> 0)
              and (:onlyWithBalance = false or g.opening + g.debit - g.credit <> 0)
            order by a.code, a.id, (g.renter_id is null),
                     lower(case when :arabic and nullif(btrim(r.name_ar), '') is not null then r.name_ar
                                else r.name_en end),
                     g.renter_id
            """.formatted(FILTER);

    private static final String LINES = """
            with base as (
                select l.account_id, l.renter_id, l.lease_id, l.cheque_id, l.contra_account_id, l.line_no, l.debit, l.credit,
                       e.id as entry_id, e.entry_number, e.doc_type, e.entry_date, e.created_at
                from journal_lines l
                join journal_entries e on e.id = l.journal_entry_id and e.tenant_id = :t
                join accounts a on a.id = l.account_id and a.tenant_id = :t
                where %s%s
            ), closing as (
                select account_id, renter_id, sum(debit - credit) as bal from base group by account_id, renter_id
            ), win as (
                select b.*, row_number() over w as rn, sum(b.debit - b.credit) over w as movement
                from base b
                where b.entry_date >= :from
                window w as (partition by b.account_id, b.renter_id
                             order by b.entry_date, b.created_at, b.entry_number, b.line_no
                             rows between unbounded preceding and current row)
            )
            select s.account_id, s.renter_id, s.rn, s.movement, s.entry_id, s.entry_number, s.doc_type, s.entry_date,
                   s.debit, s.credit, s.lease_id, s.cheque_id,
                   ca.name as contra_name, ca.name_ar as contra_name_ar,
                   c.cheque_number, c.mode as cheque_mode, c.payee_bank, c.cheque_date, c.cbr_journal_id, c.bounced_at,
                   c.pdr_journal_id
            from win s
            join closing k on k.account_id = s.account_id and k.renter_id is not distinct from s.renter_id
            left join accounts ca on ca.id = s.contra_account_id and ca.tenant_id = :t
            left join cheques  c  on c.id  = s.cheque_id        and c.tenant_id  = :t
            where (:onlyWithBalance = false or k.bal <> 0)
            %s
            order by s.account_id, s.renter_id nulls last, s.rn
            """.formatted(FILTER, PAGE, PAGE_ROWS);

    private static final String COUNTER_NAMES = """
            select x.entry_id, x.account_id,
                   string_agg(distinct a2.name, ' / ' order by a2.name) as names,
                   string_agg(distinct coalesce(nullif(a2.name_ar, ''), a2.name), ' / '
                              order by coalesce(nullif(a2.name_ar, ''), a2.name)) as names_ar
            from (select distinct l.journal_entry_id as entry_id, l.account_id
                  from journal_lines l
                  where l.tenant_id = :t and l.journal_entry_id in (:entryIds) and l.account_id in (:accountIds)) x
            join journal_lines l2 on l2.journal_entry_id = x.entry_id and l2.tenant_id = :t and l2.account_id <> x.account_id
            join accounts a2 on a2.id = l2.account_id and a2.tenant_id = :t
            group by x.entry_id, x.account_id
            """;

    /**
     * Every (account, renter) with a non-zero opening or a row in the window, ordered: account code, account id,
     * renters by name (in an {@link Scope#arabic() Arabic} report the Arabic name when the renter has one, else the
     * English) with "No tenant" last, renter id.
     */
    public List<GroupRow> groups(Scope s) {
        if (s.selectsNothing()) {
            return List.of();
        }
        return jdbc.query(GROUPS, params(s), (rs, i) -> new GroupRow(
                uuid(rs, "account_id"), uuid(rs, "renter_id"),
                rs.getString("account_code"), rs.getString("account_name"), rs.getString("account_name_ar"),
                rs.getString("renter_name"), rs.getString("renter_name_ar"),
                rs.getBigDecimal("opening"), rs.getBigDecimal("debit"), rs.getBigDecimal("credit"),
                rs.getInt("row_count")));
    }

    /**
     * The window's lines, by group then {@code rn}. {@code only == null} reads every group; otherwise exactly the
     * groups named (an empty collection reads nothing). {@code rn} and {@code movement} always count from the
     * start of the group's window, whichever groups are read.
     */
    public List<LineRow> lines(Scope s, Collection<GroupKey> only) {
        return lines(s, only, null);
    }

    /**
     * {@link #lines(Scope, Collection)} cut to one screen page in SQL: with {@code bounds}, only the rows of the
     * page leave the database ({@code null} reads every row of the groups). {@code rn} and {@code movement} still
     * count from the start of the group's window, so a page that starts mid-group carries its balance.
     */
    public List<LineRow> lines(Scope s, Collection<GroupKey> only, PageBounds bounds) {
        if (s.selectsNothing() || (only != null && only.isEmpty())) {
            return List.of();
        }
        MapSqlParameterSource p = params(s);
        p.addValue("none", NONE).addValue("allRows", bounds == null);
        if (bounds == null) {
            p.addValue("firstAccount", NONE).addValue("firstRenter", NONE).addValue("firstRow", 1)
                    .addValue("lastAccount", NONE).addValue("lastRenter", NONE).addValue("lastRow", Integer.MAX_VALUE);
        } else {
            p.addValue("firstAccount", orNone(bounds.first().accountId()))
                    .addValue("firstRenter", orNone(bounds.first().renterId()))
                    .addValue("firstRow", bounds.firstRow())
                    .addValue("lastAccount", orNone(bounds.last().accountId()))
                    .addValue("lastRenter", orNone(bounds.last().renterId()))
                    .addValue("lastRow", bounds.lastRow());
        }
        Set<GroupKey> wanted = only == null ? null : new HashSet<>(only);
        if (wanted == null) {
            p.addValue("allGroups", true)
                    .addValue("pageAccounts", List.of(NONE))
                    .addValue("pageRenters", List.of(NONE))
                    .addValue("pageNoTenant", false);
        } else {
            List<UUID> accounts = wanted.stream().map(GroupKey::accountId).filter(Objects::nonNull).distinct().toList();
            List<UUID> renters = wanted.stream().map(GroupKey::renterId).filter(Objects::nonNull).distinct().toList();
            p.addValue("allGroups", false)
                    .addValue("pageAccounts", accounts.isEmpty() ? List.of(NONE) : accounts)
                    .addValue("pageRenters", renters.isEmpty() ? List.of(NONE) : renters)
                    .addValue("pageNoTenant", wanted.stream().anyMatch(k -> k.renterId() == null));
        }
        List<LineRow> rows = jdbc.query(LINES, p, (rs, i) -> new LineRow(
                uuid(rs, "account_id"), uuid(rs, "renter_id"), rs.getInt("rn"), rs.getBigDecimal("movement"),
                uuid(rs, "entry_id"), rs.getString("entry_number"), rs.getString("doc_type"), date(rs, "entry_date"),
                rs.getBigDecimal("debit"), rs.getBigDecimal("credit"), uuid(rs, "lease_id"), uuid(rs, "cheque_id"),
                rs.getString("contra_name"), rs.getString("contra_name_ar"),
                rs.getString("cheque_number"), rs.getString("cheque_mode"), rs.getString("payee_bank"),
                date(rs, "cheque_date"), uuid(rs, "cbr_journal_id"), date(rs, "bounced_at"), uuid(rs, "pdr_journal_id")));
        // The two IN lists can cross-match (account X with a renter named only under account Y): keep exactly the keys.
        return wanted == null ? rows : rows.stream().filter(r -> wanted.contains(r.key())).toList();
    }

    /**
     * For each (entry, account) among the given, the names of the entry's other accounts — the "Particular" of a
     * line the posting left unpaired. Entry ids are read {@value #CHUNK} at a time.
     */
    public List<CounterRow> counterNames(UUID tenantId, Collection<UUID> entryIds, Collection<UUID> accountIds) {
        List<UUID> entries = distinct(entryIds);
        List<UUID> accounts = distinct(accountIds);
        if (entries.isEmpty() || accounts.isEmpty()) {
            return List.of();
        }
        List<CounterRow> out = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += CHUNK) {
            MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId)
                    .addValue("entryIds", entries.subList(i, Math.min(i + CHUNK, entries.size())))
                    .addValue("accountIds", accounts);
            out.addAll(jdbc.query(COUNTER_NAMES, p, (rs, n) -> new CounterRow(
                    uuid(rs, "entry_id"), uuid(rs, "account_id"), rs.getString("names"), rs.getString("names_ar"))));
        }
        return out;
    }

    /** Every non-group RECEIVABLE-subtype account: what the report runs over when no account is named. */
    public List<UUID> receivableLeafIds(UUID tenantId) {
        return jdbc.queryForList("""
                select id from accounts
                where tenant_id = :t and account_sub_type = 'RECEIVABLE' and is_group = false
                order by code, id""", new MapSqlParameterSource("t", tenantId), UUID.class);
    }

    /** The receivable leaves for the account picker, by code; {@code propertyId == null} lists every property's. */
    public List<ReceivableAccountDTO> receivableLeaves(UUID tenantId, UUID propertyId) {
        MapSqlParameterSource p = new MapSqlParameterSource("t", tenantId)
                .addValue("hasProperty", propertyId != null)
                .addValue("propertyId", propertyId == null ? NONE : propertyId);
        return jdbc.query("""
                select a.id, a.code, a.name, a.name_ar, a.property_id, p.name_en as property_name
                from accounts a
                left join properties p on p.id = a.property_id and p.tenant_id = :t
                where a.tenant_id = :t and a.account_sub_type = 'RECEIVABLE' and a.is_group = false
                  and (:hasProperty = false or a.property_id = :propertyId)
                order by a.code, a.id""", p, (rs, i) -> new ReceivableAccountDTO(
                uuid(rs, "id"), rs.getString("code"), rs.getString("name"), rs.getString("name_ar"),
                uuid(rs, "property_id"), rs.getString("property_name")));
    }

    /** The organisation's name for the title band (the org's id is the tenant id). */
    public Optional<String> orgName(UUID tenantId) {
        return jdbc.queryForList("select name from landlord_org where id = :t",
                new MapSqlParameterSource("t", tenantId), String.class).stream().findFirst();
    }

    public Optional<NamedRef> property(UUID tenantId, UUID id) {
        return named("select id, name_en as name, name_ar from properties where id = :id and tenant_id = :t", tenantId, id);
    }

    public Optional<NamedRef> renter(UUID tenantId, UUID id) {
        return named("select id, name_en as name, name_ar from renters where id = :id and tenant_id = :t", tenantId, id);
    }

    /**
     * A contract, named as the lease screens show it ({@code <property code>/<contract number>}); else by the
     * reference it carried in the system it was imported from; else, for a contract that has neither (its
     * number is assigned only when a contract document is generated), by its unit and start date
     * ({@code "<unit number> · <yyyy-MM-dd>"}). The name is never {@code null}; {@code nameAr} always is.
     */
    public Optional<NamedRef> lease(UUID tenantId, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.query("""
                select l.id, l.contract_number, l.external_contract_ref, l.start_date, u.unit_number,
                       p.code as property_code
                from leases l
                left join units u on u.id = l.unit_id and u.tenant_id = :t
                left join properties p on p.id = u.property_id and p.tenant_id = :t
                where l.id = :id and l.tenant_id = :t""",
                new MapSqlParameterSource("t", tenantId).addValue("id", id), (rs, i) -> {
                    long n = rs.getLong("contract_number");
                    String name = rs.wasNull() ? null : LeaseService.displayContractNumber(rs.getString("property_code"), n);
                    String ref = rs.getString("external_contract_ref");
                    if (name == null && ref != null && !ref.isBlank()) {
                        name = ref.strip();
                    }
                    if (name == null) {
                        name = unitAndStart(rs.getString("unit_number"), date(rs, "start_date"));
                    }
                    return new NamedRef(uuid(rs, "id"), name, null);
                }).stream().findFirst();
    }

    /** {@code "<unit number> · <start date>"}, either part left out when absent; the start date is never null. */
    static String unitAndStart(String unitNumber, LocalDate start) {
        String unit = unitNumber == null ? "" : unitNumber.strip();
        String date = start == null ? "" : start.toString();
        return unit.isEmpty() || date.isEmpty() ? unit + date : unit + " · " + date;
    }

    /** The named accounts found in this tenant, by code; an id from elsewhere is simply absent. */
    public Map<UUID, NamedRef> accounts(UUID tenantId, Collection<UUID> ids) {
        List<UUID> wanted = distinct(ids);
        Map<UUID, NamedRef> out = new LinkedHashMap<>();
        if (wanted.isEmpty()) {
            return out;
        }
        jdbc.query("select id, name, name_ar from accounts where tenant_id = :t and id in (:ids) order by code, id",
                        new MapSqlParameterSource("t", tenantId).addValue("ids", wanted),
                        (rs, i) -> new NamedRef(uuid(rs, "id"), rs.getString("name"), rs.getString("name_ar")))
                .forEach(a -> out.put(a.id(), a));
        return out;
    }

    // ------------------------------------------------------------------

    private Optional<NamedRef> named(String sql, UUID tenantId, UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.query(sql, new MapSqlParameterSource("t", tenantId).addValue("id", id),
                (rs, i) -> new NamedRef(uuid(rs, "id"), rs.getString("name"), rs.getString("name_ar"))).stream().findFirst();
    }

    private static MapSqlParameterSource params(Scope s) {
        return new MapSqlParameterSource("t", s.tenantId())
                .addValue("from", s.from())
                .addValue("to", s.to())
                .addValue("accountIds", s.accountIds())
                .addValue("hasRenter", s.renterId() != null)
                .addValue("renterId", s.renterId() == null ? NONE : s.renterId())
                .addValue("hasLease", s.leaseId() != null)
                .addValue("leaseId", s.leaseId() == null ? NONE : s.leaseId())
                .addValue("unrestricted", s.properties().unrestricted())
                .addValue("propertyIds", s.properties().sqlIds())
                .addValue("onlyWithBalance", s.onlyWithBalance())
                .addValue("hideReversed", s.hideReversed())
                .addValue("arabic", s.arabic());
    }

    private static UUID orNone(UUID id) {
        return id == null ? NONE : id;
    }

    private static List<UUID> distinct(Collection<UUID> ids) {
        return ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }
}
