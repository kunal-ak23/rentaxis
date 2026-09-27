import { useEffect, useState } from "react";
import { useLocale } from "next-intl";

export type Building = { id: string; nameEn?: string | null; nameAr?: string | null };

/**
 * S16-02: the towers (Buildings) of one property, for the Tower/Building
 * filter on the Units, Contracts and Tickets lists — `GET
 * /buildings/property/{propertyId}` (the same read `UnitStatusBoard` uses to
 * group by tower). Empty for a property with no towers, so callers hide the
 * select rather than show a filter with nothing to pick.
 */
export function useBuildings(propertyId: string | null | undefined) {
    const locale = useLocale();
    // Tagged by the property it was fetched for (the `UnitStatusBoard` pattern):
    // reading it back only when the tag still matches `propertyId` means no
    // setState has to run synchronously in the effect body just to clear a
    // stale value — a falsy or just-changed `propertyId` simply has no match.
    const [loaded, setLoaded] = useState<{ key: string; buildings: Building[] } | null>(null);
    // R1 P3-3: a network error or a transient 5xx is not the same fact as "this
    // property genuinely has zero towers" — the old code treated both as an
    // empty list, so a caller (TowerSelect) reported the filter unavailable and
    // dropped a perfectly valid `buildingId` off the back of a blip. `failed`
    // is tagged the same way `loaded` is, and is cleared the moment a read (the
    // one retry, or a later `propertyId` change) succeeds.
    const [failed, setFailed] = useState<string | null>(null);

    useEffect(() => {
        if (!propertyId) return;
        let alive = true;
        let attempt = 0;
        const load = () => {
            fetch(`/api/proxy/v1/buildings/property/${encodeURIComponent(propertyId)}`)
                .then(r => {
                    // A resolved refusal (403 — this role, e.g. ACCOUNTANT, is
                    // not admitted to the endpoint at all — or a 404) is a
                    // genuine, permanent "no" from the server: treated as zero
                    // towers, same as before. Only a transient failure — no
                    // response at all (a network error) or a 5xx the server
                    // itself flags as its own trouble — is worth retrying
                    // rather than reported as "unavailable".
                    if (r.ok) return r.json();
                    if (r.status >= 500) return Promise.reject(new Error(String(r.status)));
                    return [];
                })
                .then(data => {
                    if (!alive) return;
                    setLoaded({ key: propertyId, buildings: Array.isArray(data) ? data : [] });
                    setFailed(null);
                })
                .catch(() => {
                    if (!alive) return;
                    if (attempt < 1) {
                        attempt++;
                        setTimeout(load, 800);
                    } else {
                        setFailed(propertyId);
                    }
                });
        };
        load();
        return () => { alive = false; };
    }, [propertyId]);

    const buildings = loaded !== null && loaded.key === propertyId ? loaded.buildings : [];
    const hasFailed = failed === propertyId;
    const loading = !!propertyId && loaded?.key !== propertyId && !hasFailed;
    const label = (b: Building) => (locale === "ar" ? b.nameAr || b.nameEn : b.nameEn || b.nameAr) ?? "";

    return { buildings, loading, hasBuildings: buildings.length > 0, label, failed: hasFailed };
}
