package com.datagami.rentaxis.api.dto.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One account's ledger over a date range. Balances are signed debit-positive:
 * {@code openingBalance} carries everything before the range, {@code closingBalance}
 * is the running balance after the last row. {@code truncated} says the account had
 * more rows in the range than the query returns.
 */
public record AccountLedgerDTO(
        UUID accountId,
        String accountCode,
        String accountName,
        String accountType,
        BigDecimal openingBalance,
        List<LedgerRowDTO> rows,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal closingBalance,
        boolean truncated) {
}
