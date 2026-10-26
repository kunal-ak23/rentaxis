package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.core.service.report.ReportPdf;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Renders a report grid as a PDF through {@link ReportPdf} (spec §1.3): one table of the visible
 * columns under the organisation, title, period/filter lines and "Generated … by …" line. The header
 * repeats on every page, every page is numbered, and a grid of more than nine columns prints landscape.
 *
 * <ul>
 *   <li>Bands span the table in the Excel band colours; an opening row prints its label in the first
 *       column and the balance in the BALANCE column; HIGHLIGHT rows keep their fill.</li>
 *   <li>Dates use the column's PACT pattern, money {@code #,##0.00}, balances PACT's
 *       {@code 12,345.00Dr} / {@code 8,000.00Cr}; digits are Latin in both languages.</li>
 *   <li>User-typed text is cleaned by {@link ReportFormat#cellText} (no control or bidi characters)
 *       and escaped by {@link ReportPdf}.</li>
 * </ul>
 *
 * The sink only collects rows; {@link #bytes()} renders once the report has ended. One sink renders one report.
 */
public final class PdfGridSink implements ReportSink {

    private GridHeader header;
    private List<ReportColumn> visible;
    private int balanceIndex = -1;
    private final List<ReportPdf.Row> rows = new ArrayList<>();
    private ReportPdf.Doc doc;
    private byte[] bytes;

    /** The finished PDF; only after {@link #end}. The first call renders it; later calls return the same bytes. */
    public byte[] bytes() {
        if (bytes == null) {
            bytes = ReportPdf.render(doc());
        }
        return bytes;
    }

    /** The document {@link #bytes()} renders; only after {@link #end}. */
    ReportPdf.Doc doc() {
        if (doc == null) {
            throw new IllegalStateException("The report has not ended yet");
        }
        return doc;
    }

    @Override
    public void begin(GridHeader header) {
        if (this.header != null) {
            throw new IllegalStateException("begin() was already called");
        }
        this.header = header;
        List<ReportColumn> cols = new ArrayList<>();
        for (ReportColumn c : header.columns()) {
            if (!c.hidden()) {
                if (c.kind() == Kind.BALANCE) {
                    balanceIndex = cols.size();
                }
                cols.add(c);
            }
        }
        visible = List.copyOf(cols);
    }

    @Override
    public void band(int level, Band band, String label, boolean continued) {
        ReportPdf.Style style = switch (band) {
            case ACCOUNT -> ReportPdf.Style.BAND_ACCOUNT;
            case PARTY -> ReportPdf.Style.BAND_PARTY;
            case GROUP -> ReportPdf.Style.BAND_GROUP;
        };
        add(style, List.of(ReportFormat.cellText(label)));
    }

    @Override
    public void opening(int level, String label, BigDecimal balance, boolean carriedForward) {
        String[] cells = blankLine();
        if (cells.length > 0) {
            cells[0] = ReportFormat.cellText(label);
        }
        if (balanceIndex >= 0) {
            cells[balanceIndex] = ReportFormat.drCr(balance);
        }
        add(ReportPdf.Style.OPENING, Arrays.asList(cells));
    }

    @Override
    public void row(int level, Map<String, Object> cells, RowStyle style, Map<String, UUID> refs) {
        String[] out = blankLine();
        for (int i = 0; i < visible.size(); i++) {
            ReportColumn c = visible.get(i);
            out[i] = format(c, cells.get(c.key()));
        }
        add(style == RowStyle.HIGHLIGHT ? ReportPdf.Style.HIGHLIGHT : ReportPdf.Style.PLAIN, Arrays.asList(out));
    }

    @Override
    public void subtotal(int level, String label, Map<String, Object> totals) {
        add(ReportPdf.Style.STRONG, totalsLine(label, totals));
    }

    @Override
    public void total(String label, Map<String, Object> totals) {
        add(ReportPdf.Style.TOTAL, totalsLine(label, totals));
    }

    @Override
    public void end(boolean truncated, boolean continuesOnNextPage) {
        requireBegun();
        if (doc != null) {
            throw new IllegalStateException("The report has already ended");
        }
        List<String> header = new ArrayList<>();
        List<Boolean> numeric = new ArrayList<>();
        for (int i = 0; i < this.header.columns().size(); i++) {
            ReportColumn c = this.header.columns().get(i);
            if (!c.hidden()) {
                header.add(this.header.columnLabels().get(i));
                numeric.add(isNumeric(c.kind()));
            }
        }
        List<String> meta = new ArrayList<>(this.header.metaLines());
        if (this.header.generatedLine() != null && !this.header.generatedLine().isBlank()) {
            meta.add(this.header.generatedLine());
        }
        ReportPdf.Table table = new ReportPdf.Table(null, List.copyOf(header), List.copyOf(numeric), List.copyOf(rows));
        doc = new ReportPdf.Doc(this.header.lang(), this.header.title(), List.copyOf(meta), List.of(table), List.of(),
                false, this.header.orgName());
    }

    // ---------------------------------------------------------------------------------------------

    private void add(ReportPdf.Style style, List<String> cells) {
        requireBegun();
        if (doc != null) {
            throw new IllegalStateException("The report has already ended");
        }
        rows.add(ReportPdf.Row.of(style, List.copyOf(cells)));
    }

    /** A Sub Total or REPORT TOTAL line: the totals in their columns, the label in the first column when that is free. */
    private List<String> totalsLine(String label, Map<String, Object> totals) {
        String[] out = blankLine();
        for (int i = 0; i < visible.size(); i++) {
            ReportColumn c = visible.get(i);
            out[i] = format(c, totals.get(c.key())); // a COUNT is a Long, printed as is
        }
        if (out.length > 0 && out[0].isEmpty()) {
            out[0] = ReportFormat.cellText(label);
        }
        return Arrays.asList(out);
    }

    private String[] blankLine() {
        requireBegun();
        String[] cells = new String[visible.size()];
        Arrays.fill(cells, "");
        return cells;
    }

    private static String format(ReportColumn c, Object v) {
        if (v == null) {
            return "";
        }
        return switch (c.kind()) {
            case DATE -> v instanceof LocalDate d ? ReportFormat.day(d, c.datePattern()) : v.toString();
            case MONEY, PERCENT -> v instanceof BigDecimal b ? ReportFormat.money(b) : v.toString();
            case BALANCE -> v instanceof BigDecimal b ? ReportFormat.drCr(b) : v.toString();
            case INT, SERIAL -> v instanceof BigDecimal b ? b.toPlainString() : v.toString();
            case TEXT, ID_TEXT -> ReportFormat.cellText(v.toString());
        };
    }

    private static boolean isNumeric(Kind k) {
        return k == Kind.MONEY || k == Kind.BALANCE || k == Kind.INT || k == Kind.SERIAL || k == Kind.PERCENT;
    }

    private void requireBegun() {
        if (header == null) {
            throw new IllegalStateException("begin() has not been called");
        }
    }
}
