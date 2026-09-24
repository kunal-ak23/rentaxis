package com.datagami.rentaxis.api.dto.payables;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The issued-cheques register's tie-out (spec §2): Σ ISSUED cheques against the
 * credit balance of the PDC_PAYABLE leaf, per bank leaf; and the cut-over check,
 * Σ opening cheques against the OB balance on PDC_PAYABLE. A non-zero difference
 * is a warning, not a block.
 */
public record IssuedChequeSummaryDTO(BigDecimal outstandingTotal, BigDecimal pdcPayableBalance, BigDecimal difference,
                                     List<Bank> perBank, BigDecimal openingTotal, BigDecimal openingBalance,
                                     BigDecimal openingDifference, int duePresentCount) {
    public record Bank(UUID bankAccountId, String bankAccountCode, String bankAccountName, int count, BigDecimal amount) { }
}
