package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookingFeeQuoteTest {

    private static BigDecimal q(String type, String amount, String start, String end) {
        return BookingFeeService.quote(type, new BigDecimal(amount), LocalDate.parse(start), LocalDate.parse(end), null, null);
    }

    @Test void perMonthWholeMonths() {
        assertThat(q("PER_MONTH", "300", "2026-12-01", "2027-01-31")).isEqualByComparingTo("600.00");
        assertThat(q("PER_MONTH", "300", "2026-12-15", "2027-02-14")).isEqualByComparingTo("600.00");
    }

    @Test void perMonthLeftoverDaysAtThatMonthsDayRate() {
        assertThat(q("PER_MONTH", "300", "2026-12-01", "2027-01-10")).isEqualByComparingTo("396.77"); // 300 + 10x300/31
        assertThat(q("PER_MONTH", "300", "2026-12-01", "2026-12-10")).isEqualByComparingTo("96.77");
    }

    @Test void perMonthFromTheEndOfAMonthDoesNotDrift() {
        assertThat(q("PER_MONTH", "300", "2027-01-31", "2027-03-30")).isEqualByComparingTo("600.00");
    }

    @Test void perMonthLeapFebruary() {
        assertThat(q("PER_MONTH", "290", "2028-02-01", "2028-02-10")).isEqualByComparingTo("100.00"); // 10x290/29
    }

    @Test void perDayCountsBothEnds() {
        assertThat(q("PER_DAY", "40", "2026-12-01", "2026-12-03")).isEqualByComparingTo("120.00");
    }

    @Test void perMonthIsParkingOnlyAndPerHourAmenityOnly() {
        assertThatThrownBy(() -> BookingFeeService.validate("PER_MONTH", BigDecimal.TEN, false)).hasMessageContaining("PER_MONTH");
        assertThatThrownBy(() -> BookingFeeService.validate("PER_HOUR", BigDecimal.TEN, true)).hasMessageContaining("PER_HOUR");
        BookingFeeService.validate("PER_DAY", BigDecimal.TEN, true);
        BookingFeeService.validate("PER_DAY", BigDecimal.TEN, false);
    }
}
