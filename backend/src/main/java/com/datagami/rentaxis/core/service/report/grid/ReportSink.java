package com.datagami.rentaxis.core.service.report.grid;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * One output format of a report grid. A sink only renders: every sum, running balance and
 * subtotal arrives already computed from {@link ReportWriter}, so the formats cannot disagree.
 *
 * <p>Events arrive in this order: {@code begin}, then any mix of {@code band}, {@code opening},
 * {@code row} and {@code subtotal}, then exactly one {@code total} and one {@code end}.
 * {@code level} is the group depth (1 for the outermost group; 0 for rows and openings outside
 * any group). Money and balance values are {@link BigDecimal}s at scale 2; maps are read-only and
 * keyed by column key, in column order, with blank cells left out.
 */
public interface ReportSink {

    /** The resolved title band and column labels. */
    void begin(GridHeader header);

    /** A group's heading row; {@code continued} when the group began on an earlier page. */
    void band(int level, Band band, String label, boolean continued);

    /** The brought-forward row, or (with {@code carriedForward}) the balance carried from an earlier page. */
    void opening(int level, String label, BigDecimal balance, boolean carriedForward);

    /** A data row with the writer's BALANCE and SERIAL cells filled in; {@code refs} are drill-down ids. */
    void row(int level, Map<String, Object> cells, RowStyle style, Map<String, UUID> refs);

    /** A group's Sub Total: SUM columns, COUNT columns (a {@link Long}) and the BALANCE column's closing balance. */
    void subtotal(int level, String label, Map<String, Object> totals);

    /** The REPORT TOTAL, with the same cells as a subtotal. */
    void total(String label, Map<String, Object> totals);

    /**
     * The last event: {@code truncated} when rows were dropped at the row cap, {@code continuesOnNextPage} when a
     * group stays open. Only the screen's JSON can report {@code truncated}: {@link GridRenderer} refuses a
     * truncated file with a 422, so a file sink's output is never short.
     */
    void end(boolean truncated, boolean continuesOnNextPage);
}
