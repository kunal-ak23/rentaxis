package com.datagami.rentaxis.core.service.report.grid;

import java.util.Set;

/**
 * One column of a report grid.
 *
 * @param key        the cell key rows use
 * @param labelKey   the header's key in the reports bundle
 * @param kind       what the column holds
 * @param widthChars the Excel width in characters
 * @param totalRule  what the column prints on Sub Total and REPORT TOTAL rows
 * @param hidden     present in the data but hidden on screen and in Excel
 * @param datePattern a {@link ReportFormat#DATE_PATTERNS} entry; DATE columns only
 * @param debitKey   the MONEY column that raises the balance; BALANCE columns only
 * @param creditKey  the MONEY column that lowers the balance; BALANCE columns only
 * @param link       drill-down target ({@code journal}, {@code lease} or {@code cheque}), or {@code null}
 */
public record ReportColumn(String key, String labelKey, Kind kind, int widthChars, TotalRule totalRule, boolean hidden,
                           String datePattern, String debitKey, String creditKey, String link) {

    /** The drill-down targets the web knows how to open. */
    public static final Set<String> LINKS = Set.of("journal", "lease", "cheque");

    public ReportColumn {
        requireText(key, "key");
        requireText(labelKey, "labelKey");
        if (kind == null || totalRule == null) {
            throw new IllegalArgumentException("Column " + key + " needs a kind and a total rule");
        }
        if (widthChars < 1) {
            throw new IllegalArgumentException("Column " + key + " needs a positive width");
        }
        if (kind == Kind.DATE) {
            if (!ReportFormat.DATE_PATTERNS.contains(datePattern)) {
                throw new IllegalArgumentException("Date column " + key + " needs one of " + ReportFormat.DATE_PATTERNS
                        + ", not " + datePattern);
            }
        } else if (datePattern != null) {
            throw new IllegalArgumentException("Only a DATE column has a date pattern: " + key);
        }
        if (kind == Kind.BALANCE) {
            if (isBlank(debitKey) || isBlank(creditKey) || totalRule != TotalRule.CLOSING_BALANCE) {
                throw new IllegalArgumentException("Balance column " + key
                        + " needs a debit key, a credit key and the CLOSING_BALANCE rule");
            }
        } else if (debitKey != null || creditKey != null || totalRule == TotalRule.CLOSING_BALANCE) {
            throw new IllegalArgumentException("Only a BALANCE column has debit/credit keys or a closing balance: " + key);
        }
        if (totalRule == TotalRule.SUM && kind != Kind.MONEY && kind != Kind.INT && kind != Kind.PERCENT) {
            throw new IllegalArgumentException("Only MONEY, INT and PERCENT columns can be summed: " + key);
        }
        if (link != null && !LINKS.contains(link)) {
            throw new IllegalArgumentException("Column " + key + " links to an unknown target " + link);
        }
    }

    public static ReportColumn text(String key, String labelKey, int width) {
        return new ReportColumn(key, labelKey, Kind.TEXT, width, TotalRule.NONE, false, null, null, null, null);
    }

    public static ReportColumn idText(String key, String labelKey, int width) {
        return idText(key, labelKey, width, null);
    }

    /** An identifier column whose cells drill down to {@code link}: {@code journal}, {@code lease} or {@code cheque}. */
    public static ReportColumn idText(String key, String labelKey, int width, String link) {
        return new ReportColumn(key, labelKey, Kind.ID_TEXT, width, TotalRule.NONE, false, null, null, null, link);
    }

    public static ReportColumn date(String key, String labelKey, int width, String pattern) {
        return new ReportColumn(key, labelKey, Kind.DATE, width, TotalRule.NONE, false, pattern, null, null, null);
    }

    /** A money column, summed on every subtotal. */
    public static ReportColumn money(String key, String labelKey, int width) {
        return new ReportColumn(key, labelKey, Kind.MONEY, width, TotalRule.SUM, false, null, null, null, null);
    }

    /** The running balance: {@code opening + Σ debit − Σ credit}, debit-positive; subtotals print the closing balance. */
    public static ReportColumn balance(String key, String labelKey, int width, String debitKey, String creditKey) {
        return new ReportColumn(key, labelKey, Kind.BALANCE, width, TotalRule.CLOSING_BALANCE, false, null,
                debitKey, creditKey, null);
    }

    public static ReportColumn integer(String key, String labelKey, int width, TotalRule rule) {
        return new ReportColumn(key, labelKey, Kind.INT, width, rule, false, null, null, null, null);
    }

    /** A 1-based row number that restarts in every group. */
    public static ReportColumn serial(String key, String labelKey, int width) {
        return new ReportColumn(key, labelKey, Kind.SERIAL, width, TotalRule.NONE, false, null, null, null, null);
    }

    /** This column, hidden. */
    public ReportColumn hiddenColumn() {
        return new ReportColumn(key, labelKey, kind, widthChars, totalRule, true, datePattern, debitKey, creditKey, link);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void requireText(String s, String what) {
        if (isBlank(s)) {
            throw new IllegalArgumentException("A report column needs a " + what);
        }
    }
}
