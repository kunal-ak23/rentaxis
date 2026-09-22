package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Splitting a year's rent into writable cheques.
 *
 * <p>There is one entry point now: {@code distribute(total, n, strategy, minStep)}.
 * The two- and three-argument overloads were v1's, and the ladder they used
 * deliberately skipped the 10 step so that regenerating a payment schedule could
 * not produce different amounts from the ones the renter had already been sent.
 * Those rows went with {@code payment_schedules} (changeset 84), so the coarsest
 * step a caller wants is now simply an argument.</p>
 *
 * <p>Both ladders are still exercised: {@code TEN} is what a PACT cheque grid uses
 * and what {@code ChequeGenerationService} passes, and {@code null} is the full
 * ladder from a thousand down to a fils.</p>
 */
class ChequeRoundingCalculatorTest {

    /** What a PACT cheque grid rounds to, and the only ladder production asks for. */
    private static final BigDecimal TEN = new BigDecimal("10");

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static BigDecimal sum(List<BigDecimal> amounts) {
        return amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The default strategy for everything below that is not about strategy. */
    private static ChequeRoundingCalculator.Result split(String total, int n, BigDecimal minStep) {
        return ChequeRoundingCalculator.distribute(
                bd(total), n, InstallmentDistribution.LAST_LARGER, minStep);
    }

    // ------------------------------------------------------------------
    // the full ladder
    // ------------------------------------------------------------------

    @Test
    void distributesIntoCleanThousandsWithResidualOnLastCheque() {
        // 31000 / 6 cheques: per=5000, last=6000.
        var result = split("31000", 6, null);
        assertThat(result.amounts())
                .containsExactly(bd("5000"), bd("5000"), bd("5000"), bd("5000"), bd("5000"), bd("6000"));
        assertThat(result.step()).isEqualByComparingTo(bd("1000"));
        assertThat(sum(result.amounts())).isEqualByComparingTo(bd("31000"));
    }

    @Test
    void allUniformWhenTotalDividesCleanly() {
        var result = split("24000", 12, null);
        assertThat(result.amounts()).hasSize(12).allSatisfy(a -> assertThat(a).isEqualByComparingTo(bd("2000")));
    }

    @Test
    void singleChequeReturnsFullAmount() {
        assertThat(split("12345.67", 1, null).amounts()).containsExactly(bd("12345.67"));
        assertThat(split("12345.67", 1, TEN).amounts())
                .as("a single cheque is the whole amount whatever the ladder")
                .containsExactly(bd("12345.67"));
    }

    @Test
    void fallsThroughToAFinerStepWhenTheRentIsSmallerThanTheStep() {
        // 1500 / 4 = 375. A 1000-step floors to zero, which would have produced
        // [0, 0, 0, 1500]; the finer step yields a real split instead.
        var result = split("1500", 4, null);
        assertThat(result.amounts()).containsExactly(bd("300"), bd("300"), bd("300"), bd("600"));
        assertThat(result.step()).isEqualByComparingTo(bd("100"));
    }

    // ------------------------------------------------------------------
    // the grid's ladder (spec §7.1)
    // ------------------------------------------------------------------

    /**
     * The case the {@code minStep} argument exists for. 61,000 over six is
     * 10,166.66: in tens that is 10,160 five times with 10,200 absorbing the
     * residual, where the coarse ladder floors to 10,000 and puts 11,000 on one
     * cheque — a denomination's worth of rent moved onto a single instrument
     * rather than a rounding difference.
     */
    @Test
    void theGridRoundsToTensSoTheResidualIsARoundingDifference() {
        var tens = ChequeRoundingCalculator.distribute(
                bd("61000"), 6, InstallmentDistribution.FIRST_LARGER, TEN);
        assertThat(tens.step()).isEqualByComparingTo(TEN);
        assertThat(tens.amounts())
                .containsExactly(bd("10200"), bd("10160"), bd("10160"), bd("10160"), bd("10160"), bd("10160"));
        assertThat(sum(tens.amounts())).isEqualByComparingTo(bd("61000"));

        var coarse = ChequeRoundingCalculator.distribute(
                bd("61000"), 6, InstallmentDistribution.FIRST_LARGER, null);
        assertThat(coarse.amounts().get(0))
                .as("the coarse ladder loads a whole thousand onto the first cheque")
                .isEqualByComparingTo(bd("11000"));
    }

    /** A rent that divides cleanly into tens needs no residual at all. */
    @Test
    void aGridThatDividesCleanlyIsFlat() {
        var result = ChequeRoundingCalculator.distribute(
                bd("51000"), 4, InstallmentDistribution.FIRST_LARGER, TEN);
        assertThat(result.amounts()).hasSize(4)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo(bd("12750")));
    }

    /**
     * {@code minStep} names the coarsest step allowed, not the only one. A rent
     * too small to floor to a ten still falls through to fils rather than failing.
     */
    @Test
    void finerStepsRemainAvailableBeneathMinStep() {
        var result = split("0.02", 2, TEN);
        assertThat(result.amounts()).containsExactly(bd("0.01"), bd("0.01"));
        assertThat(result.step()).isEqualByComparingTo(bd("0.01"));
    }

    /**
     * A {@code minStep} finer than a fils leaves no ladder at all. Refused as a bad
     * argument rather than silently answered, because the alternative is a split
     * computed on a ladder the caller did not ask for.
     */
    @Test
    void aMinStepBelowTheFinestDenominationIsRejected() {
        assertThatThrownBy(() -> ChequeRoundingCalculator.distribute(
                bd("60000"), 6, InstallmentDistribution.LAST_LARGER, bd("0.001")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No rounding step");

        // A minStep coarser than the ladder's head is simply the whole ladder.
        assertThat(split("31000", 6, bd("5000")).amounts())
                .isEqualTo(split("31000", 6, null).amounts());
    }

    // ------------------------------------------------------------------
    // no deposit cap
    // ------------------------------------------------------------------

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
        assertThat(split("60000", 1, TEN).amounts()).containsExactly(bd("60000"));
        assertThat(split("60000", 2, TEN).amounts()).containsExactly(bd("30000"), bd("30000"));
        assertThat(split("60000", 4, TEN).amounts())
                .containsExactly(bd("15000"), bd("15000"), bd("15000"), bd("15000"));
        assertThat(split("60000", 6, TEN).amounts())
                .containsExactly(bd("10000"), bd("10000"), bd("10000"), bd("10000"), bd("10000"), bd("10000"));
    }

    /**
     * The general form of the same rule: a cheque far larger than any plausible
     * deposit is fine, and the split stays exact.
     */
    @Test
    void aChequeMayGreatlyExceedAnyDepositTheLeaseWouldCarry() {
        var result = split("31500", 6, null);

        assertThat(result.amounts().get(5))
                .as("the residual cheque is not trimmed to fit a deposit")
                .isEqualByComparingTo(bd("6500"));
        assertThat(result.step())
                .as("and the clean 1,000 denomination is kept rather than dropping to 100 to satisfy a cap")
                .isEqualByComparingTo(bd("1000"));
        assertThat(sum(result.amounts())).isEqualByComparingTo(bd("31500"));
    }

    /**
     * Removing the deposit cap left the final throw reachable after all: a rent
     * below one fils per cheque floors every step to zero and falls out of the
     * loop. It had been left as an IllegalStateException on the belief that
     * nothing could reach it, which would surface as a 500.
     */
    @Test
    void rejectsARentTooSmallToSplitRatherThanFailingInternally() {
        assertThatThrownBy(() -> split("0.01", 2, null))
                .isInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too small");

        // The boundary either side: one fils each is fine.
        assertThat(split("0.02", 2, null).amounts()).containsExactly(bd("0.01"), bd("0.01"));
    }

    @Test
    void everyChequeCountFromOneToTwentyFourProducesAnExactSplit() {
        // The old cap made the viable range depend on the deposit. Nothing
        // narrows it now, so assert across the whole plausible range, on both
        // ladders — a grid is cut in tens and nothing else may be.
        for (BigDecimal minStep : new BigDecimal[]{null, TEN}) {
            for (int n = 1; n <= 24; n++) {
                String where = "cheque count " + n + " at minStep " + minStep;
                var amounts = split("60000", n, minStep).amounts();
                assertThat(amounts).as(where).hasSize(n);
                assertThat(amounts).as(where).allSatisfy(a -> assertThat(a).isPositive());
                assertThat(sum(amounts)).as(where).isEqualByComparingTo(bd("60000"));
            }
        }
    }
}
