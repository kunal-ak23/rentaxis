package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.api.dto.report.BalanceSheetDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Amount;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Column;
import com.datagami.rentaxis.core.service.report.ReportPdf.Row;
import com.datagami.rentaxis.core.service.report.ReportPdf.Style;
import com.datagami.rentaxis.core.service.report.statement.ReportCsv;
import com.datagami.rentaxis.core.service.report.statement.StatementLabels;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PDF and CSV of the balance sheet, the company P&L and the property P&L
 * (F14-10), from the same DTOs the JSON endpoints return — so an export can never
 * disagree with the screen. CSV goes through {@link ReportCsv#encode} (formula
 * injection guard, BOM); amounts in CSV are plain decimals, in PDF grouped.
 */
public final class FinancialReportExport {

    private FinancialReportExport() { }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ENGLISH);

    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(
            () -> new DecimalFormat("#,##0.00;-#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH)));

    public static String money(BigDecimal v) {
        return v == null ? "" : MONEY.get().format(v);
    }

    static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    static String l(String lang) {
        return "ar".equals(lang) ? "ar" : "en";
    }

    static String lbl(String key, String lang) {
        return StatementLabels.of(key, l(lang));
    }

    static String pick(String en, String ar, String lang) {
        return "ar".equals(l(lang)) && ar != null && !ar.isBlank() ? ar : (en == null ? "" : en);
    }

    static String day(LocalDate d) {
        return d == null ? "" : DAY.format(d);
    }

    static String columnName(Column c, String lang) {
        return "PROPERTY".equals(c.kind()) ? pick(c.name(), c.nameAr(), lang) : lbl("pnl." + c.kind(), lang);
    }

    /** One report line: a label and the figures under every column (amount, and the comparative when asked). */
    private record Line(Style style, String label, Map<String, Amount> cells) { }

    // ------------------------------------------------------------------ balance sheet

    private static List<Line> balanceSheetLines(BalanceSheetDTO r, String lang) {
        List<Line> out = new ArrayList<>();
        for (BalanceSheetDTO.Section s : r.sections()) {
            out.add(new Line(Style.HEAD, lbl("bs." + s.type(), lang), null));
            for (PropertyPnlDTO.Group g : s.groups()) {
                for (PropertyPnlDTO.Row row : g.rows()) out.add(new Line(Style.PLAIN, "  " + pick(row.label(), row.labelAr(), lang), row.cells()));
                out.add(new Line(Style.STRONG, pick(g.name(), g.nameAr(), lang), g.subtotal()));
            }
            if ("EQUITY".equals(s.type())) {
                out.add(new Line(Style.PLAIN, "  " + lbl("bs.earlierYearsResult", lang), r.earlierYearsResult()));
                out.add(new Line(Style.PLAIN, "  " + lbl("bs.currentYearResult", lang), r.currentYearResult()));
            }
            out.add(new Line(Style.TOTAL, lbl("bs.total." + s.type(), lang), s.total()));
        }
        out.add(new Line(Style.TOTAL, lbl("bs.liabilitiesAndEquity", lang), r.liabilitiesAndEquity()));
        out.add(new Line(r.ok() ? Style.OK : Style.BAD, lbl("bs.check", lang), r.check()));
        return out;
    }

    public static byte[] balanceSheetPdf(BalanceSheetDTO r, String lang) {
        boolean prior = r.compareAt() != null;
        List<String> meta = new ArrayList<>();
        meta.add(lbl("bs.asAt", lang) + ": " + day(r.asAt()));
        if (prior) meta.add(lbl("bs.compareAt", lang) + ": " + day(r.compareAt()));
        meta.add(lbl("bs.fiscalYearStart", lang) + ": " + day(r.fiscalYearStart()));
        List<String> notes = new ArrayList<>();
        notes.add(r.ok() ? lbl("bs.checkOk", lang) : lbl("bs.checkBad", lang));
        if (r.columns().size() > 1) notes.add(lbl("bs.propertyNote", lang));
        return ReportPdf.render(new ReportPdf.Doc(l(lang), lbl("bs.title", lang), meta,
                List.of(table(balanceSheetLines(r, lang), r.columns(), prior, lang)), notes, r.columns().size() > 3));
    }

    public static byte[] balanceSheetCsv(BalanceSheetDTO r, String lang) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(lbl("bs.title", lang), lbl("bs.asAt", lang), r.asAt().toString(),
                r.compareAt() == null ? "" : lbl("bs.compareAt", lang), r.compareAt() == null ? "" : r.compareAt().toString()));
        rows.addAll(csv(balanceSheetLines(r, lang), r.columns(), r.compareAt() != null, lang));
        return ReportCsv.encode(rows);
    }

    // ------------------------------------------------------------------ P&L

    private static List<Line> pnlLines(PropertyPnlDTO r, String lang) {
        List<Line> out = new ArrayList<>();
        String type = null;
        for (PropertyPnlDTO.Group g : r.groups()) {
            if (!g.accountType().equals(type)) {
                type = g.accountType();
                out.add(new Line(Style.HEAD, lbl("INCOME".equals(type) ? "pnl.income" : "pnl.expenses", lang), null));
            }
            for (PropertyPnlDTO.Row row : g.rows()) out.add(new Line(Style.PLAIN, "  " + pick(row.label(), row.labelAr(), lang), row.cells()));
            out.add(new Line(Style.STRONG, pick(g.name(), g.nameAr(), lang), g.subtotal()));
        }
        out.add(new Line(Style.TOTAL, lbl("pnl.income", lang), r.income()));
        out.add(new Line(Style.TOTAL, lbl("pnl.expenses", lang), r.expenses()));
        out.add(new Line(Style.TOTAL, lbl("pnl.noi", lang), r.noi()));
        return out;
    }

    /** The company P&L is the Total column: {@code company} keeps only it. */
    public static byte[] pnlPdf(PropertyPnlDTO r, String lang, boolean company) {
        boolean prior = r.priorFrom() != null;
        List<Column> columns = company ? r.columns().stream().filter(c -> PropertyPnlDTO.TOTAL.equals(c.key())).toList() : r.columns();
        // A wide property report prints the figures only; the comparison stays in the CSV.
        boolean withPrior = prior && (company || columns.size() <= 3);
        List<String> meta = new ArrayList<>();
        meta.add(lbl("pnl.periodLabel", lang) + ": " + day(r.from()) + " – " + day(r.to()));
        if (prior) meta.add(lbl("pnl.priorPeriod", lang) + ": " + day(r.priorFrom()) + " – " + day(r.priorTo()));
        List<String> notes = new ArrayList<>();
        if (r.check() != null) {
            notes.add(r.check().ok() ? lbl("pnl.checkOk", lang)
                    : lbl("pnl.check", lang) + ": " + money(r.check().difference()));
        }
        if (prior && !withPrior) notes.add(lbl("pnl.compareInCsv", lang));
        String title = lbl(company ? "cpl.title" : "pnl.title", lang);
        return ReportPdf.render(new ReportPdf.Doc(l(lang), title, meta,
                List.of(table(pnlLines(r, lang), columns, withPrior, lang)), notes, !company && columns.size() > 3));
    }

    public static byte[] companyPnlCsv(PropertyPnlDTO r, String lang) {
        List<Column> columns = r.columns().stream().filter(c -> PropertyPnlDTO.TOTAL.equals(c.key())).toList();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of(lbl("cpl.title", lang), r.from().toString(), r.to().toString(),
                r.priorFrom() == null ? "" : r.priorFrom().toString(), r.priorTo() == null ? "" : r.priorTo().toString()));
        rows.addAll(csv(pnlLines(r, lang), columns, r.priorFrom() != null, lang));
        if (r.check() != null) rows.add(List.of(lbl("pnl.check", lang), plain(r.check().difference())));
        return ReportCsv.encode(rows);
    }

    // ------------------------------------------------------------------ shared

    private static List<String> header(List<Column> columns, boolean prior, String lang) {
        List<String> h = new ArrayList<>(List.of(lbl("pnl.line", lang)));
        boolean single = columns.size() == 1;
        for (Column c : columns) {
            String name = single ? lbl("cpl.current", lang) : columnName(c, lang);
            h.add(name);
            if (prior) {
                h.add(single ? lbl("cpl.prior", lang) : name + " " + lbl("pnl.prior", lang));
                h.add(single ? lbl("cpl.change", lang) : name + " " + lbl("pnl.delta", lang));
            }
        }
        return h;
    }

    private static ReportPdf.Table table(List<Line> lines, List<Column> columns, boolean prior, String lang) {
        List<String> header = header(columns, prior, lang);
        List<Boolean> numeric = new ArrayList<>();
        numeric.add(false);
        for (int i = 1; i < header.size(); i++) numeric.add(true);
        List<Row> rows = new ArrayList<>();
        for (Line line : lines) {
            List<String> cells = new ArrayList<>(List.of(line.label()));
            for (Column c : columns) {
                Amount a = line.cells() == null ? null : line.cells().get(c.key());
                cells.add(a == null ? "" : money(a.amount()));
                if (prior) {
                    cells.add(a == null ? "" : money(a.prior()));
                    cells.add(a == null ? "" : money(a.delta()));
                }
            }
            rows.add(Row.of(line.style(), cells));
        }
        return new ReportPdf.Table(null, header, numeric, rows);
    }

    private static List<List<String>> csv(List<Line> lines, List<Column> columns, boolean prior, String lang) {
        List<List<String>> out = new ArrayList<>();
        List<String> header = new ArrayList<>(header(columns, prior, lang));
        out.add(header);
        for (Line line : lines) {
            List<String> cells = new ArrayList<>(List.of(line.label().strip()));
            for (Column c : columns) {
                Amount a = line.cells() == null ? null : line.cells().get(c.key());
                cells.add(a == null ? "" : plain(a.amount()));
                if (prior) {
                    cells.add(a == null ? "" : plain(a.prior()));
                    cells.add(a == null ? "" : plain(a.delta()));
                }
            }
            out.add(cells);
        }
        return out;
    }
}
