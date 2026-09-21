package com.datagami.rentaxis.core.service.voucher;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VoucherMathTest {

    private record Line(BigDecimal getAmount, BigDecimal getVatAmount) implements VoucherMath.HasAmounts {
        @Override public BigDecimal getAmount() { return getAmount; }
        @Override public BigDecimal getVatAmount() { return getVatAmount; }
    }

    private static Line line(String amount, String vat) {
        return new Line(new BigDecimal(amount), new BigDecimal(vat));
    }

    @Test
    void fivePercentOfARoundNumber() {
        assertThat(VoucherMath.vat(new BigDecimal("1000.00"), new BigDecimal("5")))
                .isEqualByComparingTo("50.00");
    }

    @Test
    void zeroRatedLinesCarryNoVat() {
        assertThat(VoucherMath.vat(new BigDecimal("1000.00"), BigDecimal.ZERO)).isEqualByComparingTo("0.00");
        assertThat(VoucherMath.vat(new BigDecimal("1000.00"), null)).isEqualByComparingTo("0.00");
    }

    /**
     * 5% of 1,234.57 is 61.7285 exactly. HALF_UP gives 61.73; the ledger takes two
     * decimals, so this is the line the journal carries and the number the FTA return
     * is built from. HALF_EVEN would give 61.73 here too — the case that separates them
     * is the .005 boundary below.
     */
    @Test
    void vatRoundsHalfUpToTwoDecimals() {
        assertThat(VoucherMath.vat(new BigDecimal("1234.57"), new BigDecimal("5")))
                .isEqualByComparingTo("61.73");
        // 5% of 100.10 = 5.005 -> HALF_UP -> 5.01 (HALF_EVEN would give 5.00)
        assertThat(VoucherMath.vat(new BigDecimal("100.10"), new BigDecimal("5")))
                .isEqualByComparingTo("5.01");
    }

    /**
     * VAT is computed and rounded PER LINE, then summed — never computed on the net
     * total. Three lines of 100.10 each round to 5.01, so the header VAT is 15.03,
     * not the 15.02 you would get from 5% of 300.30. PACT does it per line and the
     * vendor's own invoice will show the per-line figures.
     */
    @Test
    void headerVatIsTheSumOfPerLineVatNotVatOfTheSum() {
        List<Line> lines = List.of(line("100.10", "5.01"), line("100.10", "5.01"), line("100.10", "5.01"));
        assertThat(VoucherMath.vatTotal(lines)).isEqualByComparingTo("15.03");
        assertThat(VoucherMath.netTotal(lines)).isEqualByComparingTo("300.30");
        assertThat(VoucherMath.grossTotal(lines)).isEqualByComparingTo("315.33");
    }

    @Test
    void totalsOfAnEmptyListAreZeroNotNull() {
        assertThat(VoucherMath.netTotal(List.of())).isEqualByComparingTo("0.00");
        assertThat(VoucherMath.vatTotal(List.of())).isEqualByComparingTo("0.00");
        assertThat(VoucherMath.grossTotal(List.of())).isEqualByComparingTo("0.00");
    }

    @Test
    void mixedRatedAndZeroRatedLinesAddUp() {
        List<Line> lines = List.of(line("5000.00", "250.00"), line("1200.00", "0.00"));
        assertThat(VoucherMath.netTotal(lines)).isEqualByComparingTo("6200.00");
        assertThat(VoucherMath.vatTotal(lines)).isEqualByComparingTo("250.00");
        assertThat(VoucherMath.grossTotal(lines)).isEqualByComparingTo("6450.00");
    }
}
