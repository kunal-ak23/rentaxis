"use client";

import { useEffect, useState } from "react";
import { useLocale } from "next-intl";
import { renterCache, renterLabel, unitCache } from "@/components/pickers/caches";

export type IdNameKind = "units" | "renters";

export type IdNames = {
    /** The display name for an id, or "" when the id is null or not (yet) known. */
    name: (id: string | null | undefined) => string;
    /** True until every id asked for has been answered (or its call failed). */
    loading: boolean;
};

/**
 * Names for the unit / renter ids a page is showing (ledger rows carry ids,
 * not names), fetched with the bounded `/names` call for just those ids instead
 * of loading the whole units or renters table.
 *
 * Shares the pickers' page-wide cache: an id already seen (by a picker's search,
 * or another mount of this hook) is never asked for again, concurrent mounts
 * share in-flight requests, and `lookupApi` chunks a long list to the server's
 * 200-id cap. Units print their unit number; renters print what `RenterPicker`
 * prints ({@link renterLabel}).
 */
export function useIdNames(kind: IdNameKind, ids: (string | null | undefined)[]): IdNames {
    const locale = useLocale();
    const [, bump] = useState(0);
    const cache = kind === "units" ? unitCache : renterCache;

    // Stable across renders for the same set of ids, whatever their order or
    // repeats, so a re-render that builds a new array does not re-run the effect.
    const unique = [...new Set(ids.filter((id): id is string => !!id))].sort();
    const key = unique.join(",");

    useEffect(() => {
        if (!key) return;
        let live = true;
        cache.resolveMany(key.split(",")).then(() => {
            if (live) bump((n) => n + 1);
        });
        return () => {
            live = false;
        };
    }, [cache, key]);

    const name = (id: string | null | undefined): string => {
        if (!id) return "";
        if (kind === "units") return unitCache.get(id)?.unitNumber ?? "";
        const r = renterCache.get(id);
        return r ? renterLabel(r, locale) : "";
    };

    return { name, loading: unique.some((id) => cache.isPending(id)) };
}
