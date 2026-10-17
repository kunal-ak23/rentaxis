import type { BadDebtWriteOff } from "@/lib/api/badDebts";

type W = Pick<BadDebtWriteOff, "status" | "amount" | "writeOffDate">;

const round2 = (n: number) => Math.round(n * 100) / 100;

/**
 * F15-22 / #102: write-offs in force on a lease (`WRITTEN_OFF`); proposed,
 * rejected and reversed ones never reduced what the renter owes.
 *
 * `sinceMoveOut` counts only those dated on or after the settlement date — the
 * ones that reduced the balance the move-out statement left. Earlier ones (a
 * bounced cheque written off mid-tenancy) were already out of the receivable
 * the balance due was taken from, so they are reported in `total` only and
 * never subtracted again (review P2-1).
 *
 * Shared by the lease's Settlement Summary card and the finalized move-out
 * statement so the two never disagree.
 */
export function writtenOffTotals(writeOffs: readonly W[], settlementDate: string | null | undefined): {
    total: number;
    sinceMoveOut: number;
} {
    const inForce = writeOffs.filter(w => w.status === "WRITTEN_OFF");
    const total = round2(inForce.reduce((sum, w) => sum + w.amount, 0));
    const sinceMoveOut = !settlementDate ? 0 : round2(inForce
        .filter(w => w.writeOffDate >= settlementDate)
        .reduce((sum, w) => sum + w.amount, 0));
    return { total, sinceMoveOut };
}

/**
 * What the renter still owes: the server's live ledger figure (`stillDue` on the
 * finalized settlement — rent and PDC receivable on the lease and renter), never
 * a sum worked out here. Null when the server sent none (a draft, an old server).
 */
export function stillDueOf(settlement: { stillDue?: number | null } | null | undefined): number | null {
    const v = settlement?.stillDue;
    return v == null ? null : round2(Math.max(0, v));
}
