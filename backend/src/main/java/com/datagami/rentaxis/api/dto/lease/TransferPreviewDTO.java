package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Spec §2: what moving on {@code moveDate} would do, with nothing written.
 *
 * @param earnedThrough      rent earned on the current unit through T
 * @param unearned           handed back by the TCR (plus {@code unearnedVat})
 * @param balanceCarried     C: the current lease's receivable after the move, bounced rows
 *                           excluded — negative when prepaid (a credit carried to the new lease)
 * @param depositCarried     the deposit the JV moves
 * @param suggestedRent      the current day rate × the new term's days (a hint)
 * @param carriedTotal       Σ the rows marked CARRY
 * @param gapToCollect       the new contract incl. VAT + C − carried rows: what new rows must cover,
 *                           when the new rent is the suggested one
 */
public record TransferPreviewDTO(LocalDate moveDate, UUID targetUnitId, LocalDate newStart, LocalDate newEnd,
                                 int newDays, BigDecimal earnedThrough, BigDecimal unearned, BigDecimal unearnedVat,
                                 BigDecimal balanceCarried, BigDecimal depositCarried, BigDecimal suggestedRent,
                                 List<Row> cheques, BigDecimal carriedTotal, BigDecimal gapToCollect,
                                 List<String> problems) {

    public record Row(UUID chequeId, int seqNo, String chequeNumber, LocalDate chequeDate, BigDecimal amount,
                      String status, String disposition) {
    }
}
