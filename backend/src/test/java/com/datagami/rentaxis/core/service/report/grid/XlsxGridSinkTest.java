package com.datagami.rentaxis.core.service.report.grid;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.util.DefaultTempFileCreationStrategy;
import org.apache.poi.util.TempFile;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XlsxGridSinkTest {

    private static final ReportLabels LABELS = new ReportLabels();

    @Test
    void titleBandHeadersAndFrame() {
        XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
        assertThat(s.getMergedRegions()).contains(CellRangeAddress.valueOf("A1:J1"));
        assertThat(s.getRow(0).getCell(0).getStringCellValue()).contains("Sample Org").contains("Tenant Ledger")
                .contains("Property: Sample Tower").contains("Generated 26/10/2026 10:05 by Test User");
        assertThat(headers(s)).containsExactly("Doc Date", "Particular", "Debit", "Credit", "Balance", "Chq No",
                "Payee Bank", "BDDays", "ConvertedDate", "Doc No");
        XSSFCellStyle h = s.getRow(1).getCell(0).getCellStyle();
        assertThat(h.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FFFFFFFF");
        assertThat(h.getFont().getXSSFColor().getARGBHex()).isEqualTo("FF000000");
        assertThat(h.getFont().getBold()).isTrue();
        assertThat(h.getAlignment()).isEqualTo(HorizontalAlignment.CENTER);
        assertThat(h.getWrapText()).isTrue();
        assertThat(s.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A2:J2");
        assertThat(s.getPaneInformation().getHorizontalSplitTopRow()).isEqualTo((short) 2);
        assertThat(s.getRepeatingRows()).isEqualTo(CellRangeAddress.valueOf("2:2"));
        assertThat(s.getPrintSetup().getLandscape()).isTrue();
        assertThat(s.getFitToPage()).isTrue();
        assertThat(s.getPrintSetup().getFitWidth()).isEqualTo((short) 1);
        assertThat(s.getPrintSetup().getFitHeight()).isEqualTo((short) 0);
        assertThat(s.getColumnWidth(1)).isEqualTo(36 * 256);
        assertThat(s.getWorkbook().getSheetName(0)).isEqualTo("Tenant Ledger");
        assertThat(s.isRightToLeft()).isFalse();
        assertThat(s.getRow(0).getHeightInPoints()).isEqualTo(5 * 15.75f); // org, title, period, filter, generated
    }

    @Test
    void theTitleBandGrowsForLinesThatWrap() {
        List<ReportFilter> filters = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            filters.add(new ReportFilter("filter.property", "Sample Tower Block " + i + " East Wing"));
        }
        ReportMeta many = new ReportMeta("sample", "tenantLedger.title", "Sample Org", filters,
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 30), null, SampleGrid.GENERATED_AT, "Test User", "en");
        XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, many, SampleGrid.COLUMNS, SampleGrid::feed);
        String filterLine = s.getRow(0).getCell(0).getStringCellValue().split("\n")[3];
        assertThat(filterLine).startsWith("Property: Sample Tower Block 1").endsWith("Block 8 East Wing");
        int visibleWidth = SampleGrid.COLUMNS.stream().mapToInt(ReportColumn::widthChars).sum();
        int filterLines = (int) Math.ceil((double) filterLine.length() / visibleWidth); // even at one unit a character
        assertThat(filterLines).isGreaterThanOrEqualTo(3);
        assertThat(s.getRow(0).getHeightInPoints()).isGreaterThanOrEqualTo((4 + filterLines) * 15.75f);

        // Hidden columns add no width: on a one-column sheet the 39-character generated line wraps too.
        List<ReportColumn> narrow = SampleGrid.COLUMNS.stream()
                .map(c -> c.key().equals("particular") ? c : c.hiddenColumn()).toList();
        XSSFSheet n = render(XlsxStyle.TENANT_LEDGER, "en", narrow, SampleGrid::feed);
        assertThat(n.getRow(0).getHeightInPoints()).isGreaterThanOrEqualTo(6 * 15.75f);
    }

    @Test
    void thePactProfileHasANavyHeader() {
        XSSFCellStyle h = render(XlsxStyle.PACT, "en").getRow(1).getCell(0).getCellStyle();
        assertThat(h.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FF000080");
        assertThat(h.getFont().getXSSFColor().getARGBHex()).isEqualTo("FFFFFFFF");
    }

    @Test
    void bandsAreMergedAndColoured() {
        XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
        assertThat(s.getMergedRegions()).contains(CellRangeAddress.valueOf("A3:J3"), CellRangeAddress.valueOf("A4:J4"));
        assertThat(s.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Rent Receivable - Sample Tower");
        XSSFCellStyle account = s.getRow(2).getCell(0).getCellStyle();
        assertThat(account.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FFD2691E");
        assertThat(account.getFont().getXSSFColor().getARGBHex()).isEqualTo("FFF0F8FF");
        assertThat(account.getFont().getFontHeightInPoints()).isEqualTo((short) 14);
        assertThat(account.getFont().getBold()).isTrue();
        XSSFCellStyle party = s.getRow(3).getCell(0).getCellStyle();
        assertThat(party.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FFFFFF00");
        assertThat(party.getFont().getFontHeightInPoints()).isEqualTo((short) 13);
        assertThat(party.getFont().getBold()).isFalse();
    }

    @Test
    void cellsAreRealNumbersDatesAndText() {
        XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
        Row opening = s.getRow(4), tco = s.getRow(5), pdr = s.getRow(6);
        assertThat(opening.getCell(0).getStringCellValue()).isEqualTo("Balance brought forward");
        assertThat(s.getMergedRegions()).contains(CellRangeAddress.valueOf("A5:D5"));
        assertThat(opening.getCell(4).getNumericCellValue()).isZero();
        assertThat(opening.getCell(4).getCellStyle().getDataFormatString())
                .isEqualTo("#,##0.00\"Dr\";#,##0.00\"Cr\";0.00\"Dr\"");
        assertThat(tco.getCell(0).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(tco.getCell(0).getCellStyle().getDataFormatString()).isEqualTo("dd-mm-yyyy");
        assertThat(tco.getCell(1).getStringCellValue()).isEqualTo("Advance Rent - Sample Tower");
        assertThat(tco.getCell(2).getNumericCellValue()).isEqualTo(60000.0);
        assertThat(tco.getCell(2).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
        assertThat(tco.getCell(2).getCellStyle().getAlignment()).isEqualTo(HorizontalAlignment.RIGHT);
        assertThat(tco.getCell(3) == null || tco.getCell(3).getCellType() == CellType.BLANK).isTrue();
        assertThat(tco.getCell(4).getNumericCellValue()).isEqualTo(60000.0);
        assertThat(pdr.getCell(3).getNumericCellValue()).isEqualTo(9500.0);
        assertThat(pdr.getCell(4).getNumericCellValue()).isEqualTo(50500.0);
        assertThat(pdr.getCell(4).getCellStyle().getDataFormatString())
                .isEqualTo("#,##0.00\"Dr\";#,##0.00\"Cr\";0.00\"Dr\"");
        assertThat(pdr.getCell(5).getCellType()).isEqualTo(CellType.STRING);
        assertThat(pdr.getCell(5).getStringCellValue()).isEqualTo("000201");
        assertThat(pdr.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("@");
        assertThat(pdr.getCell(6).getStringCellValue()).isEqualTo("Emirates NBD");
        assertThat(pdr.getCell(7).getNumericCellValue()).isEqualTo(1.0);
        assertThat(pdr.getCell(7).getCellStyle().getDataFormatString()).isEqualTo("0");
        assertThat(pdr.getCell(8).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(pdr.getCell(8).getCellStyle().getDataFormatString()).isEqualTo("dd/mm/yyyy");
        assertThat(pdr.getCell(9).getStringCellValue()).isEqualTo("PDR-26/1");
        XSSFCellStyle data = (XSSFCellStyle) tco.getCell(1).getCellStyle();
        assertThat(data.getFont().getFontName()).isEqualTo("Calibri");
        assertThat(data.getFont().getFontHeightInPoints()).isEqualTo((short) 9);
        assertThat(data.getBorderTop()).isEqualTo(BorderStyle.THIN);
        assertThat(data.getBorderBottom()).isEqualTo(BorderStyle.THIN);
    }

    @Test
    void aNegativeBalanceStaysANegativeNumber() {
        XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en", SampleGrid.COLUMNS, w -> {
            w.openGroup(1, Band.PARTY, "Tenant Name : Tenant B");
            w.opening(new BigDecimal("-8000"));
            w.row(SampleGrid.cells("credit", new BigDecimal("12.5")), RowStyle.PLAIN, Map.of());
            w.closeGroup("Sub Total");
            w.end();
        });
        assertThat(s.getRow(3).getCell(4).getNumericCellValue()).isEqualTo(-8000.0);
        assertThat(s.getRow(4).getCell(4).getNumericCellValue()).isEqualTo(-8012.5);
        assertThat(s.getRow(5).getCell(4).getNumericCellValue()).isEqualTo(-8012.5);
    }

    @Test
    void formulaLookingTextStaysAString() {
        XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
        Cell c = s.getRow(7).getCell(1);
        assertThat(c.getCellType()).isEqualTo(CellType.STRING);
        assertThat(c.getStringCellValue()).isEqualTo("=HYPERLINK(\"x\")");
        assertThat(c.getCellStyle().getQuotePrefixed()).isTrue();
        assertThat(s.getRow(5).getCell(1).getCellStyle().getQuotePrefixed()).isFalse();
        s.forEach(r -> r.forEach(cell -> assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA)));
        assertThat(s.getHyperlinkList()).isEmpty();
    }

    @Test
    void userTextIsCleanedAndQuotedInBandsAndCells() {
        XSSFSheet s = render(XlsxStyle.PACT, "en", SampleGrid.COLUMNS, w -> {
            w.openGroup(1, Band.GROUP, "=Block‏ A");
            w.row(SampleGrid.cells("particular", "@SUM(1)", "chqNo", "+000301", "payeeBank", "\u200F=Tenant",
                    "docNo", "\u200E-1"), RowStyle.HIGHLIGHT, Map.of());
            w.closeGroup("Sub Total");
            w.end();
        });
        Cell band = s.getRow(2).getCell(0);
        assertThat(band.getStringCellValue()).isEqualTo("=Block A");
        assertThat(band.getCellStyle().getQuotePrefixed()).isTrue();
        XSSFCellStyle group = (XSSFCellStyle) band.getCellStyle();
        assertThat(group.getFillForegroundXSSFColor().getARGBHex()).isEqualTo("FF7F7F7F");
        assertThat(group.getFont().getBold()).isTrue();
        assertThat(group.getFont().getFontHeightInPoints()).isEqualTo((short) 11);
        Row row = s.getRow(3);
        assertThat(row.getCell(1).getStringCellValue()).isEqualTo("@SUM(1)");
        assertThat(row.getCell(1).getCellStyle().getQuotePrefixed()).isTrue();
        assertThat(row.getCell(5).getStringCellValue()).isEqualTo("+000301");
        assertThat(row.getCell(5).getCellStyle().getQuotePrefixed()).isTrue();
        assertThat(row.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("@");
        // A leading bidi mark is stripped first, so the quote decision sees the "=" / "-" it hid.
        assertThat(row.getCell(6).getStringCellValue()).isEqualTo("=Tenant");
        assertThat(row.getCell(6).getCellStyle().getQuotePrefixed()).isTrue();
        assertThat(row.getCell(9).getStringCellValue()).isEqualTo("-1");
        assertThat(row.getCell(9).getCellStyle().getQuotePrefixed()).isTrue();
        assertThat(row.getCell(9).getCellStyle().getDataFormatString()).isEqualTo("@");
        s.forEach(r -> r.forEach(cell -> assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA)));
    }

    @Test
    void highlightedRowsAreFilledInEveryCell() {
        XSSFSheet s = render(XlsxStyle.PACT, "en", SampleGrid.COLUMNS, w -> {
            w.openGroup(1, Band.GROUP, "Block A");
            w.row(SampleGrid.cells("particular", "Vacant", "docDate", LocalDate.of(2026, 9, 1)), RowStyle.HIGHLIGHT,
                    Map.of());
            w.row(SampleGrid.cells("particular", "Let"), RowStyle.PLAIN, Map.of());
            w.closeGroup("Sub Total");
            w.end();
        });
        Row vacant = s.getRow(3);
        for (int i = 0; i < 10; i++) {
            XSSFCellStyle st = (XSSFCellStyle) vacant.getCell(i).getCellStyle();
            assertThat(st.getFillForegroundXSSFColor().getARGBHex()).as("column " + i).isEqualTo("FFFAC08F");
        }
        assertThat(vacant.getCell(0).getCellStyle().getDataFormatString()).isEqualTo("dd-mm-yyyy");
        assertThat(((XSSFCellStyle) s.getRow(4).getCell(1).getCellStyle()).getFillForegroundXSSFColor()).isNull();
    }

    @Test
    void totalsMergeTheLabelAndCloseWithABlankRow() {
        XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "en");
        // rows: 8 = Sub Total, 9 = REPORT TOTAL, 10 = blank
        assertThat(s.getMergedRegions()).contains(CellRangeAddress.valueOf("A9:B9"), CellRangeAddress.valueOf("A10:B10"),
                CellRangeAddress.valueOf("A11:J11"));
        assertThat(s.getRow(8).getCell(0).getStringCellValue()).isEqualTo("Sub Total");
        assertThat(s.getWorkbook().getFontAt(s.getRow(8).getCell(0).getCellStyle().getFontIndex()).getBold()).isFalse();
        assertThat(s.getRow(8).getCell(2).getNumericCellValue()).isEqualTo(60000.1);
        assertThat(s.getRow(8).getCell(3).getNumericCellValue()).isEqualTo(9500.0);
        assertThat(s.getRow(8).getCell(4).getNumericCellValue()).isEqualTo(50500.1);
        assertThat(s.getRow(8).getCell(4).getCellStyle().getDataFormatString())
                .isEqualTo("#,##0.00\"Dr\";#,##0.00\"Cr\";0.00\"Dr\"");
        assertThat(s.getRow(9).getCell(0).getStringCellValue()).isEqualTo("REPORT TOTAL");
        assertThat(s.getWorkbook().getFontAt(s.getRow(9).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
        assertThat(s.getRow(9).getCell(2).getNumericCellValue()).isEqualTo(60000.1);
        assertThat(s.getRow(9).getCell(2).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
        assertThat(s.getWorkbook().getFontAt(s.getRow(9).getCell(2).getCellStyle().getFontIndex()).getBold()).isTrue();
        assertThat(s.getRow(9).getCell(4).getNumericCellValue()).isEqualTo(50500.1);
        assertThat(s.getRow(10).getHeightInPoints()).isEqualTo(11.25f);
        assertThat(s.getLastRowNum()).isEqualTo(10);
    }

    @Test
    void aCountColumnPrintsItsCountOnTheTotals() {
        List<ReportColumn> cols = List.of(
                ReportColumn.serial("sn", "col.docNo", 6),
                ReportColumn.text("particular", "col.particular", 30),
                ReportColumn.integer("units", "col.bdDays", 8, TotalRule.COUNT),
                ReportColumn.money("debit", "col.debit", 14));
        XSSFSheet s = render(XlsxStyle.PACT, "en", cols, w -> {
            w.openGroup(1, Band.GROUP, "Block A");
            w.row(SampleGrid.cells("particular", "One", "units", 3, "debit", new BigDecimal("1")), RowStyle.PLAIN, Map.of());
            w.row(SampleGrid.cells("particular", "Two", "units", 4, "debit", new BigDecimal("2")), RowStyle.PLAIN, Map.of());
            w.closeGroup("Sub Total");
            w.end();
        });
        assertThat(s.getRow(3).getCell(0).getNumericCellValue()).isEqualTo(1.0);
        assertThat(s.getRow(4).getCell(0).getNumericCellValue()).isEqualTo(2.0);
        Row sub = s.getRow(5);
        assertThat(sub.getCell(0).getStringCellValue()).isEqualTo("Sub Total");
        assertThat(s.getMergedRegions()).contains(CellRangeAddress.valueOf("A6:B6"));
        assertThat(sub.getCell(2).getNumericCellValue()).isEqualTo(2.0);
        assertThat(sub.getCell(2).getCellStyle().getDataFormatString()).isEqualTo("0");
        assertThat(sub.getCell(3).getNumericCellValue()).isEqualTo(3.0);
    }

    @Test
    void arabicIsRightToLeftWithArabicHeaders() {
        XSSFSheet s = render(XlsxStyle.TENANT_LEDGER, "ar");
        assertThat(s.isRightToLeft()).isTrue();
        assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("تاريخ المستند");
        assertThat(s.getRow(0).getCell(0).getStringCellValue()).contains("دفتر أستاذ المستأجر");
        assertThat(s.getRow(9).getCell(0).getStringCellValue()).isEqualTo("إجمالي التقرير");
        assertThat(s.getRow(9).getCell(4).getNumericCellValue()).isEqualTo(50500.1);
    }

    @Test
    void hiddenColumnsAreHidden() {
        List<ReportColumn> cols = SampleGrid.COLUMNS.stream()
                .map(c -> c.key().equals("bdDays") ? c.hiddenColumn() : c).toList();
        XSSFSheet s = render(XlsxStyle.PACT, "en", cols, SampleGrid::feed);
        assertThat(s.isColumnHidden(7)).isTrue();
        assertThat(s.isColumnHidden(6)).isFalse();
        assertThat(s.getRow(6).getCell(7).getNumericCellValue()).isEqualTo(1.0);
    }

    @Test
    void anEmptyGridIsAValidWorkbook() {
        XSSFSheet s = render(XlsxStyle.PACT, "en", SampleGrid.COLUMNS, ReportWriter::end);
        assertThat(headers(s)).hasSize(10);
        Row total = s.getRow(2);
        assertThat(total.getCell(0).getStringCellValue()).isEqualTo("REPORT TOTAL");
        assertThat(total.getCell(2).getNumericCellValue()).isZero();
        assertThat(total.getCell(3).getNumericCellValue()).isZero();
        assertThat(total.getCell(4).getNumericCellValue()).isZero();
        assertThat(s.getMergedRegions()).contains(CellRangeAddress.valueOf("A4:J4"));
        assertThat(s.getLastRowNum()).isEqualTo(3);
    }

    @Test
    void stylesAreMadeOnceNotPerCell() {
        int small = render(XlsxStyle.PACT, "en").getWorkbook().getNumCellStyles();
        XSSFSheet big = render(XlsxStyle.PACT, "en", SampleGrid.COLUMNS, w -> {
            w.openGroup(1, Band.PARTY, "Tenant A");
            w.opening(BigDecimal.ONE);
            for (int i = 0; i < 1_000; i++) {
                w.row(SampleGrid.cells("docDate", LocalDate.of(2026, 9, 1).plusDays(i % 30), "particular", i % 2 == 0 ? "=x" : "x",
                        "debit", new BigDecimal(i), "chqNo", "0" + i, "bdDays", i,
                        "convertedDate", LocalDate.of(2026, 10, 1)), i % 3 == 0 ? RowStyle.HIGHLIGHT : RowStyle.PLAIN, Map.of());
            }
            w.closeGroup("Sub Total");
            w.end();
        });
        assertThat(big.getLastRowNum()).isEqualTo(2 + 1 + 1 + 1_000 + 1 + 1);
        assertThat(big.getWorkbook().getNumCellStyles()).isEqualTo(small);
    }

    @Test
    void bytesAreOnlyReadyAfterTheEnd() {
        try (XlsxGridSink sink = new XlsxGridSink(XlsxStyle.PACT)) {
            ReportWriter w = new ReportWriter(sink, LABELS.bind("en"), 50_000);
            w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
            assertThatThrownBy(sink::bytes).isInstanceOf(IllegalStateException.class);
            w.end();
            byte[] first = sink.bytes();
            assertThat(sink.bytes()).isEqualTo(first);
        }
    }

    @Test
    void leavesNoTempFilesBehind(@TempDir Path dir) throws IOException {
        TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy(dir.toFile()));
        try {
            render(XlsxStyle.PACT, "en");
            try (var files = Files.walk(dir)) {
                assertThat(files.filter(Files::isRegularFile)).isEmpty();
            }
        } finally {
            TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy());
        }
    }

    @Test
    void closingWithoutAnEndLeavesNoTempFilesBehind(@TempDir Path dir) throws IOException {
        TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy(dir.toFile()));
        try {
            try (XlsxGridSink sink = new XlsxGridSink(XlsxStyle.PACT)) {
                ReportWriter w = new ReportWriter(sink, LABELS.bind("en"), 50_000);
                w.begin(SampleGrid.meta("en"), SampleGrid.COLUMNS);
                w.openGroup(1, Band.PARTY, "Tenant A");
                try (var files = Files.walk(dir)) {
                    assertThat(files.filter(Files::isRegularFile)).isNotEmpty(); // SXSSF spools the sheet to a temp file
                }
            }
            try (var files = Files.walk(dir)) {
                assertThat(files.filter(Files::isRegularFile)).isEmpty();
            }
        } finally {
            TempFile.setTempFileCreationStrategy(new DefaultTempFileCreationStrategy());
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static XSSFSheet render(XlsxStyle style, String lang) {
        return render(style, lang, SampleGrid.COLUMNS, SampleGrid::feed);
    }

    private static XSSFSheet render(XlsxStyle style, String lang, List<ReportColumn> columns, Consumer<ReportWriter> feed) {
        return render(style, SampleGrid.meta(lang), columns, feed);
    }

    private static XSSFSheet render(XlsxStyle style, ReportMeta meta, List<ReportColumn> columns, Consumer<ReportWriter> feed) {
        byte[] bytes;
        try (XlsxGridSink sink = new XlsxGridSink(style)) {
            ReportWriter w = new ReportWriter(sink, LABELS.bind(meta.lang()), 50_000);
            w.begin(meta, columns);
            feed.accept(w);
            bytes = sink.bytes();
        }
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(bytes)).getSheetAt(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> headers(XSSFSheet s) {
        Row r = s.getRow(1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < r.getLastCellNum(); i++) {
            out.add(r.getCell(i).getStringCellValue());
        }
        return out;
    }
}
