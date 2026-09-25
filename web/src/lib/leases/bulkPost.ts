// src/lib/leases/bulkPost.ts
import { ApiError } from "@/lib/api/facilities";

/** How many drafts a bulk post sends at once (backend contention at higher counts, scale test 2026-09-25). */
export const BULK_POST_CONCURRENCY = 3;
/** Wait before the one retry of a contended post. */
export const BULK_POST_RETRY_DELAY_MS = 750;

/**
 * A post that lost a race rather than one the server refused on its merits:
 * a 409, or a 500 whose message or body names a lock / optimistic-locking
 * failure. Only these are retried; a 400 (bad data) never is.
 */
export function isContentionError(e: unknown): boolean {
    if (!(e instanceof ApiError)) return false;
    if (e.status === 409) return true;
    if (e.status !== 500) return false;
    return /lock|optimistic|deadlock|concurrent|could not serialize/i.test(`${e.message} ${e.body ?? ""}`);
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
