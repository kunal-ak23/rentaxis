package com.datagami.rentaxis.api.dto.ledger;

import java.math.BigDecimal;
import java.util.UUID;

/** One account's totals as of a date. {@code balance} is debit minus credit, so a credit balance is negative. */
public record TrialBalanceRowDTO(
        UUID accountId,
        String code,
        String name,
        String accountType,
        UUID parentId,
        UUID propertyId,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal balance) {
}
