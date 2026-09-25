package com.datagami.rentaxis.domain.entity.enums;

/**
 * Where a receipt instrument stands in its lifecycle (spec §7).
 *
 * <p>The register's money questions are asked through the two predicates rather
 * than through ad-hoc status lists at every call site: {@link #isUncleared()} is
 * "the landlord is still owed this", {@link #isTerminal()} is "nothing further
 * will happen to this row".</p>
 *
 * <p>{@code BOUNCED} is deliberately neither: a bounced cheque is not uncleared
 * (its PDC receivable has already been reversed) but it is not finished either —
 * it is waiting for a replacement, a penalty, or a return.</p>
 */
public enum ChequeStatus {
    DRAFT,
    REGISTERED,
    DEPOSITED,
    CLEARED,
    BOUNCED,
    REPLACED,
    CANCELLED,
    RETURNED,
    ONLINE_PENDING,
    /**
     * Spec §2 (#52): terminal. The renter moved unit and this instrument went with
     * them: its PDR was reversed here and the successor lease's row (named by
     * {@code transferredToId}) holds it now.
     */
    TRANSFERRED;

    /** Money the landlord is still waiting on: registered, banked, or in flight online. */
    public boolean isUncleared() {
        return this == REGISTERED || this == DEPOSITED || this == ONLINE_PENDING;
    }

    /** No further lifecycle transition is possible from here. */
    public boolean isTerminal() {
        return this == CLEARED || this == REPLACED || this == CANCELLED || this == RETURNED;
    }
}
