package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.core.util.PdfResourcePolicy;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A tabular financial report as a PDF, English or Arabic (F14-10, #55): the
 * balance sheet, the company and property P&L, the VAT return and the grid reports
 * ({@code PdfGridSink}).
 *
 * <p>The stack and resource policy of the property statement: openhtmltopdf, the
 * Noto fonts as classpath streams (the Arabic one shapes RTL text) and
 * {@link PdfResourcePolicy}, so nothing but inline {@code data:} URIs loads. Every
 * cell and header line is escaped and loses the characters XML forbids (account,
 * property, org and user names are user-typed). Numbers use Latin digits and sit
 * in LTR spans, so a minus stays on the left in RTL.</p>
 *
 * <p>An Arabic table runs right to left. openhtmltopdf does not mirror a table's
 * columns for {@code direction: rtl}, so the Arabic html writes each header and row
 * last column first, a spanning label last: the first logical column prints rightmost
 * and a Sub Total's label sits over the columns it covers. Text cells align right and
 * amounts left, the mirror of the English page.</p>
 *
 * <p>The header row sits in a {@code <thead>} that repeats on every page, every page
 * carries a "Page x / y" footer, and an optional organisation line sits above the
 * title. Band rows span the whole table, and a row's label may span its first
 * columns ({@link Row#labelSpan()}). A document that opts in ({@link Doc#autoLandscape()},
 * the grid reports) turns to landscape when a table has more than nine columns; every
 * other caller keeps the orientation it asks for.</p>
 */
public final class ReportPdf {

    private ReportPdf() { }

    /**
     * STRONG: subtotal, TOTAL: a section total, HEAD: a group heading, OK / BAD: a check line.
     * BAND_*: a group band spanning the table (its first cell is the label); OPENING: a
     * brought- or carried-forward balance; HIGHLIGHT: a row to pick out (a vacant unit).
     */
    public enum Style { PLAIN, HEAD, STRONG, TOTAL, OK, BAD, BAND_ACCOUNT, BAND_PARTY, BAND_GROUP, OPENING, HIGHLIGHT }

    /**
     * labelSpan: how many leading columns the first cell spans (1 = its own column only); the
     * cells it covers are not printed. A Sub Total's label spans up to its first figure, as the
     * Excel sink merges it, instead of widening the first column.
     */
    public record Row(List<String> cells, Style style, int labelSpan) {
        public Row {
            if (labelSpan < 1) throw new IllegalArgumentException("A label spans at least one column, not " + labelSpan);
        }
        public Row(List<String> cells, Style style) { this(cells, style, 1); }
        public static Row of(Style style, List<String> cells) { return new Row(cells, style); }
        /** A row whose first cell spans the first {@code labelSpan} columns. */
        public static Row labelled(Style style, List<String> cells, int labelSpan) { return new Row(cells, style, labelSpan); }
    }

    /** numeric: per column, whether it holds an amount (right-aligned in LTR, left in RTL). */
    public record Table(String caption, List<String> header, List<Boolean> numeric, List<Row> rows) { }

    /**
     * orgName: printed above the title when not {@code null}. landscape: the caller's orientation.
     * autoLandscape: also turn to landscape when a table has more than nine columns (the grid reports
     * opt in; the balance sheet, P&L and VAT return keep the orientation they ask for).
     */
    public record Doc(String lang, String title, List<String> meta, List<Table> tables, List<String> notes,
                      boolean landscape, String orgName, boolean autoLandscape) {
        public Doc(String lang, String title, List<String> meta, List<Table> tables, List<String> notes, boolean landscape) {
            this(lang, title, meta, tables, notes, landscape, null, false);
        }
        public Doc(String lang, String title, List<String> meta, List<Table> tables, List<String> notes, boolean landscape,
                   String orgName) {
            this(lang, title, meta, tables, notes, landscape, orgName, false);
        }
    }

    /** What XML 1.0 does not allow in a document, even escaped. */
    private static final Pattern XML_FORBIDDEN = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFE\\uFFFF]");

    /** Above this many columns a table of an {@link Doc#autoLandscape()} document is printed in landscape. */
    private static final int PORTRAIT_MAX_COLUMNS = 9;

    public static byte[] render(Doc d) {
        return renderPdf(html(d));
    }

    public static String html(Doc d) {
        boolean ar = "ar".equals(d.lang());
        String start = ar ? "right" : "left", end = ar ? "left" : "right";
        boolean landscape = d.landscape()
                || (d.autoLandscape() && d.tables().stream().anyMatch(t -> t.header().size() > PORTRAIT_MAX_COLUMNS));
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
                .append("tr.BAND_GROUP td {background:#7F7F7F;color:#000000;font-weight:bold}")
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
            int cols = t.header().size();
            b.append("<table><thead><tr>");
            // openhtmltopdf lays a table's columns out left to right whatever its direction, so an Arabic
            // table is written last column first: its first logical column prints rightmost.
            for (int i = 0; i < cols; i++) {
                int c = ar ? cols - 1 - i : i;
                b.append("<th").append(num(t, c) ? " class=\"num\"" : "").append(">").append(esc(t.header().get(c))).append("</th>");
            }
            b.append("</tr></thead><tbody>");
            for (Row r : t.rows()) {
                Style style = r.style() == null ? Style.PLAIN : r.style();
                b.append("<tr class=\"").append(style).append("\">");
                if (isBand(style)) {
                    String label = r.cells().isEmpty() ? "" : r.cells().get(0);
                    b.append("<td colspan=\"").append(Math.max(1, cols)).append("\">").append(esc(label))
                            .append("</td></tr>");
                    continue;
                }
                int span = Math.min(r.labelSpan(), cols);
                // The cells after a spanning label: in Arabic they come first, reversed, and the label last.
                int first = span > 1 ? span : 0;
                if (span > 1 && !ar) spanningLabel(b, r, span);
                for (int i = first; i < cols; i++) {
                    cell(b, t, r, ar ? cols - 1 - (i - first) : i);
                }
                if (span > 1 && ar) spanningLabel(b, r, span);
                b.append("</tr>");
            }
            b.append("</tbody></table>");
        }
        for (String n : d.notes()) b.append("<div class=\"note\">").append(esc(n)).append("</div>");
        b.append("</body></html>");
        return b.toString();
    }

    /** A row's first cell spanning its first {@code span} columns. */
    private static void spanningLabel(StringBuilder b, Row r, int span) {
        String label = r.cells().isEmpty() ? "" : r.cells().get(0);
        b.append("<td colspan=\"").append(span).append("\">").append(esc(label)).append("</td>");
    }

    /** Column {@code c} of a row; an amount sits in an LTR span. */
    private static void cell(StringBuilder b, Table t, Row r, int c) {
        String v = c < r.cells().size() ? r.cells().get(c) : "";
        boolean n = num(t, c);
        b.append("<td").append(n ? " class=\"num\"" : "").append(">");
        if (n) b.append("<span>");
        b.append(esc(v));
        if (n) b.append("</span>");
        b.append("</td>");
    }

    private static boolean isBand(Style s) {
        return s == Style.BAND_ACCOUNT || s == Style.BAND_PARTY || s == Style.BAND_GROUP;
    }

    private static boolean num(Table t, int c) {
        return t.numeric() != null && c < t.numeric().size() && Boolean.TRUE.equals(t.numeric().get(c));
    }

    /**
     * {@code value} escaped for the XHTML the renderer parses. Characters XML 1.0 forbids (the C0
     * controls but tab, newline and carriage return; U+FFFE, U+FFFF) are dropped first: the parser
     * rejects them even as character references, so one in an org, property or user name failed the export.
     */
    public static String esc(String value) {
        if (value == null) return "";
        return XML_FORBIDDEN.matcher(value).replaceAll("")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
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
