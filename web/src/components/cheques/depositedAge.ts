import type { Cheque } from "@/lib/api/leasing";

/**
 * Tutorial 35: a DEPOSITED cheque waiting on the bank is not "overdue" — the tenant has
 * handed it over; only clearing is pending. Lists that would print "N days overdue" for
 * it print how long ago it was banked instead.
 */
export function isAwaitingClearing(c: Pick<Cheque, "status">): boolean {
    return c.status === "DEPOSITED";
}

/** Whole days from an ISO date (yyyy-mm-dd) to today, in the viewer's calendar; null when unknown. */
export function daysSince(iso: string | null | undefined, now: Date = new Date()): number | null {
    if (!iso) return null;
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    if (!y || !m || !d) return null;
    const from = Date.UTC(y, m - 1, d);
    const to = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
    return Math.max(0, Math.round((to - from) / 86_400_000));
}

/**
 * PR #399 R1 P3-4: past this many days a deposit that has not cleared is likely a bounce
 * nobody recorded — the row keeps "Deposited N days ago" and adds an amber flag.
 */
export const STALE_DEPOSIT_DAYS = 14;

export function isStaleDeposit(days: number | null): boolean {
    return days !== null && days > STALE_DEPOSIT_DAYS;
}
