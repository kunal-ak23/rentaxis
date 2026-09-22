package com.datagami.rentaxis.api.dto.penalty;

import java.time.LocalDate;

/**
 * Finance's decision on a proposal: approve, waive or reverse.
 *
 * <p>One shape for the three verbs rather than three, because what they need is
 * the same two fields and which of them matters is the verb's business. Approve
 * and reverse use {@code date} as the journal's entry date; waive posts nothing
 * and ignores it. Waive requires {@code note} — "we decided not to charge this"
 * with no reason recorded is not a decision anyone can audit.</p>
 *
 * @param date the day the decision files under; defaults to today.
 * @param note free text kept on the assessment and used as the reversal's reason.
 */
public record PenaltyDecisionRequest(LocalDate date, String note) {

    public static PenaltyDecisionRequest empty() {
        return new PenaltyDecisionRequest(null, null);
    }
}
