package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.core.util.PdfResourcePolicy;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * A tabular financial report as a PDF, English or Arabic (F14-10, #55): the
 * balance sheet, the company and property P&L, the VAT return and the grid reports
 * ({@code PdfGridSink}).
 *
 * <p>The stack and resource policy of the property statement: openhtmltopdf, the
 * Noto fonts as classpath streams (the Arabic one shapes RTL text) and
 * {@link PdfResourcePolicy}, so nothing but inline {@code data:} URIs loads. Every
 * cell is escaped (account, property and document names are user-typed). Numbers
 * use Latin digits and sit in LTR spans, so a minus stays on the left in RTL.</p>
 *
 * <p>The header row sits in a {@code <thead>} that repeats on every page, every page
 * carries a "Page x / y" footer, and an optional organisation line sits above the
 * title. Band rows span the whole table; a table of more than nine columns turns
 * the page to landscape.</p>
 */
public final class ReportPdf {

    private ReportPdf() { }

    /**
     * STRONG: subtotal, TOTAL: a section total, HEAD: a group heading, OK / BAD: a check line.
     * BAND_*: a group band spanning the table (its first cell is the label); OPENING: a
     * brought- or carried-forward balance; HIGHLIGHT: a row to pick out (a vacant unit).
     */
    public enum Style { PLAIN, HEAD, STRONG, TOTAL, OK, BAD, BAND_ACCOUNT, BAND_PARTY, BAND_GROUP, OPENING, HIGHLIGHT }

    public record Row(List<String> cells, Style style) {
        public static Row of(Style style, List<String> cells) { return new Row(cells, style); }
    }

    /** numeric: per column, whether it holds an amount (right-aligned in LTR, left in RTL). */
    public record Table(String caption, List<String> header, List<Boolean> numeric, List<Row> rows) { }

    /** orgName: printed above the title when not {@code null}. landscape: also forced by a table of more than nine columns. */
    public record Doc(String lang, String title, List<String> meta, List<Table> tables, List<String> notes,
                      boolean landscape, String orgName) {
        public Doc(String lang, String title, List<String> meta, List<Table> tables, List<String> notes, boolean landscape) {
            this(lang, title, meta, tables, notes, landscape, null);
        }
    }

    /** Above this many columns a table is printed in landscape. */
    private static final int PORTRAIT_MAX_COLUMNS = 9;

    public static byte[] render(Doc d) {
        return renderPdf(html(d));
    }

    public static String html(Doc d) {
        boolean ar = "ar".equals(d.lang());
        String start = ar ? "right" : "left", end = ar ? "left" : "right";
        boolean landscape = d.landscape()
                || d.tables().stream().anyMatch(t -> t.header().size() > PORTRAIT_MAX_COLUMNS);
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"").append(ar ? "ar" : "en").append("\" dir=\"").append(ar ? "rtl" : "ltr")
                .append("\"><head><meta charset=\"UTF-8\"/><style>")
                .append("@page { size: A4").append(landscape ? " landscape" : "").append("; margin: 12mm 10mm; ")
                .append("@bottom-center { content: element(pageFooter); } }")
                // A running element, not margin-box text: generated margin text skips the bidi
                // reorderer and the Arabic shaper, so "صفحة" printed reversed and unjoined.
                .append("#pageFooter { position: running(pageFooter); font-size: 7pt; color: #555; text-align: center; }")
                .append("#pageFooter .pg::before { content: counter(page); } #pageFooter .pgs::before { content: counter(pages); }")
                .append("body { font-family: 'Noto Sans', 'Noto Sans Arabic', sans-serif; font-size: 8.5pt; color: #1B1B1B; }")
                .append(".org { font-size: 9pt; font-weight: bold; color: #555; margin-bottom: 1mm; }")
                .append("h1 { font-size: 15pt; margin: 0 0 2mm 0; } .meta { color: #555; margin-bottom: 1mm; }")
                .append("h2 { font-size: 10.5pt; margin: 5mm 0 1mm 0; border-bottom: 1px solid #EEC046; padding-bottom: 1mm; }")
                .append("table { width: 100%; border-collapse: collapse; margin-top: 1mm; -fs-table-paginate: paginate; }")
                .append("tr { page-break-inside: avoid; }")
                .append("th, td { padding: 1mm 1.4mm; border-bottom: 0.5px solid #ddd; text-align: ").append(start).append("; }")
                .append("th { background: #f4f4f4; font-size: 7.5pt; } td.num, th.num { text-align: ").append(end).append("; }")
                .append(".num span { direction: ltr; unicode-bidi: embed; }")
                .append("tr.HEAD td { font-weight: bold; background: #fafafa; } tr.STRONG td { font-weight: bold; }")
                .append("tr.TOTAL td { font-weight: bold; border-top: 1px solid #1B1B1B; }")
                .append("tr.OK td { color: #1a7f37; font-weight: bold; } tr.BAD td { color: #b42318; font-weight: bold; }")
                .append("tr.BAND_ACCOUNT td {background:#D2691E;color:#F0F8FF;font-weight:bold}")
                .append("tr.BAND_PARTY td {background:#FFFF00}")
                .append("tr.BAND_GROUP td {background:#7F7F7F;color:#FFFFFF;font-weight:bold}")
                .append("tr.HIGHLIGHT td {background:#FAC08F}")
                .append("tr.OPENING td {font-style:italic}")
                .append(".note { color: #666; font-size: 7.5pt; margin-top: 1.5mm; }")
                .append("</style></head><body>");
        // First in the body, so the footer runs from page 1.
        b.append("<div id=\"pageFooter\">").append(ar ? "صفحة" : "Page")
                .append(" <span class=\"pg\"></span> / <span class=\"pgs\"></span></div>");
        if (d.orgName() != null) b.append("<div class=\"org\">").append(esc(d.orgName())).append("</div>");
        b.append("<h1>").append(esc(d.title())).append("</h1>");
        for (String m : d.meta()) b.append("<div class=\"meta\">").append(esc(m)).append("</div>");
        for (Table t : d.tables()) {
            if (t.caption() != null && !t.caption().isBlank()) b.append("<h2>").append(esc(t.caption())).append("</h2>");
            b.append("<table><thead><tr>");
            for (int c = 0; c < t.header().size(); c++) {
                b.append("<th").append(num(t, c) ? " class=\"num\"" : "").append(">").append(esc(t.header().get(c))).append("</th>");
            }
            b.append("</tr></thead><tbody>");
            for (Row r : t.rows()) {
                Style style = r.style() == null ? Style.PLAIN : r.style();
                b.append("<tr class=\"").append(style).append("\">");
                if (isBand(style)) {
                    String label = r.cells().isEmpty() ? "" : r.cells().get(0);
                    b.append("<td colspan=\"").append(Math.max(1, t.header().size())).append("\">").append(esc(label))
                            .append("</td></tr>");
                    continue;
                }
                for (int c = 0; c < t.header().size(); c++) {
                    String v = c < r.cells().size() ? r.cells().get(c) : "";
                    boolean n = num(t, c);
                    b.append("<td").append(n ? " class=\"num\"" : "").append(">");
                    if (n) b.append("<span>");
                    b.append(esc(v));
                    if (n) b.append("</span>");
                    b.append("</td>");
                }
                b.append("</tr>");
            }
            b.append("</tbody></table>");
        }
        for (String n : d.notes()) b.append("<div class=\"note\">").append(esc(n)).append("</div>");
        b.append("</body></html>");
        return b.toString();
    }

    private static boolean isBand(Style s) {
        return s == Style.BAND_ACCOUNT || s == Style.BAND_PARTY || s == Style.BAND_GROUP;
    }

    private static boolean num(Table t, int c) {
        return t.numeric() != null && c < t.numeric().size() && Boolean.TRUE.equals(t.numeric().get(c));
    }

    public static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static byte[] renderPdf(String html) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            ClassPathResource arabicFont = new ClassPathResource("fonts/NotoSansArabic.ttf");
            ClassPathResource latinFont = new ClassPathResource("fonts/NotoSans.ttf");
            builder.useFont(() -> {
                try { return arabicFont.getInputStream(); } catch (IOException ex) { throw new UncheckedIOException(ex); }
            }, "Noto Sans Arabic");
            builder.useFont(() -> {
                try { return latinFont.getInputStream(); } catch (IOException ex) { throw new UncheckedIOException(ex); }
            }, "Noto Sans");
            PdfResourcePolicy.apply(builder);
            // Shared sanitiser: no bidi controls or glyph-less characters (R3 portal3 F11).
            builder.withHtmlContent(com.datagami.rentaxis.core.util.PdfText.clean(html), null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render the report PDF", e);
        }
    }
}
