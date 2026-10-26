package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.core.service.report.statement.ReportCsv;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Renders a report grid as CSV (spec §1.1): UTF-8 with a BOM, through {@link ReportCsv#encode} and its
 * formula guard. The first line holds the visible column labels, with a "Dr/Cr" column right after
 * each BALANCE column; every line has the same width.
 *
 * <ul>
 *   <li>Money is a plain decimal ({@code 60000.00}, no thousands separator) so a spreadsheet reads a
 *       number; the balance is signed ({@code -8000.00}) with {@code Dr} (zero or more) or {@code Cr} beside it.</li>
 *   <li>Dates use the column's PACT pattern; band, opening, Sub Total and REPORT TOTAL labels sit in
 *       the first column.</li>
 *   <li>Text loses control and bidi characters ({@link ReportFormat#cellText}) before the guard, so a
 *       mark in front of {@code =} cannot hide a formula from it.</li>
 * </ul>
 *
 * One sink renders one report. A truncated report never reaches a file: {@link GridRenderer} refuses a writer
 * that dropped rows (a 422).
 */
public final class CsvGridSink implements ReportSink {

    private final ReportLabels.Bound labels;

    private List<ReportColumn> visible;
    /** Where each visible column lands on a line. */
    private int[] position;
    private int width;
    private final List<List<String>> lines = new ArrayList<>();
    private boolean begun;
    private boolean ended;
    private byte[] bytes;

    /** {@code labels}: the report's labels, for the "Dr/Cr" header. */
    public CsvGridSink(ReportLabels.Bound labels) {
        this.labels = Objects.requireNonNull(labels, "labels");
    }

    /** The finished CSV; only after {@link #end}. */
    public byte[] bytes() {
        if (!ended) {
            throw new IllegalStateException("The report has not ended yet");
        }
        if (bytes == null) {
            bytes = ReportCsv.encode(lines, true);
        }
        return bytes;
    }

    @Override
    public void begin(GridHeader header) {
        if (begun) {
            throw new IllegalStateException("begin() was already called");
        }
        begun = true;
        List<ReportColumn> cols = new ArrayList<>();
        List<String> head = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < header.columns().size(); i++) {
            ReportColumn c = header.columns().get(i);
            if (c.hidden()) {
                continue;
            }
            cols.add(c);
            positions.add(head.size());
            head.add(ReportFormat.cellText(header.columnLabels().get(i)));
            if (c.kind() == Kind.BALANCE) {
                head.add(labels.get("report.drCr"));
            }
        }
        visible = List.copyOf(cols);
        position = positions.stream().mapToInt(Integer::intValue).toArray();
        width = head.size();
        add(head);
    }

    @Override
    public void band(int level, Band band, String label, boolean continued) {
        String[] line = blankLine();
        if (width > 0) {
            line[0] = ReportFormat.cellText(label);
        }
        add(Arrays.asList(line));
    }

    @Override
    public void opening(int level, String label, BigDecimal balance, boolean carriedForward) {
        String[] line = blankLine();
        if (width > 0) {
            line[0] = ReportFormat.cellText(label);
        }
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i).kind() == Kind.BALANCE) {
                putBalance(line, position[i], balance);
            }
        }
        add(Arrays.asList(line));
    }

    @Override
    public void row(int level, Map<String, Object> cells, RowStyle style, Map<String, UUID> refs) {
        add(Arrays.asList(values(cells)));
    }

    @Override
    public void subtotal(int level, String label, Map<String, Object> totals) {
        add(totalsLine(label, totals));
    }

    @Override
    public void total(String label, Map<String, Object> totals) {
        add(totalsLine(label, totals));
    }

    /** {@code truncated} is never printed: {@link GridRenderer} refuses a truncated report before any bytes exist. */
    @Override
    public void end(boolean truncated, boolean continuesOnNextPage) {
        requireOpen();
        ended = true;
    }

    // ---------------------------------------------------------------------------------------------

    /** A line with each visible column's value in its place (and a balance's Dr/Cr beside it). */
    private String[] values(Map<String, Object> cells) {
        String[] line = blankLine();
        for (int i = 0; i < visible.size(); i++) {
            ReportColumn c = visible.get(i);
            Object v = cells.get(c.key());
            if (v == null) {
                continue;
            }
            if (c.kind() == Kind.BALANCE && v instanceof BigDecimal b) {
                putBalance(line, position[i], b);
            } else {
                line[position[i]] = format(c, v);
            }
        }
        return line;
    }

    /** A Sub Total or REPORT TOTAL line: the totals in their columns, the label in the first column when that is free. */
    private List<String> totalsLine(String label, Map<String, Object> totals) {
        String[] line = values(totals);
        if (width > 0 && line[0].isEmpty()) {
            line[0] = ReportFormat.cellText(label);
        }
        return Arrays.asList(line);
    }

    private static void putBalance(String[] line, int at, BigDecimal balance) {
        if (balance == null) {
            return;
        }
        BigDecimal b = balance.setScale(2, RoundingMode.HALF_UP);
        line[at] = b.toPlainString();
        line[at + 1] = b.signum() >= 0 ? "Dr" : "Cr";
    }

    private static String format(ReportColumn c, Object v) {
        return switch (c.kind()) {
            case DATE -> v instanceof LocalDate d ? ReportFormat.day(d, c.datePattern()) : v.toString();
            case MONEY -> v instanceof BigDecimal b ? b.setScale(2, RoundingMode.HALF_UP).toPlainString() : v.toString();
            case PERCENT, INT, SERIAL, BALANCE -> v instanceof BigDecimal b ? b.toPlainString() : v.toString();
            case TEXT, ID_TEXT -> ReportFormat.cellText(v.toString());
        };
    }

    private String[] blankLine() {
        requireOpen();
        String[] line = new String[width];
        Arrays.fill(line, "");
        return line;
    }

    private void add(List<String> line) {
        requireOpen();
        lines.add(List.copyOf(line));
    }

    private void requireOpen() {
        if (!begun) {
            throw new IllegalStateException("begin() has not been called");
        }
        if (ended) {
            throw new IllegalStateException("The report has already ended");
        }
    }
}
