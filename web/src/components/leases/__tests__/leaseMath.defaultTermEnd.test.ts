import { describe, expect, it } from "vitest";
import { defaultTermEnd, sameTermEnd, termEnd } from "../leaseMath";

/**
 * Review of R4-B, M5: a new contract's default end is the day before the
 * anniversary of its start. The anniversary of 29 February is 1 March, so a term
 * from 29/02/2028 ends 28/02/2029 (365 days; it used to end 27/02).
 */
describe("defaultTermEnd", () => {
    it("is the day before the anniversary", () => {
        expect(defaultTermEnd("2026-09-09")).toBe("2027-09-08");
        expect(defaultTermEnd("2027-01-31")).toBe("2028-01-30");
        expect(defaultTermEnd("2027-03-01")).toBe("2028-02-29");
        expect(defaultTermEnd("2028-03-01")).toBe("2029-02-28");
    });
    it("ends a term from 28 February on 27 February", () => {
        expect(defaultTermEnd("2027-02-28")).toBe("2028-02-27");
        expect(defaultTermEnd("2028-02-28")).toBe("2029-02-27");
    });
    it("ends a term from 29 February on 28 February", () => {
        expect(defaultTermEnd("2028-02-29")).toBe("2029-02-28");
    });
    it("rolls a month-end start the target month lacks to that month's last day", () => {
        expect(termEnd("2027-01-31", 1)).toBe("2027-02-28");
        expect(termEnd("2027-01-15", 1)).toBe("2027-02-14");
    });
});

describe("sameTermEnd over a 29 February term", () => {
    it("renews a 12-month term from 29/02 as 12 months", () => {
        expect(sameTermEnd("2028-02-29", "2029-02-28", "2029-03-01")).toBe("2030-02-28");
        expect(sameTermEnd("2027-03-01", "2028-02-29", "2028-03-01")).toBe("2029-02-28");
    });
});
