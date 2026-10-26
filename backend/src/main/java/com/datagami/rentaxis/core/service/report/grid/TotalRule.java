package com.datagami.rentaxis.core.service.report.grid;

/**
 * What a column prints on a Sub Total and the REPORT TOTAL.
 *
 * <ul>
 *   <li>{@code NONE}: nothing.</li>
 *   <li>{@code SUM}: the sum of the column (MONEY, INT and PERCENT columns only).</li>
 *   <li>{@code CLOSING_BALANCE}: the group's closing balance (BALANCE columns, which always use it).</li>
 *   <li>{@code COUNT}: the number of rows.</li>
 * </ul>
 */
public enum TotalRule {
    NONE, SUM, CLOSING_BALANCE, COUNT
}
