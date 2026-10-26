package com.datagami.rentaxis.core.service.report.grid;

import com.datagami.rentaxis.core.service.report.ReportPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.datagami.rentaxis.core.service.report.grid.SampleGrid.cells;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfGridSinkTest {

    private static final ReportLabels LABELS = new ReportLabels();

    @Test
    void rendersAndRepeatsTheHeaderOnPageTwo() throws IOException {
        PdfGridSink sink = feed("en", SampleGrid.COLUMNS, w -> {
            w.openGroup(1, Band.PARTY, "Tenant Name : Tenant A");
            w.opening(BigDecimal.ZERO);
            for (int i = 1; i <= 150; i++) {
                w.row(cells("docDate", LocalDate.of(2026, 9, 16), "particular", "Rent instalment " + i,
                        "debit", new BigDecimal("100"), "docNo", "JV-26/" + i), RowStyle.PLAIN, Map.of());
            }
            w.closeGroup("Sub Total");
            w.end();
        });
        byte[] pdf = sink.bytes();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isGreaterThan(doc.getPage(0).getMediaBox().getHeight());
            PDFTextStripper s = visualOrder();
            s.setStartPage(2);
            s.setEndPage(2);
            assertThat(s.getText(doc)).contains("Particular").contains("Page 2 /");
        }
    }

    @Test
    void theSampleGridReadsInPactsForms() throws IOException {
        PdfGridSink sink = feed("en", SampleGrid.COLUMNS, SampleGrid::feed);
        ReportPdf.Doc d = sink.doc();
        assertThat(d.lang()).isEqualTo("en");
        assertThat(d.orgName()).isEqualTo("Sample Org");
        assertThat(d.title()).isEqualTo("Tenant Ledger");
        assertThat(d.meta()).containsExactly("01/09/2026 – 30/09/2027", "Property: Sample Tower",
                "Generated 26/10/2026 10:05 by Test User");
        assertThat(d.notes()).isEmpty();
        assertThat(d.tables()).hasSize(1);
        ReportPdf.Table t = d.tables().get(0);
        assertThat(t.caption()).isNull();
        assertThat(t.header()).containsExactly("Doc Date", "Particular", "Debit", "Credit", "Balance", "Chq No",
                "Payee Bank", "BDDays", "ConvertedDate", "Doc No");
        assertThat(t.numeric()).containsExactly(false, false, true, true, true, false, false, true, false, false);
        assertThat(t.rows()).extracting(ReportPdf.Row::style).containsExactly(
                ReportPdf.Style.BAND_ACCOUNT, ReportPdf.Style.BAND_PARTY, ReportPdf.Style.OPENING,
                ReportPdf.Style.PLAIN, ReportPdf.Style.PLAIN, ReportPdf.Style.PLAIN,
                ReportPdf.Style.STRONG, ReportPdf.Style.TOTAL);
        assertThat(t.rows().get(0).cells()).containsExactly("Rent Receivable - Sample Tower");
        assertThat(t.rows().get(1).cells()).containsExactly("Tenant Name : Tenant A");
        assertThat(t.rows().get(2).cells()).containsExactly("Balance brought forward", "", "", "", "0.00Dr",
                "", "", "", "", "");
        assertThat(t.rows().get(3).cells()).containsExactly("16-09-2026", "Advance Rent - Sample Tower", "60,000.00", "",
                "60,000.00Dr", "", "", "", "", "TCO-26/1");
        assertThat(t.rows().get(4).cells()).containsExactly("16-09-2026", "PDC Receivable Sample Tower", "", "9,500.00",
                "50,500.00Dr", "000201", "Emirates NBD", "1", "02/10/2026", "PDR-26/1");
        assertThat(t.rows().get(5).cells().get(1)).isEqualTo("=HYPERLINK(\"x\")");
        assertThat(t.rows().get(6).cells()).containsExactly("Sub Total", "", "60,000.10", "9,500.00", "50,500.10Dr",
                "", "", "", "", "");
        assertThat(t.rows().get(7).cells()).containsExactly("REPORT TOTAL", "", "60,000.10", "9,500.00", "50,500.10Dr",
                "", "", "", "", "");

        byte[] pdf = sink.bytes();
        assertThat(sink.bytes()).isSameAs(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = visualOrder().getText(doc);
            assertThat(text).contains("Sample Org").contains("Tenant Ledger").contains("50,500.10Dr")
                    .contains("REPORT TOTAL").contains("Page 1 / 1");
        }
    }

    @Test
    void creditBalancesReadCrAndHighlightRowsKeepTheirStyle() {
        PdfGridSink sink = feed("en", SampleGrid.COLUMNS, w -> {
            w.openGroup(1, Band.GROUP, "Vacant");
            w.opening(new BigDecimal("-8000"));
            w.row(cells("credit", new BigDecimal("12.5")), RowStyle.HIGHLIGHT, Map.of());
            w.closeGroup("Sub Total");
            w.end();
        });
        List<ReportPdf.Row> rows = sink.doc().tables().get(0).rows();
        assertThat(rows.get(0).style()).isEqualTo(ReportPdf.Style.BAND_GROUP);
        assertThat(rows.get(1).cells().get(4)).isEqualTo("8,000.00Cr");
        assertThat(rows.get(2).style()).isEqualTo(ReportPdf.Style.HIGHLIGHT);
        assertThat(rows.get(2).cells()).containsExactly("", "", "", "12.50", "8,012.50Cr", "", "", "", "", "");
    }

    @Test
    void aContinuedGroupShowsItsCarriedBalance() {
        PdfGridSink sink = feed("en", SampleGrid.COLUMNS, w -> {
            w.markPartial();
            w.continueGroup(2, Band.PARTY, "Tenant A", new BigDecimal("500"));
            w.row(cells("debit", new BigDecimal("10")), RowStyle.PLAIN, Map.of());
            w.end();
        });
        List<ReportPdf.Row> rows = sink.doc().tables().get(0).rows();
        assertThat(rows.get(0).cells()).containsExactly("Tenant A (continued)");
        assertThat(rows.get(1).style()).isEqualTo(ReportPdf.Style.OPENING);
        assertThat(rows.get(1).cells().get(0)).isEqualTo("Balance carried forward");
        assertThat(rows.get(1).cells().get(4)).isEqualTo("500.00Dr");
        assertThat(rows.get(2).cells().get(4)).isEqualTo("510.00Dr");
    }

    @Test
    void hiddenColumnsAreLeftOut() {
        List<ReportColumn> cols = SampleGrid.COLUMNS.stream()
                .map(c -> c.key().equals("bdDays") ? c.hiddenColumn() : c).toList();
        ReportPdf.Table t = feed("en", cols, SampleGrid::feed).doc().tables().get(0);
        assertThat(t.header()).hasSize(9).doesNotContain("BDDays");
        assertThat(t.numeric()).hasSize(9);
        assertThat(t.rows().get(4).cells()).containsExactly("16-09-2026", "PDC Receivable Sample Tower", "", "9,500.00",
                "50,500.00Dr", "000201", "Emirates NBD", "02/10/2026", "PDR-26/1");
    }

    @Test
    void userTextLosesControlAndBidiCharactersAndStillRenders() {
        PdfGridSink sink = feed("en", SampleGrid.COLUMNS, w -> {
            w.openGroup(1, Band.PARTY, "Tenant\u0007 A‮");
            w.row(cells("particular", "A\u0007b‏c<d>", "docNo", "JV\u0000-1"), RowStyle.PLAIN, Map.of());
            w.closeGroup(null);
            w.end();
        });
        List<ReportPdf.Row> rows = sink.doc().tables().get(0).rows();
        assertThat(rows.get(0).cells()).containsExactly("Tenant A");
        assertThat(rows.get(1).cells().get(1)).isEqualTo("Abc<d>");
        assertThat(rows.get(1).cells().get(9)).isEqualTo("JV-1");
        assertThat(new String(sink.bytes(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    }

    @Test
    void controlCharactersInTheHeaderLinesStillRender() throws IOException {
        // The org, filter and generated lines bypass cellText; a C0 control there broke the XHTML parse (a 500).
        ReportMeta meta = new ReportMeta("sample", "tenantLedger.title", "Sample\u0001 Org",
                List.of(new ReportFilter("filter.property", "Sample\u0007 Tower")),
                LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 30), null, SampleGrid.GENERATED_AT,
                "Test\u0008 User", "en");
        PdfGridSink sink = feed(meta, SampleGrid.COLUMNS, SampleGrid::feed);
        byte[] pdf = sink.bytes();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(visualOrder().getText(doc)).contains("Sample Org").contains("Sample Tower")
                    .contains("Test User");
        }
    }

    @Test
    void arabicAndAnEmptyGridStillRender() throws IOException {
        PdfGridSink ar = feed("ar", SampleGrid.COLUMNS, SampleGrid::feed);
        assertThat(ar.doc().lang()).isEqualTo("ar");
        assertThat(ar.doc().tables().get(0).header().get(0)).isEqualTo("تاريخ المستند");
        assertThat(new String(ar.bytes(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(ar.bytes())) {
            assertThat(new PDFTextStripper().getText(doc)).contains("صفحة").doesNotContain("ةحفص");
        }

        PdfGridSink empty = feed("en", SampleGrid.COLUMNS, ReportWriter::end);
        List<ReportPdf.Row> rows = empty.doc().tables().get(0).rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).style()).isEqualTo(ReportPdf.Style.TOTAL);
        assertThat(rows.get(0).cells()).startsWith("REPORT TOTAL", "", "0.00", "0.00", "0.00Dr");
        try (PDDocument doc = Loader.loadPDF(empty.bytes())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    void bytesBeforeTheEndIsRefused() {
        PdfGridSink sink = new PdfGridSink();
        assertThatThrownBy(sink::bytes).isInstanceOf(IllegalStateException.class);
    }

    /** Text in reading order on the page: the footer's page counters are drawn in their own run, after the label. */
    private static PDFTextStripper visualOrder() {
        PDFTextStripper s = new PDFTextStripper();
        s.setSortByPosition(true);
        return s;
    }

    private static PdfGridSink feed(String lang, List<ReportColumn> columns, Consumer<ReportWriter> feed) {
        return feed(SampleGrid.meta(lang), columns, feed);
    }

    private static PdfGridSink feed(ReportMeta meta, List<ReportColumn> columns, Consumer<ReportWriter> feed) {
        PdfGridSink sink = new PdfGridSink();
        ReportWriter w = new ReportWriter(sink, LABELS.bind(meta.lang()), 50_000);
        w.begin(meta, columns);
        feed.accept(w);
        return sink;
    }
}
