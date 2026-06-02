package com.datagami.rentaxis.core.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DateMathTest {

    @Test
    void countsEndDateAsInclusive() {
        // Jun 1 → Dec 31 is a 7-month tenancy (inclusive last day), not 6.
        assertThat(DateMath.monthsInclusive(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .isEqualTo(7);
    }

    @Test
    void oneYearLeaseIsTwelveMonths() {
        // Jan 1 → Dec 31 is a full 12-month lease, not 11.
        assertThat(DateMath.monthsInclusive(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)))
                .isEqualTo(12);
    }

    @Test
    void crossYearTwelveMonthLease() {
        // Apr 1 2026 → Mar 31 2027 is 12 months.
        assertThat(DateMath.monthsInclusive(LocalDate.of(2026, 4, 1), LocalDate.of(2027, 3, 31)))
                .isEqualTo(12);
    }

    @Test
    void sameDayStartAndEndFloorsToOne() {
        // Edge case: a same-day lease must floor at 1 to avoid divide-by-zero.
        assertThat(DateMath.monthsInclusive(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)))
                .isEqualTo(1);
    }

    @Test
    void shortLeaseFloorsToOne() {
        // Less than a whole month still yields at least 1.
        assertThat(DateMath.monthsInclusive(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 15)))
                .isEqualTo(1);
    }
}
