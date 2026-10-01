import { describe, expect, it } from "vitest";
import { chequeMonths } from "../leaseMath";

/**
 * Review of R4-B, I1: the wizard's cheque count and its cap are the generator's month count —
 *   whole months (rounded down) from the first due date, or the start, to the end
 *   (`DateMath.monthsInclusive`) — so the default never puts two cheques on a date.
 */
describe("chequeMonths (the generator's month count)", () => {
    it("is 12 on the default 12-month term", () => {
        expect(chequeMonths("2026-09-09", "2027-09-08")).toBe(12);
        expect(chequeMonths("2028-02-29", "2029-02-28")).toBe(12);
        expect(chequeMonths("2027-01-31", "2028-01-30")).toBe(12);
    });
    it("rounds a part month down", () => {
        expect(chequeMonths("2026-09-09", "2027-03-20")).toBe(6);
    });
    it("counts from a first due date after the start", () => {
        expect(chequeMonths("2026-09-15", "2027-09-08")).toBe(11);
    });
    it("is at least 1", () => {
        expect(chequeMonths("2026-09-09", "2026-09-20")).toBe(1);
        expect(chequeMonths("", "")).toBe(1);
    });
});
