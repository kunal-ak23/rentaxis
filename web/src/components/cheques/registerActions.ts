import type { ChequeMode, ChequeStatus } from "@/lib/api/leasing";

/**
 * What a cheque row offers, by the state and the instrument it is in.
 *
 * The one definition, shared by the register and by the lease page's own
 * `ChequeGrid`. The lease page briefly carried a second, narrower table of its
 * own: it offered Receive on ONLINE_PENDING (which the server always 400s),
 * never offered Receive on a cash row, and never offered the late-return
 * bounce on a cleared PDC. Two tables over one state machine is how a screen
 * comes to disagree with the server about what a row can do.
 *
 * Mirrors `ChequeController`'s own transitions (spec §7.4):
 *  - REGISTERED + PDC        → deposit, details, cancel
 *  - REGISTERED + CASH/TRANSFER → receive, details, cancel
 *  - REGISTERED + ONLINE     → details, cancel only — `ChequeService#receive`
 *    accepts only CASH and TRANSFER, and an ONLINE row waiting to be paid
 *    moves to ONLINE_PENDING through the gateway, not through this dialog
 *  - DEPOSITED               → clear, bounce
 *  - CLEARED + PDC           → bounce (late return), receipt
 *  - CLEARED + CASH/TRANSFER → receipt only — cash does not un-arrive, and a
 *    settled transfer is reversed by the bank as its own receipt, not by
 *    editing this one (`ChequeService#bounce` 400s a non-PDC bounce after
 *    clearing, so the UI never offers it)
 *  - BOUNCED                 → replace
 *  - ONLINE_PENDING          → releaseOnline — a gateway session that was
 *    abandoned without a callback leaves the row parked out of the register's
 *    reach: not payable, not depositable, not cancellable. Staff release it
 *    back to REGISTERED (`POST /cheques/{id}/release-online`), which is the
 *    same move the gateway makes when it reports a failure
 *  - everything else (REPLACED, CANCELLED, RETURNED) → none
 *
 * `cancel` is gated separately: it reverses the registering journal
 * (`canCancelCheques`, SA/TA/ACCOUNTANT), narrower than the rest of the
 * register (`canManageCheques`, which also admits PROPERTY_MANAGER).
 */
export type RegisterAction =
    | "deposit"
    | "receive"
    | "details"
    | "cancel"
    | "clear"
    | "bounce"
    | "replace"
    | "receipt"
    | "releaseOnline";

export function registerActionsFor(
    status: ChequeStatus,
    mode: ChequeMode,
    canCancel: boolean,
): RegisterAction[] {
    switch (status) {
        case "REGISTERED": {
            const actions: RegisterAction[] = [];
            // `receive()` on the server accepts only CASH and TRANSFER; an ONLINE
            // row is settled by the gateway, and offering "receive" for it here
            // would always 400.
            if (mode === "PDC") actions.push("deposit");
            else if (mode === "CASH" || mode === "TRANSFER") actions.push("receive");
            actions.push("details");
            if (canCancel) actions.push("cancel");
            return actions;
        }
        case "DEPOSITED":
            return ["clear", "bounce"];
        case "CLEARED":
            return mode === "PDC" ? ["bounce", "receipt"] : ["receipt"];
        case "BOUNCED":
            return ["replace"];
        case "ONLINE_PENDING":
            // Not a state transition the renter can finish from here: the
            // gateway either calls back or it does not. Staff put the row back
            // on the register so it can be collected another way.
            return ["releaseOnline"];
        default:
            return [];
    }
}
