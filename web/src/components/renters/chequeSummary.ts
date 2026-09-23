import type { Cheque } from "@/lib/api/leasing";

/** Cheques that are still the renter's to honour: not yet cleared, not dead. */
const OUTSTANDING = new Set(["REGISTERED", "DEPOSITED", "ONLINE_PENDING"]);

/**
 * The cheque figures a manager asks about on the phone. Pure so the test can
 * lock it down: DRAFT, CANCELLED and REPLACED rows are not money the renter owes
 * or has paid, so they are left out of every total.
 */
export function chequeSummary(cheques: Pick<Cheque, "amount" | "status">[]) {
    const live = cheques.filter(c => !["DRAFT", "CANCELLED", "REPLACED"].includes(c.status));
    const sum = (xs: typeof live) => Math.round(xs.reduce((s, c) => s + (c.amount ?? 0), 0) * 100) / 100;
    return {
        count: live.length,
        total: sum(live),
        cleared: sum(live.filter(c => c.status === "CLEARED")),
        outstanding: sum(live.filter(c => OUTSTANDING.has(c.status))),
        bounced: live.filter(c => c.status === "BOUNCED" || c.status === "RETURNED").length,
    };
}
