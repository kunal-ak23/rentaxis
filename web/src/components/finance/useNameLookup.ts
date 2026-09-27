"use client";

import { useEffect, useMemo, useState } from "react";
import { useLocale } from "next-intl";

/**
 * Properties only: a tenant has few enough of them to list whole. Units and
 * renters are named per id with `useIdNames` and picked with the server-searched
 * `UnitPicker` / `RenterPicker` (scale P1-6), so this type refuses them.
 */
export type LookupKind = "properties";

export type LookupEntry = { id: string; en: string; ar: string };

/**
 * Rows and filters carry a property id; the report prints the property's name.
 * The tenant's property list is fetched once per page load and cached
 * module-wide, so every finance page and filter bar shares one GET.
 *
 * `GET /v1/properties` returns a plain `List<…>` (`PropertyController#getAllProperties`),
 * not paginated. The `content` unwrapping below is defensive for the day it
 * grows a `Page<…>`.
 *
 * Both names are kept so the hook can pick per locale without a second fetch:
 * the cache is shared across locales within a session (a locale switch is a full
 * navigation, but the module may survive it under client-side routing).
 */
const cache: Partial<Record<LookupKind, Promise<LookupEntry[]>>> = {};

type RawRow = {
    id?: string;
    nameEn?: string;
    nameAr?: string;
    name?: string;
    /** `GET /v1/properties` returns portfolio-summary rows that wrap the property. */
    property?: { id?: string; nameEn?: string; nameAr?: string; name?: string };
};

function normalise(data: unknown): LookupEntry[] {
    const list: RawRow[] = Array.isArray(data)
        ? (data as RawRow[])
        : ((data as { content?: RawRow[] } | null)?.content ?? []);

    return list
        .map(row => {
            // A portfolio row is `{ property: {...}, vacancies, … }`; a bare property row is the property.
            const source: RawRow = row.property ?? row;
            const en = source.nameEn ?? source.name ?? "";
            const ar = source.nameAr ?? en;
            return { id: source.id ?? "", en: en || (source.id ?? ""), ar: ar || en || (source.id ?? "") };
        })
        .filter(e => e.id);
}

export function loadNames(kind: LookupKind): Promise<LookupEntry[]> {
    if (!cache[kind]) {
        cache[kind] = fetch(`/api/proxy/v1/${kind}`)
            .then(res => (res.ok ? res.json() : []))
            .then(data => normalise(data))
            .catch(err => {
                // A failed load must not poison the cache forever — the next
                // mount retries. Names simply render blank until it succeeds.
                cache[kind] = undefined;
                throw err;
            });
    }
    return cache[kind]!;
}

export function invalidateNames(kind?: LookupKind) {
    if (kind) cache[kind] = undefined;
    else (Object.keys(cache) as LookupKind[]).forEach(k => (cache[k] = undefined));
}

export type NameLookup = {
    /** The display name for an id, or "" when the id is null or not (yet) known. */
    name: (id: string | null | undefined) => string;
    /** The same rows, locale-resolved and name-sorted, for `<select>` options. */
    options: { id: string; label: string }[];
    loading: boolean;
};

/**
 * `enabled` exists so a page that hides a filter does not pay for its list: the
 * hook cannot be called conditionally, but the fetch can be skipped.
 */
export function useNameLookup(kind: LookupKind, enabled = true): NameLookup {
    const locale = useLocale();
    const [entries, setEntries] = useState<LookupEntry[]>([]);
    const [loading, setLoading] = useState(enabled);

    useEffect(() => {
        if (!enabled) return;
        let alive = true;
        // `loading` starts true and is only ever cleared here. Re-arming it at the
        // top of the effect would be a setState in an effect body (cascading
        // render), and `kind` is a literal at every call site — it never changes
        // for a mounted hook.
        loadNames(kind)
            .then(rows => {
                if (alive) setEntries(rows);
            })
            .catch(() => {})
            .finally(() => {
                if (alive) setLoading(false);
            });
        return () => {
            alive = false;
        };
    }, [kind, enabled]);

    return useMemo(() => {
        const isAr = locale === "ar";
        const byId = new Map(entries.map(e => [e.id, isAr ? e.ar : e.en]));
        const options = entries
            .map(e => ({ id: e.id, label: isAr ? e.ar : e.en }))
            .sort((a, b) => a.label.localeCompare(b.label, locale));
        return {
            name: (id: string | null | undefined) => (id ? byId.get(id) ?? "" : ""),
            options,
            loading,
        };
    }, [entries, locale, loading]);
}
