package com.datagami.rentaxis.core.service.voucher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * VAT and total arithmetic for vouchers. Deliberately a plain final class with
 * static methods and no Spring: this is the only arithmetic in the voucher path
 * that can be wrong in a way the ledger will not catch (a balanced journal with
 * the wrong VAT split is still balanced), so it is tested in isolation.
 *
 * <p>VAT is rounded per line, then summed. Computing 5% of the net total instead
 * would disagree with the vendor's invoice by a fil or two on multi-line
 * invoices; see VoucherMathTest#headerVatIsTheSumOfPerLineVatNotVatOfTheSum.
 */
public final class VoucherMath {

    private VoucherMath() {}

    public static final int SCALE = 2;
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    /** Anything carrying a net amount and its already-computed VAT. Implemented by VoucherLine. */
    public interface HasAmounts {
        BigDecimal getAmount();
        BigDecimal getVatAmount();
    }

    /** {@code amount * rate/100}, HALF_UP to 2dp. A null or zero rate gives 0.00. */
    public static BigDecimal vat(BigDecimal amount, BigDecimal vatRatePercent) {
        if (amount == null || vatRatePercent == null || vatRatePercent.signum() == 0) return ZERO;
        return amount.multiply(vatRatePercent)
                .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal netTotal(List<? extends HasAmounts> lines) {
        return sum(lines, HasAmounts::getAmount);
    }

    public static BigDecimal vatTotal(List<? extends HasAmounts> lines) {
        return sum(lines, HasAmounts::getVatAmount);
    }

    public static BigDecimal grossTotal(List<? extends HasAmounts> lines) {
        return netTotal(lines).add(vatTotal(lines));
    }

    private static BigDecimal sum(List<? extends HasAmounts> lines,
                                  java.util.function.Function<HasAmounts, BigDecimal> f) {
        BigDecimal total = ZERO;
        if (lines == null) return total;
        for (HasAmounts l : lines) {
            BigDecimal v = f.apply(l);
            if (v != null) total = total.add(v);
        }
        return total.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
