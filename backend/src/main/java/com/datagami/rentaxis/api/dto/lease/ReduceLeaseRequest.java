package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F14-32: a mid-term reduction, posted as a numbered credit addendum.
 *
 * @param effectiveFrom  the first day the lower rate applies
 * @param contractDate   the addendum's entry date (today when null)
 * @param lines          each line cut: its new value over its whole window (0 removes it)
 * @param excess         CHEQUES — uncleared instalments are handed back ({@code returnChequeIds})
 *                       and optionally replaced ({@code cheques}), and together they must come to
 *                       the credit; CREDIT — the credit stays on the renter's receivable, applied
 *                       to later dues and refunded at settlement
 */
public record ReduceLeaseRequest(LocalDate effectiveFrom,
                                 LocalDate contractDate,
                                 String reason,
                                 String ejariNumber,
                                 List<LineReduction> lines,
                                 String excess,
                                 List<UUID> returnChequeIds,
                                 List<ChequeRowInput> cheques) {

    public record LineReduction(UUID lineId, BigDecimal newAmount) {
    }

    public static final String EXCESS_CHEQUES = "CHEQUES";
    public static final String EXCESS_CREDIT = "CREDIT";
}
