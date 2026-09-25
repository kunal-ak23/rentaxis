package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.api.dto.report.BalanceSheetDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Amount;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Column;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Group;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Row;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.PnlCellRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * F14-10: the balance sheet as at a date — assets, liabilities and equity per
 * property and consolidated, with an optional comparative date.
 *
 * <p>Computed live from {@code journal_lines} through the date, year-end closes
 * included: a closed year's result sits in Retained Earnings (PR #358), the open
 * years' result is shown as two computed equity rows — the current fiscal year to
 * date and earlier years not yet closed. Columns follow the property P&L: one per
 * property (effective property = line property, else account property), an
 * Unassigned column and the Total, which is the company balance sheet.</p>
 *
 * <p>The report carries its own check: Assets − (Liabilities + Equity) for every
 * column. The Total always balances on a balanced ledger; a property column
 * balances only when each entry stays inside the property (an entry split across
 * a property and a tenant-level account leaves the difference in Unassigned).</p>
 *
 * <p>A property manager sees only assigned properties, never Unassigned or Total.</p>
 */
@Service
public class BalanceSheetService {

    private static final List<AccountType> SIDES = List.of(AccountType.ASSET, AccountType.LIABILITY, AccountType.EQUITY);
    private static final LocalDate BEGINNING = LocalDate.of(1900, 1, 1);

    private final JournalLineRepository lines;
    private final PropertyPnlService pnl;
    private final PropertyScope scope;
    private final TenantFiscalSettingsService fiscal;

    public BalanceSheetService(JournalLineRepository lines, PropertyPnlService pnl, PropertyScope scope,
                               TenantFiscalSettingsService fiscal) {
        this.lines = lines;
        this.pnl = pnl;
        this.scope = scope;
        this.fiscal = fiscal;
    }

    /** Not read-only: the fiscal settings row is created on a tenant's first access. */
    @Transactional
    public BalanceSheetDTO balanceSheet(LocalDate asAt, LocalDate compareAt, Collection<UUID> propertyIds) {
        UUID tenantId = PropertyPnlService.requireTenant();
        if (asAt == null) throw new BusinessRuleViolationException("'asAt' is required");
        boolean scoped = scope.isScoped();
        LocalDate fyStart = fiscalYearStart(asAt);

        List<Property> columnProperties = pnl.columnProperties(tenantId, propertyIds, scoped);
        Snapshot now = snapshot(tenantId, asAt);
        Snapshot before = compareAt == null ? null : snapshot(tenantId, compareAt);

        // A property whose row is gone keeps a column while its lines remain (as the P&L).
        if (!scoped && (propertyIds == null || propertyIds.isEmpty())) {
            Set<UUID> known = new HashSet<>();
            columnProperties.forEach(p -> known.add(p.getId()));
            Set<UUID> extra = new LinkedHashSet<>();
            for (Snapshot s : before == null ? List.of(now) : List.of(now, before)) {
                for (PnlCellRow r : s.all) if (r.getPropertyId() != null && !known.contains(r.getPropertyId())) extra.add(r.getPropertyId());
            }
            if (!extra.isEmpty()) {
                List<Property> more = new ArrayList<>(columnProperties);
                for (UUID id : extra) more.add(PropertyPnlService.placeholder(id));
                columnProperties = more;
            }
        }

        List<Column> columns = new ArrayList<>();
        for (Property p : columnProperties) {
            columns.add(new Column(p.getId().toString(), p.getId(), "PROPERTY", p.getNameEn(), p.getNameAr()));
        }
        if (!scoped) {
            columns.add(new Column(PropertyPnlDTO.UNASSIGNED, null, PropertyPnlDTO.UNASSIGNED, "Unassigned", "غير مخصص"));
            columns.add(new Column(PropertyPnlDTO.TOTAL, null, PropertyPnlDTO.TOTAL, "Total", "الإجمالي"));
        }
        Set<UUID> columnSet = new HashSet<>();
        columnProperties.forEach(p -> columnSet.add(p.getId()));
        List<String> sumKeys = new ArrayList<>(columnProperties.stream().map(p -> p.getId().toString()).toList());
        if (!scoped) sumKeys.add(PropertyPnlDTO.UNASSIGNED);
        Function<UUID, String> columnOf = pid -> pid == null ? (scoped ? null : PropertyPnlDTO.UNASSIGNED)
                : columnSet.contains(pid) ? pid.toString() : null;

        Map<UUID, Account> byId = pnl.accountsById(tenantId);
        boolean withPrior = before != null;

        // row key → column → debit-positive net, per snapshot
        Map<String, Map<String, BigDecimal>> nowNet = fold(now.all, byId, columnOf);
        Map<String, Map<String, BigDecimal>> beforeNet = withPrior ? fold(before.all, byId, columnOf) : Map.of();

        Map<String, RowMeta> meta = new LinkedHashMap<>();
        List<PnlCellRow> seen = new ArrayList<>(now.all);
        if (withPrior) seen.addAll(before.all);
        seen.sort(Comparator.comparing((PnlCellRow r) -> byId.containsKey(r.getAccountId())
                ? byId.get(r.getAccountId()).getCode() : "").thenComparing(r -> r.getAccountId().toString()));
        for (PnlCellRow r : seen) {
            Account a = byId.get(r.getAccountId());
            if (a == null || !SIDES.contains(a.getAccountType())) continue;
            String key = PropertyPnlService.rowKey(a);
            RowMeta m = meta.computeIfAbsent(key, k -> new RowMeta(k, a.getReportLine(), a.getAccountType(),
                    PropertyPnlService.levelTwo(a, byId)));
            m.accountIds.add(a.getId());
            if (a.getReportLine() == null) { m.label = a.getName(); m.labelAr = a.getNameAr(); }
        }

        Map<AccountType, Map<String, BigDecimal>> totalsNow = new HashMap<>(), totalsBefore = new HashMap<>();
        List<BalanceSheetDTO.Section> sections = new ArrayList<>();
        for (AccountType side : SIDES) {
            int sign = side == AccountType.ASSET ? 1 : -1;
            Map<UUID, List<RowMeta>> byGroup = new LinkedHashMap<>();
            Map<UUID, Account> groups = new HashMap<>();
            for (RowMeta m : meta.values()) {
                if (m.type != side) continue;
                boolean shown = sumKeys.stream().anyMatch(k -> nonZero(nowNet, m.key, k) || nonZero(beforeNet, m.key, k));
                if (!shown) continue;
                byGroup.computeIfAbsent(m.group.getId(), g -> new ArrayList<>()).add(m);
                groups.put(m.group.getId(), m.group);
            }
            Map<String, BigDecimal> sideNow = new HashMap<>(), sideBefore = new HashMap<>();
            List<Group> groupDtos = new ArrayList<>();
            for (Account g : groups.values().stream().sorted(Comparator.comparing(Account::getCode)).toList()) {
                Map<String, BigDecimal> subNow = new HashMap<>(), subBefore = new HashMap<>();
                List<Row> rows = new ArrayList<>();
                for (RowMeta m : byGroup.get(g.getId()).stream()
                        .sorted(Comparator.comparing((RowMeta x) -> Objects.toString(x.firstCode(byId), ""))).toList()) {
                    Map<String, BigDecimal> n = signed(nowNet.getOrDefault(m.key, Map.of()), sign, sumKeys, scoped);
                    Map<String, BigDecimal> b = signed(beforeNet.getOrDefault(m.key, Map.of()), sign, sumKeys, scoped);
                    addInto(subNow, n);
                    addInto(subBefore, b);
                    String label = m.reportLine != null ? ReportLines.labelEn(m.reportLine) : m.label;
                    String labelAr = m.reportLine != null ? ReportLines.labelAr(m.reportLine) : m.labelAr;
                    rows.add(new Row(m.key, m.reportLine, label, labelAr,
                            m.accountIds.stream().sorted(Comparator.comparing(UUID::toString)).toList(),
                            amounts(n, b, columns, withPrior)));
                }
                addInto(sideNow, subNow);
                addInto(sideBefore, subBefore);
                groupDtos.add(new Group(g.getId(), g.getCode(), g.getName(), g.getNameAr(), g.getAccountType().name(),
                        rows, amounts(subNow, subBefore, columns, withPrior)));
            }
            totalsNow.put(side, sideNow);
            totalsBefore.put(side, sideBefore);
            sections.add(new BalanceSheetDTO.Section(side.name(), groupDtos, null));
        }

        // The open years' result: income less expenses, credit-positive.
        Map<String, BigDecimal> cyNow = resultOf(now.currentYear, byId, columnOf, sumKeys, scoped);
        Map<String, BigDecimal> allNow = resultOf(now.all, byId, columnOf, sumKeys, scoped);
        Map<String, BigDecimal> cyBefore = withPrior ? resultOf(before.currentYear, byId, columnOf, sumKeys, scoped) : Map.of();
        Map<String, BigDecimal> allBefore = withPrior ? resultOf(before.all, byId, columnOf, sumKeys, scoped) : Map.of();
        Map<String, BigDecimal> earlierNow = minus(allNow, cyNow, columns);
        Map<String, BigDecimal> earlierBefore = minus(allBefore, cyBefore, columns);

        Map<String, BigDecimal> equityNow = new HashMap<>(totalsNow.get(AccountType.EQUITY));
        addInto(equityNow, allNow);
        Map<String, BigDecimal> equityBefore = new HashMap<>(totalsBefore.get(AccountType.EQUITY));
        addInto(equityBefore, allBefore);

        List<BalanceSheetDTO.Section> finalSections = new ArrayList<>();
        for (BalanceSheetDTO.Section s : sections) {
            AccountType t = AccountType.valueOf(s.type());
            Map<String, BigDecimal> tn = t == AccountType.EQUITY ? equityNow : totalsNow.get(t);
            Map<String, BigDecimal> tb = t == AccountType.EQUITY ? equityBefore : totalsBefore.get(t);
            finalSections.add(new BalanceSheetDTO.Section(s.type(), s.groups(), amounts(tn, tb, columns, withPrior)));
        }

        Map<String, BigDecimal> leNow = new HashMap<>(totalsNow.get(AccountType.LIABILITY));
        addInto(leNow, equityNow);
        Map<String, BigDecimal> leBefore = new HashMap<>(totalsBefore.get(AccountType.LIABILITY));
        addInto(leBefore, equityBefore);
        Map<String, BigDecimal> checkNow = minus(totalsNow.get(AccountType.ASSET), leNow, columns);
        Map<String, BigDecimal> checkBefore = minus(totalsBefore.get(AccountType.ASSET), leBefore, columns);

        boolean ok = scoped
                ? checkNow.values().stream().allMatch(v -> money(v).signum() == 0)
                : money(checkNow.getOrDefault(PropertyPnlDTO.TOTAL, BigDecimal.ZERO)).signum() == 0;
        BigDecimal imbalance = scoped ? null
                : money(Objects.requireNonNullElse(lines.ledgerImbalance(tenantId, asAt), BigDecimal.ZERO));
        if (imbalance != null && imbalance.signum() != 0) ok = false;

        return new BalanceSheetDTO(asAt, compareAt, fyStart, scoped, columns, finalSections,
                amounts(cyNow, cyBefore, columns, withPrior),
                amounts(earlierNow, earlierBefore, columns, withPrior),
                amounts(leNow, leBefore, columns, withPrior),
                amounts(checkNow, checkBefore, columns, withPrior),
                ok, imbalance);
    }

    LocalDate fiscalYearStart(LocalDate date) {
        int startMonth = fiscal.get().getFiscalYearStartMonth();
        return LocalDate.of(fiscal.fiscalYearOf(date), startMonth, 1);
    }

    // ------------------------------------------------------------------ folding

    private record Snapshot(List<PnlCellRow> all, List<PnlCellRow> currentYear) { }

    private Snapshot snapshot(UUID tenantId, LocalDate asAt) {
        return new Snapshot(lines.balanceCells(tenantId, BEGINNING, asAt),
                lines.balanceCells(tenantId, fiscalYearStart(asAt), asAt));
    }

    private static final class RowMeta {
        final String key;
        final String reportLine;
        final AccountType type;
        final Account group;
        final Set<UUID> accountIds = new LinkedHashSet<>();
        String label;
        String labelAr;

        RowMeta(String key, String reportLine, AccountType type, Account group) {
            this.key = key;
            this.reportLine = reportLine;
            this.type = type;
            this.group = group;
        }

        String firstCode(Map<UUID, Account> byId) {
            return accountIds.stream().map(byId::get).filter(Objects::nonNull).map(Account::getCode)
                    .filter(Objects::nonNull).min(String::compareTo).orElse(key);
        }
    }

    /** row key → column → Σ(debit − credit). */
    private static Map<String, Map<String, BigDecimal>> fold(List<PnlCellRow> rows, Map<UUID, Account> byId,
                                                             Function<UUID, String> columnOf) {
        Map<String, Map<String, BigDecimal>> out = new HashMap<>();
        for (PnlCellRow r : rows) {
            Account a = byId.get(r.getAccountId());
            if (a == null || !SIDES.contains(a.getAccountType())) continue;
            String col = columnOf.apply(r.getPropertyId());
            if (col == null) continue;
            out.computeIfAbsent(PropertyPnlService.rowKey(a), k -> new HashMap<>())
                    .merge(col, r.getDebit().subtract(r.getCredit()), BigDecimal::add);
        }
        return out;
    }

    /** Income less expenses per column, credit-positive; TOTAL for tenant-wide callers. */
    private static Map<String, BigDecimal> resultOf(List<PnlCellRow> rows, Map<UUID, Account> byId,
                                                    Function<UUID, String> columnOf, List<String> sumKeys, boolean scoped) {
        Map<String, BigDecimal> net = new HashMap<>();
        for (PnlCellRow r : rows) {
            Account a = byId.get(r.getAccountId());
            if (a == null || (a.getAccountType() != AccountType.INCOME && a.getAccountType() != AccountType.EXPENSE)) continue;
            String col = columnOf.apply(r.getPropertyId());
            if (col == null) continue;
            net.merge(col, r.getCredit().subtract(r.getDebit()), BigDecimal::add);
        }
        return signed(net, 1, sumKeys, scoped);
    }

    private static Map<String, BigDecimal> signed(Map<String, BigDecimal> net, int sign, List<String> sumKeys, boolean scoped) {
        Map<String, BigDecimal> out = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (String k : sumKeys) {
            BigDecimal v = net.getOrDefault(k, BigDecimal.ZERO);
            if (sign < 0) v = v.negate();
            out.put(k, v);
            total = total.add(v);
        }
        if (!scoped) out.put(PropertyPnlDTO.TOTAL, total);
        return out;
    }

    private static void addInto(Map<String, BigDecimal> into, Map<String, BigDecimal> add) {
        add.forEach((k, v) -> into.merge(k, v, BigDecimal::add));
    }

    private static Map<String, BigDecimal> minus(Map<String, BigDecimal> a, Map<String, BigDecimal> b, List<Column> columns) {
        Map<String, BigDecimal> out = new HashMap<>();
        for (Column c : columns) {
            out.put(c.key(), a.getOrDefault(c.key(), BigDecimal.ZERO).subtract(b.getOrDefault(c.key(), BigDecimal.ZERO)));
        }
        return out;
    }

    private static boolean nonZero(Map<String, Map<String, BigDecimal>> m, String row, String col) {
        BigDecimal v = m.getOrDefault(row, Map.of()).get(col);
        return v != null && v.signum() != 0;
    }

    private static Map<String, Amount> amounts(Map<String, BigDecimal> now, Map<String, BigDecimal> before,
                                               List<Column> columns, boolean withPrior) {
        Map<String, Amount> out = new LinkedHashMap<>();
        for (Column c : columns) {
            BigDecimal a = money(now.getOrDefault(c.key(), BigDecimal.ZERO));
            if (!withPrior) {
                out.put(c.key(), new Amount(a, null, null, null));
                continue;
            }
            BigDecimal p = money(before.getOrDefault(c.key(), BigDecimal.ZERO));
            BigDecimal d = a.subtract(p);
            BigDecimal pct = p.signum() == 0 ? null
                    : d.multiply(BigDecimal.valueOf(100)).divide(p.abs(), 2, RoundingMode.HALF_UP);
            out.put(c.key(), new Amount(a, p, d, pct));
        }
        return out;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
