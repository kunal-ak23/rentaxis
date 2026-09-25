package com.datagami.rentaxis.domain.entity.enums;

/**
 * Where a penalty stands (spec §7.3).
 *
 * <p>The client's accountant was explicit: a fine is not charged because a rule
 * fired. After two or three returned cheques finance <em>decides</em>. So the
 * system only ever proposes, and {@link #APPROVED} is the single status that has
 * a journal behind it.</p>
 *
 * <ul>
 *   <li>{@code PROPOSED} — raised by a rule or by hand; nothing posted.</li>
 *   <li>{@code APPROVED} — a {@code PEN} journal and a collection row exist.</li>
 *   <li>{@code WAIVED} — finance declined it; nothing posted, ever.</li>
 *   <li>{@code REVERSED} — approved in error; the mirror journal exists and the
 *       collection row was cancelled.</li>
 *   <li>{@code WRITTEN_OFF} — F14-38 (PR #361 R1 P1-1): approved, and its collection
 *       row was taken by a bad-debt write-off. It cannot be reversed (the receivable
 *       is already credited by the BDW); a reversal of the write-off restores APPROVED.</li>
 * </ul>
 */
public enum PenaltyAssessmentStatus {
    PROPOSED, APPROVED, WAIVED, REVERSED, WRITTEN_OFF
}
