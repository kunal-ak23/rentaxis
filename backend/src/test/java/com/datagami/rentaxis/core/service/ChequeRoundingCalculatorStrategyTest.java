package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ChequeRoundingCalculatorStrategyTest {
    private static BigDecimal sum(List<BigDecimal> xs){ return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add); }

    @Test void lastLargerUnchanged() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, null, InstallmentDistribution.LAST_LARGER).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(r.size()-1)).isEqualByComparingTo(r.stream().max(BigDecimal::compareTo).get()); // last is largest
        // identical to legacy 3-arg overload
        assertThat(r).isEqualTo(ChequeRoundingCalculator.distribute(new BigDecimal("35000"),6,null).amounts());
    }
    @Test void firstLarger() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, null, InstallmentDistribution.FIRST_LARGER).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(0)).isEqualByComparingTo(r.stream().max(BigDecimal::compareTo).get());
    }
    @Test void uniformAllEqualWithinACent() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, null, InstallmentDistribution.UNIFORM).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        BigDecimal min = r.stream().min(BigDecimal::compareTo).get(), max = r.stream().max(BigDecimal::compareTo).get();
        assertThat(max.subtract(min)).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }
    @Test void bothLargerSplitsRemainder() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 6, null, InstallmentDistribution.FIRST_AND_LAST_LARGER).amounts();
        assertThat(sum(r)).isEqualByComparingTo("35000");
        assertThat(r.get(0)).isGreaterThan(r.get(1));
        assertThat(r.get(5)).isGreaterThan(r.get(1));
    }
    @Test void singleCheque() {
        var r = ChequeRoundingCalculator.distribute(new BigDecimal("35000"), 1, null, InstallmentDistribution.UNIFORM).amounts();
        assertThat(r).containsExactly(new BigDecimal("35000"));
    }
}
