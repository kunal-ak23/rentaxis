package com.datagami.rentaxis.api.dto.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A manual journal voucher as the client submits it: header dimensions plus flat
 * debit/credit lines. Each line is one side only; the entry has to balance.
 */
public record ManualJournalRequest(
        LocalDate entryDate,
        String narration,
        UUID propertyId,
        List<Line> lines) {

    public record Line(UUID accountId, BigDecimal debit, BigDecimal credit, String narration,
                       UUID unitId, UUID leaseId, UUID renterId) {}
}
