package com.datagami.rentaxis.api.validation;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;

import java.math.BigDecimal;

/**
 * What an amount of money may be, in one place (break-it round 1, money F1–F3).
 *
 * <p>Every money column is {@code numeric(14,2)}: two decimals, twelve digits in
 * front. A request that carries a third decimal used to be rounded HALF_UP and
 * posted — the user typed 1000.555 and the ledger says 1000.56 without anybody
 * being told. A value past the column used to reach the database, overflow, and
 * come back as a 409 "conflicts with existing related records". And 0.001 passed
 * every {@code > 0} check, then rounded to a zero rent or tripped a CHECK
 * constraint whose name was the only error the user saw.</p>
 *
 * <p>So: at most two decimals (trailing zeros do not count — 1000.500 is 1000.50),
 * at least one fil where the amount must be positive, and no more than
 * {@link #MAX}. Refused with these sentences, never rounded.</p>
 */
public final class MoneyAmounts {

    private MoneyAmounts() {
    }

    /** The largest amount a numeric(14,2) column holds. */
    public static final BigDecimal MAX = new BigDecimal("999999999999.99");

    /** One fil: the smallest positive amount. */
    public static final BigDecimal MIN = new BigDecimal("0.01");

    public static final String TOO_MANY_DECIMALS = "Amounts can have at most 2 decimal places";
    public static final String BELOW_MINIMUM = "Amounts must be at least 0.01";
    public static final String NEGATIVE = "Amounts cannot be negative";
    public static final String TOO_LARGE = "The amount is too large (the maximum is 999,999,999,999.99)";

    /**
     * Why this amount is refused, or null when it is fine. Null in, null out: whether
     * an amount is required at all is the caller's rule, not this one's.
     *
     * @param positive the amount must be at least one fil
     * @param allowNegative a negative amount is acceptable (its magnitude is still bounded)
     */
    public static String problem(BigDecimal amount, boolean positive, boolean allowNegative) {
        if (amount == null) return null;
        // Below one fil first: for 0.001 "at least 0.01" is the sentence that helps.
        if (positive && amount.compareTo(MIN) < 0) return BELOW_MINIMUM;
        if (amount.stripTrailingZeros().scale() > 2) return TOO_MANY_DECIMALS;
        if (amount.abs().compareTo(MAX) > 0) return TOO_LARGE;
        if (!positive && !allowNegative && amount.signum() < 0) return NEGATIVE;
        return null;
    }

    /** The same rule for service code: throws the refusal as a 400. */
    public static BigDecimal requirePositive(BigDecimal amount) {
        String p = problem(amount, true, false);
        if (amount == null) p = BELOW_MINIMUM;
        if (p != null) throw new BusinessRuleViolationException(p);
        return amount;
    }
}
