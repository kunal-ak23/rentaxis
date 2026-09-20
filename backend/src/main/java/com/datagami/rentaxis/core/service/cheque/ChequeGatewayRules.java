package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;

/**
 * "May this register row be collected through the payment gateway?" — asked in
 * one place, so the renter's portal cannot offer a button the register then
 * refuses (spec §7.3, §9.3, §11).
 *
 * <p>It used to be asked in three, and they disagreed. {@code OnlinePaymentService}
 * decided what to show the renter from the row's status and amount alone;
 * {@code ChequeService.registerOnlinePending} and the capture's own
 * {@code unappliable} check each carried their own copy of "PDC or ONLINE".
 * An approved penalty is created as a {@code CASH} collection row, so the portal
 * offered Pay-now on every fine and the gateway answered with a raw Java sentence
 * about a CASH receipt — on the one charge a landlord most wants settled quickly.</p>
 *
 * <p>A pure function of the row, with no clock and no database, so the portal, the
 * order path and the capture path can all reach it.</p>
 */
public final class ChequeGatewayRules {

    private ChequeGatewayRules() {
    }

    /**
     * Whether the gateway may collect this row.
     *
     * <p><b>A post-dated cheque or an online row</b>, which is the ordinary case: a
     * PDC carries a {@code PDR} the capture's {@code CRT} clears, and an ONLINE row
     * exists only because the gateway created it.</p>
     *
     * <p><b>Or a penalty collection row, whatever its mode.</b> Spec §7.3 lets an
     * approval be collected as CASH, TRANSFER or ONLINE and §11 lists "approved
     * penalties, Razorpay pay" in the renter portal, so a fine is meant to be
     * payable online. The mode stays as the approval wrote it — the row is still
     * receivable over the counter if the renter turns up with the cash, and a
     * released session leaves it exactly as it was — because rewriting the mode to
     * ONLINE would take that door away for the sake of a flag. The ledger does not
     * care either way: the capture posts the same Dr settlement / Cr PDC receivable
     * it posts for rent.</p>
     *
     * <p><b>Not a plain CASH or TRANSFER rent row.</b> Those are recorded when they
     * arrive, by {@code ChequeService.receive}, and a gateway has no business
     * clearing one.</p>
     */
    public static boolean payableThroughGateway(Cheque cheque) {
        if (cheque == null) {
            return false;
        }
        if (cheque.getPenaltyAssessmentId() != null) {
            return true;
        }
        ChequeMode mode = cheque.getMode();
        return mode == ChequeMode.PDC || mode == ChequeMode.ONLINE;
    }
}
