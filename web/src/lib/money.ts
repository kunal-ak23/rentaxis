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

/** Anything a grid cell or a JSON field might hand us for an amount. */
export type AmountLike = number | string | null | undefined;

/**
 * Optional grouping, then digits, then an optional decimal part — or a bare
 * decimal part (".5"). Rejects "1.2.3", "12a", "--1" and the empty string.
 */
const AMOUNT_RE = /^[+-]?(?:\d{1,3}(?:,\d{3})*|\d*)(?:\.\d*)?$/;

/**
 * An amount as fils, parsed EXACTLY, or null when the value is not an amount.
 *
 * Why a string parser rather than `Number(value) * 100`: the multiply is where
 * the fractions go wrong (8.29 * 100 is 828.9999999999999), and `Number.isFinite`
 * — the old guard — does not coerce, so `Number.isFinite("12.34")` is false and
 * a perfectly good numeric string came back as **zero**. Conflating "not a
 * number" with "zero" on a ledger screen is how a figure disappears without
 * anybody being told.
 *
 * So the digits are split on the decimal point and assembled as integers, and
 * anything unreadable returns null for the caller to deal with.
 */
export function toFils(value: AmountLike): number | null {
    if (value === null || value === undefined) return null;

    if (typeof value === "number") {
        if (!Number.isFinite(value)) return null;
        // A number has already lost whatever precision it was going to lose, so
        // the epsilon-nudged round is the right tool here; the string path below
        // never needs it.
        return Math.round(value * 100 + Math.sign(value) * Math.abs(value) * Number.EPSILON * 100);
    }

    const text = value.trim();
    if (!text || !AMOUNT_RE.test(text)) return null;

    const sign = text.startsWith("-") ? -1 : 1;
    const unsigned = text.replace(/^[+-]/, "").replace(/,/g, "");
    const [whole = "", fraction = ""] = unsigned.split(".");
    // "." alone passes the shape test but names no digits at all.
    if (!whole && !fraction) return null;

    // Two decimal places, HALF_UP on the third — the same rounding
    // `BigDecimal.setScale(2, HALF_UP)` applies server-side.
    const fils = Number(whole || "0") * 100 + Number((fraction + "00").slice(0, 2) || "0");
    const roundUp = Number(fraction[2] ?? "0") >= 5;
    return sign * (fils + (roundUp ? 1 : 0));
}

/**
 * The value as a 2dp number, or null when it is not an amount. The parse every
 * caller wants when it needs to tell "blank or broken" from "zero".
 */
export function parseAmount(value: AmountLike): number | null {
    const fils = toFils(value);
    return fils === null ? null : fromFils(fils);
}

/** Fils back to an amount. The single division at the end of a sum, not once per row. */
export function fromFils(fils: number): number {
    return fils / 100;
}

/**
 * The total of a column, added in fils.
 *
 * Values that are not amounts are skipped rather than propagated: a blank or
 * half-typed grid cell must not turn the whole footer into "NaN". Use
 * {@link sumAmountsChecked} where the caller has to KNOW that something was
 * skipped — before a save, say — rather than quietly showing a total that
 * omits a row.
 */
export function sumAmounts(amounts: AmountLike[]): number {
    return sumAmountsChecked(amounts).total;
}

/**
 * The same total, plus how many entries could not be read as an amount.
 *
 * A blank cell is NOT counted: an untouched row is the ordinary state of a grid,
 * not a problem. Only genuinely unreadable text ("abc", "1.2.3") is.
 */
export function sumAmountsChecked(amounts: AmountLike[]): { total: number; invalid: number } {
    let fils = 0;
    let invalid = 0;
    for (const a of amounts) {
        const f = toFils(a);
        if (f === null) {
            // Blank is empty, not broken.
            const blank = a === null || a === undefined || (typeof a === "string" && !a.trim());
            if (!blank) invalid++;
            continue;
        }
        fils += f;
    }
    return { total: fromFils(fils), invalid };
}

/** `a − b`, in fils, so two columns that agree read exactly 0 rather than 1e-15. */
export function differenceOf(a: AmountLike, b: AmountLike): number {
    return fromFils((toFils(a) ?? 0) - (toFils(b) ?? 0));
}

/**
 * Is this figure zero to the fil?
 *
 * Written as a fils comparison rather than `Math.abs(n) < 0.005` so that
 * "balanced" means the same thing here as it does in the totals above.
 */
export function isZeroAmount(n: AmountLike): boolean {
    return toFils(n) === 0;
}
