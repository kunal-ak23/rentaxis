package com.datagami.rentaxis.api.exception;

/**
 * "Somebody else is holding this row; try again."
 *
 * <p>Every NOWAIT lock in the register translates a
 * {@code PessimisticLockingFailureException} into this rather than letting it out
 * as a 500: the other caller is almost always the same clerk double-clicking, and
 * the right answer is a 400 that says so. It stays a
 * {@link BusinessRuleViolationException} for exactly that reason — the HTTP
 * behaviour and the message are unchanged.</p>
 *
 * <p><b>Why it is its own type.</b> A lock conflict is the one refusal in that
 * family that is <em>transient</em>: the same request a second later succeeds.
 * {@code WebhookService} has to tell that apart from a deterministic refusal (a
 * locked period, a row a clerk has moved), because the two need opposite answers
 * to the gateway — redeliver this one, never redeliver that one. Matching on the
 * message string would have been a rule that a reworded sentence silently
 * breaks.</p>
 */
public class RowLockedException extends BusinessRuleViolationException {

    public RowLockedException(String message) {
        super(message);
    }
}
