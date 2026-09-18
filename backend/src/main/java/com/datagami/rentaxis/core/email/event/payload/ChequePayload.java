package com.datagami.rentaxis.core.email.event.payload;

import com.datagami.rentaxis.domain.entity.Cheque;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The variables every cheque email interpolates (received, deposited, cleared,
 * bounced).
 *
 * <p><b>{@code paymentScheduleId} is the id of the row the email is about</b> —
 * a {@code payment_schedules} row for the v1 path, a {@code cheques} row for the
 * v2 register. It is nothing but a dedupe key and a reference on the rendered
 * mail; nothing loads it back, so the two sources can share the field until the
 * v1 path is deleted. The name is kept rather than fixed because it is a record
 * component, which means it is also the template variable name, and renaming it
 * would silently blank the variable in every cheque template at once.</p>
 */
public record ChequePayload(
        UUID paymentScheduleId,
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
     * The v2 register's shape of the same email.
     *
     * <p>{@code propertyManagerUserId} is left null exactly as the schedule path
     * leaves it: it is not stored on the lease, and {@code RecipientResolver}
     * falls back to the tenant's admins.</p>
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
