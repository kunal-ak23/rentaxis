package com.datagami.rentaxis.api.dto.settlement;

import java.time.LocalDate;
import java.util.UUID;

/**
 * What finalising a settlement needs that the draft cannot know.
 *
 * @param settlementDate       the date the {@code STL} carries. Required, on or
 *                             after the termination date, and in an open period —
 *                             a settlement is a real payment on a real day, not
 *                             "whenever the screen was opened".
 * @param refundBankAccountId  the account the refund is paid from. Required when
 *                             the statement's {@code netRefund} is positive and
 *                             ignored when it is not: a settlement the renter owes
 *                             money on pays nothing out.
 */
public record FinalizeSettlementRequest(LocalDate settlementDate, UUID refundBankAccountId) {
}
