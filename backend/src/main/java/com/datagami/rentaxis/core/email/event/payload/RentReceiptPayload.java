package com.datagami.rentaxis.core.email.event.payload;
import java.util.UUID;
public record RentReceiptPayload(
        UUID receiptId,
        UUID leaseId,
        UUID renterUserId,
        String amountDisplay,
        String paidOnIso,
        String pdfBase64,
        String pdfFileName
) {}
