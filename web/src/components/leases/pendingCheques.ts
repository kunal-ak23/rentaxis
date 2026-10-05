import type { Cheque } from "@/lib/api/leasing";

export type ChequeTally = { count: number; amount: number };
/** Returned cheques: `amount` is what is still owed on them (PR #397 R1-P3-2), `face` their face value. */
export type ReturnedTally = ChequeTally & { face: number };
export type PendingChequeSummary = { pending: ChequeTally; returned: ReturnedTally };

const round2 = (n: number) => Math.round(n * 100) / 100;

/**
 * Demo feedback 2026-09-29: the contract header's "Pending cheques" strip.
 *
 * - **Pending** — instruments still expected to be paid: REGISTERED (in the
 *   drawer) or DEPOSITED (at the bank), not yet cleared.
 * - **Returned** — BOUNCED with no replacement yet. The landlord is still owed
 *   the money, but it is not "pending" (its PDC receivable was reversed when it
 *   bounced), so it is counted apart and never in the pending figure.
 *
 * Returned cheques count what the ledger still carries on them (`openAmount`, the
 * figure the Bounced tile shows), with their face value alongside; a row whose open
 * amount is not known counts at face.
 *
 * A row the ledger already settled some other way (`ledgerSettled`, e.g. absorbed
 * into a settlement) is in neither: nothing more is expected from it. DRAFT,
 * CLEARED, REPLACED, CANCELLED, RETURNED, TRANSFERRED and ONLINE_PENDING are out.
 */
export function pendingChequeSummary(cheques: Cheque[]): PendingChequeSummary {
    const pending: ChequeTally = { count: 0, amount: 0 };
    const returned: ReturnedTally = { count: 0, amount: 0, face: 0 };
    for (const c of cheques) {
        if (c.ledgerSettled) continue;
        if (c.status === "REGISTERED" || c.status === "DEPOSITED") {
            pending.count++;
            pending.amount = round2(pending.amount + (c.amount ?? 0));
        } else if (c.status === "BOUNCED" && !c.replacedById) {
            returned.count++;
            returned.amount = round2(returned.amount + (c.openAmount ?? c.amount ?? 0));
            returned.face = round2(returned.face + (c.amount ?? 0));
        }
    }
    return { pending, returned };
}
