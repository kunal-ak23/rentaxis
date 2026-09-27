import { lookupApi, type RenterOption } from "@/lib/api/lookup";

/**
 * Break round 1 (controller ruling): the backend keeps accepting two renters
 * with the same email, so the Add Tenant form warns before creating one whose
 * email or phone already belongs to a renter in the organisation. The check
 * reuses the bounded server search (`GET /renters/search`, tenant- and
 * property-scoped server-side) — the exact comparison happens here.
 */

export type DuplicateMatch = { renter: RenterOption; by: ("email" | "phone")[] };

/** Emails compare case-insensitively and trimmed. */
export const normaliseEmail = (email: string | null | undefined) => (email ?? "").trim().toLowerCase();

/** Only the digits of a phone number ("+971 50-123 4567" → "971501234567"). */
export const phoneDigits = (phone: string | null | undefined) => (phone ?? "").replace(/\D/g, "");

const UAE = "971";

/**
 * A phone reduced to a comparable form. A UAE number (written +971 …, 00971 …,
 * 971 …, or with the trunk 0 as 0X …) becomes its national number — "501234567"
 * for a mobile, "41234567" for a Dubai landline. A foreign number keeps its
 * country code behind a "+" (so +44 … and 0044 … agree, and neither equals a
 * UAE number). Anything else is its bare digits.
 */
export function nationalNumber(phone: string | null | undefined): string {
    const raw = (phone ?? "").trim();
    const digits = phoneDigits(raw);
    if (!digits) return "";
    let international: string | null = null;
    if (raw.startsWith("+")) international = digits;
    else if (digits.startsWith("00")) international = digits.slice(2);
    // "971501234567" typed without the +: long enough to hold a country code.
    else if (digits.startsWith(UAE) && digits.length >= 11) international = digits;
    if (international !== null) {
        if (!international.startsWith(UAE)) return `+${international}`;
        return international.slice(UAE.length).replace(/^0/, ""); // "+971 (0) 4 …"
    }
    return digits.replace(/^0/, "");
}

/**
 * Two phones are the same number when their national numbers (see
 * {@link nationalNumber}) are equal, so "+971 50 123 4567", "00971501234567"
 * and "050-123-4567" match, as do "+971 4 123 4567" and "04 123 4567". A short
 * fragment only matches itself (a 4-digit extension is not a mobile number).
 */
export function phonesMatch(a: string | null | undefined, b: string | null | undefined): boolean {
    const na = nationalNumber(a);
    const nb = nationalNumber(b);
    return na !== "" && na === nb;
}

/** Server search page size: the lookup endpoint's own maximum. */
const SEARCH_LIMIT = 50;

/**
 * The existing renters (visible to the caller) sharing the given email or
 * phone. The phone is searched by its last four digits — contiguous in every
 * common way of writing a number — and then compared digits-normalised.
 */
export async function findDuplicateRenters(input: { email: string; phone: string }): Promise<DuplicateMatch[]> {
    const email = normaliseEmail(input.email);
    const digits = phoneDigits(input.phone);
    const searches: Promise<RenterOption[]>[] = [];
    if (email) searches.push(lookupApi.searchRenters({ q: email, limit: SEARCH_LIMIT }));
    if (digits.length >= 4) searches.push(lookupApi.searchRenters({ q: digits.slice(-4), limit: SEARCH_LIMIT }));
    if (searches.length === 0) return [];

    const byId = new Map<string, DuplicateMatch>();
    for (const rows of await Promise.all(searches)) {
        for (const r of rows) {
            if (byId.has(r.id)) continue;
            const by: DuplicateMatch["by"] = [];
            if (email && normaliseEmail(r.email) === email) by.push("email");
            if (digits && phonesMatch(r.phone, input.phone)) by.push("phone");
            if (by.length > 0) byId.set(r.id, { renter: r, by });
        }
    }
    return [...byId.values()];
}
