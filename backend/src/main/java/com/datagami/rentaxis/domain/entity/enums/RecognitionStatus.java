package com.datagami.rentaxis.domain.entity.enums;

/**
 * Where one calendar-month slice of a rent segment stands (spec §8.2).
 *
 * <p>{@code PLANNED} is the whole schedule the moment a lease posts: precomputed
 * so the accountant can see next September's figure today. The nightly run turns
 * each row into a {@code CIL} journal as its period ends.</p>
 *
 * <p>The distinction between {@code REVERSED} and {@code CANCELLED} is whether
 * the ledger ever saw the row: a posted row is unwound with a mirror entry, a
 * merely planned one is struck out and leaves no trace in the books.</p>
 */
public enum RecognitionStatus {
    /** Scheduled, not yet in the ledger. */
    PLANNED,
    /** One {@code CIL} journal written; {@code journalId} names it. */
    POSTED,
    /** Was posted, then reversed — by an amendment or a termination. */
    REVERSED,
    /** Struck out before it was ever posted. */
    CANCELLED
}
