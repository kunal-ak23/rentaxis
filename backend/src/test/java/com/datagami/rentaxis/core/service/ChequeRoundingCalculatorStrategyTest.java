package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ChequeRoundingCalculatorStrategyTest {
    private static BigDecimal sum(List<BigDecimal> xs){ return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add); }

    @Test void lastLargerUnchanged() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, InstallmentDistribution.LAST_LARGER).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(r.size()-1)).isEqualByComparingTo(r.stream().max(BigDecimal::compareTo).get()); // last is largest
        // identical to the default-strategy overload
        assertThat(r).isEqualTo(ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6).amounts());
    }
    @Test void firstLarger() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, InstallmentDistribution.FIRST_LARGER).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(0)).isEqualByComparingTo(r.stream().max(BigDecimal::compareTo).get());
    }
    @Test void uniformAllEqualWithinACent() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, InstallmentDistribution.UNIFORM).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        BigDecimal min = r.stream().min(BigDecimal::compareTo).get(), max = r.stream().max(BigDecimal::compareTo).get();
        assertThat(max.subtract(min)).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }
    @Test void bothLargerSplitsRemainder() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, InstallmentDistribution.FIRST_AND_LAST_LARGER).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(0)).isGreaterThan(r.get(1));
        assertThat(r.get(5)).isGreaterThan(r.get(1));
    }
    @Test void singleCheque() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 1, InstallmentDistribution.UNIFORM).amounts();
        assertThat(r).containsExactly(new BigDecimal("35000"));
    }

    /**
     * Verifies the zero-value cheque guard: when the per-cheque average is smaller
     * than the largest candidate step, the algorithm skips that step instead of
     * emitting zero-value cheques (legacy would have produced [0,0,0,total]).
     *
     * 1500 / 4 = 375 → step 1000 floors to 0 (skip), step 500 floors to 0 (skip),
     *   step 100 → per=300, last=600. No zeros; last is largest; sum=1500.
     *
     * 2000 / 4 = 500 → step 1000 floors to 0 (skip), step 500 → per=500, last=500.
     *   All equal; sum=2000.
     */
    @Test void lastLarger_lowPerCheque_noZeroCheques() {
        // Case 1: 1500 / 4 — must not produce any zero-value cheque
        var r1 = ChequeRoundingCalculator.distribute(new BigDecimal("1500"), 4, InstallmentDistribution.LAST_LARGER).amounts();
        assertThat(sum(r1)).isEqualByComparingTo("1500");
        assertThat(r1).doesNotContain(BigDecimal.ZERO).noneMatch(a -> a.signum() == 0);
        assertThat(r1.get(r1.size() - 1)).isEqualByComparingTo(r1.stream().max(BigDecimal::compareTo).get()); // last is largest

        // Case 2: 2000 / 4 — divides cleanly into 500s
        var r2 = ChequeRoundingCalculator.distribute(new BigDecimal("2000"), 4, InstallmentDistribution.LAST_LARGER).amounts();
        assertThat(sum(r2)).isEqualByComparingTo("2000");
        assertThat(r2).hasSize(4).allSatisfy(a -> assertThat(a).isEqualByComparingTo("500"));
    }
}
