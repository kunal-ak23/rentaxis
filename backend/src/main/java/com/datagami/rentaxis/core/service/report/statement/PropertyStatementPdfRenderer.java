package com.datagami.rentaxis.core.service.report.statement;

import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Figure;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Section;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Table;
import com.datagami.rentaxis.core.util.PdfResourcePolicy;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The property statement pack as a PDF, English or Arabic (finance-ops spec §1).
 *
 * <p>The same stack and resource policy as {@code TaxInvoicePdfRenderer}:
 * openhtmltopdf, the Noto fonts handed over as classpath streams (the Arabic one
 * shapes the RTL text), and {@link PdfResourcePolicy}, so the render loads nothing
 * but inline {@code data:} URIs. Every value that came from a user — property,
 * renter, vendor and account names, narrations — is escaped before it reaches the
 * markup. Numbers use Latin digits in both languages, and sit in LTR spans so a
 * minus sign stays on the left inside an RTL page.</p>
 */
@Component
public class PropertyStatementPdfRenderer {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Dubai"));

    public byte[] render(PropertyStatementDTO s, String lang) {
        return renderPdf(html(s, lang));
    }

    /** The markup — package-visible so a test can read what went into the PDF. */
    String html(PropertyStatementDTO s, String lang) {
        boolean ar = "ar".equals(lang);
        String l = ar ? "ar" : "en";
        // openhtmltopdf is CSS 2.1: no logical start / end, so the sides are spelled per direction.
        String start = ar ? "right" : "left", end = ar ? "left" : "right";
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"").append(l).append("\" dir=\"").append(ar ? "rtl" : "ltr").append("\"><head><meta charset=\"UTF-8\"/>")
                .append("<style>")
                .append("@page { size: A4; margin: 14mm 12mm; }")
                .append("body { font-family: 'Noto Sans', 'Noto Sans Arabic', sans-serif; font-size: 9pt; color: #1B1B1B; }")
                .append("h1 { font-size: 15pt; margin: 0 0 2mm 0; } h2 { font-size: 11pt; margin: 5mm 0 1.5mm 0; border-bottom: 1px solid #EEC046; padding-bottom: 1mm; }")
                .append(".meta { color: #555; margin-bottom: 1mm; } .src { color: #777; font-size: 8pt; font-weight: normal; }")
                .append("table { width: 100%; border-collapse: collapse; margin-top: 1.5mm; } th, td { padding: 1.2mm 1.5mm; border-bottom: 0.5px solid #ddd; text-align: " + start + "; }")
                .append("th { background: #f4f4f4; font-size: 8pt; } td.num, th.num { text-align: " + end + "; } .num span { direction: ltr; unicode-bidi: embed; }")
                .append(".fig td { border: none; padding: 0.8mm 1.5mm; } .note { color: #666; font-size: 8pt; margin-top: 1mm; }")
                .append(".footer { margin-top: 6mm; font-size: 8pt; color: #555; } .final { color: #1a7f37; } .prov { color: #b35900; }")
                .append("</style></head><body>");
        String name = ar && s.propertyNameAr() != null && !s.propertyNameAr().isBlank() ? s.propertyNameAr() : s.propertyName();
        b.append("<h1>").append(esc(StatementLabels.of("title", l))).append("</h1>");
        b.append("<div class=\"meta\">").append(esc(StatementLabels.of("property", l))).append(": <b>").append(esc(name)).append("</b></div>");
        b.append("<div class=\"meta\">").append(esc(StatementLabels.of("period", l))).append(": <span dir=\"ltr\">")
                .append(DAY.format(s.from())).append(" – ").append(DAY.format(s.to())).append("</span></div>");

        for (Section sec : s.sections()) {
            b.append("<h2>").append(sec.number()).append(". ").append(esc(StatementLabels.of("section." + sec.key(), l)))
                    .append(" <span class=\"src\">(").append(esc(StatementLabels.of("source." + sec.source(), l))).append(")</span></h2>");
            if (!sec.figures().isEmpty()) {
                b.append("<table class=\"fig\">");
                for (Figure f : sec.figures()) {
                    b.append("<tr><td>").append(esc(StatementLabels.of("figure." + f.key(), l)));
                    if (f.count() != null) b.append(" <span dir=\"ltr\">(").append(f.count()).append(")</span>");
                    b.append("</td><td class=\"num\"><span>").append(StatementFormat.money(f.amount())).append("</span></td></tr>");
                }
                b.append("</table>");
            }
            for (Table t : sec.tables()) {
                if (t.rows().isEmpty()) continue;
                List<Integer> cols = StatementFormat.visibleColumns(t);
                b.append("<table><tr>");
                for (int c : cols) {
                    b.append("<th").append(StatementFormat.numeric(t, c) ? " class=\"num\"" : "").append(">")
                            .append(esc(StatementLabels.of("column." + t.columns().get(c), l))).append("</th>");
                }
                b.append("</tr>");
                for (List<Object> row : t.rows()) {
                    b.append("<tr>");
                    for (int c : cols) {
                        boolean num = StatementFormat.numeric(t, c);
                        b.append("<td").append(num ? " class=\"num\"" : "").append(">");
                        if (num) b.append("<span>");
                        b.append(esc(StatementFormat.cell(t, row, c, l)));
                        if (num) b.append("</span>");
                        b.append("</td>");
                    }
                    b.append("</tr>");
                }
                b.append("</table>");
            }
            for (String note : sec.notes()) {
                b.append("<div class=\"note\">").append(esc(StatementLabels.of("note." + note, l))).append("</div>");
            }
        }

        PropertyStatementDTO.Footer f = s.footer();
        b.append("<div class=\"footer\"><div class=\"").append(f.isFinal() ? "final" : "prov").append("\">")
                .append(esc(StatementLabels.of(f.isFinal() ? "final" : "provisional", l))).append("</div>")
                .append("<div>").append(esc(StatementLabels.of("generated", l))).append(": <span dir=\"ltr\">")
                .append(STAMP.format(f.generatedAt())).append("</span>");
        if (f.generatedBy() != null && !f.generatedBy().isBlank()) {
            b.append(" ").append(esc(StatementLabels.of("by", l))).append(" ").append(esc(f.generatedBy()));
        }
        b.append("</div></div></body></html>");
        return b.toString();
    }

    static String esc(String value) {
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
            // Only inline data: URIs load; no http(s), no file:, no jar:.
            PdfResourcePolicy.apply(builder);
            builder.withHtmlContent(html, null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render the property statement PDF", e);
        }
    }
}
