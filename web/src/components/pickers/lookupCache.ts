import { useEffect, useState } from "react";

/**
 * Per-id cache of every option a picker has seen (search results and names
 * lookups), shared across picker instances for the life of the page, so the
 * label of a selected value is usually free. `useResolved` fetches a value
 * missing from it with one names call, deduplicated while in flight.
 */
export function createLookupCache<T extends { id: string }>(fetchNames: (ids: string[]) => Promise<T[]>) {
    const seen = new Map<string, T>();
    const inFlight = new Map<string, Promise<void>>();

    const remember = (rows: T[]) => {
        for (const row of rows) seen.set(row.id, row);
        return rows;
    };

    const resolve = (id: string): Promise<void> => {
        let p = inFlight.get(id);
        if (!p) {
            p = fetchNames([id])
                .then((rows) => {
                    remember(rows);
                })
                .catch(() => {
                    // Leave it unresolved; the trigger falls back to the placeholder.
                })
                .finally(() => inFlight.delete(id));
            inFlight.set(id, p);
        }
        return p;
    };

    /** The cached row for `id`, fetching it once if the cache has not seen it. */
    function useResolved(id: string): T | undefined {
        const [, bump] = useState(0);
        const cached = id ? seen.get(id) : undefined;
        useEffect(() => {
            if (!id || seen.has(id)) return;
            let live = true;
            resolve(id).then(() => {
                if (live) bump((n) => n + 1);
            });
            return () => {
                live = false;
            };
        }, [id]);
        return cached;
    }

    return { remember, get: (id: string) => seen.get(id), useResolved };
}
