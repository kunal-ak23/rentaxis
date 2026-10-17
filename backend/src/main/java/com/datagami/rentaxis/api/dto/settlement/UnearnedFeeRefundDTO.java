package com.datagami.rentaxis.api.dto.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Follow-up ruling R1 (F14-37): a periodic fee a cut-over (at-posting) lease took to
 * income for the whole term, and the part of it not earned by the termination date,
 * per day like rent. Offered on the settlement statement and off by default: the user
 * may add it as an {@code UNEARNED_FEE_REFUND} addition, which debits the fee's own
 * income leaf ({@code accountId}) and adds the amount to the refund.
 *
 * @param unearnedDays the days after the termination date to the window's end
 * @param amount       net (excluding VAT): {@code net − earnedThrough(net, from, to, terminatedOn)}
 */
public record UnearnedFeeRefundDTO(UUID leaseLineId, String chargeTypeCode, String chargeTypeName,
                                   String chargeTypeNameAr, UUID accountId, String accountName,
                                   BigDecimal lineAmount, LocalDate from, LocalDate to, int days,
                                   int unearnedDays, BigDecimal amount) {
}
