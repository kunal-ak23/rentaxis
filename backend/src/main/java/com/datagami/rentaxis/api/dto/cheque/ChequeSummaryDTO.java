package com.datagami.rentaxis.api.dto.cheque;

import java.math.BigDecimal;

/**
 * The register's summary tiles (spec §7.4): what is in the drawer, what is at the
 * bank, what came back, and what is late.
 *
 * <p>Counts and amounts travel together because the screen shows both and
 * deriving one from the other is impossible — six cheques and 76,500 AED are two
 * different questions about the same rows.</p>
 *
 * <p>{@code dueCount}/{@code overdueCount} are not a slice of the status tiles and
 * deliberately overlap them: a bounced cheque is counted under {@code bounced}
 * and again under {@code due}, because it is both a returned instrument and money
 * the landlord is owed today. Summing the tiles would therefore not give the
 * register's total, and nothing should try to.</p>
 *
 * @param clearedThisMonthAmount value banked in the current calendar month, by the
 *        date the money actually landed rather than the date on the paper.
 */
public record ChequeSummaryDTO(long registeredCount,
                               BigDecimal registeredAmount,
                               long depositedCount,
                               BigDecimal depositedAmount,
                               BigDecimal clearedThisMonthAmount,
                               long bouncedCount,
                               BigDecimal bouncedAmount,
                               long dueCount,
                               BigDecimal dueAmount,
                               long overdueCount,
                               BigDecimal overdueAmount) {
}
