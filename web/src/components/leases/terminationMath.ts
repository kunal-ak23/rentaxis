/**
 * The arithmetic the termination screen does while finance is still deciding.
 *
 * `TerminationPreviewDTO.receivableAfter` is the server's answer for the
 * DEFAULT return/keep split, and the screen lets a row be flipped before the
 * confirm. Rather than a round trip per click — which would also mean a figure
 * that disagrees with the table for as long as the request is in flight — the
 * page recomputes it here, mirroring
 * `LeaseTerminationService.receivableAfter`
 * (backend/src/main/java/com/datagami/rentaxis/core/service/lease/LeaseTerminationService.java:384-392):
 *
 *     receivableAfter = currentBalance + Σ(returned amounts) − unearnedRent
 *
 * Only the middle term moves as rows are flipped, so the whole edit is
 * "subtract the default returns, add the chosen ones". `POST /terminate`
 * recomputes all of it from the register; this is what the reader sees while
 * deciding, never the truth of record.
 */

import type { Cheque, TerminationPreview } from "@/lib/api/leasing";

/** What finance has decided to do with one uncleared instrument. */
export type ChequeDecision = "RETURN" | "KEEP";

export type ChequeDecisions = Record<string, ChequeDecision>;

/** Half-up to the fils — the same rounding the server's `setScale(2, HALF_UP)` does. */
function round2(n: number): number {
    if (!Number.isFinite(n)) return 0;
    return Math.round((n + Number.EPSILON) * 100) / 100;
}

/**
 * Every row the request has to answer for, in register order.
 *
 * Bounced rows are deliberately absent: `ChequeStatus.isUncleared()` excludes
 * BOUNCED, so the server neither requires them nor accepts them in either list
 * ("These cheques are not uncleared rows of this lease").
 */
export function unclearedRows(preview: TerminationPreview): Cheque[] {
    return [...preview.chequesToReturn, ...preview.chequesToKeep].sort((a, b) => a.seqNo - b.seqNo);
}

/** The preview's own split, as the map the table edits. */
export function defaultDecisions(preview: TerminationPreview): ChequeDecisions {
    const out: ChequeDecisions = {};
    for (const c of preview.chequesToReturn) out[c.id] = "RETURN";
    for (const c of preview.chequesToKeep) out[c.id] = "KEEP";
    return out;
}

function sum(rows: Cheque[]): number {
    return rows.reduce((t, c) => t + (c.amount ?? 0), 0);
}

/**
 * The receivable the renter would be left with under `decisions`.
 *
 * Returning a row adds its amount back to the receivable — the `PDR` that moved
 * it into PDC receivable is reversed, so the money is owed again; keeping it
 * leaves it where it is. A decision for an id that is not an uncleared row of
 * this preview is ignored, which is what keeps a stale bounced entry in the map
 * from moving a figure.
 */
export function receivableAfterForSplit(preview: TerminationPreview, decisions: ChequeDecisions): number {
    const rows = unclearedRows(preview);
    const chosenReturns = rows.filter(c => decisions[c.id] === "RETURN");
    return round2(preview.receivableAfter - sum(preview.chequesToReturn) + sum(chosenReturns));
}

/**
 * The two lists `TerminateLeaseRequest` wants: **every** uncleared row in
 * exactly one of them. A row with no decision falls to Keep, which is the
 * conservative half — keeping an instrument leaves the register exactly as it
 * is, while returning one reverses a journal.
 */
export function splitIds(
    preview: TerminationPreview,
    decisions: ChequeDecisions,
): { returnChequeIds: string[]; keepChequeIds: string[] } {
    const rows = unclearedRows(preview);
    return {
        returnChequeIds: rows.filter(c => decisions[c.id] === "RETURN").map(c => c.id),
        keepChequeIds: rows.filter(c => decisions[c.id] !== "RETURN").map(c => c.id),
    };
}
