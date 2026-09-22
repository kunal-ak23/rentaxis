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
    EXPIRED,
    CLOSED
}
