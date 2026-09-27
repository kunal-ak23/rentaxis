import { apiGet, qs } from "@/lib/api/ledger";

/**
 * Bounded lookups for pickers (scale P1-6): a server search returning the first
 * `limit` matches, and a names call resolving ids the picker has not seen.
 * These replace loading every unit/renter into the browser.
 */

export type UnitOption = {
    id: string;
    unitNumber: string;
    propertyId: string | null;
    propertyName: string | null;
    propertyType: string | null;
    buildingId: string | null;
    buildingName: string | null;
    status: string | null;
};

export type RenterOption = {
    id: string;
    nameEn: string;
    nameAr: string | null;
    phone: string | null;
    email: string | null;
};

/** The server refuses a names call naming more than this many ids. */
const NAMES_CHUNK = 200;

/** GET `{path}?ids=a&ids=b…` in chunks of at most {@link NAMES_CHUNK}, concatenated in order. */
async function names<T>(path: string, ids: string[]): Promise<T[]> {
    const chunks: string[][] = [];
    for (let i = 0; i < ids.length; i += NAMES_CHUNK) chunks.push(ids.slice(i, i + NAMES_CHUNK));
    const pages = await Promise.all(
        chunks.map((chunk) => {
            const sp = new URLSearchParams();
            for (const id of chunk) sp.append("ids", id);
            return apiGet<T[]>(`${path}?${sp.toString()}`);
        }),
    );
    return pages.flat();
}

export const lookupApi = {
    searchUnits: (p: { q?: string; propertyId?: string; status?: string; limit?: number }) =>
        apiGet<UnitOption[]>(`/units/search${qs({ q: p.q?.trim(), propertyId: p.propertyId, status: p.status, limit: p.limit })}`),
    searchRenters: (p: { q?: string; limit?: number }) =>
        apiGet<RenterOption[]>(`/renters/search${qs({ q: p.q?.trim(), limit: p.limit })}`),
    unitNames: (ids: string[]) => names<UnitOption>("/units/names", ids),
    renterNames: (ids: string[]) => names<RenterOption>("/renters/names", ids),
};
