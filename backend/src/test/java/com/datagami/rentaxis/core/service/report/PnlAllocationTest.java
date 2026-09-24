package com.datagami.rentaxis.core.service.report;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PnlAllocationTest {

    private static Map<String, BigDecimal> weights(String... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], new BigDecimal(kv[i + 1]));
        return m;
    }

    private static BigDecimal sum(Map<String, BigDecimal> m) {
        return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void theLargestRemaindersTakeTheLeftoverFilsSoTheSumIsExact() {
        Map<String, BigDecimal> a = PnlAllocation.largestRemainder(new BigDecimal("100.00"), weights("a", "1", "b", "1", "c", "1"));
        assertThat(sum(a)).isEqualByComparingTo("100.00");
        assertThat(a.get("a")).isEqualByComparingTo("33.34");   // ties go to the first column
        assertThat(a.get("b")).isEqualByComparingTo("33.33");
        assertThat(a.get("c")).isEqualByComparingTo("33.33");

        Map<String, BigDecimal> r = PnlAllocation.largestRemainder(new BigDecimal("50.00"),
                weights("p1", "82191.78", "p2", "61000.00", "p3", "12345.67"));
        assertThat(sum(r)).isEqualByComparingTo("50.00");
        assertThat(r.get("p1")).isEqualByComparingTo("26.42");
        assertThat(r.get("p2")).isEqualByComparingTo("19.61");
        assertThat(r.get("p3")).isEqualByComparingTo("3.97");
    }

    @Test
    void aNegativeAmountSpreadsWithItsSign() {
        Map<String, BigDecimal> a = PnlAllocation.largestRemainder(new BigDecimal("-70.00"), weights("a", "2", "b", "1"));
        assertThat(sum(a)).isEqualByComparingTo("-70.00");
        assertThat(a.get("a")).isEqualByComparingTo("-46.67");
        assertThat(a.get("b")).isEqualByComparingTo("-23.33");
    }

    @Test
    void noPositiveWeightFallsBackToEqualShares() {
        Map<String, BigDecimal> a = PnlAllocation.largestRemainder(new BigDecimal("0.05"), weights("a", "0", "b", "-3"));
        assertThat(sum(a)).isEqualByComparingTo("0.05");
        assertThat(a.get("a")).isEqualByComparingTo("0.03");
        assertThat(a.get("b")).isEqualByComparingTo("0.02");
    }

    @Test
    void aZeroWeightTakesNothingWhenOthersHaveWeight() {
        Map<String, BigDecimal> a = PnlAllocation.largestRemainder(new BigDecimal("10.00"), weights("a", "0", "b", "5"));
        assertThat(a.get("a")).isEqualByComparingTo("0.00");
        assertThat(a.get("b")).isEqualByComparingTo("10.00");
    }
}
