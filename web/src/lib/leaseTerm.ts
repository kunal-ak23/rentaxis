/**
 * End-date-inclusive whole-month count for a lease term.
 *
 * Mirrors the backend's DateMath.monthsInclusive, which is what the payment
 * schedule is built from. The wizard previously used
 *
 *     (endYear - startYear) * 12 + (endMonth - startMonth) + 1
 *
 * which ignores the day of the month. That agrees for a lease ending on the
 * last day of a month — 1 Jan → 31 Dec is 12 either way — and is wrong by a
 * whole month for anything else: 3 Oct 2026 → 3 Oct 2027 came out as 13, so
 * the lease was submitted with a 13-month total while its cheques covered 12.
 *
 * A lease end date is the last day of the tenancy, so the count runs to the
 * day after it. Floors at 1 so a same-day term does not produce zero.
 */
export function monthsInclusive(startDate: string, endDate: string): number {
    // Parsed as local midnight: `new Date("2026-10-03")` is UTC midnight, which
    // is the previous day in any negative-offset timezone.
    const start = new Date(`${startDate}T00:00:00`);
    const end = new Date(`${endDate}T00:00:00`);
    if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return 1;

    end.setDate(end.getDate() + 1);
    let months = (end.getFullYear() - start.getFullYear()) * 12 + (end.getMonth() - start.getMonth());
    // Not a whole month yet if the day has not come round.
    if (end.getDate() < start.getDate()) months -= 1;
    return Math.max(months, 1);
}

/** Longest term a contract may run — the backend's LeaseService.MAX_TERM_YEARS. */
export const MAX_TERM_YEARS = 50;
/** Terms longer than this ask the operator to confirm (a typo'd year is likelier than a 6-year lease). */
export const CONFIRM_TERM_YEARS = 5;

/**
 * `iso` (YYYY-MM-DD) plus `years`, as java.time's plusYears: a 29 February
 * that lands in a common year becomes the 28th.
 */
export function addYearsIso(iso: string, years: number): string {
    const [y, m, d] = iso.split("-").map(Number);
    const year = y + years;
    const lastDay = new Date(year, m, 0).getDate();
    const pad = (n: number, w = 2) => String(n).padStart(w, "0");
    return `${pad(year, 4)}-${pad(m)}-${pad(Math.min(d, lastDay))}`;
}

/**
 * Whether an end-inclusive term runs longer than `years` years — the same
 * rule as the backend: 01/06/2026 → 31/05/2076 is exactly 50 years; one more
 * day is over. Missing or malformed dates are left to the other checks.
 */
export function termExceedsYears(startDate: string, endDate: string, years: number): boolean {
    const iso = /^\d{4}-\d{2}-\d{2}$/;
    if (!iso.test(startDate) || !iso.test(endDate)) return false;
    return endDate >= addYearsIso(startDate, years);
}

/** Whole years in an end-inclusive term (1 Jun 2026 → 31 May 2032 is 6). */
export function termYears(startDate: string, endDate: string): number {
    return Math.floor(monthsInclusive(startDate, endDate) / 12);
}
