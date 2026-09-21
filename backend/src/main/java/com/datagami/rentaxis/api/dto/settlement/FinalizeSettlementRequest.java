package com.datagami.rentaxis.api.dto.settlement;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What finalising a settlement needs that the draft cannot know.
 *
 * @param settlementDate       the date the {@code STL} carries. Required, on or
 *                             after the day the tenancy ended, and in an open
 *                             period — a settlement is a real payment on a real
 *                             day, not "whenever the screen was opened".
 * @param refundBankAccountId  the account the refund is paid from. Required when
 *                             the statement's {@code netRefund} is positive and
 *                             ignored when it is not: a settlement the renter owes
 *                             money on pays nothing out.
 * @param acknowledgeOutstanding the accountant has seen
 *                             {@code statement.instrumentsOutstanding} and is
 *                             paying the refund anyway. Required — and only
 *                             required — when the settlement refunds <em>and</em>
 *                             the register is still holding something: handing a
 *                             renter their deposit back while their kept cheque or
 *                             their unpaid fine is still outstanding is a decision
 *                             somebody has to make on purpose. A balance-due or
 *                             zero settlement never needs it.
 *                             <p><b>Boxed, and absent means false.</b> This
 *                             application runs Jackson with
 *                             {@code FAIL_ON_NULL_FOR_PRIMITIVES}, so a primitive
 *                             {@code boolean} here would turn "I did not send the
 *                             flag" into a 400 about a malformed body rather than
 *                             the refusal that explains what is outstanding. Read
 *                             it through {@link #acknowledged()}.</p>
 */
public record FinalizeSettlementRequest(LocalDate settlementDate,
                                        UUID refundBankAccountId,
                                        Boolean acknowledgeOutstanding) {

    /** Absent or null is "not acknowledged": this flag is opt-in and fails closed. */
    @JsonIgnore
    public boolean acknowledged() {
        return Boolean.TRUE.equals(acknowledgeOutstanding);
    }
}
