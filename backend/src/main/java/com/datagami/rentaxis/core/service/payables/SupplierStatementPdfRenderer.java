package com.datagami.rentaxis.core.service.payables;

import com.datagami.rentaxis.api.dto.ledger.AccountLedgerDTO;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.api.dto.payables.AdvanceDTO;
import com.datagami.rentaxis.api.dto.payables.OpenItemDTO;
import com.datagami.rentaxis.core.util.PdfResourcePolicy;
import com.datagami.rentaxis.core.util.PdfRow;
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
 * inside an RTL page. An Arabic table's cells are written last column first
 * ({@link PdfRow}), so its columns run right to left. Balances are shown as the
 * supplier reads them: a credit is what we owe.</p>
 */
@Component
public class SupplierStatementPdfRenderer {

    /**
     * What one statement shows. {@code ledger} balances are debit-positive, as the
     * ledger returns them. {@code closingBalance} is the leaf's balance at the end
     * of {@code to}, read on its own: when the ledger's rows are truncated the last
     * running balance is not the period's closing.
     */
    public record Statement(String vendorName, String vendorNameAr, String vendorTrn, LocalDate from, LocalDate to,
                            AccountLedgerDTO ledger, BigDecimal closingBalance, List<OpenItemDTO> openItems,
                            List<AdvanceDTO> advances, Instant generatedAt, String generatedBy) {
        /** Without a separate closing: the ledger's own (tests, and a ledger that is never truncated). */
        public Statement(String vendorName, String vendorNameAr, String vendorTrn, LocalDate from, LocalDate to,
                         AccountLedgerDTO ledger, List<OpenItemDTO> openItems, List<AdvanceDTO> advances,
                         Instant generatedAt, String generatedBy) {
            this(vendorName, vendorNameAr, vendorTrn, from, to, ledger, ledger.closingBalance(), openItems, advances,
                    generatedAt, generatedBy);
        }
    }

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
        put("truncated", "Only the first rows of the period are listed; the closing balance is the whole period's.",
                "تظهر الصفوف الأولى من الفترة فقط؛ والرصيد الختامي يشمل الفترة كاملة.");
        put("reconcile", "Ledger balance and open items", "رصيد الدفتر والبنود المفتوحة");
        put("ledgerOwed", "Owed per the ledger (closing balance)", "المستحق حسب الدفتر (الرصيد الختامي)");
        put("difference", "Difference", "الفرق");
        put("differenceNote", "Something other than an invoice, a payment or an opening item moved this account — a journal voucher, a credit note or an opening balance without open items. The ledger balance is the one that counts.",
                "حرّك هذا الحساب قيدٌ غير الفواتير والدفعات والبنود الافتتاحية — قيد يومية أو إشعار دائن أو رصيد افتتاحي بلا بنود مفتوحة. رصيد الدفتر هو المعتمد.");
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

    /**
     * The statement's account activity as CSV (tutorial 19: a spreadsheet alongside the PDF) —
     * the same rows, labels and Dr/Cr balances, plain numbers for the amounts.
     */
    public byte[] csv(Statement s, String lang) {
        boolean ar = "ar".equals(lang);
        AccountLedgerDTO g = s.ledger();
        java.util.List<java.util.List<String>> out = new java.util.ArrayList<>();
        String name = ar && s.vendorNameAr() != null && !s.vendorNameAr().isBlank() ? s.vendorNameAr() : s.vendorName();
        out.add(java.util.List.of(label("vendor", ar), name == null ? "" : name));
        out.add(java.util.List.of(label("period", ar), s.from() + " – " + s.to()));
        out.add(java.util.List.of(label("date", ar), label("entry", ar), label("doc", ar), label("narration", ar),
                label("debit", ar), label("credit", ar), label("balance", ar)));
        out.add(java.util.List.of("", "", "", label("opening", ar), "", "", balance(g.openingBalance(), ar)));
        for (LedgerRowDTO r : g.rows()) {
            String text = r.narration() != null && !r.narration().isBlank() ? r.narration() : r.particular();
            out.add(java.util.List.of(r.entryDate().toString(), nz(r.entryNumber()), nz(r.docType()), nz(text),
                    plain(r.debit()), plain(r.credit()), balance(r.balance(), ar)));
        }
        out.add(java.util.List.of("", "", "", label("closing", ar), plain(g.totalDebit()), plain(g.totalCredit()),
                balance(s.closingBalance(), ar)));
        if (g.truncated()) out.add(java.util.List.of(label("truncated", ar)));
        return com.datagami.rentaxis.core.service.report.statement.ReportCsv.encode(out);
    }

    private static String nz(String v) { return v == null ? "" : v; }

    private static String plain(BigDecimal v) {
        return v == null || v.signum() == 0 ? "" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
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
                .append(PdfRow.cells(ar, th(label("date", ar), false), th(label("entry", ar), false),
                        th(label("doc", ar), false), th(label("narration", ar), false), th(label("debit", ar), true),
                        th(label("credit", ar), true), th(label("balance", ar), true))).append("</tr>");
        b.append("<tr>").append(PdfRow.cells(ar, "<td colspan=\"6\">" + esc(label("opening", ar)) + "</td>",
                num(balance(g.openingBalance(), ar)))).append("</tr>");
        for (LedgerRowDTO r : g.rows()) {
            String text = r.narration() != null && !r.narration().isBlank() ? r.narration() : r.particular();
            b.append("<tr>").append(PdfRow.cells(ar, "<td><span class=\"ltr\">" + DAY.format(r.entryDate()) + "</span></td>",
                    "<td>" + esc(r.entryNumber()) + "</td>", "<td>" + esc(r.docType()) + "</td>", "<td>" + esc(text) + "</td>",
                    num(amount(r.debit())), num(amount(r.credit())), num(balance(r.balance(), ar)))).append("</tr>");
        }
        b.append("<tr class=\"total\">").append(PdfRow.cells(ar, "<td colspan=\"4\">" + esc(label("closing", ar)) + "</td>",
                num(amount(g.totalDebit())), num(amount(g.totalCredit())), num(balance(s.closingBalance(), ar))))
                .append("</tr></table>");
        if (g.truncated()) b.append("<div class=\"note\">").append(esc(label("truncated", ar))).append("</div>");

        // Open items as of `to`.
        b.append("<h2>").append(esc(label("openItems", ar))).append(" <span class=\"ltr\">").append(DAY.format(s.to()))
                .append("</span></h2>");
        BigDecimal totalOpen = BigDecimal.ZERO;
        if (s.openItems().isEmpty()) {
            b.append("<div class=\"note\">").append(esc(label("none", ar))).append("</div>");
        } else {
            b.append("<table><tr>").append(PdfRow.cells(ar, th(label("invoice", ar), false), th(label("entry", ar), false),
                    th(label("invoiceDate", ar), false), th(label("dueDate", ar), false), th(label("daysOverdue", ar), true),
                    th(label("gross", ar), true), th(label("paid", ar), true), th(label("open", ar), true))).append("</tr>");
            for (OpenItemDTO i : s.openItems()) {
                totalOpen = totalOpen.add(i.open());
                b.append("<tr>").append(PdfRow.cells(ar, "<td>" + esc(i.invoiceNumber()) + "</td>",
                        "<td>" + esc(i.docNumber()) + "</td>",
                        "<td><span class=\"ltr\">" + DAY.format(i.invoiceDate()) + "</span></td>",
                        "<td><span class=\"ltr\">" + DAY.format(i.dueDate()) + "</span></td>",
                        num(String.valueOf(i.daysOverdue())), num(amount(i.gross())), num(amount(i.allocated())),
                        num(amount(i.open())))).append("</tr>");
            }
            b.append("<tr class=\"total\">").append(PdfRow.cells(ar, "<td colspan=\"7\">" + esc(label("totalOpen", ar)) + "</td>",
                    num(amount(totalOpen)))).append("</tr></table>");
        }

        // Unallocated payments.
        BigDecimal totalAdvance = BigDecimal.ZERO;
        if (!s.advances().isEmpty()) {
            b.append("<h2>").append(esc(label("advances", ar))).append("</h2><table><tr>")
                    .append(PdfRow.cells(ar, th(label("payment", ar), false), th(label("date", ar), false),
                            th(label("paid", ar), true), th(label("unallocated", ar), true))).append("</tr>");
            for (AdvanceDTO a : s.advances()) {
                totalAdvance = totalAdvance.add(a.unallocated());
                b.append("<tr>").append(PdfRow.cells(ar, "<td>" + esc(a.voucherNumber()) + "</td>",
                        "<td><span class=\"ltr\">" + DAY.format(a.docDate()) + "</span></td>", num(amount(a.paid())),
                        num(amount(a.unallocated())))).append("</tr>");
            }
            b.append("</table>");
        }
        // The two balances side by side, with the gap between them named (review P3-7).
        BigDecimal net = totalOpen.subtract(totalAdvance);
        BigDecimal ledgerOwed = (s.closingBalance() == null ? BigDecimal.ZERO : s.closingBalance()).negate();
        BigDecimal diff = ledgerOwed.subtract(net);
        b.append("<h2>").append(esc(label("reconcile", ar))).append("</h2><table>")
                .append("<tr>").append(PdfRow.cells(ar, "<td>" + esc(label("net", ar)) + "</td>", num(signed(net)))).append("</tr>")
                .append("<tr>").append(PdfRow.cells(ar, "<td>" + esc(label("ledgerOwed", ar)) + "</td>", num(signed(ledgerOwed))))
                .append("</tr>");
        if (diff.signum() != 0) {
            b.append("<tr class=\"total\">").append(PdfRow.cells(ar, "<td>" + esc(label("difference", ar)) + "</td>",
                    num(signed(diff)))).append("</tr></table>")
                    .append("<div class=\"note\">").append(esc(label("differenceNote", ar))).append("</div>");
        } else {
            b.append("</table>");
        }

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

    private static String signed(BigDecimal v) {
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
            // Shared sanitiser: no bidi controls or glyph-less characters (R3 portal3 F11).
            builder.withHtmlContent(com.datagami.rentaxis.core.util.PdfText.clean(html), null);
            builder.toStream(baos);
            builder.run();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render the supplier statement PDF", e);
        }
    }
}
