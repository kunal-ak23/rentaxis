package com.datagami.rentaxis.core.service.bank;

import java.util.List;

/**
 * A statement file read into rows of cells, before any mapping. A cell is a
 * {@code String}, a {@code java.time.LocalDate} (an Excel date cell), a
 * {@code java.math.BigDecimal} (an Excel number) or {@code null}. Row 0 is the
 * file's first row (spreadsheet row 1).
 */
public record StatementGrid(List<List<Object>> rows, List<String> sheetNames, String sheetName) {

    public Object cell(int row, int col) {
        if (row < 0 || row >= rows.size()) return null;
        List<Object> r = rows.get(row);
        return col < 0 || col >= r.size() ? null : r.get(col);
    }

    public static String text(Object cell) {
        if (cell == null) return "";
        if (cell instanceof java.math.BigDecimal d) return d.stripTrailingZeros().toPlainString();
        if (cell instanceof java.time.LocalDate d) return d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        return cell.toString().trim();
    }
}
