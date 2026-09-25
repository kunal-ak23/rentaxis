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
 * balance sheet, the company and property P&L and the VAT return.
 *
 * <p>The stack and resource policy of the property statement: openhtmltopdf, the
 * Noto fonts as classpath streams (the Arabic one shapes RTL text) and
 * {@link PdfResourcePolicy}, so nothing but inline {@code data:} URIs loads. Every
 * cell is escaped (account, property and document names are user-typed). Numbers
 * use Latin digits and sit in LTR spans, so a minus stays on the left in RTL.</p>
 */
public final class ReportPdf {

    private ReportPdf() { }

    /** STRONG: subtotal, TOTAL: a section total, HEAD: a group heading, OK / BAD: a check line. */
    public enum Style { PLAIN, HEAD, STRONG, TOTAL, OK, BAD }

    public record Row(List<String> cells, Style style) {
        public static Row of(Style style, List<String> cells) { return new Row(cells, style); }
    }

    /** numeric: per column, whether it holds an amount (right-aligned in LTR, left in RTL). */
    public record Table(String caption, List<String> header, List<Boolean> numeric, List<Row> rows) { }

    public record Doc(String lang, String title, List<String> meta, List<Table> tables, List<String> notes,
                      boolean landscape) { }

    public static byte[] render(Doc d) {
        return renderPdf(html(d));
    }

    public static String html(Doc d) {
        boolean ar = "ar".equals(d.lang());
        String start = ar ? "right" : "left", end = ar ? "left" : "right";
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"").append(ar ? "ar" : "en").append("\" dir=\"").append(ar ? "rtl" : "ltr")
                .append("\"><head><meta charset=\"UTF-8\"/><style>")
                .append("@page { size: A4").append(d.landscape() ? " landscape" : "").append("; margin: 12mm 10mm; }")
                .append("body { font-family: 'Noto Sans', 'Noto Sans Arabic', sans-serif; font-size: 8.5pt; color: #1B1B1B; }")
                .append("h1 { font-size: 15pt; margin: 0 0 2mm 0; } .meta { color: #555; margin-bottom: 1mm; }")
                .append("h2 { font-size: 10.5pt; margin: 5mm 0 1mm 0; border-bottom: 1px solid #EEC046; padding-bottom: 1mm; }")
                .append("table { width: 100%; border-collapse: collapse; margin-top: 1mm; }")
                .append("th, td { padding: 1mm 1.4mm; border-bottom: 0.5px solid #ddd; text-align: ").append(start).append("; }")
                .append("th { background: #f4f4f4; font-size: 7.5pt; } td.num, th.num { text-align: ").append(end).append("; }")
                .append(".num span { direction: ltr; unicode-bidi: embed; }")
                .append("tr.HEAD td { font-weight: bold; background: #fafafa; } tr.STRONG td { font-weight: bold; }")
                .append("tr.TOTAL td { font-weight: bold; border-top: 1px solid #1B1B1B; }")
                .append("tr.OK td { color: #1a7f37; font-weight: bold; } tr.BAD td { color: #b42318; font-weight: bold; }")
                .append(".note { color: #666; font-size: 7.5pt; margin-top: 1.5mm; }")
                .append("</style></head><body>");
        b.append("<h1>").append(esc(d.title())).append("</h1>");
        for (String m : d.meta()) b.append("<div class=\"meta\">").append(esc(m)).append("</div>");
        for (Table t : d.tables()) {
            if (t.caption() != null && !t.caption().isBlank()) b.append("<h2>").append(esc(t.caption())).append("</h2>");
            b.append("<table><tr>");
            for (int c = 0; c < t.header().size(); c++) {
                b.append("<th").append(num(t, c) ? " class=\"num\"" : "").append(">").append(esc(t.header().get(c))).append("</th>");
            }
            b.append("</tr>");
            for (Row r : t.rows()) {
                b.append("<tr class=\"").append(r.style() == null ? Style.PLAIN : r.style()).append("\">");
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
            b.append("</table>");
        }
        for (String n : d.notes()) b.append("<div class=\"note\">").append(esc(n)).append("</div>");
        b.append("</body></html>");
        return b.toString();
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
            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render the report PDF", e);
        }
    }
}
