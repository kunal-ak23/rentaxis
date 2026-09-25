package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.vat.VatReturnDTO;
import com.datagami.rentaxis.core.service.report.FinancialReportExport;
import com.datagami.rentaxis.core.service.report.ReportPdf;
import com.datagami.rentaxis.core.service.report.statement.ReportCsv;
import com.datagami.rentaxis.core.service.report.statement.StatementLabels;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** #55: the VAT return as a PDF (EN/AR, the report stack) and an injection-safe CSV, from the JSON the screen shows. */
public final class VatReturnExport {

    private VatReturnExport() { }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.of("Asia/Dubai"));

    private static String l(String lang) { return "ar".equals(lang) ? "ar" : "en"; }

    private static String lbl(String key, String lang) { return StatementLabels.of(key, l(lang)); }

    public static byte[] pdf(VatReturnDTO r, String lang) {
        List<String> meta = new ArrayList<>();
        meta.add(lbl("vat.period", lang) + ": " + DAY.format(r.periodStart()) + " – " + DAY.format(r.periodEnd()));
        meta.add(lbl("vat.status." + r.status(), lang)
                + (r.filedAt() == null ? "" : " · " + lbl("vat.filedAt", lang) + " " + STAMP.format(r.filedAt())
                + (r.filedByName() == null ? "" : " · " + r.filedByName()))
                + (r.filingReference() == null ? "" : " · " + lbl("vat.reference", lang) + " " + r.filingReference()));
        List<ReportPdf.Row> rows = new ArrayList<>();
        for (VatReturnDTO.Box b : r.boxes()) {
            rows.add(ReportPdf.Row.of(b.total() ? ReportPdf.Style.TOTAL : ReportPdf.Style.PLAIN,
                    List.of(b.code(), lbl("vat." + b.key(), lang), FinancialReportExport.money(b.amount()),
                            FinancialReportExport.money(b.vat()))));
        }
        List<String> notes = new ArrayList<>();
        if (r.outputCheck() != null) {
            notes.add(r.outputCheck().ok() ? lbl("vat.outputCheckOk", lang)
                    : lbl("vat.outputCheckBad", lang) + " " + FinancialReportExport.money(r.outputCheck().difference()));
        }
        if (r.commercialWithoutVat() != null && r.commercialWithoutVat().signum() != 0) {
            notes.add(lbl("vat.commercialWithoutVat", lang) + ": " + FinancialReportExport.money(r.commercialWithoutVat()));
        }
        if (r.inputVatOther() != null && r.inputVatOther().signum() != 0) {
            notes.add(lbl("vat.inputVatOther", lang) + ": " + FinancialReportExport.money(r.inputVatOther()));
        }
        if (r.inputVatOnExempt() != null && r.inputVatOnExempt().signum() != 0) {
            notes.add(lbl("vat.inputVatOnExempt", lang) + ": " + FinancialReportExport.money(r.inputVatOnExempt()));
        }
        ReportPdf.Table t = new ReportPdf.Table(null,
                List.of(lbl("vat.box", lang), lbl("vat.description", lang), lbl("vat.amount", lang), lbl("vat.vat", lang)),
                List.of(false, false, true, true), rows);
        return ReportPdf.render(new ReportPdf.Doc(l(lang), lbl("vat.title", lang), meta, List.of(t), notes, false));
    }

    public static byte[] csv(VatReturnDTO r, String lang) {
        List<List<String>> out = new ArrayList<>();
        out.add(List.of(lbl("vat.title", lang), r.periodStart().toString(), r.periodEnd().toString(),
                lbl("vat.status." + r.status(), lang), r.filingReference() == null ? "" : r.filingReference()));
        out.add(List.of(lbl("vat.box", lang), lbl("vat.description", lang), lbl("vat.amount", lang), lbl("vat.vat", lang)));
        for (VatReturnDTO.Box b : r.boxes()) {
            out.add(List.of(b.code(), lbl("vat." + b.key(), lang), plain(b.amount()), plain(b.vat())));
        }
        if (r.outputCheck() != null) out.add(List.of("", lbl("vat.outputCheckBad", lang), plain(r.outputCheck().difference())));
        out.add(List.of("", lbl("vat.inputVatOther", lang), plain(r.inputVatOther())));
        out.add(List.of("", lbl("vat.inputVatOnExempt", lang), plain(r.inputVatOnExempt())));
        return ReportCsv.encode(out);
    }

    private static String plain(BigDecimal v) { return v == null ? "" : v.toPlainString(); }
}
