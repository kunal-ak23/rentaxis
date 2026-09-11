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
