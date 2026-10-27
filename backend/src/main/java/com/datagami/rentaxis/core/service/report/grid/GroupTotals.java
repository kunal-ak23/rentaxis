package com.datagami.rentaxis.core.service.report.grid;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * A group's totals: the sum of each SUM column by key, the row count, and the closing balance.
 * A report service builds them from a SQL aggregate and hands them to the writer as
 * "authoritative" totals (see {@link ReportWriter#closeGroup(String, GroupTotals)}).
 * {@code sums} is copied and read-only; a {@code null} closing is zero.
 */
public record GroupTotals(Map<String, BigDecimal> sums, long count, BigDecimal closing) {

    public GroupTotals {
        sums = sums == null ? Map.of() : Map.copyOf(sums);
        closing = closing == null ? BigDecimal.ZERO : closing;
    }

    /**
     * Totals for a debit/credit ledger whose MONEY columns are keyed {@code debit} and {@code credit}:
     * sums {@code {debit, credit}} and closing {@code opening + debit − credit}; {@code null}s are zero.
     * The count is 0, so these suit grids without a COUNT column.
     */
    public static GroupTotals debitCredit(BigDecimal opening, BigDecimal debit, BigDecimal credit) {
        BigDecimal d = nz(debit);
        BigDecimal c = nz(credit);
        return new GroupTotals(Map.of("debit", d, "credit", c), 0, nz(opening).add(d).subtract(c));
    }

    /** These totals and {@code other} added key by key. */
    public GroupTotals plus(GroupTotals other) {
        Map<String, BigDecimal> merged = new HashMap<>(sums);
        other.sums().forEach((k, v) -> merged.merge(k, v, BigDecimal::add));
        return new GroupTotals(merged, count + other.count(), closing.add(other.closing()));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
