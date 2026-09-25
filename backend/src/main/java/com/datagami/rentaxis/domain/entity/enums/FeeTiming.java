package com.datagami.rentaxis.domain.entity.enums;

/**
 * Whether a lease's periodic (RENT_LIKE) fees are earned over its term (F14-18).
 *
 * <ul>
 *   <li>{@link #OVER_TERM} — leases posted after the round-15 change: a
 *       {@link ChargeRecognition#RENT_LIKE} fee is deferred at posting and
 *       recognised day by day, like rent.</li>
 *   <li>{@link #AT_POSTING} — the earlier model, kept for leases already posted
 *       (changeset 128 backfills them) and for every cut-over import, whose
 *       replay must stay byte-identical to PACT: every fee is income on the
 *       contract date.</li>
 * </ul>
 */
public enum FeeTiming {
    OVER_TERM, AT_POSTING
}
