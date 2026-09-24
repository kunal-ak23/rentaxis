package com.datagami.rentaxis.api.dto.lease;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the termination screen shows before anything is written (spec §9.1).
 *
 * <p>Computed by the same code that performs the termination, so the page and the
 * confirm cannot disagree: the numbers here are what will be posted, and the two
 * cheque lists are the defaults the confirm will use if finance does not flip a
 * row.</p>
 *
 * @param terminationDate       {@code T}, echoed so a response read on its own is
 *                              unambiguous.
 * @param earnedRentThroughDate Σ over the lease's live RENT segments of the rent
 *                              earned up to and including {@code T}, at the
 *                              segment's own stored day rate.
 * @param recognisedSoFar       Σ of the lease's {@code POSTED} recognition rows as
 *                              they stand today. The gap between this and
 *                              {@code earnedRentThroughDate} is what the
 *                              truncation will correct — it is usually the part of
 *                              the current month that has not been closed yet, and
 *                              it can be negative when a period past {@code T} has
 *                              already been recognised.
 * @param unearnedRent          Σ {@code (segment.amount − earned)} — the advance
 *                              rent the {@code TCR} hands back.
 * @param unearnedVat           the VAT charged on that unearned rent, which the
 *                              same {@code TCR} credits back as a credit note
 *                              ({@code Dr OUTPUT_VAT / Cr RENT_RECEIVABLE}). Zero
 *                              on a residential tenancy, and zero for a deposit
 *                              line whatever its flag says — one definition, in
 *                              {@code LeaseVat}.
 * @param chequesToReturn       uncleared rows dated after {@code T}: the default
 *                              "give the paper back".
 * @param chequesToKeep         uncleared rows dated on or before {@code T}: the
 *                              money was already due, so it stays owed.
 * @param bouncedOutstanding    rows that already failed. They are neither returned
 *                              nor kept — there is nothing to hand back and
 *                              nothing to bank — and their amount is still owed,
 *                              which is why the screen lists them separately
 *                              rather than burying them.
 * @param receivableAfter       what the lease's rent receivable will read once the
 *                              returns and the {@code TCR} are posted. Negative
 *                              means the landlord owes the renter.
 * @param vatSettlement         on an INSTALMENT lease (spec 2026-09-24 §1), what the
 *                              termination does to the VAT not yet declared: the
 *                              tax points due by {@code T} it posts first, the
 *                              pending VAT {@code P} it cancels, and the settling
 *                              pair — reversed from the deferred account, declared at
 *                              {@code T} ({@code P > U}) or credited back
 *                              ({@code U > P}). All zeros on a legacy lease.
 */
public record TerminationPreviewDTO(LocalDate terminationDate,
                                    BigDecimal earnedRentThroughDate,
                                    BigDecimal recognisedSoFar,
                                    BigDecimal unearnedRent,
                                    BigDecimal unearnedVat,
                                    List<ChequeDTO> chequesToReturn,
                                    List<ChequeDTO> chequesToKeep,
                                    List<ChequeDTO> bouncedOutstanding,
                                    BigDecimal receivableAfter,
                                    VatSettlement vatSettlement) {

    /** See {@code VatTaxPointService.TerminationVat}; amounts are positive. */
    public record VatSettlement(BigDecimal dueByTerminationDate,
                                BigDecimal pendingCancelled,
                                BigDecimal reversedFromDeferred,
                                BigDecimal declaredAtTermination,
                                BigDecimal creditedBack) {
    }
}
