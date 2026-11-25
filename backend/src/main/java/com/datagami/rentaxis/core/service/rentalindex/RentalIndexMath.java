package com.datagami.rentaxis.core.service.rentalindex;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Dubai Decree 43 of 2013 rent increase cap arithmetic. Pure functions, no state. */
public final class RentalIndexMath {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal DAYS_IN_YEAR = BigDecimal.valueOf(365);

    private RentalIndexMath() {
    }

    public record Cap(BigDecimal average, BigDecimal gap, int percent, BigDecimal capRent) {
    }

    /** (min + max) / 2, 2 dp. */
    public static BigDecimal average(BigDecimal min, BigDecimal max) {
        return min.add(max).divide(TWO, 2, RoundingMode.HALF_UP);
    }

    /** (average - base) / average x 100, 4 dp. Zero when the average is not positive. */
    public static BigDecimal gapPercent(BigDecimal average, BigDecimal base) {
        if (average.signum() <= 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return average.subtract(base).multiply(HUNDRED).divide(average, 4, RoundingMode.HALF_UP);
    }

    /** Art. 1 bands: up to 10 is 0, up to 20 is 5, up to 30 is 10, up to 40 is 15, above 40 is 20. */
    public static int allowedPercent(BigDecimal gap) {
        if (gap.compareTo(BigDecimal.TEN) <= 0) return 0;
        if (gap.compareTo(BigDecimal.valueOf(20)) <= 0) return 5;
        if (gap.compareTo(BigDecimal.valueOf(30)) <= 0) return 10;
        if (gap.compareTo(BigDecimal.valueOf(40)) <= 0) return 15;
        return 20;
    }

    /** base x (1 + pct/100), HALF_UP to whole AED. */
    public static BigDecimal capRent(BigDecimal base, int pct) {
        return base.multiply(BigDecimal.valueOf(100L + pct)).divide(HUNDRED, 0, RoundingMode.HALF_UP);
    }

    /** Unchanged for an exact 12-month term, otherwise amount x 365 / days (inclusive), 2 dp. */
    public static BigDecimal annualise(BigDecimal amount, LocalDate start, LocalDate end) {
        if (start.plusMonths(12).minusDays(1).equals(end)) {
            return amount;
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        return amount.multiply(DAYS_IN_YEAR).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
    }

    public static Cap of(BigDecimal min, BigDecimal max, BigDecimal annualBase) {
        BigDecimal avg = average(min, max);
        BigDecimal gap = gapPercent(avg, annualBase);
        int pct = allowedPercent(gap);
        return new Cap(avg, gap, pct, capRent(annualBase, pct));
    }
}
