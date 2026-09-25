package com.datagami.rentaxis.api.dto.lease;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F14-32: what a credit addendum would do, with nothing written.
 *
 * @param creditNet        Σ line credits (the TCC's contract part)
 * @param vatFromDeferred  the credit's VAT still to be declared, taken off the planned tax points
 * @param vatCreditNote    the credit's VAT already declared, credited back with a TCN
 * @param creditTotal      creditNet + VAT: what comes off the renter's receivable
 * @param returnedTotal    Σ the instalments chosen to hand back
 * @param newRowsTotal     Σ the replacement rows
 * @param gap              for CHEQUES: creditTotal − (returned − new rows); must be zero
 */
public record ReductionPreviewDTO(LocalDate effectiveFrom,
                                  List<LineCredit> lines,
                                  BigDecimal creditNet,
                                  BigDecimal vatFromDeferred,
                                  BigDecimal vatCreditNote,
                                  BigDecimal creditTotal,
                                  List<ReturnableCheque> returnable,
                                  BigDecimal returnedTotal,
                                  BigDecimal newRowsTotal,
                                  BigDecimal gap,
                                  List<String> problems) {

    public record LineCredit(UUID lineId, String chargeTypeCode, String chargeTypeName, String chargeTypeNameAr,
                             BigDecimal lineAmount, BigDecimal newLineAmount,
                             LocalDate from, LocalDate to, int remainingDays,
                             BigDecimal remainingBefore, BigDecimal remainingAfter,
                             BigDecimal credit, BigDecimal vat) {
    }

    /** An uncleared instalment that could be handed back. */
    public record ReturnableCheque(UUID id, int seqNo, String chequeNumber, LocalDate chequeDate,
                                   BigDecimal amount, BigDecimal vatAmount) {
    }
}
