package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;

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

    /** Smallest currency unit, used for UNIFORM cent distribution. */
    private static final BigDecimal CENT = new BigDecimal("0.01");

    public record Result(List<BigDecimal> amounts, BigDecimal step) {}

    private ChequeRoundingCalculator() {}

    /**
     * Legacy 3-arg overload. Delegates to the strategy overload with the
     * default LAST_LARGER distribution so existing callers/tests are unchanged.
     *
     * @param totalRent total amount to distribute (must be > 0).
     * @param n number of cheques (>= 1).
     * @param depositCap maximum allowed last-cheque amount. Null disables the cap.
     */
    public static Result distribute(BigDecimal totalRent, int n, BigDecimal depositCap) {
        return distribute(totalRent, n, depositCap, InstallmentDistribution.LAST_LARGER);
    }

    /**
     * Distributes {@code totalRent} across {@code n} cheques, placing the
     * rounding residual per the chosen {@link InstallmentDistribution} strategy.
     *
     * <ul>
     *   <li>UNIFORM — even per-cheque amount; the cent remainder is spread one
     *       cent at a time across the earliest cheques so the sum is exact.</li>
     *   <li>LAST_LARGER (default) — non-last cheques floored to a clean step;
     *       the remainder lands on the last cheque. Byte-identical to the legacy
     *       behavior.</li>
     *   <li>FIRST_LARGER — remainder on the first cheque.</li>
     *   <li>FIRST_AND_LAST_LARGER — remainder split across first + last.</li>
     * </ul>
     *
     * The deposit cap (when active) applies to the largest cheque. When no clean
     * step keeps the largest cheque within the cap, distribution is rejected.
     *
     * @param totalRent total amount to distribute (must be > 0).
     * @param n number of cheques (>= 1).
     * @param depositCap maximum allowed largest-cheque amount. Null disables the cap.
     * @param strategy remainder-placement strategy; null defaults to LAST_LARGER.
     */
    public static Result distribute(BigDecimal totalRent, int n, BigDecimal depositCap,
                                    InstallmentDistribution strategy) {
        if (totalRent == null || totalRent.signum() <= 0) {
            throw new IllegalArgumentException("totalRent must be > 0");
        }
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        if (strategy == null) strategy = InstallmentDistribution.LAST_LARGER;
        boolean capActive = depositCap != null && depositCap.signum() > 0;
        BigDecimal nBd = BigDecimal.valueOf(n);

        if (n == 1) {
            // Single cheque carries the full amount under every strategy. The cap
            // still applies — preserving the prior single-cheque rejection.
            if (capActive && totalRent.compareTo(depositCap) > 0) {
                throw new BusinessRuleViolationException(capMsg(totalRent, n, depositCap));
            }
            return new Result(List.of(totalRent), null);
        }

        if (strategy == InstallmentDistribution.UNIFORM) {
            BigDecimal per = totalRent.divide(nBd, 2, RoundingMode.DOWN);
            int extraCents = totalRent.subtract(per.multiply(nBd)).movePointRight(2).intValueExact();
            List<BigDecimal> amounts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) amounts.add(i < extraCents ? per.add(CENT) : per);
            BigDecimal largest = amounts.stream().max(BigDecimal::compareTo).orElse(per);
            if (capActive && largest.compareTo(depositCap) > 0) {
                throw new BusinessRuleViolationException(capMsg(totalRent, n, depositCap));
            }
            return new Result(amounts, CENT);
        }

        for (BigDecimal step : STEPS) {
            BigDecimal per = floorToStep(totalRent.divide(nBd, 2, RoundingMode.FLOOR), step);
            if (per.signum() <= 0) continue;
            List<BigDecimal> amounts = new ArrayList<>(n);
            BigDecimal big;
            switch (strategy) {
                case FIRST_LARGER -> {
                    big = totalRent.subtract(per.multiply(BigDecimal.valueOf(n - 1L)));
                    amounts.add(big);
                    for (int i = 0; i < n - 1; i++) amounts.add(per);
                }
                case FIRST_AND_LAST_LARGER -> {
                    BigDecimal middle = per.multiply(BigDecimal.valueOf(Math.max(n - 2, 0)));
                    BigDecimal rem = totalRent.subtract(middle);
                    BigDecimal first = floorToStep(rem.divide(BigDecimal.valueOf(2), 2, RoundingMode.FLOOR), step);
                    BigDecimal last = rem.subtract(first);
                    amounts.add(first);
                    for (int i = 0; i < n - 2; i++) amounts.add(per);
                    amounts.add(last);
                    big = first.max(last);
                }
                default -> { // LAST_LARGER
                    big = totalRent.subtract(per.multiply(BigDecimal.valueOf(n - 1L)));
                    for (int i = 0; i < n - 1; i++) amounts.add(per);
                    amounts.add(big);
                }
            }
            if (!capActive || big.compareTo(depositCap) <= 0) return new Result(amounts, step);
        }

        throw new BusinessRuleViolationException(capMsg(totalRent, n, depositCap));
    }

    private static String capMsg(BigDecimal totalRent, int n, BigDecimal cap) {
        return "Cannot distribute rent " + totalRent + " across " + n
                + " cheques without the last cheque exceeding the deposit ("
                + cap + "). Increase the deposit or the cheque count.";
    }

    private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.FLOOR).multiply(step);
    }
}
