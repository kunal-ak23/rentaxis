import type { ChequeMode, ChequeStatus } from "@/lib/api/leasing";

/**
 * The register's own row actions — separate from the lease page's
 * `ChequeGrid#actionsFor`, which offers a narrower set (no cancel, no
 * mode-aware receive/deposit split, no late-return bounce on a cleared row):
 * the lease page works one contract's grid at a time and never needed those,
 * and widening it would be a change to a screen this task does not own.
 *
 * Mirrors `ChequeController`'s own transitions (spec §7.4):
 *  - REGISTERED + PDC        → deposit, details, cancel
 *  - REGISTERED + CASH/TRANSFER → receive, details, cancel
 *  - DEPOSITED               → clear, bounce
 *  - CLEARED + PDC           → bounce (late return), receipt
 *  - CLEARED + CASH/TRANSFER → receipt only — cash does not un-arrive, and a
 *    settled transfer is reversed by the bank as its own receipt, not by
 *    editing this one (`ChequeService#bounce` 400s a non-PDC bounce after
 *    clearing, so the UI never offers it)
 *  - BOUNCED                 → replace
 *  - everything else (REPLACED, CANCELLED, RETURNED, ONLINE_PENDING) → none
 *
 * `cancel` is gated separately: it reverses the registering journal
 * (`canCancelCheques`, SA/TA/ACCOUNTANT), narrower than the rest of the
 * register (`canManageCheques`, which also admits PROPERTY_MANAGER).
 */
export type RegisterAction = "deposit" | "receive" | "details" | "cancel" | "clear" | "bounce" | "replace" | "receipt";

export function registerActionsFor(
    status: ChequeStatus,
    mode: ChequeMode,
    canCancel: boolean,
): RegisterAction[] {
    switch (status) {
        case "REGISTERED": {
            const actions: RegisterAction[] = [mode === "PDC" ? "deposit" : "receive", "details"];
            if (canCancel) actions.push("cancel");
            return actions;
        }
        case "DEPOSITED":
            return ["clear", "bounce"];
        case "CLEARED":
            return mode === "PDC" ? ["bounce", "receipt"] : ["receipt"];
        case "BOUNCED":
            return ["replace"];
        default:
            return [];
    }
}
