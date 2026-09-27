import { lookupApi, type RenterOption, type UnitOption } from "@/lib/api/lookup";
import { createLookupCache } from "@/components/pickers/lookupCache";

/** The page-wide unit and renter caches, shared by the pickers and `useIdNames`. */
export const unitCache = createLookupCache<UnitOption>(lookupApi.unitNames);
export const renterCache = createLookupCache<RenterOption>(lookupApi.renterNames);

/**
 * The renter's name as the app shows it: Arabic under /ar when there is one;
 * under /en "English (Arabic)", as the lease forms have always shown it.
 */
export function renterLabel(r: RenterOption, locale: string): string {
    if (locale === "ar") return r.nameAr || r.nameEn;
    return r.nameAr ? `${r.nameEn} (${r.nameAr})` : r.nameEn;
}
