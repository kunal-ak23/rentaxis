import { describe, expect, it } from "vitest";
import { MONEY_MAX, MONEY_MAX_12_2, parseMoneyInput } from "@/lib/money";

/**
 * Break-it round 1 (money) F1–F3: the one parse every money field uses. It never
 * rounds, never guesses at a comma, and reads Arabic-Indic digits as the digits
 * they are — or refuses, with a reason the field can show.
 */
describe("parseMoneyInput", () => {
    it("reads plain amounts, and grouping the way the app prints it", () => {
        expect(parseMoneyInput("1000")).toEqual({ ok: true, value: 1000 });
        expect(parseMoneyInput("1000.5")).toEqual({ ok: true, value: 1000.5 });
        expect(parseMoneyInput("1000.55")).toEqual({ ok: true, value: 1000.55 });
        expect(parseMoneyInput("1,000")).toEqual({ ok: true, value: 1000 });
        expect(parseMoneyInput("1,234,567.89")).toEqual({ ok: true, value: 1234567.89 });
        expect(parseMoneyInput(" 250 ")).toEqual({ ok: true, value: 250 });
        expect(parseMoneyInput(".5")).toEqual({ ok: true, value: 0.5 });
        expect(parseMoneyInput("1000.500")).toEqual({ ok: true, value: 1000.5 });
    });

    it("treats an empty field as no amount, not zero and not an error", () => {
        expect(parseMoneyInput("")).toEqual({ ok: true, value: null });
        expect(parseMoneyInput("   ")).toEqual({ ok: true, value: null });
    });

    it("refuses a third decimal instead of rounding it", () => {
        expect(parseMoneyInput("1000.555")).toEqual({ ok: false, error: "decimals" });
        expect(parseMoneyInput("15000.555")).toEqual({ ok: false, error: "decimals" });
        expect(parseMoneyInput("100.004")).toEqual({ ok: false, error: "decimals" });
    });

    it("refuses less than one fil where the amount must be positive", () => {
        expect(parseMoneyInput("0.001")).toEqual({ ok: false, error: "min" });
        expect(parseMoneyInput("0.001", { allowZero: true })).toEqual({ ok: false, error: "decimals" });
        expect(parseMoneyInput("0")).toEqual({ ok: false, error: "min" });
        expect(parseMoneyInput("0.00")).toEqual({ ok: false, error: "min" });
        expect(parseMoneyInput("0.01")).toEqual({ ok: true, value: 0.01 });
        expect(parseMoneyInput("0", { allowZero: true })).toEqual({ ok: true, value: 0 });
    });

    it("refuses negatives unless the field allows them", () => {
        expect(parseMoneyInput("-500")).toEqual({ ok: false, error: "negative" });
        expect(parseMoneyInput("-500", { allowZero: true })).toEqual({ ok: false, error: "negative" });
        expect(parseMoneyInput("-500", { allowNegative: true })).toEqual({ ok: true, value: -500 });
    });

    it("refuses an amount the ledger cannot hold", () => {
        expect(parseMoneyInput("999999999999.99")).toEqual({ ok: true, value: MONEY_MAX });
        expect(parseMoneyInput("1000000000000")).toEqual({ ok: false, error: "max" });
        expect(parseMoneyInput("99999999999999")).toEqual({ ok: false, error: "max" });
    });

    it("refuses anything that is not plainly an amount", () => {
        for (const raw of ["1e12", "AED 5,000", "5,000 AED", "12a", "1.2.3", "--1", "+5", "1 000", ".", "Infinity", "NaN", "0x10"]) {
            expect(parseMoneyInput(raw), raw).toEqual({ ok: false, error: "format" });
        }
    });

    it("refuses a comma that is not thousands grouping, rather than guessing it is a decimal point", () => {
        for (const raw of ["1,5", "1,00", "12,34", "1,0000", ",100", "100,", "1,000.5,0"]) {
            expect(parseMoneyInput(raw), raw).toEqual({ ok: false, error: "format" });
        }
    });

    it("reads Arabic-Indic and Persian digits as the same digits", () => {
        expect(parseMoneyInput("١٢٣")).toEqual({ ok: true, value: 123 });
        expect(parseMoneyInput("١٢٣٤")).toEqual({ ok: true, value: 1234 });
        expect(parseMoneyInput("١٬٢٣٤٫٥٠")).toEqual({ ok: true, value: 1234.5 });
        expect(parseMoneyInput("۱۲۳")).toEqual({ ok: true, value: 123 });
        expect(parseMoneyInput("١٢٣٫٤٥٦")).toEqual({ ok: false, error: "decimals" });
    });

    it("refuses beyond a caller-supplied ceiling, for a field on a narrower column", () => {
        expect(parseMoneyInput("9999999999.99", { max: MONEY_MAX_12_2 }))
            .toEqual({ ok: true, value: MONEY_MAX_12_2 });
        expect(parseMoneyInput("10000000000.00", { max: MONEY_MAX_12_2 }))
            .toEqual({ ok: false, error: "max" });
        // Within the ledger-wide max but past this field's own, narrower one.
        expect(parseMoneyInput("999999999999.99", { max: MONEY_MAX_12_2 }))
            .toEqual({ ok: false, error: "max" });
    });

    it("takes a number as well as text", () => {
        expect(parseMoneyInput(1000.55)).toEqual({ ok: true, value: 1000.55 });
        expect(parseMoneyInput(1000.555)).toEqual({ ok: false, error: "decimals" });
        expect(parseMoneyInput(Number.NaN)).toEqual({ ok: false, error: "format" });
        expect(parseMoneyInput(null)).toEqual({ ok: true, value: null });
    });
});
