package com.datagami.rentaxis.core.service.report.statement;

import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Amount;
import com.datagami.rentaxis.api.dto.report.PropertyPnlDTO.Column;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Figure;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Section;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Table;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CSV for the P&L and the statement pack, from the same DTOs the JSON endpoints
 * return. Amounts are plain decimals (no thousands separator) so a spreadsheet
 * reads them as numbers. UTF-8 with a BOM, so Excel shows Arabic names.
 */
public final class ReportCsv {

    private ReportCsv() { }

    public static byte[] pnl(PropertyPnlDTO r, String lang) {
        String l = "ar".equals(lang) ? "ar" : "en";
        boolean prior = r.priorFrom() != null;
        List<List<String>> out = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of(StatementLabels.of("pnl.group", l), StatementLabels.of("pnl.line", l)));
        for (Column c : r.columns()) {
            String name = columnName(c, l);
            header.add(name);
            if (prior) {
                header.add(name + " " + StatementLabels.of("pnl.prior", l));
                header.add(name + " " + StatementLabels.of("pnl.delta", l));
                header.add(name + " " + StatementLabels.of("pnl.deltaPct", l));
            }
        }
        out.add(header);
        for (PropertyPnlDTO.Group g : r.groups()) {
            String group = pick(g.name(), g.nameAr(), l);
            for (PropertyPnlDTO.Row row : g.rows()) {
                out.add(amountRow(group, pick(row.label(), row.labelAr(), l), row.cells(), r.columns(), prior));
            }
            out.add(amountRow(group, "", g.subtotal(), r.columns(), prior));
        }
        out.add(amountRow("", StatementLabels.of("pnl.income", l), r.income(), r.columns(), prior));
        out.add(amountRow("", StatementLabels.of("pnl.expenses", l), r.expenses(), r.columns(), prior));
        out.add(amountRow("", StatementLabels.of("pnl.noi", l), r.noi(), r.columns(), prior));
        if (r.allocation() != null) {
            out.add(plainRow(StatementLabels.of("pnl.allocated", l), r.allocation().allocated(), r.columns(), prior));
            out.add(plainRow(StatementLabels.of("pnl.noiAfter", l), r.allocation().noiAfter(), r.columns(), prior));
            out.add(List.of("", StatementLabels.of("pnl.allocatedOthers", l), plain(r.allocation().allocatedToOthers())));
        }
        if (r.check() != null) {
            out.add(List.of("", StatementLabels.of("pnl.check", l), plain(r.check().difference())));
        }
        return encode(out);
    }

    public static byte[] statement(PropertyStatementDTO s, String lang) {
        String l = "ar".equals(lang) ? "ar" : "en";
        List<List<String>> out = new ArrayList<>();
        out.add(List.of(StatementLabels.of("title", l), pick(s.propertyName(), s.propertyNameAr(), l),
                s.from().toString(), s.to().toString()));
        for (Section sec : s.sections()) {
            out.add(List.of());
            out.add(List.of(sec.number() + ". " + StatementLabels.of("section." + sec.key(), l),
                    StatementLabels.of("source." + sec.source(), l)));
            for (Figure f : sec.figures()) {
                List<String> row = new ArrayList<>(List.of(StatementLabels.of("figure." + f.key(), l), plain(f.amount())));
                if (f.count() != null) row.add(String.valueOf(f.count()));
                out.add(row);
            }
            for (Table t : sec.tables()) {
                if (t.rows().isEmpty()) continue;
                List<Integer> cols = StatementFormat.visibleColumns(t);
                List<String> head = new ArrayList<>();
                for (int c : cols) head.add(StatementLabels.of("column." + t.columns().get(c), l));
                out.add(head);
                for (List<Object> row : t.rows()) {
                    List<String> cells = new ArrayList<>();
                    for (int c : cols) {
                        Object v = row.get(c);
                        cells.add(v instanceof BigDecimal b ? plain(b) : StatementFormat.cell(t, row, c, l));
                    }
                    out.add(cells);
                }
            }
            for (String note : sec.notes()) out.add(List.of(StatementLabels.of("note." + note, l)));
        }
        out.add(List.of());
        out.add(List.of(StatementLabels.of(s.footer().isFinal() ? "final" : "provisional", l)));
        return encode(out);
    }

    private static String columnName(Column c, String l) {
        return switch (c.kind()) {
            case "PROPERTY" -> pick(c.name(), c.nameAr(), l);
            default -> StatementLabels.of("pnl." + c.kind(), l);
        };
    }

    private static String pick(String en, String ar, String l) {
        return "ar".equals(l) && ar != null && !ar.isBlank() ? ar : (en == null ? "" : en);
    }

    private static List<String> amountRow(String group, String line, Map<String, Amount> cells, List<Column> columns, boolean prior) {
        List<String> row = new ArrayList<>(List.of(group, line));
        for (Column c : columns) {
            Amount a = cells.get(c.key());
            row.add(a == null ? "" : plain(a.amount()));
            if (prior) {
                row.add(a == null ? "" : plain(a.prior()));
                row.add(a == null ? "" : plain(a.delta()));
                row.add(a == null ? "" : plain(a.deltaPct()));
            }
        }
        return row;
    }

    private static List<String> plainRow(String line, Map<String, BigDecimal> cells, List<Column> columns, boolean prior) {
        List<String> row = new ArrayList<>(List.of("", line));
        for (Column c : columns) {
            row.add(plain(cells.get(c.key())));
            if (prior) { row.add(""); row.add(""); row.add(""); }
        }
        return row;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    public static byte[] encode(List<List<String>> rows) {
        StringBuilder b = new StringBuilder("﻿");
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) b.append(',');
                b.append(escape(row.get(i)));
            }
            b.append("\r\n");
        }
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * RFC 4180 quoting, plus a leading apostrophe on a value a spreadsheet would
     * run as a formula (names and narrations are user-typed).
     */
    static String escape(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?[0-9.]+")) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
