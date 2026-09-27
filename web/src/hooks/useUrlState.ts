"use client";

import { useSyncExternalStore } from "react";
import { useSearchParams } from "next/navigation";

/**
 * A single URL query parameter, extracted from the Tickets list's property +
 * tower (buildingId) filters (tickets/page.tsx) so Renters — and Tickets
 * itself, switched onto this hook in the same change — share one
 * implementation. The Contracts (leases) list's own store follows the exact
 * same shape; only this one is shared for now.
 *
 * - Read: `useSyncExternalStore` over `location.search`, with an empty string
 *   as the server snapshot (the server never sees `location.search`) so
 *   hydration never mismatches; every subscriber — including a different
 *   `useUrlState` call on the same page — re-renders on any change.
 * - Write: `history.replaceState`, never a navigation (`pushState`), so
 *   typing or paging never spams back/forward history. A write touches only
 *   this hook's own key — every other query parameter, including one owned
 *   by another `useUrlState` call, is left exactly as it was.
 * - Setting the fallback value, or `""`, removes the key from the URL rather
 *   than writing it out explicitly.
 */
const urlListeners = new Set<() => void>();
function subscribeUrl(cb: () => void) {
    urlListeners.add(cb);
    window.addEventListener("popstate", cb);
    return () => {
        urlListeners.delete(cb);
        window.removeEventListener("popstate", cb);
    };
}

export function useUrlState(key: string, fallback: string): [string, (v: string) => void] {
    // Re-renders this component when a same-route Next navigation (e.g. a
    // <Link> to a pinned/bookmarked query) changes the URL — a plain
    // `popstate` listener alone doesn't see that.
    useSearchParams();
    const search = useSyncExternalStore(subscribeUrl, () => window.location.search, () => "");
    const value = new URLSearchParams(search).get(key) ?? fallback;

    const setValue = (v: string) => {
        const url = new URL(window.location.href);
        if (v && v !== fallback) url.searchParams.set(key, v);
        else url.searchParams.delete(key);
        window.history.replaceState(window.history.state, "", url.toString());
        urlListeners.forEach((l) => l());
    };

    return [value, setValue];
}
