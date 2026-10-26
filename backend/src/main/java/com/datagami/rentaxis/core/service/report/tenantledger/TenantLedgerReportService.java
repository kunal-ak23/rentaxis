package com.datagami.rentaxis.core.service.report.tenantledger;

import com.datagami.rentaxis.api.dto.report.ReceivableAccountDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.report.grid.ExportFormat;
import com.datagami.rentaxis.core.service.report.grid.GroupPager;
import com.datagami.rentaxis.core.service.report.grid.ReportCaps;
import com.datagami.rentaxis.core.service.report.grid.ReportFilter;
import com.datagami.rentaxis.core.service.report.grid.ReportLabels;
import com.datagami.rentaxis.core.service.report.grid.ReportMeta;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.CounterRow;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.GroupKey;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.GroupRow;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.LineRow;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.NamedRef;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.Scope;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerResult.AccountGroup;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerResult.Line;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerResult.TenantGroup;
import com.datagami.rentaxis.core.util.Search;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * R1 Tenant Ledger (reports spec §2): the receivable ledger grouped by account, then by tenant, each tenant with its
 * own brought-forward balance and running balance, PACT's cheque columns, a Sub Total per tenant (and per account
 * when it has several tenants) and a REPORT TOTAL.
 *
 * <p>Reads everything inside one read-only transaction into a {@link TenantLedgerResult}: names already in the
 * report language, groups with their SQL ("authoritative") totals, and the rows of the page asked for, or of the
 * whole report for an export. {@link TenantLedgerGrid} renders it after the transaction. Every query binds the
 * caller's tenant ({@link TenantLedgerQueries}); a filter id that does not resolve in it is a 404.</p>
 */
@Service
@Transactional(readOnly = true, timeout = 60)
public class TenantLedgerReportService {

    private static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");

    private final TenantLedgerQueries queries;
    private final PropertyScope propertyScope;
    private final ReportLabels labels;
    private final ReportCaps caps;
    private final Clock clock;

    public TenantLedgerReportService(TenantLedgerQueries queries, PropertyScope propertyScope, ReportLabels labels,
                                     ReportCaps caps, Clock clock) {
        this.queries = queries;
        this.propertyScope = propertyScope;
        this.labels = labels;
        this.caps = caps;
        this.clock = clock;
    }

    /**
     * One screen page: whole tenant groups while they fit in {@code pageRows} (at most
     * {@link ReportCaps#screenPageRows()}), a longer group split across pages. A page past the end reads as the
     * last one.
     */
    public TenantLedgerResult page(TenantLedgerFilter f, int page, int pageRows, String generatedBy) {
        Objects.requireNonNull(f, "filter");
        Prepared p = prepare(f, generatedBy);
        int size = Math.max(1, Math.min(pageRows, caps.screenPageRows()));
        List<GroupPager.PageSpan> plan = GroupPager.plan(p.rowCounts(), size);
        int index = Math.max(0, Math.min(page, plan.size() - 1));
        GroupPager.PageSpan span = plan.get(index);

        List<LineRow> raw = List.of();
        if (p.scope() != null && span.lastGroup() >= span.firstGroup()) {
            List<GroupRow> onPage = p.groups().subList(span.firstGroup(), span.lastGroup() + 1);
            Map<GroupKey, Integer> ordinal = new HashMap<>();
            for (int i = 0; i < onPage.size(); i++) {
                ordinal.put(onPage.get(i).key(), span.firstGroup() + i);
            }
            raw = queries.lines(p.scope(), onPage.stream().map(GroupRow::key).toList()).stream()
                    .filter(l -> {
                        int g = ordinal.get(l.key());
                        return (g != span.firstGroup() || l.rn() >= span.firstRow())
                                && (g != span.lastGroup() || l.rn() <= span.lastRow());
                    })
                    .toList();
        }
        return p.result(lines(p, raw), span, index, plan.size(), size);
    }

    /**
     * The whole report for a file. Over the format's row cap it is a {@code ReportTooLargeException} (422), raised
     * from the groups' row counts before any line is read.
     */
    public TenantLedgerResult export(TenantLedgerFilter f, ExportFormat format, String generatedBy) {
        Objects.requireNonNull(f, "filter");
        Objects.requireNonNull(format, "format");
        Prepared p = prepare(f, generatedBy);
        caps.requireWithin(format, p.totalRows());
        List<LineRow> raw = p.scope() == null ? List.of() : queries.lines(p.scope(), null);
        return p.result(lines(p, raw), null, 0, 1, 0);
    }

    /**
     * The receivable leaves the account picker offers, by code: one property's ({@code propertyId}, 404 when it is
     * not the caller's) or every property's the caller may see.
     */
    public List<ReceivableAccountDTO> receivableAccounts(UUID propertyId) {
        UUID t = Search.requireTenant();
        PropertyScope.Selection selection = propertyScope.requireSelection(propertyId == null ? List.of() : List.of(propertyId));
        if (propertyId != null && queries.property(t, propertyId).isEmpty()) {
            throw new NotFoundException("Property not found");
        }
        if (selection.empty()) {
            return List.of();
        }
        List<ReceivableAccountDTO> leaves = queries.receivableLeaves(t, propertyId);
        if (selection.unrestricted() || propertyId != null) {
            return leaves;
        }
        Set<UUID> visible = Set.copyOf(selection.ids());
        return leaves.stream().filter(a -> a.propertyId() == null || visible.contains(a.propertyId())).toList();
    }

    // ------------------------------------------------------------------

    /** What {@link #page} and {@link #export} share: the resolved filters, the meta and the groups. */
    private record Prepared(UUID tenantId, TenantLedgerFilter filter, ReportMeta meta, Scope scope, List<GroupRow> groups,
                            List<AccountGroup> accounts) {

        List<Integer> rowCounts() {
            return groups.stream().map(GroupRow::rowCount).toList();
        }

        long totalRows() {
            return groups.stream().mapToLong(GroupRow::rowCount).sum();
        }

        TenantLedgerResult result(List<Line> lines, GroupPager.PageSpan span, int page, int pageCount, int pageRows) {
            return new TenantLedgerResult(meta, accounts, lines, span, page, pageCount, pageRows, totalRows(), groups.size());
        }
    }

    private Prepared prepare(TenantLedgerFilter f, String generatedBy) {
        UUID t = Search.requireTenant();
        PropertyScope.Selection selection = propertyScope.requireSelection(
                f.propertyId() == null ? List.of() : List.of(f.propertyId()));
        ReportLabels.Bound l = labels.bind(f.lang());
        boolean ar = "ar".equals(f.lang());

        List<ReportFilter> filters = new ArrayList<>();
        if (f.propertyId() != null) {
            NamedRef property = queries.property(t, f.propertyId()).orElseThrow(() -> new NotFoundException("Property not found"));
            filters.add(new ReportFilter("filter.property", name(ar, property)));
        }
        if (f.accountIds().isEmpty()) {
            filters.add(new ReportFilter("filter.accounts", l.get("filter.allReceivable")));
        } else {
            Map<UUID, NamedRef> found = queries.accounts(t, f.accountIds());
            if (!found.keySet().containsAll(f.accountIds())) {
                throw new NotFoundException("Account not found");
            }
            filters.add(new ReportFilter("filter.accounts",
                    found.values().stream().map(a -> name(ar, a)).collect(Collectors.joining(", "))));
        }
        if (f.renterId() != null) {
            NamedRef renter = queries.renter(t, f.renterId()).orElseThrow(() -> new NotFoundException("Renter not found"));
            filters.add(new ReportFilter("filter.tenant", name(ar, renter)));
        }
        if (f.leaseId() != null) {
            NamedRef lease = queries.lease(t, f.leaseId()).orElseThrow(() -> new NotFoundException("Lease not found"));
            filters.add(new ReportFilter("filter.contract", name(ar, lease)));
        }
        if (f.onlyWithBalance()) {
            filters.add(new ReportFilter("filter.onlyWithBalance", l.get("report.yes")));
        }
        if (f.hideReversed()) {
            filters.add(new ReportFilter("filter.hideReversed", l.get("report.yes")));
        }
        ReportMeta meta = new ReportMeta(TenantLedgerGrid.KEY, "tenantLedger.title", queries.orgName(t).orElse(null),
                filters, f.from(), f.to(), null, ZonedDateTime.now(clock).withZoneSameInstant(DUBAI), generatedBy, f.lang());

        List<UUID> accountIds = f.accountIds().isEmpty() ? queries.receivableLeafIds(t) : f.accountIds();
        if (accountIds.isEmpty() || selection.empty()) {
            return new Prepared(t, f, meta, null, List.of(), List.of());
        }
        Scope scope = new Scope(t, f.from(), f.to(), accountIds, f.renterId(), f.leaseId(), selection,
                f.onlyWithBalance(), f.hideReversed());
        List<GroupRow> groups = queries.groups(scope);
        return new Prepared(t, f, meta, scope, groups, accountGroups(groups, l, ar));
    }

    /** The groups (in report order) as account bands holding tenant bands; ordinals are the groups' indexes. */
    private static List<AccountGroup> accountGroups(List<GroupRow> groups, ReportLabels.Bound l, boolean ar) {
        List<AccountGroup> out = new ArrayList<>();
        List<TenantGroup> tenants = new ArrayList<>();
        GroupRow head = null;
        for (int i = 0; i < groups.size(); i++) {
            GroupRow g = groups.get(i);
            if (head != null && !head.accountId().equals(g.accountId())) {
                out.add(account(head, tenants, l, ar));
                tenants = new ArrayList<>();
            }
            if (tenants.isEmpty()) {
                head = g;
            }
            String renter = g.renterId() == null ? l.get("tenantLedger.noTenant")
                    : Objects.toString(TenantLedgerRules.pick(ar, g.renterName(), g.renterNameAr()), "");
            tenants.add(new TenantGroup(i, g.renterId(), l.get("tenantLedger.tenantBand", renter), nz(g.opening()),
                    nz(g.debit()), nz(g.credit()), g.rowCount()));
        }
        if (head != null) {
            out.add(account(head, tenants, l, ar));
        }
        return out;
    }

    private static AccountGroup account(GroupRow head, List<TenantGroup> tenants, ReportLabels.Bound l, boolean ar) {
        String label = Objects.toString(TenantLedgerRules.pick(ar, head.accountName(), head.accountNameAr()), "");
        return new AccountGroup(head.accountId(), head.accountCode(), label,
                l.get("tenantLedger.accountSubTotal", label), tenants);
    }

    /** The rows as printed: Particular (counter-account names for unpaired lines), cheque columns, blank zeros. */
    private List<Line> lines(Prepared p, List<LineRow> raw) {
        if (raw.isEmpty()) {
            return List.of();
        }
        Set<UUID> unpairedEntries = new LinkedHashSet<>();
        Set<UUID> unpairedAccounts = new LinkedHashSet<>();
        for (LineRow r : raw) {
            if (r.contraName() == null) {
                unpairedEntries.add(r.entryId());
                unpairedAccounts.add(r.accountId());
            }
        }
        Map<EntryAccount, CounterRow> counters = new HashMap<>();
        if (!unpairedEntries.isEmpty()) {
            for (CounterRow c : queries.counterNames(p.tenantId(), unpairedEntries, unpairedAccounts)) {
                counters.put(new EntryAccount(c.entryId(), c.accountId()), c);
            }
        }
        String lang = p.filter().lang();
        LocalDate to = p.filter().to();
        List<Line> out = new ArrayList<>(raw.size());
        for (LineRow r : raw) {
            String particular;
            if (r.contraName() != null) {
                particular = TenantLedgerRules.particular(lang, r.contraName(), r.contraNameAr(), null, null);
            } else {
                CounterRow c = counters.get(new EntryAccount(r.entryId(), r.accountId()));
                particular = TenantLedgerRules.particular(lang, null, null,
                        c == null ? null : c.names(), c == null ? null : c.namesAr());
            }
            out.add(new Line(r.accountId(), r.renterId(), r.rn(), nz(r.movement()), r.entryId(), r.entryNumber(),
                    r.docType(), r.entryDate(), particular, blankZero(r.debit()), blankZero(r.credit()),
                    TenantLedgerRules.chqNo(r.chequeId(), r.chequeNumber(), r.chequeMode()),
                    TenantLedgerRules.payeeBank(r.chequeId(), r.payeeBank(), r.chequeMode()),
                    TenantLedgerRules.bdDays(r.cbrJournalId(), r.bouncedAt(), to),
                    TenantLedgerRules.convertedDate(r.entryId(), r.pdrJournalId(), r.chequeDate()),
                    r.leaseId(), r.chequeId()));
        }
        return out;
    }

    private record EntryAccount(UUID entryId, UUID accountId) {}

    private static String name(boolean ar, NamedRef ref) {
        return Objects.toString(TenantLedgerRules.pick(ar, ref.name(), ref.nameAr()), "");
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal blankZero(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : v;
    }
}
