package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The bank reconciliation statement, English or Arabic (finance-ops spec §4
 * "Reconciliation report"): the header (bank, masked IBAN, ledger accounts,
 * period), the formula table, the item lists, matched counts by method, and who
 * prepared and finalized it.
 *
 * <p>The stack and resource policy of the supplier statement: openhtmltopdf, the
 * Noto fonts as classpath streams, and {@link PdfResourcePolicy} so nothing but
 * inline {@code data:} URIs loads. Every value from the bank's file or a user is
 * escaped. Amounts and dates use Latin digits in LTR spans inside an RTL page.</p>
 */
@Component
public class BankReconciliationPdfRenderer {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Dubai"));

    private static final Map<String, String[]> L = new HashMap<>();

    private static void put(String key, String en, String ar) { L.put(key, new String[]{en, ar}); }

    static {
        put("title", "Bank reconciliation statement", "كشف التسوية البنكية");
        put("bank", "Bank account", "الحساب البنكي");
        put("iban", "IBAN", "رقم الآيبان");
        put("accountNo", "Account number", "رقم الحساب");
        // F14-48: the CSV export's own labels.
        put("csvFigure", "Figure", "البند");
        put("csvStatementBalance", "Balance per bank statement", "الرصيد حسب كشف البنك");
        put("csvBooks", "Balance per books", "الرصيد حسب الدفاتر");
        put("csvBookedAfter", "Booked after the period", "مسجلة بعد الفترة");
        put("csvUnrecorded", "Unrecorded statement items", "بنود الكشف غير المسجلة");
        put("csvSection", "Section", "القسم");
        put("csvWithoutEvidence", "Cleared without statement evidence", "مقاصة بلا دليل من الكشف");
        put("csvDit", "Deposit in transit", "إيداع في الطريق");
        put("csvUnpresented", "Unpresented payment", "دفعة لم تقدم بعد");
        put("csvUnrecordedItem", "Unrecorded statement item", "بند كشف غير مسجل");
        put("yes", "yes", "نعم");
        put("leaves", "Ledger accounts", "حسابات الأستاذ");
        put("period", "Period", "الفترة");
        put("status", "Status", "الحالة");
        put("DRAFT", "Draft", "مسودة");
        put("FINALIZED", "Finalized", "معتمدة");
        put("REOPENED", "Reopened", "أعيد فتحها");
        put("perStatement", "Balance per bank statement at", "الرصيد حسب كشف البنك في");
        put("dit", "Add: deposits in transit", "يضاف: إيداعات في الطريق");
        put("unpresented", "Less: unpresented payments", "يطرح: مدفوعات لم تقدم بعد");
        put("bookedAfter", "Less: on the statement, booked after the period", "يطرح: في الكشف ومسجلة بعد الفترة");
        put("adjustedBank", "Adjusted bank balance", "رصيد البنك المعدل");
        put("perBooks", "Balance per books at", "الرصيد حسب الدفاتر في");
        put("unrecorded", "Add/(less): statement items not yet in the books", "يضاف/(يطرح): بنود الكشف غير المسجلة في الدفاتر");
        put("adjustedBook", "Adjusted book balance", "رصيد الدفاتر المعدل");
        put("difference", "Difference", "الفرق");
        put("continuity", "Statement opening + lines = closing", "رصيد الكشف الافتتاحي + الحركات = الختامي");
        put("date", "Date", "التاريخ");
        put("document", "Document", "المستند");
        put("narration", "Details", "البيان");
        put("cheque", "Cheque", "الشيك");
        put("amount", "Amount", "المبلغ");
        put("none", "None", "لا يوجد");
        put("ditList", "Deposits in transit", "إيداعات في الطريق");
        put("unpresentedList", "Unpresented payments", "مدفوعات لم تقدم بعد");
        put("bookedAfterList", "On the statement, booked after the period", "في الكشف ومسجلة بعد الفترة");
        put("unrecordedList", "Statement items not yet in the books", "بنود الكشف غير المسجلة");
        put("noEvidence", "cleared by hand, no statement line yet", "مقاصة يدوية بلا سطر في الكشف بعد");
        put("matched", "Matched lines by method", "الأسطر المطابقة حسب الطريقة");
        put("prepared", "Prepared by", "أعدها");
        put("finalized", "Finalized by", "اعتمدها");
        put("reopened", "Reopened by", "أعاد فتحها");
        put("on", "on", "في");
        put("reason", "Reason", "السبب");
        put("AUTO_CHEQUE", "Cheque number", "رقم الشيك");
        put("AUTO_REFERENCE", "Reference", "المرجع");
        put("AUTO_AMOUNT_DATE", "Amount and date", "المبلغ والتاريخ");
        put("AUTO_GROUP", "Deposit group", "مجموعة إيداع");
        put("MANUAL", "Manual", "يدوي");
        put("CREATED", "Booked from the line", "مسجل من السطر");
        put("CONTRA", "Contra", "قيد عكسي");
    }

    public static String label(String key, boolean ar) {
        String[] l = L.get(key);
        return l == null ? key : ar ? l[1] : l[0];
    }

    public byte[] render(BankRecDTOs.Reconciliation r, String lang) {
        return renderPdf(html(r, lang));
    }

    /** The markup, package-visible so a test can read what went into the PDF. */
    String html(BankRecDTOs.Reconciliation r, String lang) {
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
                .append(".footer { margin-top: 4mm; font-size: 8pt; color: #555; page-break-before: avoid; page-break-inside: avoid; }")
                .append("table.meta { width: auto; margin: 0 0 1mm 0; } table.meta td { border: none; padding: 0.3mm 1.5mm 0.3mm 0; color: #555; }")
                .append("</style></head><body>");
        b.append("<h1>").append(esc(label("title", ar))).append("</h1>");
        meta(b, label("bank", ar), esc(r.bankLabel()));
        // F14-48: an account with no IBAN shows its account number, labelled as one.
        boolean iban = r.ibanMasked() != null && r.ibanMasked().matches("^[A-Za-z]{2}.*");
        meta(b, label(iban ? "iban" : "accountNo", ar), "<span class=\"ltr\">" + esc(r.ibanMasked()) + "</span>");
        meta(b, label("leaves", ar), esc(r.leaves().stream().map(l -> l.code() + " " + l.name()).collect(Collectors.joining(", "))));
        meta(b, label("period", ar), "<span class=\"ltr\">" + DAY.format(r.periodFrom()) + " – " + DAY.format(r.periodTo()) + "</span>");
        meta(b, label("status", ar), esc(label(r.status(), ar)));

        // The formula.
        String to = "<span class=\"ltr\">" + DAY.format(r.periodTo()) + "</span>";
        b.append("<table>");
        row(b, esc(label("perStatement", ar)) + " " + to, r.statementClosing(), false);
        row(b, esc(label("dit", ar)), r.depositsInTransit(), false);
        row(b, esc(label("unpresented", ar)), neg(r.unpresentedPayments()), false);
        if (r.bookedAfterPeriod() != null && r.bookedAfterPeriod().signum() != 0) {
            row(b, esc(label("bookedAfter", ar)), neg(r.bookedAfterPeriod()), false);
        }
        row(b, esc(label("adjustedBank", ar)), r.adjustedBank(), true);
        row(b, esc(label("perBooks", ar)) + " " + to, r.bookBalance(), false);
        row(b, esc(label("unrecorded", ar)), r.unrecordedCredits().subtract(r.unrecordedDebits()), false);
        row(b, esc(label("adjustedBook", ar)), r.adjustedBook(), true);
        row(b, esc(label("difference", ar)), r.difference(), true);
        b.append("</table>");
        if (r.statementOpening() != null && r.statementClosing() != null) {
            b.append("<div class=\"note\">").append(esc(label("continuity", ar))).append(": <span class=\"ltr\">")
                    .append(amount(r.statementOpening())).append(" + ").append(amount(r.statementMovement()))
                    .append(" = ").append(amount(r.statementClosing())).append("</span></div>");
        }

        items(b, label("ditList", ar), r.depositsInTransitItems(), ar);
        items(b, label("unpresentedList", ar), r.unpresentedItems(), ar);
        if (!r.bookedAfterItems().isEmpty()) items(b, label("bookedAfterList", ar), r.bookedAfterItems(), ar);
        if (!r.unrecordedItems().isEmpty()) items(b, label("unrecordedList", ar), r.unrecordedItems(), ar);

        b.append("<h2>").append(esc(label("matched", ar))).append("</h2><table>");
        if (r.matchedByMethod().isEmpty()) {
            b.append("<tr><td>").append(esc(label("none", ar))).append("</td></tr>");
        }
        r.matchedByMethod().forEach((m, n) -> b.append("<tr><td>").append(esc(label(m, ar))).append("</td>")
                .append(num(String.valueOf(n))).append("</tr>"));
        b.append("</table>");

        b.append("<div class=\"footer\">");
        stamp(b, label("prepared", ar), r.preparedByName(), r.preparedAt(), ar);
        if (r.finalizedAt() != null) stamp(b, label("finalized", ar), r.finalizedByName(), r.finalizedAt(), ar);
        if (r.reopenedAt() != null) {
            stamp(b, label("reopened", ar), r.reopenedByName(), r.reopenedAt(), ar);
            b.append("<div>").append(esc(label("reason", ar))).append(": ").append(esc(r.reopenReason())).append("</div>");
        }
        b.append("</div></body></html>");
        return b.toString();
    }

    /**
     * F14-48: label and value in cells of their own, so in Arabic an LTR value
     * ("PROBE-A 0001") cannot run into the RTL label.
     */
    private static void meta(StringBuilder b, String key, String valueHtml) {
        b.append("<table class=\"meta\"><tr><td>").append(esc(key)).append(":</td><td><b>").append(valueHtml)
                .append("</b></td></tr></table>");
    }

    private static void row(StringBuilder b, String labelHtml, BigDecimal v, boolean total) {
        b.append("<tr").append(total ? " class=\"total\"" : "").append("><td>").append(labelHtml).append("</td>")
                .append(num(v == null ? "—" : amount(v))).append("</tr>");
    }

    private static void items(StringBuilder b, String title, List<BankRecDTOs.RecItem> items, boolean ar) {
        b.append("<h2>").append(esc(title)).append("</h2>");
        if (items.isEmpty()) {
            b.append("<div class=\"note\">").append(esc(label("none", ar))).append("</div>");
            return;
        }
        b.append("<table><tr><th>").append(esc(label("date", ar))).append("</th><th>").append(esc(label("document", ar)))
                .append("</th><th>").append(esc(label("narration", ar))).append("</th><th>").append(esc(label("cheque", ar)))
                .append("</th><th class=\"num\">").append(esc(label("amount", ar))).append("</th></tr>");
        BigDecimal total = BigDecimal.ZERO;
        for (BankRecDTOs.RecItem i : items) {
            total = total.add(i.amount());
            b.append("<tr><td><span class=\"ltr\">").append(i.date() == null ? "" : DAY.format(i.date())).append("</span></td>")
                    .append("<td>").append(esc(i.document())).append("</td>")
                    .append("<td>").append(esc(i.narration()))
                    .append(i.withoutEvidence() ? " <i>(" + esc(label("noEvidence", ar)) + ")</i>" : "").append("</td>")
                    .append("<td><span class=\"ltr\">").append(esc(i.chequeNo())).append("</span></td>")
                    .append(num(amount(i.amount()))).append("</tr>");
        }
        b.append("<tr class=\"total\"><td colspan=\"4\"></td>").append(num(amount(total))).append("</tr></table>");
    }

    private static void stamp(StringBuilder b, String what, String who, java.time.Instant when, boolean ar) {
        b.append("<div>").append(esc(what)).append(" ").append(esc(who == null ? "—" : who));
        if (when != null) {
            b.append(" ").append(esc(label("on", ar))).append(" <span class=\"ltr\">").append(STAMP.format(when)).append("</span>");
        }
        b.append("</div>");
    }

    private static BigDecimal neg(BigDecimal v) {
        return v == null ? null : v.negate();
    }

    private static String num(String text) {
        return "<td class=\"num\"><span>" + esc(text) + "</span></td>";
    }

    static String amount(BigDecimal v) {
        if (v == null) return "";
        String n = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)).format(v.abs());
        return v.signum() < 0 ? "(" + n + ")" : n;
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
            throw new UncheckedIOException("Failed to render the bank reconciliation PDF", e);
        }
    }
}
