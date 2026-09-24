import { describe, expect, it } from "vitest";
import { lineVatOf, vatFilsOf } from "../settlementMath";

/** The server's rule in exact integers: fils × 5 / 100, HALF_UP (BigDecimal.setScale(2, HALF_UP)). */
function serverVatFils(fils: bigint): bigint {
    return (fils * BigInt(5) + BigInt(50)) / BigInt(100);
}

const rule = { vatRate: 0.05, vatableCategories: ["PROPERTY_DAMAGE" as const] };

describe("VAT on a recharge, in whole fils (F14-61 R1 P2-1)", () => {
    it.each([
        [80.3, 4.02], [82.1, 4.11], [85.3, 4.27], [0.1, 0.01], [400, 20], [0.3, 0.02],
    ])("%s → %s", (amount, vat) => {
        expect(lineVatOf({ type: "DEDUCTION", category: "PROPERTY_DAMAGE", amount }, rule)).toBe(vat);
    });

    it("matches the server on every amount from 0.10 to 100,000.00 that lands on half a fil", () => {
        const wrong: string[] = [];
        // fils × 5 ends in 50 exactly when fils ≡ 10 (mod 20).
        for (let fils = 10; fils <= 10_000_000; fils += 20) {
            const amount = Number((fils / 100).toFixed(2));
            if (vatFilsOf(amount, 0.05) !== Number(serverVatFils(BigInt(fils)))) wrong.push(amount.toFixed(2));
            if (wrong.length > 5) break;
        }
        expect(wrong).toEqual([]);
    });

    it("gives no VAT outside the VAT-able categories or on a non-VAT lease", () => {
        expect(lineVatOf({ type: "DEDUCTION", category: "UTILITY_ARREARS", amount: 80.3 }, rule)).toBe(0);
        expect(lineVatOf({ type: "DEDUCTION", category: "PROPERTY_DAMAGE", amount: 80.3 }, { vatRate: 0 })).toBe(0);
    });
});
