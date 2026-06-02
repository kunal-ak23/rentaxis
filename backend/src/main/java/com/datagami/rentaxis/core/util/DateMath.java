package com.datagami.rentaxis.core.util;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Date arithmetic shared across lease scheduling and portfolio import. */
public final class DateMath {

    private DateMath() {
    }

    /**
     * End-date-inclusive whole-month count between two dates.
     *
     * <p>Lease end dates are the last day of the tenancy (inclusive), so a lease
     * Jan 1 → Dec 31 is a 12-month lease and Jun 1 → Dec 31 is 7 months. Plain
     * {@code MONTHS.between(start, end)} is exclusive of the end and would return
     * 11 and 6 respectively, dropping the final month's rent. Stepping the end
     * forward by one day before counting whole months gives the inclusive count.
     * Floors at 1 to avoid divide-by-zero downstream (e.g. same-day start=end).
     */
    public static long monthsInclusive(LocalDate startDate, LocalDate endDate) {
        return Math.max(ChronoUnit.MONTHS.between(startDate, endDate.plusDays(1)), 1);
    }
}
