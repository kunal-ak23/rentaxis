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

    // ---- spec 2026-11-19 §6: the credit for the unused part on an early parking release

    private static BigDecimal credit(String type, String unit, String fee, String start, String end, String release) {
        return BookingFeeService.creditForRelease(type, unit == null ? null : new BigDecimal(unit), new BigDecimal(fee),
                LocalDate.parse(start), LocalDate.parse(end), LocalDate.parse(release));
    }

    @Test void releaseAfterTheFirstWholeMonthCreditsTheSecond() {
        assertThat(credit("PER_MONTH", "300", "600", "2026-12-01", "2027-01-31", "2026-12-31")).isEqualByComparingTo("300.00");
    }

    @Test void releaseOnTheDayAfterAWholeMonthUsesOneDayOfTheNext() {
        // used 300 + 300/31 = 309.68
        assertThat(credit("PER_MONTH", "300", "600", "2026-12-01", "2027-01-31", "2027-01-01")).isEqualByComparingTo("290.32");
    }

    @Test void releaseOnOrAfterTheLastDayCreditsNothing() {
        assertThat(credit("PER_MONTH", "300", "600", "2026-12-01", "2027-01-31", "2027-01-31")).isEqualByComparingTo("0.00");
        assertThat(credit("PER_MONTH", "300", "600", "2026-12-01", "2027-01-31", "2027-02-15")).isEqualByComparingTo("0.00");
    }

    @Test void aPerBookingFeeOrAMissingSnapshotStands() {
        assertThat(credit("PER_BOOKING", "600", "600", "2026-12-01", "2027-01-31", "2026-12-05")).isEqualByComparingTo("0.00");
        assertThat(credit(null, null, "600", "2026-12-01", "2027-01-31", "2026-12-05")).isEqualByComparingTo("0.00");
        assertThat(credit("PER_MONTH", null, "600", "2026-12-01", "2027-01-31", "2026-12-05")).isEqualByComparingTo("0.00");
    }

    @Test void perDayCreditsTheDaysAfterTheRelease() {
        assertThat(credit("PER_DAY", "40", "400", "2026-12-01", "2026-12-10", "2026-12-03")).isEqualByComparingTo("280.00");
    }

    @Test void releaseBeforeTheStartUsesNothing() {
        assertThat(credit("PER_DAY", "40", "400", "2026-12-01", "2026-12-10", "2026-11-30")).isEqualByComparingTo("400.00");
        assertThat(credit("PER_MONTH", "300", "600", "2026-12-01", "2027-01-31", "2026-11-30")).isEqualByComparingTo("600.00");
    }

    @Test void theCreditStaysBetweenZeroAndTheFeeCharged() {
        assertThat(credit("PER_DAY", "40", "100", "2026-12-01", "2026-12-10", "2026-12-03")).isEqualByComparingTo("0.00");
        assertThat(credit("PER_DAY", "40", "500", "2026-12-01", "2026-12-10", "2026-11-30")).isEqualByComparingTo("500.00");
    }

    /** A month-end start clamps (31 Mar - 1 month = 28 Feb): one whole month to 27 Mar, then 4 days at 300/31. */
    @Test void releaseFromAClampedMonthEndStart() {
        assertThat(credit("PER_MONTH", "300", "600", "2027-02-28", "2027-04-27", "2027-03-31")).isEqualByComparingTo("261.29");
    }

    /**
     * The golden release scenario on every calendar day of 2026-2028 (month ends and leap
     * February included): a two-month booking from day d quotes 600, and moved back one month
     * and released on d it credits 600 less quote(d - 1 month, d), strictly between 0 and 300.
     */
    @Test void theGoldenReleaseScenarioHoldsOnEveryDay() {
        BigDecimal unit = new BigDecimal("300");
        for (LocalDate d = LocalDate.of(2026, 1, 1); d.isBefore(LocalDate.of(2029, 1, 1)); d = d.plusDays(1)) {
            assertThat(BookingFeeService.quote("PER_MONTH", unit, d, d.plusMonths(2).minusDays(1), null, null))
                    .as("fee from " + d).isEqualByComparingTo("600.00");
            LocalDate start = d.minusMonths(1);
            BigDecimal credit = BookingFeeService.creditForRelease("PER_MONTH", unit, new BigDecimal("600.00"), start,
                    start.plusMonths(2).minusDays(1), d);
            assertThat(credit).as("credit on " + d)
                    .isEqualByComparingTo(new BigDecimal("600.00").subtract(BookingFeeService.quote("PER_MONTH", unit, start, d, null, null)))
                    .isPositive().isLessThan(unit);
        }
    }
}
