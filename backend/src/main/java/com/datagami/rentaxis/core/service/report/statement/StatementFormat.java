package com.datagami.rentaxis.core.service.report.statement;

import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Table;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How a statement table prints in one language, shared by the PDF and the CSV so
 * they cannot disagree: which columns show (the Arabic line label replaces the
 * English one under {@code ar}), and each cell as text. Numbers use Latin digits
 * in both languages, matching the tax invoice.
 */
final class StatementFormat {

    private StatementFormat() { }

    private static final ThreadLocal<DecimalFormat> MONEY = ThreadLocal.withInitial(
            () -> new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US)));

    static String money(BigDecimal v) {
        return v == null ? "" : MONEY.get().format(v);
    }

    /** Column indexes to print, in order. */
    static List<Integer> visibleColumns(Table t) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < t.columns().size(); i++) {
            if (!t.columns().get(i).equals("lineAr")) out.add(i);
        }
        return out;
    }

    static String cell(Table t, List<Object> row, int col, String lang) {
        String column = t.columns().get(col);
        Object v = row.get(col);
        if (column.equals("line") && "ar".equals(lang)) {
            int ar = t.columns().indexOf("lineAr");
            if (ar >= 0 && row.get(ar) instanceof String s && !s.isBlank()) v = s;
        }
        if (v == null) return "";
        if (v instanceof BigDecimal b) return money(b);
        if (column.equals("mode")) return StatementLabels.of("mode." + v, lang);
        if (column.equals("type")) return StatementLabels.of("type." + v, lang);
        if (column.equals("basis")) return StatementLabels.of("basis." + v, lang);
        return String.valueOf(v);
    }

    static boolean numeric(Table t, int col) {
        return switch (t.columns().get(col)) {
            case "amount", "prior", "delta", "net", "vat", "outputVat", "daysOverdue" -> true;
            default -> false;
        };
    }
}
