import { lookupApi, type RenterOption, type UnitOption } from "@/lib/api/lookup";
import { createLookupCache } from "@/components/pickers/lookupCache";

/** The page-wide unit and renter caches, shared by the pickers and `useIdNames`. */
export const unitCache = createLookupCache<UnitOption>(lookupApi.unitNames);
export const renterCache = createLookupCache<RenterOption>(lookupApi.renterNames);

/**
 * A renter's name in a picker: Arabic under /ar when there is one; under /en
 * "English (Arabic)", as the lease forms have always shown it.
 */
export function renterPickerLabel(r: RenterOption, locale: string): string {
    if (locale === "ar") return r.nameAr || r.nameEn;
    return r.nameAr ? `${r.nameEn} (${r.nameAr})` : r.nameEn;
}

/**
 * A renter's name on a report row (ledger Tenant column, CSV, tenant band):
 * Arabic under /ar when there is one, otherwise English. Exactly what the
 * ledgers printed before scale P1-6, so reports and exports read as before.
 */
export function renterRowLabel(r: RenterOption, locale: string): string {
    if (locale === "ar") return r.nameAr || r.nameEn;
    return r.nameEn;
}
