/**
 * True for the rejection of a request its own AbortController cancelled
 * (a page unmounting, or a newer request superseding it). Such a request was
 * abandoned on purpose: callers return quietly — no console.error, no error
 * banner, no state update.
 */
export function isAbortError(err: unknown): boolean {
    return typeof err === "object" && err !== null && (err as { name?: unknown }).name === "AbortError";
}
