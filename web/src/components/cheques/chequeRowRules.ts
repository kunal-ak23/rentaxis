import type { ChequeMode } from "@/lib/api/leasing";

/**
 * What makes a typed cheque row acceptable — the client's one mirror of
 * `backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeRowRules.java`
 * (`validateRow`, :127-157; `blankToNull`, :159).
 *
 * The register's `registerActionsFor` is the model: one exported table with the
 * server's rule quoted beside it, unit-tested as a pure function, imported by
 * every screen that asks the same question. Before this module the same three
 * lines were re-guessed at each door — the draft grid, the generate form, the
 * extension grid, the two replacement dialogs — and four of them guessed
 * wrong, offering rows the server always refused (a Cash replacement with the
 * date discarded, an Extend with a dateless PDC, an ONLINE option no
 * user-facing path can save).
 *
 * The rules mirrored here, in the server's own order:
 *  - ONLINE is refused on every user-facing path — an online receipt is
 *    created by the payment gateway callback, never typed (:130-135). Only
 *    `ChequeService.replaceForOnlinePayment` may say otherwise, and no screen
 *    is that caller, so this module has no `fromGateway` escape hatch at all.
 *  - the amount must be greater than zero (:137-139);
 *  - `chequeDate` is required for EVERY mode — a PDC needs the date written on
 *    it, a CASH or TRANSFER receipt the date it is expected on (:140-144);
 *  - a cheque number belongs to a PDC row only (:147-151), and may not repeat a
 *    number already live on the lease or used by another row of the same
 *    payload (:152-155).
 *
 * Not mirrored: `validateGrid`'s id checks (:57-67) and `validateNewRows`'
 * refusal of an id (:90-93). Those are about which entity a row addresses, and
 * a screen never builds one by hand — the grid edits the rows it was given and
 * the dialogs create rows with no id at all.
 */

/** The modes a human may type. ONLINE is the gateway's, never the grid's (:130-135). */
export const TYPEABLE_MODES: ChequeMode[] = ["PDC", "CASH", "TRANSFER"];

export type ChequeRowErrorCode =
    /** "ONLINE receipts are recorded by the payment gateway, not entered on the grid" */
    | "onlineNotTyped"
    /** "amount must be greater than zero" */
    | "amountPositive"
    /** "a post-dated cheque needs the date written on it" */
    | "pdcDateRequired"
    /** "a CASH receipt needs the date it is expected on" */
    | "receiptDateRequired"
    /** "a CASH receipt has no cheque number" */
    | "numberOnlyOnPdc"
    /** "cheque number 100041 is already used on this lease" */
    | "numberTaken";

export type ChequeRowError = { code: ChequeRowErrorCode; number?: string };

/**
 * Anything shaped like a row the server will be sent: `Cheque`, `ChequeRowInput`
 * and each dialog's own draft type all satisfy it.
 */
export type ChequeRowLike = {
    mode?: ChequeMode | null;
    amount?: number | null;
    chequeDate?: string | null;
    chequeNumber?: string | null;
};

/** `ChequeRowRules.blankToNull` (:159-161). */
function blankToNull(s: string | null | undefined): string | null {
    return s == null || s.trim() === "" ? null : s.trim();
}

/**
 * One row's own rules. Unlike the server, which throws on the first violation,
 * this collects them all: a disabled button that explains every reason at once
 * is one round trip, not five.
 *
 * @param takenNumbers cheque numbers already live on the lease — what the
 * server's `takenNumbers` (ChequeService.java:1096-1104) collects from every
 * row of the register regardless of status. A caller that cannot know them
 * (the replacement dialogs, which hold one cheque and not its lease) passes
 * nothing and leaves that one check to the server.
 */
export function chequeRowErrors(
    row: ChequeRowLike,
    takenNumbers: Iterable<string> = [],
): ChequeRowError[] {
    const taken = takenNumbers instanceof Set ? (takenNumbers as Set<string>) : new Set(takenNumbers);
    return rowErrors(row, taken, new Set<string>());
}

export function chequeRowIsValid(row: ChequeRowLike, takenNumbers: Iterable<string> = []): boolean {
    return chequeRowErrors(row, takenNumbers).length === 0;
}

/**
 * A whole payload, with the number set accumulated across it exactly as the
 * server accumulates `seenNumbers` (:127-131 doc, :152) — so two rows that
 * collide with each other, and not only with what is stored, are caught here.
 *
 * @returns one error list per row, positionally.
 */
export function chequeRowsErrors(
    rows: readonly ChequeRowLike[],
    takenNumbers: Iterable<string> = [],
): ChequeRowError[][] {
    const taken = takenNumbers instanceof Set ? (takenNumbers as Set<string>) : new Set(takenNumbers);
    const seen = new Set<string>();
    return rows.map(row => rowErrors(row, taken, seen));
}

/** Every row valid, and at least one row — `validateNewRows` (:82-84). */
export function chequeRowsAreValid(
    rows: readonly ChequeRowLike[],
    takenNumbers: Iterable<string> = [],
): boolean {
    if (rows.length === 0) return false;
    return chequeRowsErrors(rows, takenNumbers).every(e => e.length === 0);
}

function rowErrors(row: ChequeRowLike, taken: Set<string>, seen: Set<string>): ChequeRowError[] {
    const errors: ChequeRowError[] = [];
    const mode: ChequeMode = row.mode ?? "PDC";

    if (mode === "ONLINE") errors.push({ code: "onlineNotTyped" });

    if (row.amount == null || !(row.amount > 0)) errors.push({ code: "amountPositive" });

    if (!blankToNull(row.chequeDate)) {
        errors.push({ code: mode === "PDC" ? "pdcDateRequired" : "receiptDateRequired" });
    }

    const number = blankToNull(row.chequeNumber);
    if (number != null) {
        if (mode !== "PDC") {
            errors.push({ code: "numberOnlyOnPdc" });
        } else if (seen.has(number) || taken.has(number)) {
            errors.push({ code: "numberTaken", number });
        } else {
            seen.add(number);
        }
    }

    return errors;
}
