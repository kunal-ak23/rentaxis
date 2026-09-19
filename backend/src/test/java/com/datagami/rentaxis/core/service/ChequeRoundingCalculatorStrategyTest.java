package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/**
 * Where the rounding residual lands, per strategy.
 *
 * <p>Every case passes {@code TEN} as the coarsest step: that is the ladder a
 * cheque grid is cut on and the only one production asks for. The overloads that
 * started at the coarsest denomination went with v1's payment schedules
 * (changeset 84), so a caller now names the step it wants.</p>
 */
class ChequeRoundingCalculatorStrategyTest {
    /** What a PACT cheque grid rounds to. */
    private static final BigDecimal TEN = new BigDecimal("10");

    private static BigDecimal sum(List<BigDecimal> xs){ return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add); }

    private static List<BigDecimal> split(String total, int n, InstallmentDistribution strategy) {
        return ChequeRoundingCalculator.distribute(new BigDecimal(total), n, strategy, TEN).amounts();
    }

    @Test void lastLargerUnchanged() {
        var r = split("35000", 6, InstallmentDistribution.LAST_LARGER);
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(r.size()-1)).isEqualByComparingTo(r.stream().max(BigDecimal::compareTo).get()); // last is largest
        // LAST_LARGER is also what a null strategy means
        assertThat(r).isEqualTo(
                ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, null, TEN).amounts());
    }
    @Test void firstLarger() {
        var r = split("35000", 6, InstallmentDistribution.FIRST_LARGER);
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(0)).isEqualByComparingTo(r.stream().max(BigDecimal::compareTo).get());
    }
    @Test void uniformAllEqualWithinACent() {
        var r = split("35000", 6, InstallmentDistribution.UNIFORM);
        assertThat(sum(r)).isEqualByComparingTo("35000");
        BigDecimal min = r.stream().min(BigDecimal::compareTo).get(), max = r.stream().max(BigDecimal::compareTo).get();
        assertThat(max.subtract(min)).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }
    @Test void bothLargerSplitsRemainder() {
        var r = split("35000", 6, InstallmentDistribution.FIRST_AND_LAST_LARGER);
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(0)).isGreaterThan(r.get(1));
        assertThat(r.get(5)).isGreaterThan(r.get(1));
    }
    @Test void singleCheque() {
        var r = split("35000", 1, InstallmentDistribution.UNIFORM);
        assertThat(r).containsExactly(new BigDecimal("35000"));
    }

    /**
     * Verifies the zero-value cheque guard: when the per-cheque average is smaller
     * than the largest candidate step, the algorithm skips that step instead of
     * emitting zero-value cheques (legacy would have produced [0,0,0,total]).
     *
     * On the grid's ladder: 1500 / 4 = 375 → floored to 370, last 390. 2000 / 4 =
     * 500, which is already a multiple of ten, so the split is flat. Neither
     * produces a zero-value cheque, which is what the legacy [0,0,0,total] was.
     */
    @Test void lastLarger_lowPerCheque_noZeroCheques() {
        // Case 1: 1500 / 4 — must not produce any zero-value cheque
        var r1 = split("1500", 4, InstallmentDistribution.LAST_LARGER);
        assertThat(sum(r1)).isEqualByComparingTo("1500");
        assertThat(r1).doesNotContain(BigDecimal.ZERO).noneMatch(a -> a.signum() == 0);
        assertThat(r1.get(r1.size() - 1)).isEqualByComparingTo(r1.stream().max(BigDecimal::compareTo).get()); // last is largest

        // Case 2: 2000 / 4 — divides cleanly into 500s
        var r2 = split("2000", 4, InstallmentDistribution.LAST_LARGER);
        assertThat(sum(r2)).isEqualByComparingTo("2000");
        assertThat(r2).hasSize(4).allSatisfy(a -> assertThat(a).isEqualByComparingTo("500"));
    }
}
