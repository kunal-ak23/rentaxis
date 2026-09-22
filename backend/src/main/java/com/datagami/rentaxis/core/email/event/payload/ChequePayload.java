package com.datagami.rentaxis.core.email.event.payload;

import com.datagami.rentaxis.domain.entity.Cheque;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The variables every cheque email interpolates (received, deposited, cleared,
 * bounced).
 *
 * <p><b>{@code chequeId} is the id of the register row the email is about.</b> It
 * is nothing but a dedupe key and a reference on the rendered mail; nothing loads
 * it back. It was called {@code paymentScheduleId} while the v1 path shared this
 * payload — a record component is also the template variable name, so renaming it
 * then would have blanked it in every cheque template. Checked before renaming:
 * no template under {@code templates/email} interpolates it, so the rename is
 * invisible to the rendered mail.</p>
 */
public record ChequePayload(
        UUID chequeId,
        UUID leaseId,
        UUID renterUserId,
        UUID propertyManagerUserId,
        int installmentNumber,
        String chequeNumber,
        String bankName,
        String amountDisplay,
        String dueDateIso,
        String depositDateIso,
        String failureReason
) {

    /**
     * The payload for one register row.
     *
     * <p>{@code propertyManagerUserId} is left null: it is not stored on the
     * lease, and {@code RecipientResolver} falls back to the tenant's admins.</p>
     *
     * <p>Reads the cheque's lazy relations, so it must be called inside the
     * transaction that loaded it.</p>
     *
     * @param depositDate the deposit date for CHEQUE_DEPOSITED; null elsewhere.
     * @param failureReason the bounce reason for CHEQUE_BOUNCED; null elsewhere.
     */
    public static ChequePayload ofCheque(Cheque cheque, LocalDate depositDate, String failureReason) {
        UUID renterUserId = cheque.getRenter() != null ? cheque.getRenter().getUserId() : null;
        return new ChequePayload(
                cheque.getId(),
                cheque.getLease() != null ? cheque.getLease().getId() : null,
                renterUserId,
                null,
                cheque.getSeqNo(),
                cheque.getChequeNumber(),
                cheque.getPayeeBank(),
                cheque.getAmount() != null ? cheque.getAmount().toPlainString() + " AED" : null,
                cheque.getChequeDate() != null ? cheque.getChequeDate().toString() : null,
                depositDate != null ? depositDate.toString() : null,
                failureReason);
    }
}
