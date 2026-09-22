package com.datagami.rentaxis.api.dto.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One printed ledger row. {@code particular} is the counter-account the row faces —
 * the line's own contra account when the posting paired it with one, otherwise every
 * other account on the entry. {@code balance} is the running balance after this row,
 * signed debit-positive.
 */
public record LedgerRowDTO(
        UUID entryId,
        String entryNumber,
        LocalDate entryDate,
        String docType,
        String particular,
        String narration,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal balance,
        UUID propertyId,
        UUID unitId,
        UUID leaseId,
        UUID renterId,
        UUID chequeId) {
}
