package com.datagami.rentaxis.core.service.lease;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Dubai Decree 43/2013: how much a landlord may raise the rent on renewal, from the
 * DLD rental index ("average comparable rent") and what the renter pays today.
 *
 * <p>The DLD calculator answers one index range (min–max AED/year) per building ×
 * bedroom category × contract-end year. The <b>average</b> is the range's midpoint,
 * and the permitted increase depends on how far below it the current rent is:</p>
 *
 * <table>
 *   <tr><th>current rent below average</th><th>increase allowed</th></tr>
 *   <tr><td>0–10 %</td><td>none</td></tr>
 *   <tr><td>11–20 %</td><td>5 %</td></tr>
 *   <tr><td>21–30 %</td><td>10 %</td></tr>
 *   <tr><td>31–40 %</td><td>15 %</td></tr>
 *   <tr><td>over 40 %</td><td>20 %</td></tr>
 * </table>
 *
 * <p><b>Boundary choice.</b> The decree states its bands in whole percentages, so
 * it does not say which band 10.6 % falls in. The calculator was measured on
 * 2026-11-11: a current rent 10.584 % below the average was allowed 5 %, i.e. the
 * bands are {@code decrease > 10 → 5 %}, {@code > 20 → 10 %}, {@code > 30 → 15 %},
 * {@code > 40 → 20 %}; exactly 10.000 % stays at 0 %. The decrease is rounded to
 * three decimals (HALF_UP) before banding, so the figure shown is the figure
 * decided on. The maximum rent is {@code current × (1 + band)}, unrounded past the
 * two decimals of a money amount (DLD's own example: average 80,000, current
 * 60,000 → 25 % below → 10 % → 66,000; measured: 58,328 vs 65,615 → 11.106 % →
 * 5 % → 61,244.40).</p>
 *
 * <p>A current rent at or above the average has nothing to increase: band 0, the
 * maximum is the current rent. This is advisory — the cap is computed from a range
 * a manager typed in, and the DLD page is the authority.</p>
 */
public final class Decree43 {

    private Decree43() {
    }

    /** What the decree permits for one current rent against one index range. */
    public record Result(BigDecimal indexAverage,
                         BigDecimal belowAveragePercent,
                         BigDecimal capPercent,
                         BigDecimal maxRent,
                         boolean exceedsCap) {
    }

    /** The midpoint of the index range, to the cent. */
    public static BigDecimal average(BigDecimal min, BigDecimal max) {
        return min.add(max).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    /** (average − current) ÷ average × 100 at three decimals; negative when the rent is above the average. */
    public static BigDecimal belowAveragePercent(BigDecimal average, BigDecimal current) {
        return average.subtract(current).multiply(BigDecimal.valueOf(100))
                .divide(average, 3, RoundingMode.HALF_UP);
    }

    /** The band for a decrease: 0, 5, 10, 15 or 20 (see the class note on boundaries). */
    public static BigDecimal capPercent(BigDecimal belowAveragePercent) {
        int band;
        if (belowAveragePercent.compareTo(BigDecimal.valueOf(40)) > 0) band = 20;
        else if (belowAveragePercent.compareTo(BigDecimal.valueOf(30)) > 0) band = 15;
        else if (belowAveragePercent.compareTo(BigDecimal.valueOf(20)) > 0) band = 10;
        else if (belowAveragePercent.compareTo(BigDecimal.valueOf(10)) > 0) band = 5;
        else band = 0;
        return BigDecimal.valueOf(band);
    }

    /**
     * The whole evaluation, or null when it cannot be made: no range, a range that
     * is not positive or inverted, or no positive current rent.
     *
     * @param proposed the renewal's rent, or null when only the cap is wanted
     */
    public static Result evaluate(BigDecimal min, BigDecimal max, BigDecimal current, BigDecimal proposed) {
        if (min == null || max == null || current == null) return null;
        if (min.signum() <= 0 || max.compareTo(min) < 0 || current.signum() <= 0) return null;
        BigDecimal average = average(min, max);
        BigDecimal below = belowAveragePercent(average, current);
        BigDecimal band = capPercent(below);
        BigDecimal maxRent = current.multiply(BigDecimal.ONE.add(band.movePointLeft(2)))
                .setScale(2, RoundingMode.HALF_UP);
        boolean exceeds = proposed != null && proposed.compareTo(maxRent) > 0;
        return new Result(average, below, band, maxRent, exceeds);
    }
}
