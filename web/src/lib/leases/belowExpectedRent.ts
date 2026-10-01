/**
 * #254 (client request): a contract's rent below the unit's asking rent.
 *
 * `Unit.expectedRent` is a year's rent; the wizard takes the rent for the whole
 * term. Both are compared as annual rates — rent ÷ term days × 365 — so a
 * 6-month or 2-year contract is judged on the same footing as a 1-year one.
 * Half a dirham of rounding is not a shortfall. Null when there is nothing to
 * say (no asking rent, no rent yet, no valid term, or not below).
 */
export function belowExpectedRent(
    expected: number | null | undefined,
    rentForTerm: number,
    startDate: string,
    endDate: string,
): { expected: number; annual: number; gap: number } | null {
    if (typeof expected !== "number" || !(expected > 0) || !(rentForTerm > 0)) return null;
    const start = Date.parse(`${(startDate || "").slice(0, 10)}T00:00:00Z`);
    const end = Date.parse(`${(endDate || "").slice(0, 10)}T00:00:00Z`);
    if (Number.isNaN(start) || Number.isNaN(end) || end < start) return null;
    const days = Math.round((end - start) / 86_400_000) + 1;
    const annual = Math.round((rentForTerm / days) * 365 * 100) / 100;
    const gap = Math.round((expected - annual) * 100) / 100;
    return gap > 0.5 ? { expected, annual, gap } : null;
}
