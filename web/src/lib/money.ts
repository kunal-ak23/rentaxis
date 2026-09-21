/**
 * Decimal-safe arithmetic for money on screen.
 *
 * AED amounts are held as JavaScript numbers all the way from the JSON to the
 * DOM, which is fine for one figure and wrong for a column of them: forty rows
 * of a trial balance summed with `reduce((a, b) => a + b)` come back as
 * 8.200000000000001, and a grid that reports a difference of 1e-15 costs an
 * accountant an afternoon looking for it.
 *
 * So every running total goes through here, where the addition happens in
 * integer fils and the result comes back to two decimals exactly once. The
 * magnitudes involved are nowhere near 2^53 — the whole UAE property market in
 * fils is not — so the integer step is exact.
 */

/**
 * Two decimals, HALF_UP — the shape `BigDecimal.setScale(2, HALF_UP)` gives.
 *
 * `Number.EPSILON` scaled by the magnitude nudges a value that binary floating
 * point has stored a hair BELOW its decimal .xx5 (100.10 * 5 / 100 is
 * 5.00499999…) back onto the boundary, so `Math.round` takes it up exactly where
 * HALF_UP does. The nudge tracks the actual ULP error bound at each magnitude
 * rather than being a fixed epsilon, which is why it holds at eight-digit
 * amounts and not only in the textbook case; it cannot move a value that is not
 * already sitting on the boundary.
 *
 * This is the definition `VoucherMath.vat` is mirrored against — it lives here
 * rather than in `api/vouchers.ts` so the app has exactly one of it.
 */
export function round2(n: number): number {
    if (!Number.isFinite(n)) return 0;
    return Math.round((n + Math.sign(n) * Math.abs(n) * Number.EPSILON) * 100) / 100;
}

/** An amount as whole fils. Exact for every figure a ledger will ever hold. */
export function toFils(amount: number): number {
    if (!Number.isFinite(amount)) return 0;
    return Math.round(amount * 100 + Math.sign(amount) * Math.abs(amount) * Number.EPSILON * 100);
}

/** Fils back to an amount. The single division at the end of a sum, not once per row. */
export function fromFils(fils: number): number {
    return fils / 100;
}

/**
 * The total of a column, added in fils.
 *
 * Values that are not finite are skipped rather than propagated: an empty or
 * half-typed grid cell parses to NaN, and one of those must not turn the whole
 * footer into "NaN".
 */
export function sumAmounts(amounts: number[]): number {
    let fils = 0;
    for (const a of amounts) fils += toFils(a);
    return fromFils(fils);
}

/** `a − b`, in fils, so two columns that agree read exactly 0 rather than 1e-15. */
export function differenceOf(a: number, b: number): number {
    return fromFils(toFils(a) - toFils(b));
}

/**
 * Is this figure zero to the fil?
 *
 * Written as a fils comparison rather than `Math.abs(n) < 0.005` so that
 * "balanced" means the same thing here as it does in the totals above.
 */
export function isZeroAmount(n: number): boolean {
    return toFils(n) === 0;
}
