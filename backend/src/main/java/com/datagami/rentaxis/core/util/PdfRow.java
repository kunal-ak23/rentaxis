package com.datagami.rentaxis.core.util;

/**
 * A table row's cells in the order openhtmltopdf lays them out.
 *
 * <p>openhtmltopdf shapes Arabic and runs its text right to left, but it does not
 * mirror a table's columns for {@code direction: rtl}: a row's first cell always
 * prints leftmost. An Arabic page therefore writes each row last cell first, so the
 * first logical column prints rightmost and a leading label spanning several columns
 * sits over the columns it covers, at the right. {@code ReportPdf} does the same for
 * the shared report tables. An English row is written as it is.</p>
 */
public final class PdfRow {

    private PdfRow() {
    }

    /** The cells' markup in logical order, joined; last first when {@code rtl}. */
    public static String cells(boolean rtl, String... cells) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            b.append(cells[rtl ? cells.length - 1 - i : i]);
        }
        return b.toString();
    }
}
