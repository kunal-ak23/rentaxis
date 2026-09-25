package com.datagami.rentaxis.api.dto.vat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * #55: a VAT return in the FTA VAT 201 layout, the boxes that apply to a landlord.
 * {@code status} is OPEN (computed live), FILED (the figures as filed) or
 * REOPENED. Box codes follow the form: 1a–1g standard-rated supplies by emirate
 * (1x: emirate not set), 4 zero-rated, 5 exempt, 8 totals, 9 standard-rated
 * expenses, 11 totals, 12 due tax, 13 recoverable tax, 14 payable (negative:
 * refundable).
 */
public record VatReturnDTO(
        UUID id,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        Instant filedAt,
        String filedByName,
        String filingReference,
        List<Box> boxes,
        BigDecimal netVat,
        OutputCheck outputCheck,
        BigDecimal commercialWithoutVat,
        boolean canFile,
        String cannotFileReason) {

    /** {@code documents}: how many documents the box drills to (0 for a total). */
    public record Box(String code, String key, BigDecimal amount, BigDecimal vat, int documents, boolean total) { }

    /** Output VAT per the tax invoices and credit notes against the Output VAT account's movement. */
    public record OutputCheck(BigDecimal documents, BigDecimal ledger, BigDecimal difference, boolean ok) { }

    /** One document behind a box: a tax invoice / credit note, or a journal entry. */
    public record Document(String kind, UUID id, String number, LocalDate date, String party, String partyAr,
                           BigDecimal amount, BigDecimal vat, UUID journalId, String entryNumber, UUID leaseId) { }

    /** A filed (or re-opened) period, for the history list. */
    public record Filing(UUID id, LocalDate periodStart, LocalDate periodEnd, String status, BigDecimal netVat,
                         String filingReference, Instant filedAt, String filedByName, Instant reopenedAt,
                         String reopenReason) { }
}
