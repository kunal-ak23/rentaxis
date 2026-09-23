package com.datagami.rentaxis.api.dto.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalLineDTO(
        int lineNo,
        UUID accountId,
        String accountCode,
        String accountName,
        BigDecimal debit,
        BigDecimal credit,
        String narration,
        UUID propertyId,
        UUID unitId,
        UUID leaseId,
        UUID renterId,
        UUID chequeId,
        /** The account's Arabic name, null when it has none. */
        String accountNameAr) {}
