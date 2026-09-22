import { describe, expect, it } from "vitest";
import {
    TYPEABLE_MODES,
    chequeRowErrors,
    chequeRowIsValid,
    chequeRowsAreValid,
    chequeRowsErrors,
} from "../chequeRowRules";

/**
 * The client mirror of `ChequeRowRules.validateRow`, exercised as a pure
 * function the way `registerActionsFor` is — one table of cases per Java
 * branch, so a screen that routes its submit gate through this cannot offer a
 * row the server refuses.
 */
describe("chequeRowErrors", () => {
    it("accepts a complete PDC row", () => {
        expect(
            chequeRowErrors({ mode: "PDC", amount: 1000, chequeDate: "2026-06-01", chequeNumber: "000101" }),
        ).toEqual([]);
        expect(chequeRowIsValid({ mode: "PDC", amount: 1000, chequeDate: "2026-06-01" })).toBe(true);
    });

    it("refuses ONLINE on every user-facing door (ChequeRowRules.java:130-135)", () => {
        expect(chequeRowErrors({ mode: "ONLINE", amount: 1000, chequeDate: "2026-06-01" })).toContainEqual({
            code: "onlineNotTyped",
        });
        expect(TYPEABLE_MODES).toEqual(["PDC", "CASH", "TRANSFER"]);
    });

    it("refuses a zero or negative amount (:137-139)", () => {
        expect(chequeRowErrors({ mode: "PDC", amount: 0, chequeDate: "2026-06-01" })).toContainEqual({
            code: "amountPositive",
        });
        expect(chequeRowErrors({ mode: "PDC", amount: -1, chequeDate: "2026-06-01" })).toContainEqual({
            code: "amountPositive",
        });
        expect(chequeRowErrors({ mode: "PDC", chequeDate: "2026-06-01" })).toContainEqual({ code: "amountPositive" });
    });

    it("requires chequeDate for EVERY mode, not only PDC (:140-144)", () => {
        expect(chequeRowErrors({ mode: "PDC", amount: 10 })).toContainEqual({ code: "pdcDateRequired" });
        expect(chequeRowErrors({ mode: "CASH", amount: 10 })).toContainEqual({ code: "receiptDateRequired" });
        expect(chequeRowErrors({ mode: "TRANSFER", amount: 10, chequeDate: "" })).toContainEqual({
            code: "receiptDateRequired",
        });
    });

    it("treats a missing mode as PDC, exactly as the server does (:129)", () => {
        expect(chequeRowErrors({ amount: 10 })).toContainEqual({ code: "pdcDateRequired" });
    });

    it("refuses a cheque number on a non-PDC row (:147-151)", () => {
        expect(
            chequeRowErrors({ mode: "CASH", amount: 10, chequeDate: "2026-06-01", chequeNumber: "000101" }),
        ).toContainEqual({ code: "numberOnlyOnPdc" });
        // Blank is not a number — `blankToNull` (:159).
        expect(chequeRowErrors({ mode: "CASH", amount: 10, chequeDate: "2026-06-01", chequeNumber: "  " })).toEqual([]);
    });

    it("refuses a number already taken on the lease (:152-155)", () => {
        expect(
            chequeRowErrors({ mode: "PDC", amount: 10, chequeDate: "2026-06-01", chequeNumber: "000101" }, ["000101"]),
        ).toContainEqual({ code: "numberTaken", number: "000101" });
    });
});

describe("chequeRowsErrors", () => {
    it("collides two rows of the same payload on one number, like the shared seenNumbers set", () => {
        const rows = [
            { mode: "PDC" as const, amount: 10, chequeDate: "2026-06-01", chequeNumber: "000101" },
            { mode: "PDC" as const, amount: 10, chequeDate: "2026-07-01", chequeNumber: "000101" },
        ];
        const errors = chequeRowsErrors(rows);
        expect(errors[0]).toEqual([]);
        expect(errors[1]).toContainEqual({ code: "numberTaken", number: "000101" });
        expect(chequeRowsAreValid(rows)).toBe(false);
    });

    it("passes a payload whose numbers are distinct and unheld", () => {
        const rows = [
            { mode: "PDC" as const, amount: 10, chequeDate: "2026-06-01", chequeNumber: "000101" },
            { mode: "CASH" as const, amount: 10, chequeDate: "2026-07-01" },
        ];
        expect(chequeRowsAreValid(rows)).toBe(true);
    });

    it("refuses an empty payload — the server requires at least one row", () => {
        expect(chequeRowsAreValid([])).toBe(false);
    });
});
