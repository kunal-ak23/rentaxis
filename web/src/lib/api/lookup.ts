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

/**
 * Ids per names call. Spring/Tomcat's default max HTTP line (request line +
 * headers, which the proxy forwards including Authorization/cookies) is 8 KB.
 * A UUID plus its `ids=` and `&` separator is ~41 bytes, so 200 ids alone is
 * already ~8.2 KB before any headers — over budget, and confirmed to 400 on a
 * running backend. 80 ids keeps the query itself to ~3.3 KB, leaving headroom
 * for a long Authorization/cookie header on top of the rest of the request line.
 */
const NAMES_CHUNK = 80;

/** One chunk's names call succeeded (its rows) or failed (its ids, for retry). */
type NamesResult<T> = { rows: T[]; failedIds: string[] };

/**
 * GET `{path}?ids=a&ids=b…` in chunks of at most {@link NAMES_CHUNK}. Chunks run
 * independently (`Promise.allSettled`) so one rejected chunk does not drop the
 * rows of the others — the caller gets every row that came back plus the ids of
 * whichever chunks failed, to retry later.
 */
async function names<T>(path: string, ids: string[]): Promise<NamesResult<T>> {
    const chunks: string[][] = [];
    for (let i = 0; i < ids.length; i += NAMES_CHUNK) chunks.push(ids.slice(i, i + NAMES_CHUNK));
    const settled = await Promise.allSettled(
        chunks.map((chunk) => {
            const sp = new URLSearchParams();
            for (const id of chunk) sp.append("ids", id);
            return apiGet<T[]>(`${path}?${sp.toString()}`);
        }),
    );
    const rows: T[] = [];
    const failedIds: string[] = [];
    settled.forEach((result, i) => {
        if (result.status === "fulfilled") rows.push(...result.value);
        else failedIds.push(...chunks[i]);
    });
    return { rows, failedIds };
}

export const lookupApi = {
    searchUnits: (p: { q?: string; propertyId?: string; status?: string; limit?: number }) =>
        apiGet<UnitOption[]>(`/units/search${qs({ q: p.q?.trim(), propertyId: p.propertyId, status: p.status, limit: p.limit })}`),
    searchRenters: (p: { q?: string; limit?: number }) =>
        apiGet<RenterOption[]>(`/renters/search${qs({ q: p.q?.trim(), limit: p.limit })}`),
    unitNames: (ids: string[]) => names<UnitOption>("/units/names", ids),
    renterNames: (ids: string[]) => names<RenterOption>("/renters/names", ids),
};
