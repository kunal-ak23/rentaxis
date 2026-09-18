package com.datagami.rentaxis.domain.entity.enums;

/**
 * What a lease line built from a charge type <em>means</em>, as opposed to which
 * account it credits (that is the charge type's {@link AccountRole}).
 *
 * <p>Posting reads the role; recognition, refunds and reporting read the
 * behaviour. They are deliberately separate because the two do not line up:
 * {@code RENT} credits a liability (unearned rent) rather than income, and is
 * recognised day by day over the term (spec §6.1), while {@code DEPOSIT} credits
 * a liability that is never recognised at all — it is refunded or forfeited.
 * {@code FEE} is the ordinary case: credited straight to income.</p>
 */
public enum ChargeBehaviour {
    /** Recognised over the term; credits unearned rent on posting. */
    RENT,
    /** Held against the renter and refunded or forfeited; never recognised as income. */
    DEPOSIT,
    /** Earned when charged; credited directly to an income account. */
    FEE
}
