package com.datagami.rentaxis.api.dto.settlement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The move-out statement, computed live from the ledger (spec §9.2).
 *
 * <p><b>Nothing here is stored while the settlement is a draft.</b> Every figure
 * is re-derived on each read from the journals, the register and the draft's own
 * lines, so a statement opened after a cheque cleared, a penalty was approved or a
 * recognition run posted shows the new position rather than yesterday's. The
 * snapshot columns on {@code lease_settlements} are written once, by finalise, and
 * are the record of what the {@code STL} was posted against.</p>
 *
 * @param earnedRent           Σ the lease's POSTED recognition entries, net of
 *                             reversals. <b>Not</b> what the term is worth to
 *                             {@code asOf}: rent that has been earned but not yet
 *                             recognised is not in the ledger, so it is not in the
 *                             receivable either, and counting it here would show a
 *                             renter owing money no journal has raised. When
 *                             {@link #unrecognisedEntries} is non-zero the screen
 *                             tells the user to run recognition first.
 * @param receivedTotal        Σ the lease's CLEARED register rows — money actually
 *                             in the bank, for the reader's orientation. It is not
 *                             an input to {@link #netRefund}; the receivable
 *                             already nets it off.
 * @param receivableBalance    the rent receivable's closing balance on this lease,
 *                             debit-positive: <b>+ve the renter owes, −ve the
 *                             landlord does</b>. It already carries the unearned
 *                             rent a termination reversed, the PDCs it handed back
 *                             and every approved penalty.
 * @param depositsHeld         Σ the credit balance of every deposit account on this
 *                             lease, as a positive number — what the landlord is
 *                             still holding, which on a renewal that carried the
 *                             deposit forward is zero however much the contract
 *                             charged.
 * @param penaltiesOutstanding APPROVED assessments whose collection row has not
 *                             cleared. <b>Shown, never added</b> — and not because
 *                             the fine is in {@link #receivableBalance}: it is not.
 *                             Approving a penalty posts {@code Dr RENT_RECEIVABLE /
 *                             Cr penalty income} and immediately raises a CASH
 *                             collection row whose {@code PDR} credits the
 *                             receivable straight back, so the fine's net movement
 *                             there is nil and the money sits in
 *                             {@code PDC_RECEIVABLE}. It is charged already, on the
 *                             register, and a deduction line for it would charge it
 *                             a second time. It is part of
 *                             {@link #instrumentsOutstanding}.
 * @param instrumentsOutstanding the lease-dimension balance of {@code PDC_RECEIVABLE}:
 *                             what the register is still holding against this
 *                             renter — kept cheques, penalty collection rows,
 *                             anything banked but not cleared. <b>Not netted into
 *                             {@link #netRefund}</b>: an uncleared instrument is
 *                             collected through the register, never silently
 *                             deducted from a deposit. Finalising a refund while
 *                             this is positive needs
 *                             {@code acknowledgeOutstanding}.
 * @param outstandingInstruments the rows behind that figure, oldest first.
 * @param netRefund            {@code depositsHeld − receivableBalance −
 *                             totalDeductions + totalAdditions}. <b>&gt;0 the
 *                             landlord pays out, &lt;0 the renter still owes.</b>
 * @param unrecognisedEntries  the lease's PLANNED recognition rows. Non-zero means
 *                             earned rent is missing from {@link #earnedRent} and
 *                             from the receivable; run recognition before settling.
 */
public record SettlementStatementDTO(
        LocalDate asOf,
        BigDecimal earnedRent,
        BigDecimal receivedTotal,
        BigDecimal receivableBalance,
        BigDecimal depositsHeld,
        BigDecimal penaltiesOutstanding,
        BigDecimal instrumentsOutstanding,
        List<OutstandingInstrumentDTO> outstandingInstruments,
        List<DeductionLineDTO> deductions,
        List<AdditionLineDTO> additions,
        BigDecimal totalDeductions,
        BigDecimal totalAdditions,
        BigDecimal netRefund,
        int unrecognisedEntries,
        /* F14-37: the output VAT on the taxable recharges, already taken off netRefund. */
        BigDecimal totalDeductionVat) {
}
