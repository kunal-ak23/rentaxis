package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One capture the gateway took and the register refused — a refund waiting to be
 * made (spec §9.3).
 *
 * <p>These rows exist because a verified capture that cannot be posted is never
 * absorbed: two orders raised against one instalment and both paid, a clerk
 * banking the cheque while the renter was paying for it, an amount that is not the
 * row's. Until this list existed the state was a status, a sentence in
 * {@code failure_reason} and a line in the application log — real money that
 * nobody could find without grepping a server.</p>
 *
 * <p>Everything a finance person needs to act is on the row: what the gateway
 * calls the payment, so they can refund it in Razorpay's own dashboard; what the
 * register calls the instalment and what state it is in now, so they can see
 * <em>why</em> it could not be taken; and who and where, so they can tell the
 * renter.</p>
 *
 * @param capturedAt when the payment was last written — the moment the capture was
 *        recorded as unappliable. {@code createdAt} is when the checkout opened.
 * @param failureReason the sentence {@code OnlinePaymentService} wrote, naming the
 *        cause and the gateway payment id.
 * @param chequeStatus what the instalment is <em>now</em>: CLEARED means another
 *        payment or a bank clearing got there first, DEPOSITED that the paper is at
 *        the bank, and so on.
 */
public record UnappliedOnlinePaymentDTO(UUID id,
                                        Instant createdAt,
                                        Instant capturedAt,
                                        BigDecimal amount,
                                        String currency,
                                        String gatewayOrderId,
                                        String gatewayPaymentId,
                                        String failureReason,
                                        UUID chequeId,
                                        String chequeNumber,
                                        ChequeStatus chequeStatus,
                                        UUID leaseId,
                                        String displayContractNumber,
                                        String renterName,
                                        String propertyName,
                                        String unitIdentifier) {
}
