package com.datagami.rentaxis.core.service.report.grid;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Renders a report grid as one PACT-layout xlsx sheet (spec §1.2).
 *
 * <ul>
 *   <li>Row 1 is a merged title band: organisation, title, period and filter lines, "Generated … by …".</li>
 *   <li>Row 2 holds the headers (autofilter, frozen below, repeated as the print title); data starts on row 3.</li>
 *   <li>Bands are merged across every column; Sub Total and REPORT TOTAL merge their label up to the
 *       first totalled column; a short blank merged row closes the sheet.</li>
 *   <li>Money, balances, integers and dates are real numbers and dates with PACT's display formats; the
 *       balance reads {@code 12,345.00Dr} but stays a summable number. Identifiers are text cells.</li>
 *   <li>Data never becomes a formula or a hyperlink: every string goes in as a string cell, cleaned by
 *       {@link ReportFormat#cellText}, and one starting with {@code = + - @} gets a quote-prefixed style
 *       (Excel's own leading apostrophe) so editing the cell in Excel keeps it text.</li>
 * </ul>
 *
 * Every cell style is made once in {@link #begin}: SXSSF shares styles across the workbook and Excel
 * caps a workbook at 64,000 of them. The sheet streams through compressed temp files, which
 * {@link #bytes()} and {@link #close()} always delete. One sink renders one report; use it in a
 * try-with-resources block.
 */
public final class XlsxGridSink implements ReportSink, AutoCloseable {

    /** Money and percent cells. */
    public static final String MONEY_FORMAT = "#,##0.00";

    /** The running balance: PACT's {@code 12,345.00Dr} / {@code 8,000.00Cr} / {@code 0.00Dr}, still a number. */
    public static final String BALANCE_FORMAT = "#,##0.00\"Dr\";#,##0.00\"Cr\";0.00\"Dr\"";

    private static final String INT_FORMAT = "0";
    private static final String TEXT_FORMAT = "@";

    private static final String ACCOUNT_FILL = "D2691E";
    private static final String ACCOUNT_FONT = "F0F8FF";
    private static final String PARTY_FILL = "FFFF00";
    private static final String GROUP_FILL = "7F7F7F";
    private static final String HIGHLIGHT_FILL = "FAC08F";
    private static final String BLACK = "000000";

    private static final short TITLE_PT = 12;
    private static final short ACCOUNT_PT = 14;
    private static final short PARTY_PT = 13;

    private static final int ROW_WINDOW = 200;
    private static final int MAX_WIDTH_CHARS = 255;
    private static final float TITLE_MIN_HEIGHT = 64f;
    private static final float TITLE_LINE_HEIGHT = 15.75f;
    private static final float LAST_ROW_HEIGHT = 11.25f;

    private static final Logger log = LoggerFactory.getLogger(XlsxGridSink.class);

    private final XlsxStyle style;
    private final SXSSFWorkbook workbook;

    private SXSSFSheet sheet;
    private List<ReportColumn> columns;
    private int lastCol;
    private int balanceCol = -1;
    private int firstTotalCol;
    private int nextRow;
    private Styles styles;
    private boolean ended;
    private boolean released;
    private byte[] bytes;

    /** A text-bearing style and its quote-prefixed twin, for text that starts like a formula. */
    private record TextStyle(CellStyle plain, CellStyle quoted) {
        CellStyle forText(String s) {
            return needsQuote(s) ? quoted : plain;
        }
    }

    /** The data-row styles of one {@link RowStyle}. */
    private record DataStyles(TextStyle text, TextStyle id, CellStyle money, CellStyle balance, CellStyle integer,
                              Map<String, CellStyle> dates) {
    }

    /** The styles of an opening, Sub Total or REPORT TOTAL row. */
    private record TotalStyles(TextStyle label, CellStyle blank, CellStyle money, CellStyle balance, CellStyle integer) {
    }

    private record Styles(TextStyle title, TextStyle header, Map<Band, TextStyle> bands, Map<RowStyle, DataStyles> data,
                          TotalStyles sub, TotalStyles total) {
    }

    public XlsxGridSink(XlsxStyle style) {
        this.style = Objects.requireNonNull(style, "style");
        this.workbook = new SXSSFWorkbook(null, ROW_WINDOW, true);
    }

    /**
     * The finished workbook; only after {@link #end}. The first call writes it and deletes the temp
     * files; later calls return the same bytes.
     */
    public byte[] bytes() {
        if (bytes != null) {
            return bytes;
        }
        if (!ended) {
            throw new IllegalStateException("The report has not ended yet");
        }
        if (released) {
            throw new IllegalStateException("The workbook was closed before it was written");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            workbook.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the xlsx report", e);
        } finally {
            release();
        }
        bytes = out.toByteArray();
        return bytes;
    }

    /** Deletes the temp files and closes the workbook, unless {@link #bytes()} already did. */
    @Override
    public void close() {
        release();
    }

    // --- ReportSink --------------------------------------------------------------------------------

    @Override
    public void begin(GridHeader header) {
        if (sheet != null) {
            throw new IllegalStateException("begin() was already called");
        }
        columns = header.columns();
        lastCol = columns.size() - 1;
        firstTotalCol = columns.size();
        for (int i = 0; i < columns.size(); i++) {
            ReportColumn c = columns.get(i);
            if (c.kind() == Kind.BALANCE) {
                balanceCol = i;
            }
            if (c.totalRule() != TotalRule.NONE && firstTotalCol == columns.size()) {
                firstTotalCol = i;
            }
        }
        styles = makeStyles();

        sheet = workbook.createSheet(ReportFormat.sheetName(header.title()));
        sheet.setRightToLeft("ar".equals(header.lang()));
        for (int i = 0; i < columns.size(); i++) {
            ReportColumn c = columns.get(i);
            sheet.setColumnWidth(i, Math.min(c.widthChars(), MAX_WIDTH_CHARS) * 256);
            if (c.hidden()) {
                sheet.setColumnHidden(i, true);
            }
        }

        List<String> titleLines = Stream.concat(
                        Stream.concat(Stream.of(header.orgName(), header.title()), header.metaLines().stream()),
                        Stream.of(header.generatedLine()))
                .filter(s -> s != null && !s.isBlank())
                .toList();
        Row title = newRow();
        title.setHeightInPoints(Math.max(TITLE_MIN_HEIGHT, titleLines.size() * TITLE_LINE_HEIGHT));
        fill(title, styles.title().plain());
        text(title.getCell(0), titleLines.stream().collect(Collectors.joining("\n")), styles.title());
        merge(title.getRowNum(), 0, lastCol);

        Row head = newRow();
        for (int i = 0; i < columns.size(); i++) {
            text(head.createCell(i), header.columnLabels().get(i), styles.header());
        }
        sheet.setAutoFilter(new CellRangeAddress(1, 1, 0, lastCol));
        sheet.createFreezePane(0, 2);
        sheet.setRepeatingRows(new CellRangeAddress(1, 1, -1, -1));
        PrintSetup print = sheet.getPrintSetup();
        print.setLandscape(true);
        print.setFitWidth((short) 1);
        print.setFitHeight((short) 0);
        sheet.setFitToPage(true);
    }

    @Override
    public void band(int level, Band band, String label, boolean continued) {
        TextStyle ts = styles.bands().get(band);
        Row r = newRow();
        r.setHeightInPoints(bandHeight(band));
        fill(r, ts.plain());
        text(r.getCell(0), label, ts);
        merge(r.getRowNum(), 0, lastCol);
    }

    @Override
    public void opening(int level, String label, BigDecimal balance, boolean carriedForward) {
        TotalStyles ts = styles.sub();
        Row r = newRow();
        fill(r, ts.blank());
        if (balanceCol > 0) {
            text(r.getCell(0), label, ts.label());
            merge(r.getRowNum(), 0, balanceCol - 1);
        }
        if (balanceCol >= 0) {
            number(r.getCell(balanceCol), balance, ts.balance());
        }
    }

    @Override
    public void row(int level, Map<String, Object> cells, RowStyle rowStyle, Map<String, UUID> refs) {
        DataStyles ds = styles.data().get(rowStyle);
        Row r = newRow();
        for (int i = 0; i < columns.size(); i++) {
            ReportColumn c = columns.get(i);
            dataCell(r.createCell(i), c, cells.get(c.key()), ds);
        }
    }

    @Override
    public void subtotal(int level, String label, Map<String, Object> totals) {
        totalsRow(label, totals, styles.sub());
    }

    @Override
    public void total(String label, Map<String, Object> totals) {
        totalsRow(label, totals, styles.total());
    }

    @Override
    public void end(boolean truncated, boolean continuesOnNextPage) {
        Row last = newRow();
        last.setHeightInPoints(LAST_ROW_HEIGHT);
        merge(last.getRowNum(), 0, lastCol);
        ended = true;
    }

    // --- rows and cells ----------------------------------------------------------------------------

    private Row newRow() {
        if (sheet == null) {
            throw new IllegalStateException("begin() has not been called");
        }
        if (ended) {
            throw new IllegalStateException("The report has already ended");
        }
        return sheet.createRow(nextRow++);
    }

    /** Creates every cell of the row with {@code style}, so borders and fills run across merged spans. */
    private void fill(Row r, CellStyle cellStyle) {
        for (int i = 0; i <= lastCol; i++) {
            r.createCell(i).setCellStyle(cellStyle);
        }
    }

    private void totalsRow(String label, Map<String, Object> totals, TotalStyles ts) {
        Row r = newRow();
        fill(r, ts.blank());
        for (int i = 0; i < columns.size(); i++) {
            ReportColumn c = columns.get(i);
            Object v = totals.get(c.key());
            if (v instanceof Number n) {
                CellStyle cs = c.totalRule() == TotalRule.COUNT ? ts.integer() : switch (c.kind()) {
                    case MONEY, PERCENT -> ts.money();
                    case BALANCE -> ts.balance();
                    default -> ts.integer();
                };
                number(r.getCell(i), n, cs);
            }
        }
        if (firstTotalCol > 0) {
            text(r.getCell(0), label, ts.label());
            merge(r.getRowNum(), 0, firstTotalCol - 1);
        }
    }

    private void dataCell(Cell cell, ReportColumn c, Object v, DataStyles ds) {
        switch (c.kind()) {
            case TEXT -> textOrBlank(cell, v, ds.text());
            case ID_TEXT -> textOrBlank(cell, v, ds.id());
            case DATE -> {
                cell.setCellStyle(ds.dates().get(c.datePattern()));
                if (v instanceof LocalDate d) {
                    cell.setCellValue(d);
                }
            }
            case MONEY, PERCENT -> numberOrBlank(cell, v, ds.money());
            case BALANCE -> numberOrBlank(cell, v, ds.balance());
            case INT, SERIAL -> numberOrBlank(cell, v, ds.integer());
        }
    }

    private static void textOrBlank(Cell cell, Object v, TextStyle ts) {
        if (v == null) {
            cell.setCellStyle(ts.plain());
        } else {
            text(cell, v.toString(), ts);
        }
    }

    private static void numberOrBlank(Cell cell, Object v, CellStyle cellStyle) {
        if (v instanceof Number n) {
            number(cell, n, cellStyle);
        } else {
            cell.setCellStyle(cellStyle);
        }
    }

    /** A string cell, cleaned, with the quote-prefixed twin style when it starts like a formula. Never a formula. */
    private static void text(Cell cell, String raw, TextStyle ts) {
        String v = ReportFormat.cellText(raw);
        cell.setCellStyle(ts.forText(v));
        if (!v.isEmpty()) {
            cell.setCellValue(v);
        }
    }

    private static void number(Cell cell, Number n, CellStyle cellStyle) {
        cell.setCellStyle(cellStyle);
        cell.setCellValue(n.doubleValue());
    }

    private static boolean needsQuote(String s) {
        return !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0;
    }

    private void merge(int row, int firstCol, int lastColumn) {
        if (lastColumn > firstCol) {
            sheet.addMergedRegionUnsafe(new CellRangeAddress(row, row, firstCol, lastColumn));
        }
    }

    private float bandHeight(Band band) {
        int pt = switch (band) {
            case ACCOUNT -> ACCOUNT_PT;
            case PARTY -> PARTY_PT;
            case GROUP -> style.fontPt() + 2;
        };
        return Math.max(15f, pt + 5f);
    }

    private void release() {
        if (released) {
            return;
        }
        released = true;
        // Since POI 5.3 close() deletes the sheet temp files itself (it calls the now-deprecated dispose()).
        try {
            workbook.close();
        } catch (IOException e) {
            // Logged, not thrown: a cleanup failure must not mask the error (or the bytes) of the render itself.
            log.warn("Could not close an xlsx report workbook", e);
        }
    }

    // --- styles ------------------------------------------------------------------------------------

    private Styles makeStyles() {
        DataFormat formats = workbook.createDataFormat();
        XSSFFont titleFont = font(TITLE_PT, true, null);
        XSSFFont headerFont = font(style.fontPt(), true, style.headerFont());
        XSSFFont dataFont = font(style.fontPt(), false, null);
        XSSFFont boldFont = font(style.fontPt(), true, null);

        TextStyle title = textStyle(titleFont, null, false, null, VerticalAlignment.TOP, true, null);
        TextStyle header = textStyle(headerFont, style.headerFill(), true, HorizontalAlignment.CENTER,
                VerticalAlignment.CENTER, true, null);

        Map<Band, TextStyle> bands = new EnumMap<>(Band.class);
        bands.put(Band.ACCOUNT, textStyle(font(ACCOUNT_PT, true, ACCOUNT_FONT), ACCOUNT_FILL, true, null,
                VerticalAlignment.CENTER, false, null));
        bands.put(Band.PARTY, textStyle(font(PARTY_PT, false, BLACK), PARTY_FILL, true, null,
                VerticalAlignment.CENTER, false, null));
        bands.put(Band.GROUP, textStyle(font((short) (style.fontPt() + 2), true, BLACK), GROUP_FILL, true, null,
                VerticalAlignment.CENTER, false, null));

        Map<RowStyle, DataStyles> data = new EnumMap<>(RowStyle.class);
        for (RowStyle rs : RowStyle.values()) {
            String fill = rs == RowStyle.HIGHLIGHT ? HIGHLIGHT_FILL : null;
            Map<String, CellStyle> dates = new HashMap<>();
            for (String pattern : ReportFormat.DATE_PATTERNS) {
                dates.put(pattern, cellStyle(dataFont, fill, true, null, VerticalAlignment.CENTER, false,
                        formats.getFormat(pattern.toLowerCase(Locale.ROOT)), false));
            }
            data.put(rs, new DataStyles(
                    textStyle(dataFont, fill, true, null, VerticalAlignment.CENTER, false, null),
                    textStyle(dataFont, fill, true, null, VerticalAlignment.CENTER, false, formats.getFormat(TEXT_FORMAT)),
                    cellStyle(dataFont, fill, true, style.moneyAlign(), VerticalAlignment.CENTER, false,
                            formats.getFormat(MONEY_FORMAT), false),
                    cellStyle(dataFont, fill, true, style.moneyAlign(), VerticalAlignment.CENTER, false,
                            formats.getFormat(BALANCE_FORMAT), false),
                    cellStyle(dataFont, fill, true, null, VerticalAlignment.CENTER, false,
                            formats.getFormat(INT_FORMAT), false),
                    Map.copyOf(dates)));
        }

        return new Styles(title, header, bands, data, totalStyles(dataFont, formats), totalStyles(boldFont, formats));
    }

    private TotalStyles totalStyles(XSSFFont f, DataFormat formats) {
        return new TotalStyles(
                textStyle(f, null, true, null, VerticalAlignment.CENTER, false, null),
                cellStyle(f, null, true, null, VerticalAlignment.CENTER, false, null, false),
                cellStyle(f, null, true, style.moneyAlign(), VerticalAlignment.CENTER, false,
                        formats.getFormat(MONEY_FORMAT), false),
                cellStyle(f, null, true, style.moneyAlign(), VerticalAlignment.CENTER, false,
                        formats.getFormat(BALANCE_FORMAT), false),
                cellStyle(f, null, true, null, VerticalAlignment.CENTER, false, formats.getFormat(INT_FORMAT), false));
    }

    private TextStyle textStyle(XSSFFont f, String fill, boolean border, HorizontalAlignment align,
                                VerticalAlignment valign, boolean wrap, Short format) {
        return new TextStyle(cellStyle(f, fill, border, align, valign, wrap, format, false),
                cellStyle(f, fill, border, align, valign, wrap, format, true));
    }

    private XSSFCellStyle cellStyle(XSSFFont f, String fill, boolean border, HorizontalAlignment align,
                                    VerticalAlignment valign, boolean wrap, Short format, boolean quoted) {
        XSSFCellStyle s = (XSSFCellStyle) workbook.createCellStyle();
        s.setFont(f);
        if (fill != null) {
            s.setFillForegroundColor(color(fill));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (border) {
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
        }
        if (align != null) {
            s.setAlignment(align);
        }
        s.setVerticalAlignment(valign);
        s.setWrapText(wrap);
        if (format != null) {
            s.setDataFormat(format);
        }
        s.setQuotePrefixed(quoted);
        return s;
    }

    private XSSFFont font(short pt, boolean bold, String colour) {
        XSSFFont f = (XSSFFont) workbook.createFont();
        f.setFontName(style.fontName());
        f.setFontHeightInPoints(pt);
        f.setBold(bold);
        if (colour != null) {
            f.setColor(color(colour));
        }
        return f;
    }

    /** An opaque colour from six hex digits, stored as Excel writes it ({@code FF} alpha first). */
    private static XSSFColor color(String hex) {
        return new XSSFColor(HexFormat.of().parseHex("FF" + hex), null);
    }
}
