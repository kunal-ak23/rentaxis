package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a total rent amount across N cheques producing clean, uniform
 * per-cheque amounts with the rounding residual placed per the chosen
 * {@link InstallmentDistribution} strategy.
 *
 * <p>Cheque-heavy markets (UAE) prefer round numbers like AED 5,000 over
 * AED 5,166.67 — easier to write, fewer disputes.</p>
 *
 * <p><b>No deposit cap.</b> This class used to reject any split whose largest
 * cheque exceeded the lease's security deposit, on the theory that the deposit
 * should always cover a bounced final cheque. That is not how UAE leasing
 * works: the deposit is a fixed percentage of annual rent (commonly 5%), while
 * cheque size is annual rent divided by the cheque count. Under the old rule a
 * 60,000/year lease with a 5,000 deposit could only be written as 12 cheques —
 * 1, 2, 4 and 6-cheque leases, which are the market norm, were all refused.
 * How much risk to carry on the final cheque is a commercial term the landlord
 * negotiates, not something the software can decide; the deposit is collected
 * as its own schedule row regardless.</p>
 */
public final class ChequeRoundingCalculator {

    /**
     * Candidate rounding steps tried in order (largest → finest). The first
     * step that yields a positive per-cheque base wins. The final step of 0.01
     * corresponds to even-cents division, used when the rent is too small for
     * any clean denomination.
     */
    private static final List<BigDecimal> STEPS = List.of(
            new BigDecimal("1000"),
            new BigDecimal("500"),
            new BigDecimal("100"),
            new BigDecimal("10"),
            new BigDecimal("0.01")
    );

    /** Smallest currency unit, used for UNIFORM cent distribution. */
    private static final BigDecimal CENT = new BigDecimal("0.01");

    public record Result(List<BigDecimal> amounts, BigDecimal step) {}

    private ChequeRoundingCalculator() {}

    /**
     * Distributes {@code totalRent} across {@code n} cheques, placing the rounding
     * residual per the chosen {@link InstallmentDistribution} and flooring the base
     * to a denomination no coarser than {@code minStep} (spec §7.1).
     *
     * <ul>
     *   <li>UNIFORM — even per-cheque amount; the cent remainder is spread one cent
     *       at a time across the earliest cheques so the sum is exact.</li>
     *   <li>LAST_LARGER — non-last cheques floored to a clean step; the remainder
     *       lands on the last cheque.</li>
     *   <li>FIRST_LARGER — remainder on the first cheque.</li>
     *   <li>FIRST_AND_LAST_LARGER — remainder split across first + last.</li>
     * </ul>
     *
     * <p><b>Zero-value cheque guard:</b> for the stepped strategies, a candidate
     * step whose floored per-cheque base evaluates to zero is skipped, and the
     * ladder falls through to a finer one. Without it, 1,500 over 4 cheques at a
     * 1,000 step floors to zero and produces {@code [0, 0, 0, 1500]} rather than
     * {@code [300, 300, 300, 600]}. Every positive rent and cheque count therefore
     * produces a schedule; this method does not reject a lease.</p>
     *
     * <p>A PACT cheque grid is written in tens, not in thousands: 61,000 over six
     * cheques is 10,160 five times with 10,200 absorbing the residual, where the
     * default ladder would have floored to 10,000 and put 11,000 on the first
     * cheque. Passing {@code minStep = 10} drops every coarser step and leaves
     * {@code [10, 0.01]}, so the base is as close to the true average as a
     * writable cheque gets and the residual is a rounding difference rather than a
     * denomination's worth of rent.</p>
     *
     * <p>{@code minStep} names the <em>coarsest</em> step that may be used, and
     * finer ones remain available beneath it: a rent too small to floor to a ten
     * still falls through to cents rather than failing. A null {@code minStep}
     * means the full ladder.</p>
     *
     * <p>The two- and three-argument overloads that started at the coarsest
     * denomination went with v1's payment schedules (changeset 84). They withheld
     * the 10 step so a regenerated schedule could not disagree with one the renter
     * had already been sent; with no rows left to regenerate, there is nothing for
     * a second ladder to protect.</p>
     *
     * @param totalRent total amount to distribute (must be &gt; 0).
     * @param n number of cheques (&gt;= 1).
     * @param strategy remainder-placement strategy; null defaults to LAST_LARGER.
     * @param minStep coarsest rounding denomination to try; finer steps follow.
     *                Null means the full ladder, starting at 1,000.
     */
    public static Result distribute(BigDecimal totalRent, int n, InstallmentDistribution strategy, BigDecimal minStep) {
        List<BigDecimal> ladder = minStep == null
                ? STEPS
                : STEPS.stream().filter(s -> s.compareTo(minStep) <= 0).toList();
        if (ladder.isEmpty()) {
            throw new IllegalArgumentException("No rounding step at or below " + minStep);
        }
        return distribute(totalRent, n, strategy, ladder);
    }

    private static Result distribute(BigDecimal totalRent, int n, InstallmentDistribution strategy,
                                     List<BigDecimal> ladder) {
        if (totalRent == null || totalRent.signum() <= 0) {
            throw new IllegalArgumentException("totalRent must be > 0");
        }
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
        if (strategy == null) strategy = InstallmentDistribution.LAST_LARGER;
        BigDecimal nBd = BigDecimal.valueOf(n);

        if (n == 1) {
            // Single cheque carries the full amount under every strategy.
            return new Result(List.of(totalRent), null);
        }

        if (strategy == InstallmentDistribution.UNIFORM) {
            BigDecimal per = totalRent.divide(nBd, 2, RoundingMode.DOWN);
            int extraCents = totalRent.subtract(per.multiply(nBd)).movePointRight(2).intValueExact();
            List<BigDecimal> amounts = new ArrayList<>(n);
            for (int i = 0; i < n; i++) amounts.add(i < extraCents ? per.add(CENT) : per);
            return new Result(amounts, CENT);
        }

        for (BigDecimal step : ladder) {
            BigDecimal per = floorToStep(totalRent.divide(nBd, 2, RoundingMode.FLOOR), step);
            if (per.signum() <= 0) continue;
            List<BigDecimal> amounts = new ArrayList<>(n);
            switch (strategy) {
                case FIRST_LARGER -> {
                    amounts.add(totalRent.subtract(per.multiply(BigDecimal.valueOf(n - 1L))));
                    for (int i = 0; i < n - 1; i++) amounts.add(per);
                }
                case FIRST_AND_LAST_LARGER -> {
                    BigDecimal middle = per.multiply(BigDecimal.valueOf(Math.max(n - 2, 0)));
                    BigDecimal rem = totalRent.subtract(middle);
                    BigDecimal first = floorToStep(rem.divide(BigDecimal.valueOf(2), 2, RoundingMode.FLOOR), step);
                    amounts.add(first);
                    for (int i = 0; i < n - 2; i++) amounts.add(per);
                    amounts.add(rem.subtract(first));
                }
                default -> { // LAST_LARGER
                    for (int i = 0; i < n - 1; i++) amounts.add(per);
                    amounts.add(totalRent.subtract(per.multiply(BigDecimal.valueOf(n - 1L))));
                }
            }
            return new Result(amounts, step);
        }

        // Reached when the rent is smaller than one fils per cheque (0.01 across
        // 2, say): every step floors the base to zero. Not an internal fault —
        // it is a bad pair of arguments, and raising it as one keeps the
        // response a 400 rather than a 500.
        throw new IllegalArgumentException(
                "Rent " + totalRent + " is too small to split across " + n + " cheques");
    }

    private static BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        return value.divide(step, 0, RoundingMode.FLOOR).multiply(step);
    }
}
