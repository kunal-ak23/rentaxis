import type { Cheque } from "@/lib/api/leasing";

export type ChequeTally = { count: number; amount: number };
export type PendingChequeSummary = { pending: ChequeTally; returned: ChequeTally };

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
 * A row the ledger already settled some other way (`ledgerSettled`, e.g. absorbed
 * into a settlement) is in neither: nothing more is expected from it. DRAFT,
 * CLEARED, REPLACED, CANCELLED, RETURNED, TRANSFERRED and ONLINE_PENDING are out.
 */
export function pendingChequeSummary(cheques: Cheque[]): PendingChequeSummary {
    const pending: ChequeTally = { count: 0, amount: 0 };
    const returned: ChequeTally = { count: 0, amount: 0 };
    for (const c of cheques) {
        if (c.ledgerSettled) continue;
        if (c.status === "REGISTERED" || c.status === "DEPOSITED") {
            pending.count++;
            pending.amount = round2(pending.amount + (c.amount ?? 0));
        } else if (c.status === "BOUNCED" && !c.replacedById) {
            returned.count++;
            returned.amount = round2(returned.amount + (c.amount ?? 0));
        }
    }
    return { pending, returned };
}
