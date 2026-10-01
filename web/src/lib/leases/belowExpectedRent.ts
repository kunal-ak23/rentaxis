/**
 * #254 (client request): a contract's rent below the unit's asking rent.
 *
 * `Unit.expectedRent` is a year's rent; the wizard takes the rent for the whole
 * term. Both are compared as annual rates:
 * - a term of whole months (start day to the day before the same day N months
 *   on — 12 months over a leap February, Jul–Dec, two years) is rent ÷ N × 12,
 *   so a contract at exactly the asking rent never reads as "below" because its
 *   year has 366 days or its half-year 184;
 * - any other term is rent ÷ its days × 365.
 * A gap of 1 AED or less is rounding, not a shortfall. Null when there is
 * nothing to say (no asking rent, no rent yet, no valid term, or not below).
 */
export const BELOW_RENT_TOLERANCE = 1;

const DAY = 86_400_000;

function parse(iso: string): number {
    return Date.parse(`${(iso || "").slice(0, 10)}T00:00:00Z`);
}

/** N when `end` is the day before `start` + N months (N ≥ 1), else null. */
export function wholeMonthsBetween(startDate: string, endDate: string): number | null {
    const start = parse(startDate);
    const end = parse(endDate);
    if (Number.isNaN(start) || Number.isNaN(end) || end < start) return null;
    const s = new Date(start);
    const [y, m, d] = [s.getUTCFullYear(), s.getUTCMonth(), s.getUTCDate()];
    for (let n = 1; n <= 600; n++) {
        const next = new Date(Date.UTC(y, m + n, d));
        // A start on the 29th–31st overflows a short month; such a term is not whole months.
        if (next.getUTCDate() !== d) continue;
        const last = next.getTime() - DAY;
        if (last === end) return n;
        if (last > end) return null;
    }
    return null;
}

export function belowExpectedRent(
    expected: number | null | undefined,
    rentForTerm: number,
    startDate: string,
    endDate: string,
): { expected: number; annual: number; gap: number } | null {
    if (typeof expected !== "number" || !(expected > 0) || !(rentForTerm > 0)) return null;
    const start = parse(startDate);
    const end = parse(endDate);
    if (Number.isNaN(start) || Number.isNaN(end) || end < start) return null;
    const months = wholeMonthsBetween(startDate, endDate);
    const days = Math.round((end - start) / DAY) + 1;
    const rate = months ? (rentForTerm / months) * 12 : (rentForTerm / days) * 365;
    const annual = Math.round(rate * 100) / 100;
    const gap = Math.round((expected - annual) * 100) / 100;
    return gap > BELOW_RENT_TOLERANCE ? { expected, annual, gap } : null;
}
