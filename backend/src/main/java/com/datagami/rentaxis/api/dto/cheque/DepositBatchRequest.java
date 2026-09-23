package com.datagami.rentaxis.api.dto.cheque;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The day's deposit run: the cheques the landlord is physically walking to the
 * bank, banked in one act (spec §7.4, "Cheque / Cash Collection").
 *
 * <p>A batch rather than a loop of single deposits because that is what the
 * screen does — tick the matured rows, name the bank, one button — and because
 * all-or-nothing is the only honest answer when one of the ticked rows turns out
 * to have been cleared five minutes ago: half a deposit run is a deposit slip
 * that does not match the register.</p>
 *
 * @param chequeIds the ticked rows; every one must be a REGISTERED PDC.
 * @param date the deposit date. Defaults to today.
 * @param debitAccountId which of our banks the paper went to, overriding whatever
 *        the rows carry. Nothing posts here — the account is remembered so the
 *        {@code CRT} that follows debits the bank the money actually reached.
 * @param useChequeDates #10: deposit each row on its own cheque date instead of
 *        one date for the whole selection — for a run entered after the fact,
 *        where October's and November's cheques went to the bank on their own
 *        days. A row with no cheque date falls back to {@code date}. A row dated
 *        after today is refused: it cannot have been banked yet, and banking it
 *        early is exactly what the #9 guard forbids.
 */
public record DepositBatchRequest(List<UUID> chequeIds, LocalDate date, UUID debitAccountId,
                                  Boolean useChequeDates) {

    /** The shape before #10: one date for the whole selection. */
    public DepositBatchRequest(List<UUID> chequeIds, LocalDate date, UUID debitAccountId) {
        this(chequeIds, date, debitAccountId, null);
    }

    /** The deposit date for one row under this request (#10). */
    public LocalDate dateFor(LocalDate chequeDate) {
        if (Boolean.TRUE.equals(useChequeDates) && chequeDate != null) {
            return chequeDate;
        }
        return dateOrToday();
    }

    public LocalDate dateOrToday() {
        return date != null ? date : LocalDate.now();
    }
}
