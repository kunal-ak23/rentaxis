package com.datagami.rentaxis.api.dto.ledger;

import com.datagami.rentaxis.api.validation.Money;
import jakarta.validation.Valid;

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
        List<@Valid Line> lines) {

    public record Line(UUID accountId, @Money BigDecimal debit, @Money BigDecimal credit, String narration,
                       UUID unitId, UUID leaseId, UUID renterId) {}
}
