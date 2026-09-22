package com.datagami.rentaxis.core.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reading a spreadsheet the way the importers read it.
 *
 * <p>These four things — a cell as a string, "is this row blank", a cell by header
 * name, and the header index itself — were private to
 * {@code PortfolioImportService}, which is fine while there is one importer. The
 * cut-over validator lives in another package and has to read the <em>same</em>
 * cells the same way: a date-formatted numeric cell has to come back as
 * {@code YYYY-MM-DD} in both, or a workbook validates against one interpretation
 * and persists under another. Copying them was the alternative, and a copied
 * {@code getCellString} is exactly the kind of thing that drifts on the
 * {@code FORMULA} branch and is never noticed.</p>
 *
 * <p>{@code PortfolioImportService} keeps its own one-line private wrappers so
 * none of its ~30 call sites changed; {@code PortfolioImportPersistService} keeps
 * its own copy, deliberately untouched — the v1 persist path is not this task's
 * to rewrite.</p>
 */
public final class SheetCells {

    private SheetCells() {
    }

    /**
     * A cell as text: trimmed, with a date-formatted number rendered ISO and a
     * whole number rendered without Excel's trailing {@code .0}.
     */
    public static String getCellString(Row row, int col) {
        Cell cell = row.getCell(col);
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception e) { yield String.valueOf(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    /** True when every cell of the row reads as empty — the "blank line in the middle" case. */
    public static boolean isRowEmpty(Row row) {
        for (int i = 0; i < row.getLastCellNum(); i++) {
            if (!getCellString(row, i).isEmpty()) return false;
        }
        return true;
    }

    /** Reads a cell by header name. Returns "" when the header is absent. */
    public static String cell(Row row, HeaderIndex hi, String header) {
        int c = hi.col(header);
        return c < 0 ? "" : getCellString(row, c);
    }

    /**
     * The formats a date cell may be typed in.
     *
     * <p>ISO is what the template emits and what every error message names. The two
     * day-first forms are there because the client's own PACT exports print
     * {@code 28-08-2025} (see the General Ledger exports), and an accountant
     * assembling a cut-over workbook pastes from those. A pasted column that is a
     * hard error on every row is a workbook nobody can import, and the alternative —
     * guessing between {@code 03-04-2026} as March and as April — is not one this
     * takes: day-first is unambiguous in both listed patterns because the month
     * position is fixed.</p>
     *
     * <p>Month-first ({@code MM/dd/yyyy}) is deliberately <b>not</b> accepted. It
     * cannot be told apart from day-first for the first twelve days of a month, and
     * silently reading 04/03/2026 as 3 April in one workbook and 4 March in another
     * is the kind of error that surfaces a year later as a rent period nobody can
     * explain.</p>
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd-MM-uuuu", Locale.ROOT),
            DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ROOT));

    /** The date this cell holds, or null when it is blank or in none of {@link #DATE_FORMATS}. */
    public static LocalDate parseDateOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(value, f);
            } catch (DateTimeParseException ignored) {
                // try the next shape; an unparseable value is the caller's error to report
            }
        }
        return null;
    }

    /**
     * Maps header names (case-insensitive, trimmed) to column indexes for a sheet.
     * Reading columns by name is what lets a new column be appended to the template
     * without breaking a workbook downloaded last month.
     */
    public static final class HeaderIndex {
        private final Map<String, Integer> byName;

        public HeaderIndex(Sheet sheet) {
            Map<String, Integer> m = new HashMap<>();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            if (header == null) header = sheet.getRow(0);
            if (header != null) {
                for (int c = 0; c < header.getLastCellNum(); c++) {
                    Cell cell = header.getCell(c);
                    if (cell == null) continue;
                    String v = cell.getCellType() == CellType.STRING
                            ? cell.getStringCellValue().trim()
                            : "";
                    if (!v.isEmpty()) m.put(v.toLowerCase(Locale.ROOT), c);
                }
            }
            this.byName = m;
        }

        /** -1 when the header isn't present (old template). */
        public int col(String name) {
            Integer v = byName.get(name.toLowerCase(Locale.ROOT));
            return v == null ? -1 : v;
        }

        public boolean has(String name) {
            return byName.containsKey(name.toLowerCase(Locale.ROOT));
        }
    }
}
