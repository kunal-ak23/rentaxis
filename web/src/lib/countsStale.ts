/**
 * Counts shown away from the page that changed them (the sidebar badge, the
 * collection pills) are read again when this event fires on `window`. Kept in
 * its own module so the API layer can announce a change without importing UI hooks.
 */
export const COUNTS_STALE_EVENT = "rentaxis:nav-counts-stale";

/** Tell every count on screen to re-read (tutorial 15: pills kept page-load numbers after a deposit). */
export function announceCountsStale(): void {
    if (typeof window !== "undefined") window.dispatchEvent(new Event(COUNTS_STALE_EVENT));
}

/** Passes `p` through, announcing once it succeeds. */
export function announcingChange<T>(p: Promise<T>): Promise<T> {
    return p.then(v => {
        announceCountsStale();
        return v;
    });
}
