package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportPdfTest {

    @Test
    void headersRepeatPagesAreNumberedAndBandsSpanTheTable() {
        List<String> header = IntStream.range(0, 10).mapToObj(i -> "H" + i).toList();
        var t = new ReportPdf.Table(null, header, Collections.nCopies(10, false), List.of(
                ReportPdf.Row.of(ReportPdf.Style.BAND_ACCOUNT, List.of("<b>Rent</b>")),
                ReportPdf.Row.of(ReportPdf.Style.PLAIN, header)));
        String html = ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(t), List.of(), false, "Sample Org", true));
        assertThat(html).contains("<thead>").contains("-fs-table-paginate: paginate").contains("counter(pages)")
                .contains("size: A4 landscape").contains("<td colspan=\"10\">&lt;b&gt;Rent&lt;/b&gt;</td>").contains("Sample Org");
        assertThat(ReportPdf.html(new ReportPdf.Doc("ar", "T", List.of(), List.of(), List.of(), false))).contains("صفحة");
    }

    @Test
    void theHeaderSitsInTheadAndTheRowsInTbody() {
        var t = new ReportPdf.Table(null, List.of("A", "B"), List.of(false, true), List.of(
                ReportPdf.Row.of(ReportPdf.Style.PLAIN, List.of("x", "1.00"))));
        String html = ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(t), List.of(), false));
        assertThat(html).contains("<table><thead><tr><th>A</th><th class=\"num\">B</th></tr></thead><tbody>"
                + "<tr class=\"PLAIN\"><td>x</td><td class=\"num\"><span>1.00</span></td></tr></tbody></table>");
    }

    @Test
    void theFooterIsARunningElementSoArabicIsShaped() {
        // Generated margin-box text skips the bidi reorderer and shaper (صفحة printed reversed and
        // unjoined); a running element is laid out like body text.
        String en = ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(), List.of(), false));
        assertThat(en).contains("@bottom-center { content: element(pageFooter); }")
                .contains("#pageFooter { position: running(pageFooter);")
                .contains("<body><div id=\"pageFooter\">Page <span class=\"pg\"></span> / <span class=\"pgs\"></span></div>");
        String ar = ReportPdf.html(new ReportPdf.Doc("ar", "T", List.of(), List.of(), List.of(), false));
        assertThat(ar).contains("<div id=\"pageFooter\">صفحة <span class=\"pg\"></span> / <span class=\"pgs\"></span></div>");
    }

    @Test
    void anOldCallerStaysPortraitWithoutAnOrgLine() {
        var t = new ReportPdf.Table("Caption", List.of("A", "B", "C"), List.of(false, false, true), List.of());
        String html = ReportPdf.html(new ReportPdf.Doc("en", "T", List.of("m"), List.of(t), List.of("n"), false));
        assertThat(html).contains("size: A4;").doesNotContain("landscape").doesNotContain("class=\"org\"");
        assertThat(new ReportPdf.Doc("en", "T", List.of(), List.of(), List.of(), true).orgName()).isNull();
        assertThat(new ReportPdf.Doc("en", "T", List.of(), List.of(), List.of(), true).autoLandscape()).isFalse();
        assertThat(new ReportPdf.Doc("en", "T", List.of(), List.of(), List.of(), true, "Org").autoLandscape()).isFalse();
        var nine = new ReportPdf.Table(null, Collections.nCopies(9, "H"), Collections.nCopies(9, false), List.of());
        var ten = new ReportPdf.Table(null, Collections.nCopies(10, "H"), Collections.nCopies(10, false), List.of());
        // An old caller gets the orientation it asks for, whatever its column count.
        assertThat(ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(ten), List.of(), false)))
                .contains("size: A4;").doesNotContain("landscape");
        assertThat(ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(nine), List.of(), true)))
                .contains("size: A4 landscape");
        // A document that opts in (the grid reports): nine columns stay portrait, the tenth turns the page.
        assertThat(ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(nine), List.of(), false, null, true)))
                .contains("size: A4;");
        assertThat(ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(ten), List.of(), false, null, true)))
                .contains("size: A4 landscape");
    }

    @Test
    void aTenColumnPropertyPnlKeepsItsPortraitPage() throws IOException {
        // Two properties and the Total with a prior period: Line + 3 × (amount, prior, change) = 10 columns, which
        // FinancialReportExport prints portrait (it asks for landscape only above three property columns).
        List<PropertyPnlDTO.Column> columns = List.of(
                new PropertyPnlDTO.Column(UUID.randomUUID().toString(), null, "PROPERTY", "Sample Tower", null),
                new PropertyPnlDTO.Column(UUID.randomUUID().toString(), null, "PROPERTY", "Sample Court", null),
                new PropertyPnlDTO.Column(PropertyPnlDTO.TOTAL, null, "TOTAL", "Total", null));
        Map<String, PropertyPnlDTO.Amount> amounts = new LinkedHashMap<>();
        columns.forEach(c -> amounts.put(c.key(), new PropertyPnlDTO.Amount(new BigDecimal("100.00"),
                new BigDecimal("90.00"), new BigDecimal("10.00"), null)));
        PropertyPnlDTO pnl = new PropertyPnlDTO(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "PRIOR_PERIOD",
                LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31), false, columns, List.of(), amounts, amounts,
                amounts, null, null, null);
        try (PDDocument doc = Loader.loadPDF(FinancialReportExport.pnlPdf(pnl, "en", false))) {
            PDRectangle page = doc.getPage(0).getMediaBox();
            assertThat(page.getHeight()).isGreaterThan(page.getWidth());
        }
    }

    @Test
    void aLabelSpansItsLeadingColumnsInsteadOfWideningTheFirst() {
        var t = new ReportPdf.Table(null, List.of("Doc Date", "Particular", "Debit", "Credit", "Balance"),
                List.of(false, false, true, true, true), List.of(
                ReportPdf.Row.labelled(ReportPdf.Style.OPENING, List.of("Balance brought forward", "", "", "", "0.00Dr"), 4),
                ReportPdf.Row.labelled(ReportPdf.Style.STRONG, List.of("Sub Total", "", "10.00", "", "10.00Dr"), 2),
                ReportPdf.Row.labelled(ReportPdf.Style.TOTAL, List.of("REPORT TOTAL", "x", "y", "z", "w"), 9),
                ReportPdf.Row.of(ReportPdf.Style.PLAIN, List.of("16-09-2026", "Rent", "10.00", "", "10.00Dr"))));
        String html = ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(t), List.of(), false));
        assertThat(html)
                .contains("<tr class=\"OPENING\"><td colspan=\"4\">Balance brought forward</td>"
                        + "<td class=\"num\"><span>0.00Dr</span></td></tr>")
                .contains("<tr class=\"STRONG\"><td colspan=\"2\">Sub Total</td><td class=\"num\"><span>10.00</span></td>"
                        + "<td class=\"num\"><span></span></td><td class=\"num\"><span>10.00Dr</span></td></tr>")
                // a span wider than the table stops at its edge
                .contains("<tr class=\"TOTAL\"><td colspan=\"5\">REPORT TOTAL</td></tr>")
                .contains("<tr class=\"PLAIN\"><td>16-09-2026</td><td>Rent</td>");
        assertThat(ReportPdf.Row.of(ReportPdf.Style.PLAIN, List.of("x")).labelSpan()).isEqualTo(1);
        assertThat(new ReportPdf.Row(List.of("x"), ReportPdf.Style.PLAIN).labelSpan()).isEqualTo(1);
        assertThatThrownBy(() -> ReportPdf.Row.labelled(ReportPdf.Style.PLAIN, List.of("x"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A ledger-shaped table: two text columns, three amounts, one more text column. */
    private static ReportPdf.Table ledgerTable(List<String> header) {
        return new ReportPdf.Table(null, header, List.of(false, false, true, true, true, false), List.of(
                ReportPdf.Row.of(ReportPdf.Style.BAND_PARTY, List.of("Tenant A")),
                ReportPdf.Row.labelled(ReportPdf.Style.OPENING, List.of("Opening", "", "", "", "0.00Dr", ""), 4),
                ReportPdf.Row.of(ReportPdf.Style.PLAIN, List.of("16-09-2026", "Rent", "10.00", "", "10.00Dr", "TCO-26/1")),
                ReportPdf.Row.labelled(ReportPdf.Style.STRONG, List.of("Sub Total", "", "10.00", "", "10.00Dr", ""), 2),
                ReportPdf.Row.labelled(ReportPdf.Style.TOTAL, List.of("REPORT TOTAL", "x", "y", "z", "w", "v"), 9)));
    }

    @Test
    void arabicTablesRunRightToLeft() {
        // openhtmltopdf lays table columns out left to right whatever the direction, so an Arabic table
        // emits its cells last column first: the first logical column is the rightmost on the page.
        var t = ledgerTable(List.of("Doc Date", "Particular", "Debit", "Credit", "Balance", "Doc No"));
        String html = ReportPdf.html(new ReportPdf.Doc("ar", "T", List.of(), List.of(t), List.of(), false));
        assertThat(html)
                .contains("<table><thead><tr><th>Doc No</th><th class=\"num\">Balance</th><th class=\"num\">Credit</th>"
                        + "<th class=\"num\">Debit</th><th>Particular</th><th>Doc Date</th></tr></thead>")
                // a band spans the table as before
                .contains("<tr class=\"BAND_PARTY\"><td colspan=\"6\">Tenant A</td></tr>")
                .contains("<tr class=\"PLAIN\"><td>TCO-26/1</td><td class=\"num\"><span>10.00Dr</span></td>"
                        + "<td class=\"num\"><span></span></td><td class=\"num\"><span>10.00</span></td>"
                        + "<td>Rent</td><td>16-09-2026</td></tr>")
                // a label spanning the leading columns is the rightmost cell: the others come first, reversed
                .contains("<tr class=\"OPENING\"><td></td><td class=\"num\"><span>0.00Dr</span></td>"
                        + "<td colspan=\"4\">Opening</td></tr>")
                .contains("<tr class=\"STRONG\"><td></td><td class=\"num\"><span>10.00Dr</span></td>"
                        + "<td class=\"num\"><span></span></td><td class=\"num\"><span>10.00</span></td>"
                        + "<td colspan=\"2\">Sub Total</td></tr>")
                .contains("<tr class=\"TOTAL\"><td colspan=\"6\">REPORT TOTAL</td></tr>")
                // amounts keep their LTR spans and sit at the cell's far side from the text
                .contains(".num span { direction: ltr; unicode-bidi: embed; }")
                .contains("text-align: right; }").contains("td.num, th.num { text-align: left; }");
    }

    @Test
    void englishTablesKeepTheirLogicalOrder() {
        var t = ledgerTable(List.of("Doc Date", "Particular", "Debit", "Credit", "Balance", "Doc No"));
        String html = ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(t), List.of(), false));
        assertThat(html)
                .contains("<table><thead><tr><th>Doc Date</th><th>Particular</th><th class=\"num\">Debit</th>"
                        + "<th class=\"num\">Credit</th><th class=\"num\">Balance</th><th>Doc No</th></tr></thead>")
                .contains("<tr class=\"BAND_PARTY\"><td colspan=\"6\">Tenant A</td></tr>")
                .contains("<tr class=\"PLAIN\"><td>16-09-2026</td><td>Rent</td><td class=\"num\"><span>10.00</span></td>"
                        + "<td class=\"num\"><span></span></td><td class=\"num\"><span>10.00Dr</span></td>"
                        + "<td>TCO-26/1</td></tr>")
                .contains("<tr class=\"OPENING\"><td colspan=\"4\">Opening</td>"
                        + "<td class=\"num\"><span>0.00Dr</span></td><td></td></tr>")
                .contains("<tr class=\"STRONG\"><td colspan=\"2\">Sub Total</td><td class=\"num\"><span>10.00</span></td>"
                        + "<td class=\"num\"><span></span></td><td class=\"num\"><span>10.00Dr</span></td><td></td></tr>")
                .contains("<tr class=\"TOTAL\"><td colspan=\"6\">REPORT TOTAL</td></tr>")
                .contains("text-align: left; }").contains("td.num, th.num { text-align: right; }");
    }

    @Test
    void anArabicPdfPrintsTheFirstColumnRightmost() throws IOException {
        // Latin markers in an Arabic document: the layout follows the document's direction, not the text's.
        var t = new ReportPdf.Table(null, List.of("COLFIRST", "COLTEXT", "COLDEBIT", "COLLAST"),
                List.of(false, false, true, false), List.of(
                ReportPdf.Row.of(ReportPdf.Style.PLAIN, List.of("16-09-2026", "Rent", "48,000.00", "TCO-26/1")),
                ReportPdf.Row.labelled(ReportPdf.Style.STRONG, List.of("SUBTOTALLABEL", "", "66,000.00", ""), 2)));
        Map<String, Float> ar = xPositions(ReportPdf.render(
                new ReportPdf.Doc("ar", "T", List.of(), List.of(t), List.of(), false)));
        assertThat(ar.get("COLFIRST")).isGreaterThan(ar.get("COLTEXT"));
        assertThat(ar.get("COLTEXT")).isGreaterThan(ar.get("COLDEBIT"));
        assertThat(ar.get("COLDEBIT")).isGreaterThan(ar.get("COLLAST"));
        // the Sub Total label spans the two rightmost columns, clear of the Debit figure on its left
        assertThat(ar.get("SUBTOTALLABEL")).isGreaterThan(ar.get("66,000.00"));
        assertThat(ar.get("16-09-2026")).isGreaterThan(ar.get("48,000.00"));

        Map<String, Float> en = xPositions(ReportPdf.render(
                new ReportPdf.Doc("en", "T", List.of(), List.of(t), List.of(), false)));
        assertThat(en.get("COLFIRST")).isLessThan(en.get("COLTEXT"));
        assertThat(en.get("COLDEBIT")).isLessThan(en.get("COLLAST"));
        assertThat(en.get("SUBTOTALLABEL")).isLessThan(en.get("66,000.00"));
    }

    /** The left edge of each word drawn on page 1, by its text. */
    private static Map<String, Float> xPositions(byte[] pdf) throws IOException {
        Map<String, Float> x = new LinkedHashMap<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper s = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) {
                    StringBuilder word = new StringBuilder();
                    float left = Float.MAX_VALUE;
                    for (TextPosition p : positions) {
                        String u = p.getUnicode();
                        if (u == null || u.isBlank()) {
                            if (!word.isEmpty()) x.putIfAbsent(word.toString(), left);
                            word.setLength(0);
                            left = Float.MAX_VALUE;
                            continue;
                        }
                        word.append(u);
                        left = Math.min(left, p.getXDirAdj());
                    }
                    if (!word.isEmpty()) x.putIfAbsent(word.toString(), left);
                }
            };
            s.setSortByPosition(true);
            s.setStartPage(1);
            s.setEndPage(1);
            s.getText(doc);
        }
        return x;
    }

    @Test
    void theOrgLineSitsAboveTheTitleAndIsEscaped() {
        String html = ReportPdf.html(new ReportPdf.Doc("en", "Tenant Ledger", List.of(), List.of(), List.of(), false,
                "Sample & <Org>"));
        assertThat(html).contains("<div class=\"org\">Sample &amp; &lt;Org&gt;</div><h1>Tenant Ledger</h1>");
    }

    @Test
    void escDropsWhatXmlForbidsAndKeepsTabsAndNewlines() {
        assertThat(ReportPdf.esc("A\u0000\u0001\u0007\u0008\u000B\u000C\u000E\u001F\uFFFE\uFFFFb&<c>"))
                .isEqualTo("Ab&amp;&lt;c&gt;");
        assertThat(ReportPdf.esc("a\tb\nc\rd")).isEqualTo("a\tb\nc\rd");
        assertThat(ReportPdf.esc(null)).isEmpty();
    }

    @Test
    void controlCharactersInEveryTextSlotStillRender() {
        // An old caller (VAT, balance sheet, P&L) with a control character in an account name or meta line.
        var t = new ReportPdf.Table("Cap\u0002tion", List.of("A\u0003", "B"), List.of(false, true), List.of(
                ReportPdf.Row.of(ReportPdf.Style.PLAIN, List.of("Rent\u0007 Income", "1.00")),
                ReportPdf.Row.of(ReportPdf.Style.BAND_PARTY, List.of("Tenant\u0001 A"))));
        byte[] pdf = ReportPdf.render(new ReportPdf.Doc("en", "T\u0004", List.of("Sample\u0007 Tower"), List.of(t),
                List.of("n\u0005"), false, "Sample\u0001 Org"));
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void everyBandOpeningAndHighlightRowHasItsStyle() {
        var t = new ReportPdf.Table(null, List.of("A", "B", "C"), List.of(false, false, true), List.of(
                ReportPdf.Row.of(ReportPdf.Style.BAND_PARTY, List.of("Tenant A")),
                ReportPdf.Row.of(ReportPdf.Style.BAND_GROUP, List.of()),
                ReportPdf.Row.of(ReportPdf.Style.OPENING, List.of("Balance brought forward", "", "0.00Dr")),
                ReportPdf.Row.of(ReportPdf.Style.HIGHLIGHT, List.of("x", "y", "1.00"))));
        String html = ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(t), List.of(), false));
        assertThat(html)
                .contains("tr.BAND_ACCOUNT td {background:#D2691E;color:#F0F8FF;font-weight:bold}")
                .contains("tr.BAND_PARTY td {background:#FFFF00}")
                // black on grey, as the Excel GROUP band
                .contains("tr.BAND_GROUP td {background:#7F7F7F;color:#000000;font-weight:bold}")
                .contains("tr.HIGHLIGHT td {background:#FAC08F}")
                .contains("tr.OPENING td {font-style:italic}")
                .contains("<tr class=\"BAND_PARTY\"><td colspan=\"3\">Tenant A</td></tr>")
                .contains("<tr class=\"BAND_GROUP\"><td colspan=\"3\"></td></tr>")
                .contains("<tr class=\"OPENING\"><td>Balance brought forward</td>")
                .contains("<tr class=\"HIGHLIGHT\"><td>x</td>");
    }
}
