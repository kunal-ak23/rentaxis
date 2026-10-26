package com.datagami.rentaxis.core.service.report.grid;

/**
 * What a report column holds, which decides the cell value the writer accepts and how each sink renders it.
 *
 * <ul>
 *   <li>{@code TEXT}: a {@link String}, user-typed text.</li>
 *   <li>{@code ID_TEXT}: a {@link String} identifier (cheque or document number); always a text cell, so leading zeros survive.</li>
 *   <li>{@code DATE}: a {@link java.time.LocalDate}, shown in the column's PACT pattern.</li>
 *   <li>{@code MONEY}: a {@link java.math.BigDecimal}, rounded to cents; {@code null} is blank and counts as zero.</li>
 *   <li>{@code BALANCE}: the running balance, filled by the writer from the column's debit and credit keys.</li>
 *   <li>{@code INT}: an {@link Integer} or {@link Long}.</li>
 *   <li>{@code SERIAL}: a 1-based row number within the group, filled by the writer.</li>
 *   <li>{@code PERCENT}: a {@link java.math.BigDecimal}.</li>
 * </ul>
 */
public enum Kind {
    TEXT, ID_TEXT, DATE, MONEY, BALANCE, INT, SERIAL, PERCENT
}
