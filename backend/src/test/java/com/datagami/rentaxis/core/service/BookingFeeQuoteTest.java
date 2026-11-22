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
    // ---- PR #468 review I1: whole months worked out, never counted; quote dates bounded

    /** The month count the quote used before the fix, one month at a time (for comparison only). */
    private static long legacyWholeMonths(LocalDate start, LocalDate last) {
        int n = 0;
        while (!start.plusMonths(n + 1).minusDays(1).isAfter(last)) n++;
        return n;
    }

    @Test void wholeMonthsMatchesTheMonthByMonthCountOnEveryStartDay() {
        for (LocalDate d = LocalDate.of(2026, 1, 1); d.isBefore(LocalDate.of(2029, 1, 1)); d = d.plusDays(1)) {
            for (int len = -3; len <= 800; len += (len < 70 ? 1 : 7)) {
                LocalDate last = d.plusDays(len);
                assertThat(BookingFeeService.wholeMonths(d, last)).as(d + " to " + last)
                        .isEqualTo(len < 0 ? 0 : legacyWholeMonths(d, last));
            }
        }
    }

    /** A 31 Jan start: plusMonths clamps, so the first whole month ends 27 Feb (28 Feb in a leap year). */
    @Test void perMonthFromJanuaryThirtyFirstAcrossAShortFebruary() {
        assertThat(BookingFeeService.wholeMonths(LocalDate.parse("2027-01-31"), LocalDate.parse("2027-02-26"))).isZero();
        assertThat(BookingFeeService.wholeMonths(LocalDate.parse("2027-01-31"), LocalDate.parse("2027-02-27"))).isOne();
        assertThat(BookingFeeService.wholeMonths(LocalDate.parse("2028-01-31"), LocalDate.parse("2028-02-28"))).isOne();
        assertThat(q("PER_MONTH", "300", "2027-01-31", "2027-02-27")).isEqualByComparingTo("300.00");
        // One whole month to 27 Feb, then 28 Feb at 300/28.
        assertThat(q("PER_MONTH", "280", "2027-01-31", "2027-02-28")).isEqualByComparingTo("290.00");
        // 26 days of January at 300/31.
        assertThat(q("PER_MONTH", "310", "2027-01-31", "2027-02-26")).isEqualByComparingTo(
                new BigDecimal("310").multiply(BigDecimal.valueOf(27)).divide(BigDecimal.valueOf(31), 2, java.math.RoundingMode.HALF_UP));
    }

    @Test void perMonthSameDayIsOneDayAtThatMonthsRate() {
        assertThat(BookingFeeService.wholeMonths(LocalDate.parse("2026-12-05"), LocalDate.parse("2026-12-05"))).isZero();
        assertThat(q("PER_MONTH", "310", "2026-12-05", "2026-12-05")).isEqualByComparingTo("10.00");
    }

    @Test void aFarOffEndIsPricedAtOnce() {
        LocalDate start = LocalDate.parse("2026-12-01");
        LocalDate far = LocalDate.of(999_999, 12, 31);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            assertThat(BookingFeeService.wholeMonths(start, far)).isEqualTo((999_999L - 2026) * 12 + 1);
            assertThat(BookingFeeService.quote("PER_MONTH", new BigDecimal("300"), start, far, null, null)).isPositive();
        });
    }

    @Test void quoteDatesAreBoundedAsARequestsAre() {
        LocalDate start = LocalDate.parse("2026-12-01");
        BookingService.validateQuoteDates(start, start.plusYears(BookingService.MAX_PARKING_YEARS));
        BookingService.validateQuoteDates(start, null);
        BookingService.validateQuoteDates(null, null);
        assertThatThrownBy(() -> BookingService.validateQuoteDates(start, start.plusYears(BookingService.MAX_PARKING_YEARS).plusDays(1)))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                .extracting(e -> ((com.datagami.rentaxis.api.exception.BusinessRuleViolationException) e).getCode())
                .isEqualTo("booking.rangeTooLong");
        assertThatThrownBy(() -> BookingService.validateQuoteDates(start, LocalDate.MAX))
                .extracting(e -> ((com.datagami.rentaxis.api.exception.BusinessRuleViolationException) e).getCode())
                .isEqualTo("booking.rangeTooLong");
        assertThatThrownBy(() -> BookingService.validateQuoteDates(start, start.minusDays(1)))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class);
        assertThatThrownBy(() -> BookingService.validateQuoteDates(null, start))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class);
    }
}
