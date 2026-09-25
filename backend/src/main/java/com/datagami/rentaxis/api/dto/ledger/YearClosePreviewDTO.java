package com.datagami.rentaxis.api.dto.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What closing a fiscal year would do (spec 2026-09-24 §3): what stops it, what
 * only warns, the year's P&L by account, the Retained Earnings line per
 * property, and the period lock afterwards.
 *
 * @param blockers  refusals; the close is refused while any exist
 * @param warnings  may be overridden ({@code overrideWarnings = true})
 */
public record YearClosePreviewDTO(int fiscalYear, LocalDate periodStart, LocalDate periodEnd,
                                  List<Issue> blockers, List<Issue> warnings,
                                  List<PnlLine> lines, BigDecimal income, BigDecimal expense, BigDecimal netResult,
                                  List<RetainedLine> retainedEarnings,
                                  LocalDate lockBefore, LocalDate lockAfter) {

    /** A coded reason, with its English text and the arguments the web translates it with. */
    public record Issue(String code, String message, Map<String, String> args) {
    }

    /** One account's balance for the year; {@code amount} is positive for income earned and expense incurred. */
    public record PnlLine(UUID accountId, String code, String name, String nameAr, String accountType,
                          UUID propertyId, String propertyName, BigDecimal amount) {
    }

    /**
     * The profit (Cr) or loss (Dr, negative) of the year itself for one property —
     * the same figure the lines and {@code netResult} add up to (F15-01) — and,
     * separately, any result of earlier years still open ({@code broughtForward}),
     * which the close would sweep into Retained Earnings too. Zero once every
     * earlier year is closed; while one is open the close is blocked anyway.
     */
    public record RetainedLine(UUID propertyId, String propertyName, BigDecimal profit, BigDecimal broughtForward) {
        public RetainedLine(UUID propertyId, String propertyName, BigDecimal profit) {
            this(propertyId, propertyName, profit, BigDecimal.ZERO.setScale(2));
        }
    }
}
