package com.datagami.rentaxis.core.service.report;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReportPdfTest {

    @Test
    void headersRepeatPagesAreNumberedAndBandsSpanTheTable() {
        List<String> header = IntStream.range(0, 10).mapToObj(i -> "H" + i).toList();
        var t = new ReportPdf.Table(null, header, Collections.nCopies(10, false), List.of(
                ReportPdf.Row.of(ReportPdf.Style.BAND_ACCOUNT, List.of("<b>Rent</b>")),
                ReportPdf.Row.of(ReportPdf.Style.PLAIN, header)));
        String html = ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(t), List.of(), false, "Sample Org"));
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
        // Nine columns is still portrait; the tenth tips it to landscape.
        var nine = new ReportPdf.Table(null, Collections.nCopies(9, "H"), Collections.nCopies(9, false), List.of());
        assertThat(ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(nine), List.of(), false)))
                .contains("size: A4;");
        assertThat(ReportPdf.html(new ReportPdf.Doc("en", "T", List.of(), List.of(nine), List.of(), true)))
                .contains("size: A4 landscape");
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
                .contains("tr.BAND_GROUP td {background:#7F7F7F;color:#FFFFFF;font-weight:bold}")
                .contains("tr.HIGHLIGHT td {background:#FAC08F}")
                .contains("tr.OPENING td {font-style:italic}")
                .contains("<tr class=\"BAND_PARTY\"><td colspan=\"3\">Tenant A</td></tr>")
                .contains("<tr class=\"BAND_GROUP\"><td colspan=\"3\"></td></tr>")
                .contains("<tr class=\"OPENING\"><td>Balance brought forward</td>")
                .contains("<tr class=\"HIGHLIGHT\"><td>x</td>");
    }
}
