package com.datagami.rentaxis.domain.entity.enums;

/**
 * Where a gateway session ended up.
 *
 * <p>{@link #CAPTURED_UNAPPLIED} is the one that matters operationally: the
 * gateway took the renter's money and the register could not accept it — two
 * orders raised against one instalment and both paid, a clerk banking the cheque
 * while the renter was paying for it, an amount that does not match the row. The
 * alternative to naming that state was absorbing it: either posting a second
 * {@code CRT} for money already collected, or answering the gateway "fine,
 * already done" and losing the fact that a refund is owed. Neither is something a
 * landlord can reconcile a month later, so it gets a status of its own, a
 * {@code failureReason} naming the cause and the gateway payment id, and an
 * {@code ERROR} line.</p>
 *
 * <p>Stored as a string in {@code online_payments.status}, VARCHAR(30) with no
 * CHECK constraint, so a new constant needs no migration.</p>
 */
public enum OnlinePaymentStatus {
    CREATED,
    CAPTURED,
    /** Money taken by the gateway that the register refused; a refund is owed. */
    CAPTURED_UNAPPLIED,
    FAILED,
    REFUNDED
}
