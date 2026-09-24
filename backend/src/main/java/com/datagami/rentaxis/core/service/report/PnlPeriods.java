package com.datagami.rentaxis.core.service.report;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The comparison period for a P&L (finance-ops spec §1, "Comparison").
 *
 * <ul>
 *   <li>{@code PREVIOUS}: the immediately preceding period of the same shape. A
 *       range of whole calendar months (a month, a quarter, a year) compares with
 *       the same number of whole months before it; any other range compares with
 *       the same number of days ending on {@code from − 1}.</li>
 *   <li>{@code LAST_YEAR}: the same dates one year earlier — whole months by
 *       twelve months to the month's end, other ranges by a year (29 February
 *       maps to 28 February).</li>
 * </ul>
 */
public final class PnlPeriods {

    private PnlPeriods() { }

    public enum Compare { NONE, PREVIOUS, LAST_YEAR }

    public record Period(LocalDate from, LocalDate to) { }

    /** The period to compare with, or null for {@link Compare#NONE}. */
    public static Period prior(LocalDate from, LocalDate to, Compare compare) {
        return switch (compare) {
            case NONE -> null;
            case PREVIOUS -> previous(from, to);
            case LAST_YEAR -> lastYear(from, to);
        };
    }

    /**
     * The same dates a year earlier. Whole months shift by twelve months and end
     * on the month's last day, so Feb 2025 compares with 1–29 Feb 2024 and a
     * Mar–Feb fiscal year does not lose 29 February; other ranges shift by a year
     * (29 February → 28 February).
     */
    static Period lastYear(LocalDate from, LocalDate to) {
        if (isWholeMonths(from, to)) {
            LocalDate end = to.minusMonths(12);
            return new Period(from.minusMonths(12), end.withDayOfMonth(end.lengthOfMonth()));
        }
        return new Period(from.minusYears(1), to.minusYears(1));
    }

    static Period previous(LocalDate from, LocalDate to) {
        if (isWholeMonths(from, to)) {
            long months = ChronoUnit.MONTHS.between(from, to.plusDays(1));
            LocalDate priorFrom = from.minusMonths(months);
            return new Period(priorFrom, from.minusDays(1));
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate priorTo = from.minusDays(1);
        return new Period(priorTo.minusDays(days - 1), priorTo);
    }

    /** Starts on the 1st and ends on the last day of a month. */
    static boolean isWholeMonths(LocalDate from, LocalDate to) {
        return from.getDayOfMonth() == 1 && to.getDayOfMonth() == to.lengthOfMonth();
    }
}
