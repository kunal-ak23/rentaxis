package com.datagami.rentaxis.core.service.payables;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.api.dto.payables.AdvanceDTO;
import com.datagami.rentaxis.api.dto.payables.OpenItemDTO;
import com.datagami.rentaxis.core.util.PdfResourcePolicy;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A supplier statement of account, English or Arabic (finance-ops spec §2, closes
 * S11): the vendor ledger for a period (opening balance, every entry, running
 * balance, closing) and the open items and unallocated advances as of the
 * period's last day.
 *
 * <p>The same stack and resource policy as the property statement and the tax
 * invoice: openhtmltopdf, the Noto fonts handed over as classpath streams, and
 * {@link PdfResourcePolicy}, so nothing but inline {@code data:} URIs loads.
 * Every user-typed value is escaped. Amounts use Latin digits and sit in LTR spans
 * inside an RTL page. Balances are shown as the supplier reads them: a credit is
 * what we owe.</p>
 */
@Component
public class SupplierStatementPdfRenderer {

    /** What one statement shows. {@code ledger} balances are debit-positive, as the ledger returns them. */
    public record Statement(String vendorName, String vendorNameAr, String vendorTrn, LocalDate from, LocalDate to,
                            AccountLedgerDTO ledger, List<OpenItemDTO> openItems, List<AdvanceDTO> advances,
                            Instant generatedAt, String generatedBy) { }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Dubai"));

    private static final Map<String, String[]> L = new HashMap<>();

    private static void put(String key, String en, String ar) { L.put(key, new String[]{en, ar}); }

    static {
        put("title", "Statement of account", "كشف حساب المورد");
        put("vendor", "Supplier", "المورد");
        put("trn", "TRN", "الرقم الضريبي");
        put("period", "Period", "الفترة");
        put("ledger", "Account activity", "حركة الحساب");
        put("opening", "Opening balance", "الرصيد الافتتاحي");
        put("closing", "Closing balance", "الرصيد الختامي");
        put("date", "Date", "التاريخ");
        put("entry", "Entry", "القيد");
        put("doc", "Doc", "المستند");
        put("narration", "Details", "البيان");
        put("debit", "Debit", "مدين");
        put("credit", "Credit", "دائن");
        put("balance", "Balance", "الرصيد");
        put("cr", "Cr", "دائن");
        put("dr", "Dr", "مدين");
        put("openItems", "Open invoices as of", "الفواتير المفتوحة حتى");
        put("invoice", "Invoice", "الفاتورة");
        put("invoiceDate", "Invoice date", "تاريخ الفاتورة");
        put("dueDate", "Due date", "تاريخ الاستحقاق");
        put("daysOverdue", "Days overdue", "أيام التأخير");
        put("gross", "Amount", "المبلغ");
        put("paid", "Paid", "المدفوع");
        put("open", "Open", "المتبقي");
        put("totalOpen", "Total open", "إجمالي المتبقي");
        put("advances", "Unallocated payments (advances)", "دفعات غير مخصصة (سلف)");
        put("payment", "Payment", "الدفعة");
        put("unallocated", "Unallocated", "غير مخصص");
        put("net", "Net owed", "صافي المستحق");
        put("none", "None", "لا يوجد");
        put("truncated", "Only the first rows of the period are shown.", "تظهر الصفوف الأولى من الفترة فقط.");
        put("generated", "Generated", "تاريخ الإنشاء");
        put("by", "by", "بواسطة");
    }

    static String label(String key, boolean ar) {
        String[] l = L.get(key);
        return l == null ? key : ar ? l[1] : l[0];
    }

    public byte[] render(Statement s, String lang) {
        return renderPdf(html(s, lang));
    }

    /** The markup — package-visible so a test can read what went into the PDF. */
    String html(Statement s, String lang) {
        boolean ar = "ar".equals(lang);
        String start = ar ? "right" : "left", end = ar ? "left" : "right";
        StringBuilder b = new StringBuilder();
        b.append("<!DOCTYPE html><html lang=\"").append(ar ? "ar" : "en").append("\" dir=\"").append(ar ? "rtl" : "ltr")
                .append("\"><head><meta charset=\"UTF-8\"/><style>")
                .append("@page { size: A4; margin: 14mm 12mm; }")
                .append("body { font-family: 'Noto Sans', 'Noto Sans Arabic', sans-serif; font-size: 9pt; color: #1B1B1B; }")
                .append("h1 { font-size: 15pt; margin: 0 0 2mm 0; } h2 { font-size: 11pt; margin: 5mm 0 1.5mm 0; border-bottom: 1px solid #EEC046; padding-bottom: 1mm; }")
                .append(".meta { color: #555; margin-bottom: 1mm; }")
                .append("table { width: 100%; border-collapse: collapse; margin-top: 1.5mm; } th, td { padding: 1.2mm 1.5mm; border-bottom: 0.5px solid #ddd; text-align: " + start + "; }")
                .append("th { background: #f4f4f4; font-size: 8pt; } td.num, th.num { text-align: " + end + "; } .num span, .ltr { direction: ltr; unicode-bidi: embed; }")
                .append("tr.total td { font-weight: bold; border-top: 1px solid #999; } .note { color: #666; font-size: 8pt; margin-top: 1mm; }")
                .append(".footer { margin-top: 6mm; font-size: 8pt; color: #555; }")
                .append("</style></head><body>");
        String name = ar && s.vendorNameAr() != null && !s.vendorNameAr().isBlank() ? s.vendorNameAr() : s.vendorName();
        b.append("<h1>").append(esc(label("title", ar))).append("</h1>");
        b.append("<div class=\"meta\">").append(esc(label("vendor", ar))).append(": <b>").append(esc(name)).append("</b></div>");
        if (s.vendorTrn() != null && !s.vendorTrn().isBlank()) {
            b.append("<div class=\"meta\">").append(esc(label("trn", ar))).append(": <span class=\"ltr\">")
                    .append(esc(s.vendorTrn())).append("</span></div>");
        }
        b.append("<div class=\"meta\">").append(esc(label("period", ar))).append(": <span class=\"ltr\">")
                .append(DAY.format(s.from())).append(" – ").append(DAY.format(s.to())).append("</span></div>");

        // Account activity.
        AccountLedgerDTO g = s.ledger();
        b.append("<h2>").append(esc(label("ledger", ar))).append("</h2><table><tr>")
                .append(th(label("date", ar), false)).append(th(label("entry", ar), false)).append(th(label("doc", ar), false))
                .append(th(label("narration", ar), false)).append(th(label("debit", ar), true))
                .append(th(label("credit", ar), true)).append(th(label("balance", ar), true)).append("</tr>");
        b.append("<tr><td colspan=\"6\">").append(esc(label("opening", ar))).append("</td>")
                .append(num(balance(g.openingBalance(), ar))).append("</tr>");
        for (LedgerRowDTO r : g.rows()) {
            String text = r.narration() != null && !r.narration().isBlank() ? r.narration() : r.particular();
            b.append("<tr><td><span class=\"ltr\">").append(DAY.format(r.entryDate())).append("</span></td>")
                    .append("<td>").append(esc(r.entryNumber())).append("</td>")
                    .append("<td>").append(esc(r.docType())).append("</td>")
                    .append("<td>").append(esc(text)).append("</td>")
                    .append(num(amount(r.debit()))).append(num(amount(r.credit())))
                    .append(num(balance(r.balance(), ar))).append("</tr>");
        }
        b.append("<tr class=\"total\"><td colspan=\"4\">").append(esc(label("closing", ar))).append("</td>")
                .append(num(amount(g.totalDebit()))).append(num(amount(g.totalCredit())))
                .append(num(balance(g.closingBalance(), ar))).append("</tr></table>");
        if (g.truncated()) b.append("<div class=\"note\">").append(esc(label("truncated", ar))).append("</div>");

        // Open items as of `to`.
        b.append("<h2>").append(esc(label("openItems", ar))).append(" <span class=\"ltr\">").append(DAY.format(s.to()))
                .append("</span></h2>");
        BigDecimal totalOpen = BigDecimal.ZERO;
        if (s.openItems().isEmpty()) {
            b.append("<div class=\"note\">").append(esc(label("none", ar))).append("</div>");
        } else {
            b.append("<table><tr>").append(th(label("invoice", ar), false)).append(th(label("entry", ar), false))
                    .append(th(label("invoiceDate", ar), false)).append(th(label("dueDate", ar), false))
                    .append(th(label("daysOverdue", ar), true)).append(th(label("gross", ar), true))
                    .append(th(label("paid", ar), true)).append(th(label("open", ar), true)).append("</tr>");
            for (OpenItemDTO i : s.openItems()) {
                totalOpen = totalOpen.add(i.open());
                b.append("<tr><td>").append(esc(i.invoiceNumber())).append("</td><td>").append(esc(i.docNumber()))
                        .append("</td><td><span class=\"ltr\">").append(DAY.format(i.invoiceDate()))
                        .append("</span></td><td><span class=\"ltr\">").append(DAY.format(i.dueDate())).append("</span></td>")
                        .append(num(String.valueOf(i.daysOverdue()))).append(num(amount(i.gross())))
                        .append(num(amount(i.allocated()))).append(num(amount(i.open()))).append("</tr>");
            }
            b.append("<tr class=\"total\"><td colspan=\"7\">").append(esc(label("totalOpen", ar))).append("</td>")
                    .append(num(amount(totalOpen))).append("</tr></table>");
        }

        // Unallocated payments.
        BigDecimal totalAdvance = BigDecimal.ZERO;
        if (!s.advances().isEmpty()) {
            b.append("<h2>").append(esc(label("advances", ar))).append("</h2><table><tr>")
                    .append(th(label("payment", ar), false)).append(th(label("date", ar), false))
                    .append(th(label("paid", ar), true)).append(th(label("unallocated", ar), true)).append("</tr>");
            for (AdvanceDTO a : s.advances()) {
                totalAdvance = totalAdvance.add(a.unallocated());
                b.append("<tr><td>").append(esc(a.voucherNumber())).append("</td><td><span class=\"ltr\">")
                        .append(DAY.format(a.docDate())).append("</span></td>").append(num(amount(a.paid())))
                        .append(num(amount(a.unallocated()))).append("</tr>");
            }
            b.append("</table>");
        }
        b.append("<table><tr class=\"total\"><td>").append(esc(label("net", ar))).append("</td>")
                .append(num(amount(totalOpen.subtract(totalAdvance)))).append("</tr></table>");

        b.append("<div class=\"footer\">").append(esc(label("generated", ar))).append(": <span class=\"ltr\">")
                .append(STAMP.format(s.generatedAt())).append("</span>");
        if (s.generatedBy() != null && !s.generatedBy().isBlank()) {
            b.append(" ").append(esc(label("by", ar))).append(" ").append(esc(s.generatedBy()));
        }
        b.append("</div></body></html>");
        return b.toString();
    }

    private static String th(String text, boolean numeric) {
        return "<th" + (numeric ? " class=\"num\"" : "") + ">" + esc(text) + "</th>";
    }

    private static String num(String text) {
        return "<td class=\"num\"><span>" + esc(text) + "</span></td>";
    }

    private static String amount(BigDecimal v) {
        if (v == null || v.signum() == 0) return "";
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(v);
    }

    /** A debit-positive ledger balance, printed as the supplier reads it: "1,500.00 Cr" is owed to them. */
    static String balance(BigDecimal debitPositive, boolean ar) {
        BigDecimal v = debitPositive == null ? BigDecimal.ZERO : debitPositive;
        String n = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(v.abs());
        if (v.signum() == 0) return n;
        return n + " " + label(v.signum() < 0 ? "cr" : "dr", ar);
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
            throw new UncheckedIOException("Failed to render the supplier statement PDF", e);
        }
    }
}
