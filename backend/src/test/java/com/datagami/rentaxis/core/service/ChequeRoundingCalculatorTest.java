package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChequeRoundingCalculatorTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void distributesIntoCleanThousandsWithResidualOnLastCheque() {
        // 31000 / 6 cheques, deposit 10000: per=5000, last=6000.
        var result = ChequeRoundingCalculator.distribute(bd("31000"), 6, bd("10000"));
        assertThat(result.amounts())
                .containsExactly(bd("5000"), bd("5000"), bd("5000"), bd("5000"), bd("5000"), bd("6000"));
        assertThat(result.step()).isEqualByComparingTo(bd("1000"));
        // Invariant: sum equals totalRent exactly.
        assertThat(result.amounts().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(bd("31000"));
    }

    @Test
    void allUniformWhenTotalDividesCleanly() {
        // 24000 / 12 = 2000 exactly. Last == per.
        var result = ChequeRoundingCalculator.distribute(bd("24000"), 12, bd("10000"));
        assertThat(result.amounts()).hasSize(12).allSatisfy(a -> assertThat(a).isEqualByComparingTo(bd("2000")));
    }

    @Test
    void fallsBackToSmallerStepWhenLastChequeExceedsDeposit() {
        // 31500 / 6 with deposit 6000.
        // Step 1000: per=5000, last=6500 → exceeds 6000.
        // Step 500: per=5000, last=6500 → still 6500 (5000 is floor of 5250 to 500).
        //   Wait: 31500/6 = 5250. floor(5250, 500) = 5000. last = 31500 - 25000 = 6500. Exceeds.
        // Step 100: per=5200, last = 31500 - 26000 = 5500 → fits within 6000. ✓
        var result = ChequeRoundingCalculator.distribute(bd("31500"), 6, bd("6000"));
        assertThat(result.step()).isEqualByComparingTo(bd("100"));
        assertThat(result.amounts().get(0)).isEqualByComparingTo(bd("5200"));
        assertThat(result.amounts().get(5)).isEqualByComparingTo(bd("5500"));
        assertThat(result.amounts().get(5)).isLessThanOrEqualTo(bd("6000"));
    }

    @Test
    void throwsWhenEvenSmallestStepCannotSatisfyDepositCap() {
        // 60000 / 6 cheques, deposit 5000. Natural per-cheque = 10000 > deposit.
        // No rounding strategy can rescue this — last cheque inevitably > deposit.
        assertThatThrownBy(() -> ChequeRoundingCalculator.distribute(bd("60000"), 6, bd("5000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("deposit");
    }

    @Test
    void noDepositCapStillRoundsToCleanThousands() {
        var result = ChequeRoundingCalculator.distribute(bd("31000"), 6, null);
        assertThat(result.amounts().get(0)).isEqualByComparingTo(bd("5000"));
        assertThat(result.amounts().get(5)).isEqualByComparingTo(bd("6000"));
        assertThat(result.step()).isEqualByComparingTo(bd("1000"));
    }

    @Test
    void singleChequeReturnsFullAmount() {
        var result = ChequeRoundingCalculator.distribute(bd("12345.67"), 1, bd("20000"));
        assertThat(result.amounts()).containsExactly(bd("12345.67"));
    }

    @Test
    void singleChequeViolatesDepositCapWhenAmountExceedsDeposit() {
        assertThatThrownBy(() -> ChequeRoundingCalculator.distribute(bd("20000"), 1, bd("5000")))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void smallTotalFallsThroughToPennyStep() {
        // 7000 / 3 = 2333.33. Step 1000 → per=2000, last=3000.
        // If cap is 2500, fallback: step 500 → per=2000, last=3000 still.
        //   step 100 → per=2300, last=2400 ≤ 2500 ✓
        var result = ChequeRoundingCalculator.distribute(bd("7000"), 3, bd("2500"));
        assertThat(result.step()).isEqualByComparingTo(bd("100"));
        assertThat(result.amounts().get(2)).isLessThanOrEqualTo(bd("2500"));
        assertThat(result.amounts().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(bd("7000"));
    }
}
