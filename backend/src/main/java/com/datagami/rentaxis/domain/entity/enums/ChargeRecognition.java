package com.datagami.rentaxis.domain.entity.enums;

/**
 * How a {@link ChargeBehaviour#FEE FEE} charge type's money reaches the books
 * (F14-18, round-15 ruling), and whether a renewal copies it (spec 2026-09-24 §4c).
 *
 * <ul>
 *   <li>{@link #RENT_LIKE} — a periodic charge (annual parking, cooling, service or
 *       maintenance charge) earned over the lease term by the same per-day rule as
 *       rent: the {@code TCO} credits {@code UNEARNED_CHARGES} and the monthly
 *       recognition run moves each month's share to the charge's income account. An
 *       early termination hands the unearned part back. Copied on renewal.</li>
 *   <li>{@link #ONE_OFF} — earned when charged (admin fee, renewal fee): credited to
 *       income at posting, exactly as every fee was before. Not copied on renewal.</li>
 *   <li>{@link #PASS_THROUGH} — a utility recovered at cost (DEWA, chiller at cost):
 *       never income. Held in {@code UNEARNED_CHARGES} at posting and released month
 *       by month, like rent, to the property's Utilities expense leaf (the line's
 *       account) as it is recovered (PR #358 R1); on a lease posted under the old
 *       rule it credits that leaf at posting. Copied on renewal.</li>
 * </ul>
 *
 * <p>RENT and DEPOSIT charge types always carry {@code RENT_LIKE}: rent is
 * recognised by its own behaviour, and a deposit follows its carry rule.</p>
 *
 * <p>The earning rule applies only to leases posted after it shipped
 * ({@link FeeTiming#OVER_TERM}); a lease already on the books, and every cut-over
 * import, keeps fees as income at posting ({@link FeeTiming#AT_POSTING}).</p>
 */
public enum ChargeRecognition {
    RENT_LIKE, ONE_OFF, PASS_THROUGH,
    /**
     * F15-06: never a charge type's rule — only a posted line's snapshot, for a line
     * that was not income at all (a deposit, held as a liability).
     */
    NONE;

    /** A renewal copies the line (spec §4c): everything but a one-off fee. */
    public boolean recurs() {
        return this != ONE_OFF && this != NONE;
    }
}
