"use client";

import { useState } from "react";
import { useSearchParams } from "next/navigation";

/**
 * The filter bar's draft/applied pair, the applied side mirrored into the URL
 * (replaceState; other params kept).
 *
 * - The URL is read once, on mount, so a bookmarked or shared report opens
 *   with its filters; `read` turns the query string into the filter.
 * - Editing the bar changes only `draft` — nothing loads and the URL stays put
 *   until the user applies it.
 * - `apply` (with the draft, or an explicit next filter) sets both sides and
 *   writes every key `write` returns: a value sets it, `undefined` or `""`
 *   removes it. Keys `write` does not name are left alone, and history is
 *   replaced rather than pushed, so applying never spams back/forward.
 *   Wire it as `onClick={() => apply()}`, not `onClick={apply}`, or the click
 *   event becomes the filter.
 *
 * No `useRouter`, so pages keep working under the existing `next/navigation`
 * test mocks (which stub `useSearchParams` only).
 */
export function useReportQuery<Q>(
    read: (sp: URLSearchParams) => Q,
    write: (q: Q) => Record<string, string | undefined>,
): { draft: Q; setDraft: (q: Q) => void; applied: Q; apply: (next?: Q) => void } {
    const params = useSearchParams();
    const [initial] = useState<Q>(() => read(new URLSearchParams(params?.toString() ?? "")));
    const [draft, setDraft] = useState<Q>(initial);
    const [applied, setApplied] = useState<Q>(initial);

    const apply = (next?: Q) => {
        const q = next === undefined ? draft : next;
        setDraft(q);
        setApplied(q);
        if (typeof window === "undefined") return;
        const url = new URL(window.location.href);
        for (const [key, value] of Object.entries(write(q))) {
            if (value === undefined || value === "") url.searchParams.delete(key);
            else url.searchParams.set(key, value);
        }
        window.history.replaceState(window.history.state, "", url.pathname + url.search + url.hash);
    };

    return { draft, setDraft, applied, apply };
}

export default useReportQuery;
