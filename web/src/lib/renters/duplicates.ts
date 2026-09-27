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

/** A UAE number's national significant number is 9 digits (5X XXX XXXX). */
const NATIONAL_DIGITS = 9;

/**
 * Two phones are the same number when their digits are equal, or — both being
 * full numbers — when their last 9 digits are, so "+971 50 123 4567",
 * "00971501234567" and "050-123-4567" all match. Short fragments compare
 * exactly (a 4-digit extension is not "the same phone" as a mobile number).
 */
export function phonesMatch(a: string | null | undefined, b: string | null | undefined): boolean {
    const da = phoneDigits(a);
    const db = phoneDigits(b);
    if (!da || !db) return false;
    if (da === db) return true;
    if (da.length < NATIONAL_DIGITS || db.length < NATIONAL_DIGITS) return false;
    return da.slice(-NATIONAL_DIGITS) === db.slice(-NATIONAL_DIGITS);
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
