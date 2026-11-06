package com.datagami.rentaxis.domain.entity.enums;

public enum LeaseStatus {
    DRAFT,
    PENDING_SIGNATURE,
    ACTIVE,
    NOTICE_GIVEN,
    TERMINATED,
    /**
     * Superseded by a successor lease in the same chain (spec §8). Distinct from
     * EXPIRED: an EXPIRED lease simply ran out, a RENEWED one handed its unit,
     * and possibly its deposit, to the next contract.
     */
    RENEWED,
    /**
     * The term ran out with no renewal and no notice, and the renter is presumed
     * still in the unit while the landlord chases them (holdover). Unlike EXPIRED
     * it keeps the unit: nothing else can be let on it. Nothing is recognised; the
     * way out is a renewal starting the day after the old end, or "tenant moved
     * out" (a use-and-occupation charge, then EXPIRED).
     */
    ON_HOLD,
    EXPIRED,
    CLOSED
}
