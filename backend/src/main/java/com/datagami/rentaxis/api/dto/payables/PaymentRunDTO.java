package com.datagami.rentaxis.api.dto.payables;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A payment run with its items; after posting each item names its vendor's BPV
 * (and whether it was reversed since). {@code referenceWarnings}: bank-file
 * references the default limit would cut (posted runs only).
 */
public record PaymentRunDTO(UUID id, String runNumber, LocalDate paymentDate, UUID paymentAccountId,
                            String paymentAccountCode, String paymentAccountName, String method, LocalDate chequeDate,
                            String firstChequeNumber, String narration, String status, Instant createdAt,
                            Instant postedAt, BigDecimal total, int vendorCount, List<Item> items,
                            List<String> referenceWarnings) {
    public record Item(UUID id, UUID vendorId, String vendorName, String kind, UUID invoiceId, UUID openingItemId,
                       String docNumber, String invoiceNumber, LocalDate dueDate, BigDecimal amount,
                       boolean applyAdvance, UUID bpvId, String bpvNumber, String bpvStatus,
                       String chequeNumber, java.math.BigDecimal bpvAmount) { }
}
