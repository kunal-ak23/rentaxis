import type { ChequeMode, ChequeStatus, LeaseStatus } from "@/lib/api/leasing";

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

/**
 * A lease whose EXISTING register rows may still move — the client mirror of
 * `ChequeService.COLLECTABLE`
 * (backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeService.java:145-147),
 * which `requireCollectable` (:1232) enforces on **every** transition:
 * `deposit`, `clear`, `receive`, `bounce`, `replace`, `cancel`,
 * `returnToTenant` and `releaseOnline`.
 *
 * TERMINATED is in: §9.1's keep list leaves uncleared instruments dated on or
 * before T on the register precisely so they can still be banked, and §9.2
 * raises a CASH balance-due row on that same lease.
 *
 * **CLOSED is deliberately absent**, and that is the whole point of this set: a
 * closed contract has a finalised settlement and nothing outstanding, so a
 * transition on it would be a movement after the books on that tenancy were
 * shut. DRAFT and PENDING_SIGNATURE are absent too — their rows are a proposal,
 * and the grid types them in place rather than moving them through the register.
 */
export const COLLECTABLE_LEASE_STATUSES: LeaseStatus[] = [
    "ACTIVE", "NOTICE_GIVEN", "EXPIRED", "RENEWED", "TERMINATED",
];

/**
 * A lease that is on the books **and still running** — the narrower set that may
 * take a NEW row typed by a user: `ChequeService.POSTED` (:123-124), enforced by
 * `addRowToPostedLease` (:649) behind `POST /cheques/lease/{id}/cash-receipt`.
 *
 * This is also the set that shapes a grid: generate, save rows, amend and extend
 * all add or re-cut instalments on a contract that is still running.
 *
 * **EXPIRED was withdrawn from this set** (ChequeService's own note, review I2):
 * a tenancy that has ended does not grow new instalments — what the renter still
 * owes is collected through the settlement, which has its own internal door in
 * `addCollectionRow`. A screen that still offers EXPIRED gets
 * *"This lease is EXPIRED; use the cheque grid to add rows until it is posted."*
 */
export const POSTED_LEASE_STATUSES: LeaseStatus[] = ["ACTIVE", "NOTICE_GIVEN", "RENEWED"];

/** {@link COLLECTABLE_LEASE_STATUSES}, read strictly: an unknown status is not in it. */
export function leaseIsCollectable(status: LeaseStatus | null | undefined): boolean {
    return status != null && COLLECTABLE_LEASE_STATUSES.includes(status);
}

/** {@link POSTED_LEASE_STATUSES}, read strictly: an unknown status is not in it. */
export function leaseTakesNewRows(status: LeaseStatus | null | undefined): boolean {
    return status != null && POSTED_LEASE_STATUSES.includes(status);
}

/**
 * What the caller knows about the row's contract.
 *
 * Optional as a whole because one caller genuinely cannot know: the finance
 * register pages `ChequeDTO`, which carries no lease status and no settlement
 * state. Omitting it keeps that screen exactly as it was rather than guessing —
 * see the gap noted in the plan 3 fix report.
 */
export type RowLeaseContext = {
    status?: LeaseStatus | null;
    /**
     * Whether this lease's settlement has been FINALIZED
     * (`SettlementStatus.FINALIZED`, read from `GET /leases/{id}/settlement`).
     */
    settlementFinalized?: boolean | null;
};

export function registerActionsFor(
    status: ChequeStatus,
    mode: ChequeMode,
    canCancel: boolean,
    lease?: RowLeaseContext,
): RegisterAction[] {
    // A lease status the caller DID supply and that `requireCollectable` refuses
    // closes the row completely: every verb below is a transition, and `details`
    // and `receipt` are withheld with them so a finished contract reads as
    // finished rather than as one button that happens to work.
    if (lease?.status != null && !leaseIsCollectable(lease.status)) return [];

    /**
     * `requireSettlementUndisturbed` (ChequeService.java:1093) refuses a `cancel`
     * or a `returnToTenant` that would put money back onto a contract the
     * statement already balanced — *"The settlement was finalised counting on
     * this cheque — replace it instead"*. The server's test is what the
     * receivable would read after the reversal, which no client can compute, so
     * the mirror is the verb: once a settlement is FINALIZED this table stops
     * offering the reversal and leaves `replace`, which is the sentence's own
     * advice and which `requireNotAlreadySettled` (:1062) refuses only in the
     * narrower case where the settlement really did pay the debt off.
     */
    const cancellable = canCancel && lease?.settlementFinalized !== true;

    switch (status) {
        case "REGISTERED": {
            const actions: RegisterAction[] = [];
            // `receive()` on the server accepts only CASH and TRANSFER; an ONLINE
            // row is settled by the gateway, and offering "receive" for it here
            // would always 400.
            if (mode === "PDC") actions.push("deposit");
            else if (mode === "CASH" || mode === "TRANSFER") actions.push("receive");
            actions.push("details");
            if (cancellable) actions.push("cancel");
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
