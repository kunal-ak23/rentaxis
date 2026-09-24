package com.datagami.rentaxis.api.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The journal lines behind one P&L cell — the drill-down a property manager can open (finance-ops spec §1). */
public record PnlLinesDTO(List<Line> lines, BigDecimal totalDebit, BigDecimal totalCredit, boolean truncated) {

    public record Line(UUID entryId, String entryNumber, LocalDate entryDate, String docType, String narration,
                       UUID accountId, String accountCode, String accountName, String accountNameAr,
                       BigDecimal debit, BigDecimal credit, UUID linePropertyId, UUID accountPropertyId) { }
}
