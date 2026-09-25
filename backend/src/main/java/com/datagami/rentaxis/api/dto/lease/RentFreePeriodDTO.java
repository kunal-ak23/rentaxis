package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A rent-free window (spec 2026-09-24 §4b), as read back and as sent.
 *
 * @param concessionOverride the operator's exact figure for this window, or null
 *                           for headline rent × days ÷ term days
 * @param concession         the figure in force — the override, or the computed one
 *                           (read-only; ignored on a request)
 * @param days               inclusive day count (read-only)
 */
public record RentFreePeriodDTO(UUID id, LocalDate fromDate, LocalDate toDate, BigDecimal concessionOverride,
                                String note, BigDecimal concession, Integer days) {
}
