package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChequeRoundingCalculatorTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void distributesIntoCleanThousandsWithResidualOnLastCheque() {
        // 31000 / 6 cheques: per=5000, last=6000.
        var result = ChequeRoundingCalculator.distribute(bd("31000"), 6);
        assertThat(result.amounts())
                .containsExactly(bd("5000"), bd("5000"), bd("5000"), bd("5000"), bd("5000"), bd("6000"));
        assertThat(result.step()).isEqualByComparingTo(bd("1000"));
        assertThat(sum(result.amounts())).isEqualByComparingTo(bd("31000"));
    }

    @Test
    void allUniformWhenTotalDividesCleanly() {
        var result = ChequeRoundingCalculator.distribute(bd("24000"), 12);
        assertThat(result.amounts()).hasSize(12).allSatisfy(a -> assertThat(a).isEqualByComparingTo(bd("2000")));
    }

    @Test
    void singleChequeReturnsFullAmount() {
        var result = ChequeRoundingCalculator.distribute(bd("12345.67"), 1);
        assertThat(result.amounts()).containsExactly(bd("12345.67"));
    }

    @Test
    void fallsThroughToAFinerStepWhenTheRentIsSmallerThanTheStep() {
        // 1500 / 4 = 375. A 1000-step floors to zero, which would have produced
        // [0, 0, 0, 1500]; the finer step yields a real split instead.
        var result = ChequeRoundingCalculator.distribute(bd("1500"), 4);
        assertThat(result.amounts()).containsExactly(bd("300"), bd("300"), bd("300"), bd("600"));
        assertThat(result.step()).isEqualByComparingTo(bd("100"));
    }

    /**
     * Regression for the constraint this class used to impose: the largest
     * cheque had to fit inside the lease's security deposit, or the lease was
     * refused outright.
     *
     * <p>Reported from the field on a 5,000/month lease with a 5,000 deposit —
     * 60,000 a year over 6 cheques, which is an entirely ordinary UAE
     * arrangement. Every cheque count below is one a landlord actually writes,
     * and every one of them was rejected. The deposit does not appear in this
     * API at all any more, so these assert on the outputs rather than on the
     * absence of an exception.</p>
     */
    @Test
    void writesTheStandardUaeChequeCountsForAnAnnualRentOfSixtyThousand() {
        assertThat(ChequeRoundingCalculator.distribute(bd("60000"), 1).amounts())
                .containsExactly(bd("60000"));
        assertThat(ChequeRoundingCalculator.distribute(bd("60000"), 2).amounts())
                .containsExactly(bd("30000"), bd("30000"));
        assertThat(ChequeRoundingCalculator.distribute(bd("60000"), 4).amounts())
                .containsExactly(bd("15000"), bd("15000"), bd("15000"), bd("15000"));
        assertThat(ChequeRoundingCalculator.distribute(bd("60000"), 6).amounts())
                .containsExactly(bd("10000"), bd("10000"), bd("10000"), bd("10000"), bd("10000"), bd("10000"));
    }

    /**
     * The general form of the same rule: a cheque far larger than any plausible
     * deposit is fine, and the split stays exact.
     */
    @Test
    void aChequeMayGreatlyExceedAnyDepositTheLeaseWouldCarry() {
        var result = ChequeRoundingCalculator.distribute(bd("31500"), 6);

        assertThat(result.amounts().get(5))
                .as("the residual cheque is not trimmed to fit a deposit")
                .isEqualByComparingTo(bd("6500"));
        assertThat(result.step())
                .as("and the clean 1,000 denomination is kept rather than dropping to 100 to satisfy a cap")
                .isEqualByComparingTo(bd("1000"));
        assertThat(sum(result.amounts())).isEqualByComparingTo(bd("31500"));
    }

    @Test
    void everyChequeCountFromOneToTwentyFourProducesAnExactSplit() {
        // The old cap made the viable range depend on the deposit. Nothing
        // narrows it now, so assert across the whole plausible range.
        for (int n = 1; n <= 24; n++) {
            var amounts = ChequeRoundingCalculator.distribute(bd("60000"), n).amounts();
            assertThat(amounts).as("cheque count " + n).hasSize(n);
            assertThat(amounts).as("cheque count " + n).allSatisfy(a -> assertThat(a).isPositive());
            assertThat(sum(amounts)).as("cheque count " + n).isEqualByComparingTo(bd("60000"));
        }
    }
}
