import { useEffect, useState } from "react";

/**
 * Per-id cache of every option a picker has seen (search results and names
 * lookups), shared across picker instances and `useIdNames` for the life of
 * the page, so the label of a selected value is usually free.
 *
 * `resolveMany` fetches the ids it has not seen with one names call (the API
 * chunks it internally and reports back per-chunk), deduplicated per id while
 * in flight, so concurrent mounts asking for overlapping ids share requests.
 * Ids a *successful* chunk answered without (deleted, out of scope) are
 * remembered as missing and not asked for again; ids whose chunk failed are
 * retried by the next caller. A partial failure — some chunks succeed, others
 * don't — never marks a successful chunk's ids as failed, and never marks a
 * failed chunk's ids as missing.
 */
export function createLookupCache<T extends { id: string }>(
    fetchNames: (ids: string[]) => Promise<{ rows: T[]; failedIds: string[] }>,
) {
    const seen = new Map<string, T>();
    // Ids a names call answered without. Lasts until the page reloads; a picker
    // search that later returns the id clears it (see `remember`).
    const missing = new Set<string>();
    const failed = new Set<string>();
    const inFlight = new Map<string, Promise<void>>();

    const remember = (rows: T[]) => {
        for (const row of rows) {
            seen.set(row.id, row);
            missing.delete(row.id);
        }
        return rows;
    };

    /** Settles once every id is known, missing, or its call failed. Never rejects. */
    const resolveMany = (ids: string[]): Promise<void> => {
        const need = [...new Set(ids)].filter((id) => id && !seen.has(id) && !missing.has(id) && !inFlight.has(id));
        if (need.length) {
            for (const id of need) failed.delete(id);
            const p = fetchNames(need)
                .then(({ rows, failedIds }) => {
                    remember(rows);
                    const failedSet = new Set(failedIds);
                    for (const id of need) {
                        if (failedSet.has(id)) failed.add(id);
                        else if (!seen.has(id)) missing.add(id);
                    }
                })
                .catch(() => {
                    // Defensive: fetchNames settles its own chunks and should not reject.
                    // Leave them unresolved; labels render blank / the trigger shows the placeholder.
                    for (const id of need) failed.add(id);
                })
                .finally(() => {
                    for (const id of need) inFlight.delete(id);
                });
            for (const id of need) inFlight.set(id, p);
        }
        const waits = new Set<Promise<void>>();
        for (const id of ids) {
            const p = inFlight.get(id);
            if (p) waits.add(p);
        }
        return Promise.all(waits).then(() => undefined);
    };

    /** True while `id` has not been settled (known, missing, or failed). */
    const isPending = (id: string) => !!id && !seen.has(id) && !missing.has(id) && !failed.has(id);

    /** The cached row for `id`, fetching it once if the cache has not seen it. */
    function useResolved(id: string): T | undefined {
        const [, bump] = useState(0);
        const cached = id ? seen.get(id) : undefined;
        useEffect(() => {
            if (!id || seen.has(id)) return;
            let live = true;
            resolveMany([id]).then(() => {
                if (live) bump((n) => n + 1);
            });
            return () => {
                live = false;
            };
        }, [id]);
        return cached;
    }

    return { remember, get: (id: string) => seen.get(id), resolveMany, isPending, useResolved };
}

export type LookupCache<T extends { id: string }> = ReturnType<typeof createLookupCache<T>>;
