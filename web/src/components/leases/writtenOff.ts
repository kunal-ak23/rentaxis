import type { BadDebtWriteOff } from "@/lib/api/badDebts";

/**
 * F15-22 / #102: the amount written off as bad debt on a lease — only the
 * write-offs in force (`WRITTEN_OFF`); proposed, rejected and reversed ones
 * never reduced what the renter owes.
 *
 * Shared by the lease's Settlement Summary card and the finalized move-out
 * statement so the two never disagree.
 */
export function writtenOffTotal(writeOffs: readonly Pick<BadDebtWriteOff, "status" | "amount">[]): number {
    return Math.round(
        writeOffs.filter(w => w.status === "WRITTEN_OFF").reduce((sum, w) => sum + w.amount, 0) * 100,
    ) / 100;
}

/** What is still due from the renter once the write-offs are taken off the settlement's balance due. */
export function stillDueOf(balanceDue: number | null | undefined, writtenOff: number): number {
    return Math.max(0, Math.round(((balanceDue ?? 0) - writtenOff) * 100) / 100);
}
