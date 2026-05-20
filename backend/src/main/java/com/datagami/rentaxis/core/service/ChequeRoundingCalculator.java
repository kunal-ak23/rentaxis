package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a total rent amount across N cheques producing clean, uniform
 * per-cheque amounts with the rounding residual placed on the last cheque.
 *
 * Cheque-heavy markets (UAE) prefer round numbers like AED 5,000 over
 * AED 5,166.67 — easier to write, fewer disputes. To preserve the deposit
 * as a safety net the last cheque (which carries the residual) is capped
 * at the lease's deposit amount; if the natural average per-cheque already
 * exceeds the deposit, distribution is rejected.
 */
public final class ChequeRoundingCalculator {

    /**
     * Candidate rounding steps tried in order (largest → finest). The first
     * step that produces a last-cheque amount within the deposit cap wins.
     * The final step of 0.01 corresponds to even-cents division, matching
     * the legacy behavior when no clean step satisfies the cap.
     */
    private static final List<BigDecimal> STEPS = List.of(
            new BigDecimal("1000"),
            new BigDecimal("500"),
            new BigDecimal("100"),
            new BigDecimal("0.01")
    );

    public record Result(List<BigDecimal> amounts, BigDecimal step) {}

    private ChequeRoundingCalculator() {}

    /**
     * @param totalRent total amount to distribute (must be > 0).
     * @param n number of cheques (>= 1).
     * @param depositCap maximum allowed last-cheque amount. Null disables the cap.
     */
    public static Result distribute(BigDecimal totalRent, int n, BigDecimal depositCap) {
        if (totalRent == null || totalRent.signum() <= 0) {
            throw new IllegalArgumentException("totalRent must be > 0");
        }
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }

        BigDecimal nBd = BigDecimal.valueOf(n);
        boolean capActive = depositCap != null && depositCap.signum() > 0;

        for (BigDecimal step : STEPS) {
            BigDecimal per = floorToStep(totalRent.divide(nBd, 2, RoundingMode.FLOOR), step);
            BigDecimal nonLastSum = per.multiply(BigDecimal.valueOf(n - 1L));
            BigDecimal last = totalRent.subtract(nonLastSum);

            if (!capActive || last.compareTo(depositCap) <= 0) {
                List<BigDecimal> amounts = new ArrayList<>(n);
                for (int i = 0; i < n - 1; i++) amounts.add(per);
                amounts.add(last);
                return new Result(amounts, step);
            }
        }

        throw new BusinessRuleViolationException(
                "Cannot distribute rent " + totalRent + " across " + n
                        + " cheques without the last cheque exceeding the deposit ("
                        + depositCap + "). Increase the deposit or the cheque count.");
    }

    private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.FLOOR).multiply(step);
    }
}
