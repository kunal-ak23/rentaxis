// src/lib/leases/bulkPost.ts
import { ApiError } from "@/lib/api/facilities";

/** How many drafts a bulk post sends at once (backend contention at higher counts, scale test 2026-09-25). */
export const BULK_POST_CONCURRENCY = 3;
/** Wait before the one retry of a contended post. */
export const BULK_POST_RETRY_DELAY_MS = 750;

/**
 * A post that lost a race rather than one the server refused on its merits:
 * a 409 that says it lost a race ("by another request … try again"), or a 500
 * whose message or body names a lock / optimistic-locking failure. Only these
 * are retried; a 400 (bad data) and a data-integrity 409 never are.
 */
export function isContentionError(e: unknown): boolean {
    if (!(e instanceof ApiError)) return false;
    const text = `${e.message} ${e.body ?? ""}`;
    if (e.status === 409) {
        // Break-it round 1 (money) F3: not every 409 is a race. A data-integrity
        // refusal ("conflicts with existing related records (uq_…)") is the same
        // answer on a retry. The server's contention refusals all say so:
        // "…by another request. Please try again." / "…at the same time…".
        return /another request|try again|same time|deadlock|concurrent/i.test(text);
    }
    if (e.status !== 500) return false;
    return /lock|optimistic|deadlock|concurrent|could not serialize/i.test(text);
}

/** Runs `attempt`; on a contention error waits `delayMs` and tries once more. Any other error, or a second failure, is thrown. */
export async function withOneRetry<T>(attempt: () => Promise<T>, delayMs = BULK_POST_RETRY_DELAY_MS): Promise<T> {
    try {
        return await attempt();
    } catch (e) {
        if (!isContentionError(e)) throw e;
        await new Promise(r => setTimeout(r, delayMs));
        return attempt();
    }
}
