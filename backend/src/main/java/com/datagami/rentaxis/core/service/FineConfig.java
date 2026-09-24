package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;

import java.math.BigDecimal;

/**
 * The fine rules in force for one property: what a returned cheque costs, how
 * long rent may be late for free, and — since the penalty module (spec §7.3) —
 * when the system is allowed to <em>propose</em> a charge at all.
 *
 * <p>The three auto-propose fields are not a licence to post. Nothing in this
 * record causes a journal; they decide whether a proposal appears on finance's
 * worklist, and a human still approves it.</p>
 *
 * @param bouncesBeforePenalty how many returned cheques on one lease before a
 *        cheque-return penalty is proposed. Counted per lease, inclusive: a
 *        threshold of 2 proposes on the second bounce.
 */
public record FineConfig(
        BigDecimal bounceAmount,
        BigDecimal signatureMismatchAmount,
        BigDecimal accountClosedAmount,
        Integer graceDays,
        BigDecimal perDayRate,
        Integer bouncesBeforePenalty,
        boolean autoProposeChequeReturn,
        boolean autoProposeLatePayment,
        Source source
) {
    public enum Source { ORG, PROPERTY }

    public BigDecimal amountFor(ChequeFailureReason reason) {
        return switch (reason) {
            // F14-22: a stopped payment and a technical return carry the generic fee.
            case BOUNCE, STOPPED_PAYMENT, TECHNICAL_RETURN -> bounceAmount;
            case SIGNATURE_MISMATCH -> signatureMismatchAmount;
            case ACCOUNT_CLOSED     -> accountClosedAmount;
        };
    }
}
