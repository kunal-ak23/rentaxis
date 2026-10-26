package com.datagami.rentaxis.core.service.report.grid;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Does every sum, running balance and subtotal of a grouped report in {@link BigDecimal} and
 * forwards the results to one {@link ReportSink}, so the screen and every file format print the
 * same figures.
 *
 * <p>A report feeds {@code begin}, then groups ({@code openGroup}/{@code continueGroup} …
 * {@code closeGroup}, nested by level), each with an optional {@code opening} and its {@code row}s,
 * then {@code end}. Rules:
 * <ul>
 *   <li>Every money and balance figure the writer emits (MONEY cells, running balances, openings,
 *       subtotals, totals) is rounded half-up to cents, so totals are the sums of the printed rows.</li>
 *   <li>The opening is never added to the Debit/Credit sums; it only starts the running balance.</li>
 *   <li>A group closes at {@code opening + Σ (debit − credit) of its rows + Σ closings of its child groups}.
 *       What a subtotal prints is what rolls into the parent group and the REPORT TOTAL.</li>
 *   <li>Authoritative totals (from a SQL aggregate) are checked against the writer's own whenever the
 *       group (or the whole report) was written whole and nothing was truncated, and are what is printed.</li>
 *   <li>Rows past {@code rowCap} are dropped and the report is marked truncated ({@link #truncated()}); a
 *       file is never written short: {@link GridRenderer} refuses it with a 422.</li>
 * </ul>
 * A writer renders one report and is not thread-safe.
 */
public final class ReportWriter {

    private static final String DAY = "dd/MM/yyyy";

    private final ReportSink sink;
    private final ReportLabels.Bound labels;
    private final int rowCap;

    private List<ReportColumn> columns;
    private Map<String, ReportColumn> byKey;
    private ReportColumn balanceColumn;
    private List<ReportColumn> sumColumns;
    private boolean hasCountColumn;

    private final Deque<Frame> frames = new ArrayDeque<>();
    private boolean partial;
    private boolean begun;
    private boolean ended;
    private boolean truncated;
    private long rowsWritten;
    private long rowsDropped;

    /** One open group, or (at the bottom of the stack, level 0) the report itself. */
    private static final class Frame {
        final int level;
        final String label;
        final boolean whole;
        final Map<String, BigDecimal> sums = new HashMap<>();
        BigDecimal opening;
        BigDecimal running = BigDecimal.ZERO;
        BigDecimal childClosings = BigDecimal.ZERO;
        long count;
        int serial;
        boolean hasRows;

        Frame(int level, String label, boolean whole) {
            this.level = level;
            this.label = label;
            this.whole = whole;
        }
    }

    public ReportWriter(ReportSink sink, ReportLabels.Bound labels, int rowCap) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.labels = Objects.requireNonNull(labels, "labels");
        if (rowCap < 0) {
            throw new IllegalArgumentException("The row cap cannot be negative: " + rowCap);
        }
        this.rowCap = rowCap;
    }

    /**
     * This writer is feeding one page of a longer report: groups may still be open at {@code end()}
     * (the page stops inside them), and the REPORT TOTAL is not checked against authoritative totals.
     */
    public void markPartial() {
        if (ended) {
            throw new IllegalStateException("The report has already ended");
        }
        partial = true;
    }

    /** Resolves the header in the report language and starts the sink. */
    public void begin(ReportMeta meta, List<ReportColumn> columns) {
        if (begun) {
            throw new IllegalStateException("begin() was already called");
        }
        Objects.requireNonNull(meta, "meta");
        indexColumns(columns);
        begun = true;
        frames.push(new Frame(0, labels.get("report.reportTotal"), true));
        sink.begin(header(meta));
    }

    /** Opens a group whose band, opening and rows all start in this feed. {@code level} must be deeper than the open group's. */
    public void openGroup(int level, Band band, String label) {
        push(level, band, label, true);
        sink.band(level, band, label, false);
    }

    /**
     * Re-opens a group that began on an earlier page: its band reads "&lt;label&gt; (continued)" and,
     * when {@code carriedForward} is not {@code null}, a "Balance carried forward" row starts its
     * running balance at the last balance the earlier page showed. Such a group's own totals are
     * never checked against authoritative totals.
     */
    public void continueGroup(int level, Band band, String label, BigDecimal carriedForward) {
        Frame f = push(level, band, label, false);
        sink.band(level, band, labels.get("report.continued", label), true);
        if (carriedForward != null) {
            requireBalanceColumn("a carried-forward balance");
            BigDecimal cf = cents(carriedForward);
            f.opening = cf;
            f.running = cf;
            sink.opening(level, labels.get("report.carriedForward"), cf, true);
        }
    }

    /** The brought-forward row of the innermost group ({@code null} is zero). It must come before the group's rows. */
    public void opening(BigDecimal balance) {
        requireOpen();
        requireBalanceColumn("an opening");
        Frame f = frames.peek();
        if (f.opening != null || f.hasRows) {
            throw new IllegalStateException("The opening of " + f.label + " must come once, before its rows");
        }
        BigDecimal b = cents(balance == null ? BigDecimal.ZERO : balance);
        f.opening = b;
        f.running = b;
        sink.opening(f.level, labels.get("report.broughtForward"), b, false);
    }

    /**
     * A data row of the innermost group. {@code cells} is keyed by column key; the writer fills the
     * BALANCE and SERIAL cells itself. A {@code null} value is a blank cell (and zero in a sum).
     */
    public void row(Map<String, Object> cells, RowStyle style, Map<String, UUID> refs) {
        requireOpen();
        Objects.requireNonNull(style, "style");
        Map<String, Object> given = checkedCells(cells);
        if (rowsWritten >= rowCap) {
            truncated = true;
            rowsDropped++;
            return;
        }
        Frame f = frames.peek();
        f.hasRows = true;
        f.count++;
        f.serial++;
        for (ReportColumn c : sumColumns) {
            f.sums.merge(c.key(), number(given.get(c.key())), BigDecimal::add);
        }
        if (balanceColumn != null) {
            f.running = f.running.add(number(given.get(balanceColumn.debitKey())))
                    .subtract(number(given.get(balanceColumn.creditKey())));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (ReportColumn c : columns) {
            Object v = switch (c.kind()) {
                case BALANCE -> cents(f.running);
                case SERIAL -> f.serial;
                default -> given.get(c.key());
            };
            if (v != null) {
                out.put(c.key(), v);
            }
        }
        rowsWritten++;
        sink.row(f.level, Collections.unmodifiableMap(out), style, refs(refs));
    }

    /** Closes the innermost group, printing a Sub Total row labelled {@code subtotalLabel} ({@code null}: no row, totals still roll up). */
    public void closeGroup(String subtotalLabel) {
        close(subtotalLabel, null);
    }

    /**
     * Closes the innermost group with totals from a SQL aggregate. When the group was opened by
     * {@link #openGroup} and no row was truncated, every SUM column and the closing balance (and the
     * count, when the grid has a COUNT column) must equal the writer's own, else
     * {@link IllegalStateException}. The authoritative totals are the ones printed and rolled up.
     */
    public void closeGroup(String subtotalLabel, GroupTotals authoritative) {
        close(subtotalLabel, Objects.requireNonNull(authoritative, "authoritative"));
    }

    /** Prints the REPORT TOTAL and ends the sink. Open groups are an error unless {@link #markPartial()} was called. */
    public void end() {
        finish(null);
    }

    /** {@link #end()} with authoritative totals, checked like {@link #closeGroup(String, GroupTotals)} unless partial or truncated. */
    public void end(GroupTotals authoritative) {
        finish(Objects.requireNonNull(authoritative, "authoritative"));
    }

    /** Whether rows were dropped at the row cap. */
    public boolean truncated() {
        return truncated;
    }

    /** The rows passed to the sink. */
    public long rowsWritten() {
        return rowsWritten;
    }

    /** The rows dropped at the row cap: {@code rowsWritten() + rowsDropped()} is every row the report fed. */
    public long rowsDropped() {
        return rowsDropped;
    }

    // ---------------------------------------------------------------------------------------------

    private void indexColumns(List<ReportColumn> cols) {
        if (cols == null || cols.isEmpty()) {
            throw new IllegalArgumentException("A report needs at least one column");
        }
        Map<String, ReportColumn> index = new LinkedHashMap<>();
        ReportColumn balance = null;
        for (ReportColumn c : cols) {
            if (index.put(c.key(), c) != null) {
                throw new IllegalArgumentException("Two columns share the key " + c.key());
            }
            if (c.kind() == Kind.BALANCE) {
                if (balance != null) {
                    throw new IllegalArgumentException("A report has at most one BALANCE column");
                }
                balance = c;
            }
        }
        if (balance != null) {
            for (String k : List.of(balance.debitKey(), balance.creditKey())) {
                ReportColumn m = index.get(k);
                if (m == null || m.kind() != Kind.MONEY) {
                    throw new IllegalArgumentException("Balance column " + balance.key() + " names " + k
                            + ", which is not a MONEY column");
                }
            }
        }
        this.columns = List.copyOf(cols);
        this.byKey = index;
        this.balanceColumn = balance;
        this.sumColumns = cols.stream().filter(c -> c.totalRule() == TotalRule.SUM).toList();
        this.hasCountColumn = cols.stream().anyMatch(c -> c.totalRule() == TotalRule.COUNT);
    }

    private GridHeader header(ReportMeta meta) {
        List<String> lines = new ArrayList<>();
        if (meta.from() != null || meta.to() != null) {
            lines.add(labels.get("report.period", ReportFormat.day(meta.from(), DAY), ReportFormat.day(meta.to(), DAY)));
        } else if (meta.asOf() != null) {
            lines.add(labels.get("report.asOf", ReportFormat.day(meta.asOf(), DAY)));
        }
        if (!meta.filters().isEmpty()) {
            lines.add(meta.filters().stream()
                    .map(f -> labels.get(f.labelKey()) + ": " + (f.value() == null ? "" : f.value()))
                    .collect(Collectors.joining(" · ")));
        }
        String by = meta.generatedBy() == null || meta.generatedBy().isBlank() ? "—" : meta.generatedBy();
        String generated = labels.get("report.generated", ReportFormat.stamp(meta.generatedAt()), by);
        List<String> columnLabels = columns.stream().map(c -> labels.get(c.labelKey())).toList();
        return new GridHeader(meta.reportKey(), labels.get(meta.titleKey()), meta.orgName(), lines, generated,
                columns, columnLabels, labels.lang());
    }

    private Frame push(int level, Band band, String label, boolean whole) {
        requireOpen();
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(label, "label");
        Frame parent = frames.peek();
        if (level <= parent.level) {
            throw new IllegalStateException("Group " + label + " at level " + level
                    + " must be deeper than the open level " + parent.level);
        }
        Frame f = new Frame(level, label, whole);
        frames.push(f);
        return f;
    }

    private void close(String subtotalLabel, GroupTotals authoritative) {
        requireOpen();
        if (frames.size() < 2) {
            throw new IllegalStateException("There is no open group to close");
        }
        Frame f = frames.pop();
        GroupTotals printed = reconcile(f.label, f.whole, computed(f), authoritative);
        if (subtotalLabel != null) {
            sink.subtotal(f.level, subtotalLabel, cells(printed));
        }
        Frame parent = frames.peek();
        printed.sums().forEach((k, v) -> parent.sums.merge(k, v, BigDecimal::add));
        parent.count += printed.count();
        parent.childClosings = parent.childClosings.add(printed.closing());
    }

    private void finish(GroupTotals authoritative) {
        requireOpen();
        boolean continues = false;
        if (frames.size() > 1) {
            if (!partial) {
                List<String> open = new ArrayList<>();
                frames.forEach(f -> {
                    if (f.level > 0) {
                        open.add(f.label);
                    }
                });
                throw new IllegalStateException("end() with groups still open: " + open);
            }
            continues = true;
            while (frames.size() > 1) {
                frames.pop();
            }
        }
        Frame report = frames.peek();
        GroupTotals printed = reconcile(report.label, !partial, computed(report), authoritative);
        sink.total(labels.get("report.reportTotal"), cells(printed));
        ended = true;
        sink.end(truncated, continues);
    }

    /** The frame's own totals: SUM columns (money at cents), row count and closing balance. */
    private GroupTotals computed(Frame f) {
        Map<String, BigDecimal> sums = new HashMap<>();
        for (ReportColumn c : sumColumns) {
            sums.put(c.key(), sumValue(c, f.sums.getOrDefault(c.key(), BigDecimal.ZERO)));
        }
        return new GroupTotals(sums, f.count, cents(f.running.add(f.childClosings)));
    }

    /** The totals to print: the computed ones, or the authoritative ones after checking them when the group is whole. */
    private GroupTotals reconcile(String label, boolean whole, GroupTotals computed, GroupTotals authoritative) {
        if (authoritative == null) {
            return computed;
        }
        Map<String, BigDecimal> sums = new HashMap<>();
        for (ReportColumn c : sumColumns) {
            BigDecimal sql = authoritative.sums().get(c.key());
            if (sql == null) {
                throw new IllegalArgumentException("The authoritative totals for " + label + " lack " + c.key());
            }
            sums.put(c.key(), sumValue(c, sql));
        }
        if (whole && !truncated) {
            for (ReportColumn c : sumColumns) {
                requireSame(label, c.key(), computed.sums().get(c.key()), sums.get(c.key()));
            }
            if (balanceColumn != null) {
                requireSame(label, balanceColumn.key(), computed.closing(), authoritative.closing());
            }
            if (hasCountColumn && computed.count() != authoritative.count()) {
                throw disagree(label, "count", BigDecimal.valueOf(computed.count()), BigDecimal.valueOf(authoritative.count()));
            }
        }
        long count = hasCountColumn ? authoritative.count() : computed.count();
        return new GroupTotals(sums, count, cents(authoritative.closing()));
    }

    private static void requireSame(String label, String key, BigDecimal computed, BigDecimal sql) {
        if (computed.compareTo(sql) != 0) {
            throw disagree(label, key, computed, sql);
        }
    }

    private static IllegalStateException disagree(String label, String key, BigDecimal computed, BigDecimal sql) {
        return new IllegalStateException("Report totals disagree for " + label + ": computed " + key + " "
                + computed.toPlainString() + ", SQL " + sql.toPlainString());
    }

    /** A subtotal's cells in column order: SUM columns, COUNT columns and the BALANCE column's closing. */
    private Map<String, Object> cells(GroupTotals t) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ReportColumn c : columns) {
            switch (c.totalRule()) {
                case SUM -> out.put(c.key(), t.sums().get(c.key()));
                case COUNT -> out.put(c.key(), t.count());
                case CLOSING_BALANCE -> out.put(c.key(), t.closing());
                case NONE -> { }
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** The row's given cells, checked against their columns; MONEY at cents; {@code null}s dropped. */
    private Map<String, Object> checkedCells(Map<String, Object> cells) {
        if (cells == null || cells.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new HashMap<>();
        for (Map.Entry<String, Object> e : cells.entrySet()) {
            ReportColumn c = byKey.get(e.getKey());
            if (c == null) {
                throw new IllegalArgumentException("No column has the key " + e.getKey());
            }
            if (c.kind() == Kind.BALANCE || c.kind() == Kind.SERIAL) {
                throw new IllegalArgumentException("The writer fills the " + c.kind() + " cell " + c.key());
            }
            Object v = e.getValue();
            if (v != null) {
                out.put(c.key(), checked(c, v));
            }
        }
        return out;
    }

    private static Object checked(ReportColumn c, Object v) {
        boolean ok = switch (c.kind()) {
            case TEXT, ID_TEXT -> v instanceof String;
            case DATE -> v instanceof LocalDate;
            case MONEY, PERCENT -> v instanceof BigDecimal;
            case INT -> v instanceof Integer || v instanceof Long;
            case BALANCE, SERIAL -> false;
        };
        if (!ok) {
            throw new IllegalArgumentException("Cell " + c.key() + " of a " + c.kind() + " column cannot hold a "
                    + v.getClass().getSimpleName());
        }
        return c.kind() == Kind.MONEY ? cents((BigDecimal) v) : v;
    }

    private static Map<String, UUID> refs(Map<String, UUID> refs) {
        if (refs == null || refs.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> out = new LinkedHashMap<>();
        refs.forEach((k, v) -> {
            if (k != null && v != null) {
                out.put(k, v);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** A summed cell as a number; blank is zero. */
    private static BigDecimal number(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        return BigDecimal.valueOf(((Number) v).longValue());
    }

    private static BigDecimal sumValue(ReportColumn c, BigDecimal v) {
        return c.kind() == Kind.MONEY ? cents(v) : v;
    }

    private static BigDecimal cents(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private void requireOpen() {
        if (!begun) {
            throw new IllegalStateException("begin() has not been called");
        }
        if (ended) {
            throw new IllegalStateException("The report has already ended");
        }
    }

    private void requireBalanceColumn(String what) {
        if (balanceColumn == null) {
            throw new IllegalStateException("This report has no BALANCE column for " + what);
        }
    }
}
