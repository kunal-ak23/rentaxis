import { describe, expect, it } from "vitest";
import { belowExpectedRent, wholeMonthsBetween } from "../belowExpectedRent";

describe("belowExpectedRent (#254)", () => {
    it("compares a year's contract rent with the asking rent", () => {
        expect(belowExpectedRent(60000, 54000, "2026-01-01", "2026-12-31")).toEqual({ expected: 60000, annual: 54000, gap: 6000 });
        expect(belowExpectedRent(60000, 60000, "2026-01-01", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(60000, 65000, "2026-01-01", "2026-12-31")).toBeNull();
    });

    // PR #396 review P2-1: whole-month terms at the asking rent never warn, whatever their day count.
    it.each([
        ["a 366-day year over a leap February", 60000, "2027-03-01", "2028-02-29"],
        ["calendar 2028", 60000, "2028-01-01", "2028-12-31"],
        ["Jul–Dec (184 days)", 30000, "2026-07-01", "2026-12-31"],
        ["Jan–Jun (181 days)", 30000, "2026-01-01", "2026-06-30"],
        ["two years across a leap year (731 days)", 120000, "2027-01-01", "2028-12-31"],
        ["one month", 5000, "2026-02-01", "2026-02-28"],
    ])("no warning at the asking rent: %s", (_label, rent, start, end) => {
        expect(belowExpectedRent(60000, rent, start, end)).toBeNull();
    });

    it("still warns on a whole-month term that is genuinely below", () => {
        expect(belowExpectedRent(60000, 59000, "2027-03-01", "2028-02-29")).toEqual({ expected: 60000, annual: 59000, gap: 1000 });
        expect(belowExpectedRent(60000, 27000, "2026-07-01", "2026-12-31")?.gap).toBe(6000);
        expect(belowExpectedRent(60000, 110000, "2027-01-01", "2028-12-31")?.gap).toBe(5000);
    });

    it("annualises an odd term by its days, with rounding tolerated", () => {
        // 2026-01-15..2026-12-31 is 351 days: 57,698.63 a year is at the asking rate.
        expect(belowExpectedRent(60000, 57698.63, "2026-01-15", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(60000, 50000, "2026-01-15", "2026-12-31")?.annual).toBeCloseTo(51994.3, 1);
        // A dirham or less is rounding.
        expect(belowExpectedRent(60000, 59999.5, "2026-01-01", "2026-12-31")).toBeNull();
    });

    it("says nothing without an asking rent, a rent or a valid term", () => {
        expect(belowExpectedRent(null, 1000, "2026-01-01", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(0, 1000, "2026-01-01", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(60000, 0, "2026-01-01", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(60000, 1000, "", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(60000, 1000, "2026-12-31", "2026-01-01")).toBeNull();
    });
});

describe("wholeMonthsBetween", () => {
    it("counts whole months, start day to the day before", () => {
        expect(wholeMonthsBetween("2027-03-01", "2028-02-29")).toBe(12);
        expect(wholeMonthsBetween("2026-07-01", "2026-12-31")).toBe(6);
        expect(wholeMonthsBetween("2026-01-15", "2026-02-14")).toBe(1);
        expect(wholeMonthsBetween("2026-01-15", "2026-12-31")).toBeNull();
        expect(wholeMonthsBetween("2026-01-31", "2026-02-27")).toBeNull();
    });
});
