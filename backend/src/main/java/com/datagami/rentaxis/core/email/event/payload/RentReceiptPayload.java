package com.datagami.rentaxis.core.email.event.payload;

import com.datagami.rentaxis.domain.entity.Cheque;

import java.util.UUID;

public record RentReceiptPayload(
        UUID receiptId,
        UUID leaseId,
        UUID renterUserId,
        String amountDisplay,
        String paidOnIso,
        String pdfBase64,
        String pdfFileName
) {

    /**
     * The register's shape of the receipt email (spec §9.3).
     *
     * <p>{@code receiptId} is the cheque's id: there is no receipt entity, and the
     * cleared row <em>is</em> the receipt — one CLEARED cheque, one receipt number,
     * one PDF. It doubles as the event's dedupe key, so a renter who downloads the
     * same receipt twice is not emailed twice.</p>
     *
     * <p>{@code paidOnIso} is {@code clearedAt}, the day the money landed, not the
     * day the PDF was rendered.</p>
     *
     * <p>Reads the cheque's lazy relations, so it must be called inside the
     * transaction that loaded it.</p>
     */
    public static RentReceiptPayload ofCheque(Cheque cheque, String amountDisplay,
                                              String pdfBase64, String pdfFileName) {
        return new RentReceiptPayload(
                cheque.getId(),
                cheque.getLease() != null ? cheque.getLease().getId() : null,
                cheque.getRenter() != null ? cheque.getRenter().getUserId() : null,
                amountDisplay,
                cheque.getClearedAt() != null ? cheque.getClearedAt().toString() : null,
                pdfBase64,
                pdfFileName);
    }
}
