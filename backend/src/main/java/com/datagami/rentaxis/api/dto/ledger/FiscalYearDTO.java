package com.datagami.rentaxis.api.dto.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One fiscal year on the Fiscal years table (spec 2026-09-24 §3).
 *
 * @param status     OPEN, CLOSED or REOPENED (the latest close record's status; OPEN when never closed)
 * @param netResult  the year's profit (income − expense), excluding the closing entry
 */
public record FiscalYearDTO(int fiscalYear, LocalDate periodStart, LocalDate periodEnd, String status,
                            BigDecimal netResult, UUID journalId, String journalNumber,
                            Instant closedAt, UUID closedBy, Instant reopenedAt, UUID reopenedBy, String reopenReason,
                            /* PR #369 R1 P3-8: set when a pre-books year is closed by this later year's close (re-open that one). */
                            Integer coveredBy) {
}
