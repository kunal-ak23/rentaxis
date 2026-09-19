package com.datagami.rentaxis.core.email.event.payload;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.OnlinePayment;

import java.util.UUID;

public record OnlinePaymentPayload(
        UUID onlinePaymentId,
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        String amountDisplay,
        String gatewayReference,
        String failureReason
) {

    /**
     * The register's shape of the same email (spec §9.3).
     *
     * <p>The amount is the <em>cheque's</em>, not the order's: they are the same
     * figure today, but the row is what the ledger booked and what the receipt will
     * show, and a gateway that ever rounds its order differently should not make
     * the email disagree with the books.</p>
     *
     * <p>{@code propertyManagerUserId} is left null exactly as the schedule path
     * left it: it is not stored on the lease, and {@code RecipientResolver} falls
     * back to the tenant's admins.</p>
     *
     * <p>Reads the cheque's lazy relations, so it must be called inside the
     * transaction that loaded it.</p>
     */
    public static OnlinePaymentPayload ofCheque(OnlinePayment payment, Cheque cheque, String failureReason) {
        return new OnlinePaymentPayload(
                payment.getId(),
                cheque.getLease() != null ? cheque.getLease().getId() : null,
                cheque.getRenter() != null ? cheque.getRenter().getUserId() : null,
                null,
                cheque.getAmount() != null
                        ? cheque.getAmount().toPlainString() + " " + (payment.getCurrency() != null
                                ? payment.getCurrency() : "AED")
                        : null,
                payment.getGatewayPaymentId(),
                failureReason);
    }
}
