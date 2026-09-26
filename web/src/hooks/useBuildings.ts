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

    useEffect(() => {
        if (!propertyId) return;
        let alive = true;
        fetch(`/api/proxy/v1/buildings/property/${encodeURIComponent(propertyId)}`)
            .then(r => (r.ok ? r.json() : []))
            .then(data => { if (alive) setLoaded({ key: propertyId, buildings: Array.isArray(data) ? data : [] }); })
            .catch(() => { if (alive) setLoaded({ key: propertyId, buildings: [] }); });
        return () => { alive = false; };
    }, [propertyId]);

    const buildings = loaded !== null && loaded.key === propertyId ? loaded.buildings : [];
    const loading = !!propertyId && loaded?.key !== propertyId;
    const label = (b: Building) => (locale === "ar" ? b.nameAr || b.nameEn : b.nameEn || b.nameAr) ?? "";

    return { buildings, loading, hasBuildings: buildings.length > 0, label };
}
