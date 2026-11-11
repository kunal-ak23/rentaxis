package com.datagami.rentaxis.core.service.lease;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Decree 43/2013 as the DLD calculator applies it (measured 2026-11-11, see the
 * class note on {@link Decree43}): midpoint average, "> band edge" banding at three
 * decimals, maximum = current × (1 + band).
 */
class Decree43Test {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Test
    void dldsOwnExample() {
        // Average 80,000, current 60,000 → 25 % below → 10 % → 66,000.
        Decree43.Result r = Decree43.evaluate(bd("70000"), bd("90000"), bd("60000"), bd("66000"));
        assertThat(r.indexAverage()).isEqualByComparingTo("80000");
        assertThat(r.belowAveragePercent()).isEqualByComparingTo("25.000");
        assertThat(r.capPercent()).isEqualByComparingTo("10");
        assertThat(r.maxRent()).isEqualByComparingTo("66000.00");
        assertThat(r.exceedsCap()).isFalse();
    }

    @Test
    void measuredTwoBed2027() {
        // 80,522–98,416 → 89,469; 80,000 is 10.584 % below → 5 % → 84,000.
        Decree43.Result r = Decree43.evaluate(bd("80522"), bd("98416"), bd("80000"), bd("84000.01"));
        assertThat(r.indexAverage()).isEqualByComparingTo("89469");
        assertThat(r.belowAveragePercent()).isEqualByComparingTo("10.584");
        assertThat(r.capPercent()).isEqualByComparingTo("5");
        assertThat(r.maxRent()).isEqualByComparingTo("84000.00");
        assertThat(r.exceedsCap()).isTrue();
    }

    @Test
    void measuredOneBed2027() {
        // 59,054–72,176 → 65,615; 58,328 is 11.106 % below → 5 % → 61,244.40, unrounded to the dirham.
        Decree43.Result r = Decree43.evaluate(bd("59054"), bd("72176"), bd("58328"), null);
        assertThat(r.indexAverage()).isEqualByComparingTo("65615");
        assertThat(r.belowAveragePercent()).isEqualByComparingTo("11.106");
        assertThat(r.capPercent()).isEqualByComparingTo("5");
        assertThat(r.maxRent()).isEqualByComparingTo("61244.40");
        assertThat(r.exceedsCap()).isFalse();
    }

    @Test
    void measuredTwoBed2026() {
        // 83,736–102,344 → 93,040 for every two-bed of the building ending in 2026.
        assertThat(Decree43.average(bd("83736"), bd("102344"))).isEqualByComparingTo("93040");
    }

    /** Every band edge: exactly on the edge stays below it; a thousandth over crosses. */
    @ParameterizedTest
    @CsvSource({
            "0.000, 0", "9.999, 0", "10.000, 0", "10.001, 5",
            "19.999, 5", "20.000, 5", "20.001, 10",
            "29.999, 10", "30.000, 10", "30.001, 15",
            "39.999, 15", "40.000, 15", "40.001, 20",
            "55.000, 20", "99.999, 20",
    })
    void bandEdges(String below, String band) {
        assertThat(Decree43.capPercent(bd(below))).isEqualByComparingTo(band);
    }

    @Test
    void rentAtOrAboveAverageHasNoIncrease() {
        Decree43.Result at = Decree43.evaluate(bd("70000"), bd("90000"), bd("80000"), bd("80000"));
        assertThat(at.belowAveragePercent()).isEqualByComparingTo("0.000");
        assertThat(at.capPercent()).isEqualByComparingTo("0");
        assertThat(at.maxRent()).isEqualByComparingTo("80000.00");
        assertThat(at.exceedsCap()).isFalse();

        Decree43.Result above = Decree43.evaluate(bd("70000"), bd("90000"), bd("95000"), bd("95000.01"));
        assertThat(above.belowAveragePercent()).isEqualByComparingTo("-18.750");
        assertThat(above.capPercent()).isEqualByComparingTo("0");
        assertThat(above.maxRent()).isEqualByComparingTo("95000.00");
        assertThat(above.exceedsCap()).isTrue();
    }

    @Test
    void bandEdgeOnTheComputedPercentIsTakenAfterRoundingToThreeDecimals() {
        // 100,000 average, 89,999.5 current → 10.0005 % → rounds to 10.001 → 5 %.
        Decree43.Result r = Decree43.evaluate(bd("90000"), bd("110000"), bd("89999.50"), null);
        assertThat(r.belowAveragePercent()).isEqualByComparingTo("10.001");
        assertThat(r.capPercent()).isEqualByComparingTo("5");
        // 89,999.6 → 10.0004 % → 10.000 → 0 %.
        Decree43.Result r2 = Decree43.evaluate(bd("90000"), bd("110000"), bd("89999.60"), null);
        assertThat(r2.belowAveragePercent()).isEqualByComparingTo("10.000");
        assertThat(r2.capPercent()).isEqualByComparingTo("0");
    }

    @Test
    void nothingToEvaluate() {
        assertThat(Decree43.evaluate(null, bd("1"), bd("1"), null)).isNull();
        assertThat(Decree43.evaluate(bd("1"), null, bd("1"), null)).isNull();
        assertThat(Decree43.evaluate(bd("1"), bd("2"), null, null)).isNull();
        assertThat(Decree43.evaluate(bd("0"), bd("2"), bd("1"), null)).isNull();
        assertThat(Decree43.evaluate(bd("3"), bd("2"), bd("1"), null)).isNull();
        assertThat(Decree43.evaluate(bd("1"), bd("2"), bd("0"), null)).isNull();
    }

    @Test
    void oddSumAveragesToTheHalfDirham() {
        assertThat(Decree43.average(bd("1"), bd("2"))).isEqualByComparingTo("1.50");
    }
}
