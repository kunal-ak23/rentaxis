import { describe, expect, it } from "vitest";
import { differenceOf, round2, sumAmounts, toFils } from "@/lib/money";

/**
 * The arithmetic behind every running total on a finance screen.
 *
 * Summing AED as plain floats is how a grid of forty rows ends up reporting a
 * difference of 0.000000000001 and an accountant spends an afternoon looking for
 * it. These helpers work in integer fils and come back to a 2dp number once, at
 * the end — and `round2` is the same HALF_UP rounding `VoucherMath.vat` uses,
 * moved here so there is exactly one definition of it in the app.
 */

describe("round2", () => {
    it("rounds HALF_UP to two decimals, including the .xx5 boundary", () => {
        expect(round2(5.005)).toBe(5.01);
        expect(round2(1234.567)).toBe(1234.57);
        expect(round2(-5.005)).toBe(-5.01);
        expect(round2(0)).toBe(0);
    });

    /** 100.10 * 5 / 100 is stored as 5.00499999…, which naive rounding takes DOWN. */
    it("survives a value binary floating point stores just below the boundary", () => {
        expect(round2((100.1 * 5) / 100)).toBe(5.01);
    });
});

describe("toFils", () => {
    it("converts to integer fils without drift", () => {
        expect(toFils(0.1)).toBe(10);
        expect(toFils(0.29)).toBe(29);
        expect(toFils(1234.56)).toBe(123456);
        expect(toFils(-0.07)).toBe(-7);
        expect(Number.isInteger(toFils(19.99))).toBe(true);
    });
});

describe("sumAmounts", () => {
    it("adds the classic float-error cases exactly", () => {
        expect(sumAmounts([0.1, 0.2])).toBe(0.3);
        expect(sumAmounts([0.1, 0.2, 0.3])).toBe(0.6);
    });

    /**
     * Each value is rounded to the fil BEFORE it is added, which is what the
     * server does: `OpeningBalanceService.scale` puts every stored row through
     * `setScale(2, HALF_UP)` and only then sums them. So 1.005 + 2.005 is
     * 1.01 + 2.01 = 3.02, not round(3.010) = 3.01 — and the footer agrees with
     * the column above it, which is the property that matters on a grid.
     */
    it("rounds each value to the fil before adding, as the server does", () => {
        // 1.005 -> 1.01 and 2.005 -> 2.01, so the total is 3.02 and not
        // round(3.010) = 3.01. Asserted as a literal on purpose: writing it as
        // `round2(1.005) + round2(2.005)` would be a float sum, which is the
        // thing this helper exists to avoid (it evaluates to 3.0199999999999996).
        expect(sumAmounts([1.005, 2.005])).toBe(3.02);
    });

    it("is empty-safe and order-independent", () => {
        expect(sumAmounts([])).toBe(0);
        expect(sumAmounts([1.15, 2.25, 3.35])).toBe(sumAmounts([3.35, 1.15, 2.25]));
    });

    /**
     * Forty rows of a real trial balance. Plain `reduce((a, b) => a + b)` on this
     * set does not come back to a 2dp number; this must.
     */
    it("keeps a long column of fils exact", () => {
        const rows = Array.from({ length: 40 }, (_, i) => 0.01 * (i + 1));
        expect(sumAmounts(rows)).toBe(8.2);
        expect(sumAmounts(Array.from({ length: 100 }, () => 0.07))).toBe(7);
    });

    it("ignores values that are not finite rather than poisoning the total", () => {
        expect(sumAmounts([1.5, Number.NaN, 2.5])).toBe(4);
    });
});

describe("differenceOf", () => {
    it("subtracts in fils so a balanced column reads exactly zero", () => {
        expect(differenceOf(0.3, sumAmounts([0.1, 0.2]))).toBe(0);
        expect(differenceOf(1000.1, 1000)).toBe(0.1);
        expect(differenceOf(1000, 1000.1)).toBe(-0.1);
    });
});
