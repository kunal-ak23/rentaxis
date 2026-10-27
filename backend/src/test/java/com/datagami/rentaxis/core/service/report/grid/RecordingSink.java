package com.datagami.rentaxis.core.service.report.grid;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A {@link ReportSink} that remembers every event, for tests of the writer and of code that feeds it.
 *
 * <ul>
 *   <li>{@link #bands()}: {@code "<level>|<band>|<label>|<continued>"}</li>
 *   <li>{@link #openings()}: {@code "<level>|<label>|<balance without trailing zeros>|<carriedForward>"}</li>
 *   <li>{@link #rows()}, {@link #rowBalances()}, {@link #rowLevels()}, {@link #rowStyles()}, {@link #rowRefs()}</li>
 *   <li>{@link #subtotal(int)} / {@link #subtotalLabel(int)}: the first subtotal at that level, or {@code null}</li>
 *   <li>{@link #total()}, {@link #totalLabel()}, and the {@code end} flags</li>
 * </ul>
 */
public final class RecordingSink implements ReportSink {

    /** One subtotal event. */
    public record Subtotal(int level, String label, Map<String, Object> totals) {}

    private GridHeader header;
    private final List<String> events = new ArrayList<>();
    private final List<String> bands = new ArrayList<>();
    private final List<String> openings = new ArrayList<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final List<Integer> rowLevels = new ArrayList<>();
    private final List<RowStyle> rowStyles = new ArrayList<>();
    private final List<Map<String, UUID>> rowRefs = new ArrayList<>();
    private final List<Subtotal> subtotals = new ArrayList<>();
    private String totalLabel;
    private Map<String, Object> total;
    private boolean ended;
    private boolean endTruncated;
    private boolean endContinues;

    @Override
    public void begin(GridHeader header) {
        if (!events.isEmpty()) {
            throw new AssertionError("begin after " + events);
        }
        check("begin");
        this.header = header;
    }

    @Override
    public void band(int level, Band band, String label, boolean continued) {
        check("band");
        bands.add(level + "|" + band + "|" + label + "|" + continued);
    }

    @Override
    public void opening(int level, String label, BigDecimal balance, boolean carriedForward) {
        check("opening");
        openings.add(level + "|" + label + "|" + balance.stripTrailingZeros().toPlainString() + "|" + carriedForward);
    }

    @Override
    public void row(int level, Map<String, Object> cells, RowStyle style, Map<String, UUID> refs) {
        check("row");
        rows.add(cells);
        rowLevels.add(level);
        rowStyles.add(style);
        rowRefs.add(refs);
    }

    @Override
    public void subtotal(int level, String label, Map<String, Object> totals) {
        check("subtotal");
        subtotals.add(new Subtotal(level, label, totals));
    }

    @Override
    public void total(String label, Map<String, Object> totals) {
        check("total");
        this.totalLabel = label;
        this.total = totals;
    }

    @Override
    public void end(boolean truncated, boolean continuesOnNextPage) {
        check("end");
        this.ended = true;
        this.endTruncated = truncated;
        this.endContinues = continuesOnNextPage;
    }

    private void check(String event) {
        if (ended) {
            throw new AssertionError(event + " after end");
        }
        if (!event.equals("begin") && header == null) {
            throw new AssertionError(event + " before begin");
        }
        events.add(event);
    }

    public GridHeader header() {
        return header;
    }

    /** The event names in order: begin, band, opening, row, subtotal, total, end. */
    public List<String> events() {
        return events;
    }

    public List<String> bands() {
        return bands;
    }

    public List<String> openings() {
        return openings;
    }

    public List<Map<String, Object>> rows() {
        return rows;
    }

    public List<Integer> rowLevels() {
        return rowLevels;
    }

    public List<RowStyle> rowStyles() {
        return rowStyles;
    }

    public List<Map<String, UUID>> rowRefs() {
        return rowRefs;
    }

    /** The BALANCE cell ({@code "balance"}) of each row. */
    public List<BigDecimal> rowBalances() {
        return rows.stream().map(r -> (BigDecimal) r.get("balance")).toList();
    }

    public List<Subtotal> subtotals() {
        return subtotals;
    }

    /** The totals of the first subtotal at {@code level}, or {@code null} when there is none. */
    public Map<String, Object> subtotal(int level) {
        return subtotals.stream().filter(s -> s.level() == level).findFirst().map(Subtotal::totals).orElse(null);
    }

    /** The label of the first subtotal at {@code level}, or {@code null} when there is none. */
    public String subtotalLabel(int level) {
        return subtotals.stream().filter(s -> s.level() == level).findFirst().map(Subtotal::label).orElse(null);
    }

    public Map<String, Object> total() {
        return total;
    }

    public String totalLabel() {
        return totalLabel;
    }

    public boolean ended() {
        return ended;
    }

    public boolean endTruncated() {
        return endTruncated;
    }

    public boolean endContinues() {
        return endContinues;
    }
}
