package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.api.dto.report.PnlLinesDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Allocation;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Amount;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Check;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Column;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.DataQuality;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Group;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Row;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.report.PnlAllocation.Basis;
import com.datagami.rentaxis.core.service.report.PnlPeriods.Compare;
import com.datagami.rentaxis.core.service.report.PnlPeriods.Period;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.PnlCellRow;
import com.datagami.rentaxis.domain.repository.JournalLineRepository.PnlLineRow;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Per-property P&L (finance-ops spec §1): income by type, expenses by type and NOI
 * per property for a period, with an optional prior-period comparison, an honest
 * Unassigned column and a check row that ties the report to the ledger.
 *
 * <p>Read-only and computed live from {@code journal_lines}: nothing is stored, so
 * every figure ties to the GL by construction. The effective property of a line is
 * {@code coalesce(line property, account property)}; rows are report lines
 * ({@link ReportLines}), so properties line up.</p>
 *
 * <p>This is the P&L the year-end-close PR reuses for its close preview
 * ({@code GET /finance/reports/property-pl}, the prod-readiness spec's
 * {@code /finance/profit-and-loss} renamed). It is built from {@link PnlCell}s so an
 * owner layer can fold the same cells over ownership shares later.</p>
 *
 * <p>Scope: a PROPERTY_MANAGER sees only assigned properties, and never the
 * Unassigned column, the Total, the allocation or the check row — those are
 * tenant-wide figures. A foreign or unassigned property named by id is a 404.</p>
 */
@Service
@Transactional(readOnly = true)
public class PropertyPnlService {

    /** A drill-down is read on screen; beyond this the caller narrows the range. */
    public static final int MAX_DRILL_LINES = 5000;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final JournalLineRepository lines;
    private final AccountRepository accounts;
    private final PropertyRepository properties;
    private final UnitRepository units;
    private final PropertyScope scope;

    public PropertyPnlService(JournalLineRepository lines, AccountRepository accounts, PropertyRepository properties,
                              UnitRepository units, PropertyScope scope) {
        this.lines = lines;
        this.accounts = accounts;
        this.properties = properties;
        this.units = units;
        this.scope = scope;
    }

    // ------------------------------------------------------------------ cells

    /**
     * The raw cells for a period, narrowed to {@code propertyIds} (null: every
     * property and Unassigned). For a property manager the cells are always
     * narrowed to the assigned properties, and Unassigned never comes back.
     */
    public List<PnlCell> cells(LocalDate from, LocalDate to, Collection<UUID> propertyIds) {
        UUID tenantId = requireTenant();
        requireRange(from, to);
        List<UUID> visible = visibleProperties(propertyIds);
        Map<UUID, Account> byId = accountsById(tenantId);
        return lines.pnlCells(tenantId, from, to).stream()
                .filter(r -> visible == null ? true : r.getPropertyId() != null && visible.contains(r.getPropertyId()))
                .filter(r -> byId.containsKey(r.getAccountId()))
                .map(r -> new PnlCell(r.getPropertyId(), byId.get(r.getAccountId()).getReportLine(), r.getAccountId(),
                        r.getDebit(), r.getCredit()))
                .toList();
    }

    // ------------------------------------------------------------------ P&L

    public PropertyPnlDTO pnl(LocalDate from, LocalDate to, Collection<UUID> propertyIds, Compare compare, Basis allocate) {
        UUID tenantId = requireTenant();
        requireRange(from, to);
        Compare cmp = compare == null ? Compare.NONE : compare;
        boolean scoped = scope.isScoped();
        Basis basis = scoped || allocate == null ? Basis.NONE : allocate;

        List<Property> columnProperties = columnProperties(tenantId, propertyIds, scoped);
        List<UUID> propertyColumnIds = columnProperties.stream().map(Property::getId).toList();

        List<PnlCellRow> current = lines.pnlCells(tenantId, from, to);
        Period prior = PnlPeriods.prior(from, to, cmp);
        List<PnlCellRow> previous = prior == null ? List.of() : lines.pnlCells(tenantId, prior.from(), prior.to());

        // Unnamed and "all" requests also show a property that has lines but no row
        // any more (spec §1: deleted or inactive properties still appear).
        if (!scoped && (propertyIds == null || propertyIds.isEmpty())) {
            Set<UUID> extra = new LinkedHashSet<>();
            for (PnlCellRow r : current) if (r.getPropertyId() != null && !propertyColumnIds.contains(r.getPropertyId())) extra.add(r.getPropertyId());
            for (PnlCellRow r : previous) if (r.getPropertyId() != null && !propertyColumnIds.contains(r.getPropertyId())) extra.add(r.getPropertyId());
            if (!extra.isEmpty()) {
                List<Property> more = new ArrayList<>(columnProperties);
                for (UUID id : extra) more.add(placeholder(id));
                columnProperties = more;
                propertyColumnIds = more.stream().map(Property::getId).toList();
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
        List<String> propertyKeys = propertyColumnIds.stream().map(UUID::toString).toList();
        List<String> sumKeys = new ArrayList<>(propertyKeys);   // the columns TOTAL adds up
        if (!scoped) sumKeys.add(PropertyPnlDTO.UNASSIGNED);

        Map<UUID, Account> byId = accountsById(tenantId);
        Set<UUID> columnSet = new java.util.HashSet<>(propertyColumnIds);
        Function<UUID, String> columnOf = pid -> pid == null ? (scoped ? null : PropertyPnlDTO.UNASSIGNED)
                : columnSet.contains(pid) ? pid.toString() : null;

        // row key -> column -> credit-positive net
        Map<String, Map<String, BigDecimal>> now = fold(current, byId, columnOf);
        Map<String, Map<String, BigDecimal>> before = fold(previous, byId, columnOf);

        // Row metadata, from every account seen in either period — in account-code
        // order, so a row spanning several leaves always takes the same group.
        Map<String, RowMeta> meta = new LinkedHashMap<>();
        List<PnlCellRow> seen = concat(current, previous);
        seen.sort(Comparator.comparing((PnlCellRow r) -> byId.containsKey(r.getAccountId())
                ? byId.get(r.getAccountId()).getCode() : "").thenComparing(r -> r.getAccountId().toString()));
        for (PnlCellRow r : seen) {
            Account a = byId.get(r.getAccountId());
            if (a == null) continue;
            String key = rowKey(a);
            RowMeta m = meta.computeIfAbsent(key, k -> new RowMeta(k, a.getReportLine(), levelTwo(a, byId)));
            m.accountIds.add(a.getId());
            if (a.getReportLine() == null) { m.label = a.getName(); m.labelAr = a.getNameAr(); }
        }

        boolean withPrior = prior != null;
        Map<UUID, List<RowMeta>> rowsByGroup = new LinkedHashMap<>();
        Map<UUID, Account> groups = new HashMap<>();
        for (RowMeta m : meta.values()) {
            boolean shown = sumKeys.stream().anyMatch(k -> nonZero(now, m.key, k) || nonZero(before, m.key, k));
            if (!shown) continue;
            rowsByGroup.computeIfAbsent(m.group.getId(), g -> new ArrayList<>()).add(m);
            groups.put(m.group.getId(), m.group);
        }

        List<Account> orderedGroups = groups.values().stream()
                .sorted(Comparator.comparing((Account g) -> g.getAccountType() == AccountType.INCOME ? 0 : 1)
                        .thenComparing(Account::getCode))
                .toList();

        Map<String, BigDecimal> incomeNow = new HashMap<>(), incomeBefore = new HashMap<>();
        Map<String, BigDecimal> expenseNow = new HashMap<>(), expenseBefore = new HashMap<>();
        List<Group> groupDtos = new ArrayList<>();
        for (Account g : orderedGroups) {
            int sign = g.getAccountType() == AccountType.INCOME ? 1 : -1;
            List<RowMeta> metas = rowsByGroup.get(g.getId()).stream().sorted(ROW_ORDER).toList();
            Map<String, BigDecimal> subNow = new HashMap<>(), subBefore = new HashMap<>();
            List<Row> rows = new ArrayList<>();
            for (RowMeta m : metas) {
                Map<String, BigDecimal> n = signed(now.getOrDefault(m.key, Map.of()), sign, sumKeys, scoped);
                Map<String, BigDecimal> b = signed(before.getOrDefault(m.key, Map.of()), sign, sumKeys, scoped);
                addInto(subNow, n);
                addInto(subBefore, b);
                String label = m.reportLine != null ? ReportLines.labelEn(m.reportLine) : m.label;
                String labelAr = m.reportLine != null ? ReportLines.labelAr(m.reportLine) : m.labelAr;
                rows.add(new Row(m.key, m.reportLine, label, labelAr,
                        m.accountIds.stream().sorted(Comparator.comparing(UUID::toString)).toList(),
                        amounts(n, b, columns, withPrior)));
            }
            addInto(g.getAccountType() == AccountType.INCOME ? incomeNow : expenseNow, subNow);
            addInto(g.getAccountType() == AccountType.INCOME ? incomeBefore : expenseBefore, subBefore);
            groupDtos.add(new Group(g.getId(), g.getCode(), g.getName(), g.getNameAr(), g.getAccountType().name(),
                    rows, amounts(subNow, subBefore, columns, withPrior)));
        }

        Map<String, BigDecimal> noiNow = minus(incomeNow, expenseNow, columns);
        Map<String, BigDecimal> noiBefore = minus(incomeBefore, expenseBefore, columns);

        Allocation allocation = basis == Basis.NONE ? null
                : allocate(basis, propertyKeys, noiNow, current, byId, tenantId);
        Check check = scoped ? null : checkOf(
                money(Objects.requireNonNullElse(propertyIds == null || propertyIds.isEmpty()
                        ? lines.pnlNetMovement(tenantId, from, to)
                        : lines.pnlNetMovementFor(tenantId, from, to, propertyColumnIds), BigDecimal.ZERO)),
                money(noiNow.getOrDefault(PropertyPnlDTO.TOTAL, BigDecimal.ZERO)));
        // Only the lines behind the columns on the report: a subset leaves the others out.
        long mismatches = current.stream()
                .filter(r -> r.getPropertyId() != null && columnSet.contains(r.getPropertyId()))
                .mapToLong(PnlCellRow::getMismatchLines).sum();

        return new PropertyPnlDTO(from, to, cmp.name(),
                prior == null ? null : prior.from(), prior == null ? null : prior.to(),
                scoped, columns, groupDtos,
                amounts(incomeNow, incomeBefore, columns, withPrior),
                amounts(expenseNow, expenseBefore, columns, withPrior),
                amounts(noiNow, noiBefore, columns, withPrior),
                allocation, check, new DataQuality(mismatches));
    }

    // ------------------------------------------------------------------ drill-down

    /**
     * The journal lines behind one figure. {@code column} is a property id,
     * {@code UNASSIGNED} or {@code TOTAL}; a property manager may name only an
     * assigned property. The figure is named by key, not by account ids, so a NOI
     * or group total over hundreds of leaves stays a short request: {@code rowKey}
     * (a report line or account id) for one row, {@code groupId} for a group
     * subtotal, neither for NOI. {@code propertyIds} is the report's selection, so
     * a TOTAL drill lists exactly what the Total column adds up.
     */
    public PnlLinesDTO lines(LocalDate from, LocalDate to, String column, String rowKey, UUID groupId,
                             Collection<UUID> propertyIds) {
        UUID tenantId = requireTenant();
        requireRange(from, to);
        String mode;
        UUID propertyId = null;
        List<UUID> scopeIds = List.of();
        boolean scoped = scope.isScoped();
        if (PropertyPnlDTO.UNASSIGNED.equals(column) || PropertyPnlDTO.TOTAL.equals(column)) {
            if (scoped) throw new NotFoundException("Property not found");
            if (PropertyPnlDTO.UNASSIGNED.equals(column)) {
                mode = "UNASSIGNED";
            } else if (propertyIds == null || propertyIds.isEmpty()) {
                mode = "ALL";
            } else {
                for (UUID id : propertyIds) requireProperty(tenantId, id);
                mode = "SCOPE";
                scopeIds = List.copyOf(new LinkedHashSet<>(propertyIds));
            }
        } else {
            try {
                propertyId = UUID.fromString(column);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new BusinessRuleViolationException("column must be a property id, UNASSIGNED or TOTAL");
            }
            requireDrillableProperty(tenantId, propertyId, scoped);
            mode = "PROPERTY";
        }

        Map<UUID, Account> byId = accountsById(tenantId);
        List<Account> pnlAccounts = byId.values().stream()
                .filter(a -> a.getAccountType() == AccountType.INCOME || a.getAccountType() == AccountType.EXPENSE)
                .toList();
        boolean all = rowKey == null && groupId == null;
        List<UUID> accountIds = all ? List.of() : pnlAccounts.stream()
                .filter(a -> rowKey != null ? rowKey.equals(rowKey(a)) : groupId.equals(levelTwo(a, byId).getId()))
                .map(Account::getId).toList();
        if (!all && accountIds.isEmpty()) return new PnlLinesDTO(List.of(), BigDecimal.ZERO, BigDecimal.ZERO, false);

        // An IN list cannot be empty in SQL; the placeholder never matches (the flags decide).
        List<UUID> none = List.of(new UUID(0, 0));
        List<PnlLineRow> raw = lines.pnlLines(tenantId, from, to, all, all ? none : accountIds, mode, propertyId,
                scopeIds.isEmpty() ? none : scopeIds, MAX_DRILL_LINES + 1);
        boolean truncated = raw.size() > MAX_DRILL_LINES;
        if (truncated) raw = raw.subList(0, MAX_DRILL_LINES);
        BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
        List<PnlLinesDTO.Line> out = new ArrayList<>(raw.size());
        for (PnlLineRow r : raw) {
            dr = dr.add(r.getDebit());
            cr = cr.add(r.getCredit());
            out.add(new PnlLinesDTO.Line(r.getEntryId(), r.getEntryNumber(), r.getEntryDate(), r.getDocType(), r.getNarration(),
                    r.getAccountId(), r.getAccountCode(), r.getAccountName(), r.getAccountNameAr(),
                    r.getDebit(), r.getCredit(), r.getLinePropertyId(), r.getAccountPropertyId()));
        }
        return new PnlLinesDTO(out, dr, cr, truncated);
    }

    /**
     * As {@link #requireProperty}, except that a tenant-wide caller may drill a
     * column whose property row is gone but whose lines are still this tenant's
     * (the deleted-property column). A manager still needs the assignment.
     */
    private void requireDrillableProperty(UUID tenantId, UUID propertyId, boolean scoped) {
        scope.requireCanAccessProperty(propertyId);
        if (!properties.findByTenantIdAndIdIn(tenantId, List.of(propertyId)).isEmpty()) return;
        if (!scoped && lines.hasLinesForProperty(tenantId, propertyId)) return;
        throw new NotFoundException("Property not found");
    }

    // ------------------------------------------------------------------ scope

    /** The property ids the caller may see among those named; null means "all, plus Unassigned". */
    private List<UUID> visibleProperties(Collection<UUID> named) {
        UUID tenantId = requireTenant();
        if (named != null && !named.isEmpty()) {
            for (UUID id : named) requireProperty(tenantId, id);
            return List.copyOf(new LinkedHashSet<>(named));
        }
        return scope.isScoped() ? scope.scopedPropertyIds() : null;
    }

    /** 404 unless the property is this tenant's and, for a manager, assigned. */
    public Property requireProperty(UUID tenantId, UUID propertyId) {
        if (propertyId == null) throw new NotFoundException("Property not found");
        scope.requireCanAccessProperty(propertyId);
        List<Property> found = properties.findByTenantIdAndIdIn(tenantId, List.of(propertyId));
        if (found.isEmpty()) throw new NotFoundException("Property not found");
        return found.getFirst();
    }

    List<Property> columnProperties(UUID tenantId, Collection<UUID> named, boolean scoped) {
        List<Property> out;
        if (named != null && !named.isEmpty()) {
            for (UUID id : named) requireProperty(tenantId, id);
            out = properties.findByTenantIdAndIdIn(tenantId, new LinkedHashSet<>(named));
        } else if (scoped) {
            List<UUID> ids = scope.scopedPropertyIds();
            out = ids.isEmpty() ? List.of() : properties.findByTenantIdAndIdIn(tenantId, ids);
        } else {
            out = properties.findByTenantIdOrderByNameEnAsc(tenantId);
        }
        return out.stream().sorted(Comparator.comparing(p -> Objects.toString(p.getNameEn(), ""), String.CASE_INSENSITIVE_ORDER)).toList();
    }

    /** A column for a property whose row is gone but whose lines remain. */
    static Property placeholder(UUID id) {
        Property p = new Property();
        p.setId(id);
        String shortId = id.toString().substring(0, 8);
        p.setNameEn("Deleted property " + shortId);
        p.setNameAr("عقار محذوف " + shortId);
        return p;
    }

    // ------------------------------------------------------------------ folding

    private static final class RowMeta {
        final String key;
        final String reportLine;
        final Account group;
        final Set<UUID> accountIds = new LinkedHashSet<>();
        String label;
        String labelAr;

        RowMeta(String key, String reportLine, Account group) {
            this.key = key;
            this.reportLine = reportLine;
            this.group = group;
        }
    }

    private static final List<String> LINE_ORDER = List.copyOf(ReportLines.known());

    /** Report lines first, in role / category order; then account rows by name. */
    private static final Comparator<RowMeta> ROW_ORDER = Comparator
            .comparingInt((RowMeta m) -> m.reportLine == null ? Integer.MAX_VALUE
                    : Math.max(0, LINE_ORDER.indexOf(m.reportLine)))
            .thenComparing(m -> Objects.toString(m.label, m.key), String.CASE_INSENSITIVE_ORDER);

    static String rowKey(Account a) {
        return a.getReportLine() != null ? a.getReportLine() : a.getId().toString();
    }

    /** row key → column → Σ(credit − debit). Cells outside every column are dropped. */
    private static Map<String, Map<String, BigDecimal>> fold(List<PnlCellRow> rows, Map<UUID, Account> byId,
                                                             Function<UUID, String> columnOf) {
        Map<String, Map<String, BigDecimal>> out = new HashMap<>();
        for (PnlCellRow r : rows) {
            Account a = byId.get(r.getAccountId());
            if (a == null) continue;
            String col = columnOf.apply(r.getPropertyId());
            if (col == null) continue;
            out.computeIfAbsent(rowKey(a), k -> new HashMap<>())
                    .merge(col, r.getCredit().subtract(r.getDebit()), BigDecimal::add);
        }
        return out;
    }

    /** Income reads credit-positive, expense debit-positive; TOTAL is added for tenant-wide callers. */
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
                    : d.multiply(HUNDRED).divide(p.abs(), 2, RoundingMode.HALF_UP);
            out.put(c.key(), new Amount(a, p, d, pct));
        }
        return out;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    @SafeVarargs
    private static <T> List<T> concat(List<T>... lists) {
        List<T> out = new ArrayList<>();
        for (List<T> l : lists) out.addAll(l);
        return out;
    }

    // ------------------------------------------------------------------ allocation, check

    /**
     * Spreads the tenant's whole Unassigned net cost over EVERY property by the
     * basis, then reports the shares of the properties on the report; whatever
     * falls to the others is {@code allocatedToOthers}. Spreading over the shown
     * columns only would load one selected building with the whole head office.
     * A deleted property's column shows a zero share.
     */
    private Allocation allocate(Basis basis, List<String> shownKeys, Map<String, BigDecimal> noiNow,
                                List<PnlCellRow> current, Map<UUID, Account> byId, UUID tenantId) {
        BigDecimal unassignedNet = current.stream().filter(r -> r.getPropertyId() == null)
                .map(r -> r.getCredit().subtract(r.getDebit())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unassignedCost = money(unassignedNet.negate());

        // The tenant's property rows only. There is no archive flag on a property; a
        // deleted one (its lines outlive it, shown as a "Deleted property" column)
        // takes no share of today's head-office cost.
        Set<String> everyProperty = new LinkedHashSet<>();
        properties.findByTenantIdOrderByNameEnAsc(tenantId).forEach(p -> everyProperty.add(p.getId().toString()));

        Map<String, BigDecimal> rent = new HashMap<>();
        if (basis == Basis.RENT) {
            for (PnlCellRow r : current) {
                Account a = byId.get(r.getAccountId());
                if (r.getPropertyId() == null || a == null || !AccountRole.RENTAL_INCOME.name().equals(a.getReportLine())) continue;
                rent.merge(r.getPropertyId().toString(), r.getCredit().subtract(r.getDebit()), BigDecimal::add);
            }
        }
        Map<String, BigDecimal> unitCounts = new HashMap<>();
        if (basis == Basis.UNITS) {
            for (Object[] row : units.countByProperty(tenantId)) {
                unitCounts.put(row[0].toString(), BigDecimal.valueOf(((Number) row[1]).longValue()));
            }
        }
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        for (String k : everyProperty) {
            weights.put(k, switch (basis) {
                case UNITS -> unitCounts.getOrDefault(k, BigDecimal.ZERO);
                case RENT -> rent.getOrDefault(k, BigDecimal.ZERO);
                case EQUAL, NONE -> BigDecimal.ONE;
            });
        }
        boolean anyWeight = weights.values().stream().anyMatch(w -> w.signum() > 0);
        Basis used = anyWeight ? basis : Basis.EQUAL;
        Map<String, BigDecimal> everyShare = PnlAllocation.largestRemainder(unassignedCost, weights);
        Map<String, BigDecimal> allocated = new LinkedHashMap<>();
        Map<String, BigDecimal> after = new LinkedHashMap<>();
        for (String k : shownKeys) {
            BigDecimal share = everyShare.getOrDefault(k, BigDecimal.ZERO.setScale(2));
            allocated.put(k, share);
            after.put(k, money(noiNow.getOrDefault(k, BigDecimal.ZERO)).subtract(share));
        }
        BigDecimal shown = allocated.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Allocation(basis.name(), used.name(), unassignedCost, allocated, after,
                unassignedCost.subtract(shown));
    }

    /**
     * The displayed Total's NOI against the ledger's own net movement over the
     * same scope (every property, or the selected ones plus Unassigned), computed
     * by a separate query. A fold, sign or bucketing error in the columns shows
     * as a difference here.
     */
    static Check checkOf(BigDecimal ledgerNet, BigDecimal displayedTotal) {
        BigDecimal diff = ledgerNet.subtract(displayedTotal);
        return new Check(ledgerNet, displayedTotal, diff, diff.signum() == 0);
    }

    // ------------------------------------------------------------------ chart helpers

    Map<UUID, Account> accountsById(UUID tenantId) {
        return accounts.findAll().stream()
                .filter(a -> tenantId.equals(a.getTenantId()))
                .collect(Collectors.toMap(Account::getId, a -> a));
    }

    /**
     * The level-2 group of the tree ("Direct Income" under "Income"). A leaf hung
     * directly under a root groups under the root; a root leaf is its own group.
     */
    static Account levelTwo(Account a, Map<UUID, Account> byId) {
        List<Account> chain = new ArrayList<>();
        Account cur = a;
        int guard = 0;
        while (cur != null && guard++ < 50) {
            chain.addFirst(cur);
            UUID parentId = cur.getParentId();
            cur = parentId == null ? null : byId.get(parentId);
        }
        if (chain.size() >= 3) return chain.get(1);
        return chain.getFirst();
    }

    // ------------------------------------------------------------------ guards

    static UUID requireTenant() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new BusinessRuleViolationException("Choose an organisation first");
        return tenantId;
    }

    static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null) throw new BusinessRuleViolationException("'from' and 'to' are required");
        if (to.isBefore(from)) throw new BusinessRuleViolationException("'to' must not be before 'from'");
        if (from.plusYears(5).isBefore(to)) throw new BusinessRuleViolationException("A report covers at most five years");
    }
}
