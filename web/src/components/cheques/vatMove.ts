import type { Cheque, ChequeStatus, VatTaxPoint } from "@/lib/api/leasing";

/**
 * Cancelling a REGISTERED row whose VAT has not been declared yet (spec
 * 2026-09-24 §1): the server refuses unless `?moveVatTo=` names another pending
 * instalment of the same lease to carry it (`VatTaxPointService.beforeCancel`).
 * This mirrors that rule so the cancel dialog can offer only rows it accepts.
 */

/** `VatTaxPointService.PENDING` — rows still to be collected. */
const PENDING: ReadonlySet<ChequeStatus> = new Set<ChequeStatus>(["REGISTERED", "DEPOSITED", "ONLINE_PENDING"]);

export type VatMove = {
    /** The undeclared VAT on the row being cancelled. */
    pendingVat: number;
    /** Other pending rows of the lease whose own tax point is still to come, by date. */
    candidates: Cheque[];
    /** The next row by date after the cancelled one, else the last candidate; null when there is none. */
    defaultId: string | null;
};

const byDate = (a: Cheque, b: Cheque) =>
    (a.chequeDate ?? "").localeCompare(b.chequeDate ?? "") || a.seqNo - b.seqNo;

/**
 * Null when cancelling `cheque` needs no VAT move: it is not REGISTERED, or it has
 * no live PLANNED tax point carrying VAT (a CONTRACT-timed lease has no schedule).
 */
export function vatMoveFor(
    cheque: Cheque,
    leaseCheques: Cheque[],
    schedule: VatTaxPoint[],
    booksLockedThrough: string | null = null,
): VatMove | null {
    if (cheque.status !== "REGISTERED") return null;
    const own = schedule.find(p => p.chequeId === cheque.id && p.status !== "CANCELLED");
    if (!own || own.status !== "PLANNED" || !(own.vatAmount > 0)) return null;

    const declared = new Set(
        schedule.filter(p => p.status === "POSTED" && p.chequeId).map(p => p.chequeId as string),
    );
    const candidates = leaseCheques
        .filter(c => c.id !== cheque.id && c.leaseId === cheque.leaseId)
        .filter(c => PENDING.has(c.status) && !declared.has(c.id))
        // A deposit carries no VAT (the server refuses it), and a tax point inside
        // the locked period would never post (re-review N5).
        .filter(c => c.rowKind !== "DEPOSIT")
        .filter(c => !booksLockedThrough || (c.chequeDate ?? "").slice(0, 10) > booksLockedThrough)
        .sort(byDate);

    const from = cheque.chequeDate ?? "";
    const next = candidates.find(c => (c.chequeDate ?? "") >= from) ?? candidates[candidates.length - 1];
    return { pendingVat: own.vatAmount, candidates, defaultId: next?.id ?? null };
}
