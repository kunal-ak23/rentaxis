import { describe, expect, it } from "vitest";

import { monthsInclusive } from "../leaseTerm";

/**
 * Every expected value below was taken from the backend's
 * DateMath.monthsInclusive, run over the same inputs. The schedule is built
 * from that count, so any disagreement means the lease states one total while
 * its cheques add up to another — which is the defect this replaced.
 */

describe("monthsInclusive", () => {
    const CASES: [string, string, number][] = [
        // The reported lease. The old formula said 13.
        ["2026-10-03", "2027-10-03", 12],
        // A year ending the day before, the more conventional way to write it.
        ["2026-10-03", "2027-10-02", 12],
        // Month-end terms, where the old formula happened to agree — which is
        // why this went unnoticed for so long.
        ["2026-01-01", "2026-12-31", 12],
        ["2026-06-01", "2026-12-31", 7],
        ["2026-02-01", "2027-01-31", 12],
        // Mid-month terms, where it did not.
        ["2026-03-15", "2026-09-14", 6],
        ["2026-11-30", "2027-11-29", 12],
        // Short months and a leap day.
        ["2026-01-31", "2026-02-27", 1],
        ["2026-01-31", "2026-02-28", 1],
        ["2024-02-29", "2025-02-28", 12],
    ];

    it.each(CASES)("%s to %s is %i months", (start, end, expected) => {
        expect(monthsInclusive(start, end)).toBe(expected);
    });

    it("floors at one month for a same-day term", () => {
        // Zero would divide by zero downstream.
        expect(monthsInclusive("2026-01-15", "2026-01-15")).toBe(1);
    });

    it("does not lose a day to the timezone", () => {
        // `new Date("2026-10-03")` is UTC midnight, which is 2 October in any
        // negative-offset timezone — enough to shift the count by a month.
        expect(monthsInclusive("2026-10-01", "2027-09-30")).toBe(12);
        expect(monthsInclusive("2026-10-01", "2026-10-31")).toBe(1);
    });

    it("falls back to one month rather than NaN on an unset date", () => {
        expect(monthsInclusive("", "2027-10-03")).toBe(1);
        expect(monthsInclusive("2026-10-03", "")).toBe(1);
    });

    it("gives the totals a 5,000 a month lease is worth", () => {
        // The figures an operator would recognise on the lease record.
        expect(5000 * monthsInclusive("2026-10-03", "2027-10-03")).toBe(60000);
        expect(5000 * monthsInclusive("2026-06-01", "2026-12-31")).toBe(35000);
    });
});

// Break round 1: a lease wizard accepted a contract to 2999. The term guard
// uses the backend's rule (LeaseService.requireSaneTerm): the end date is
// inclusive, so a term "exceeds N years" once the end reaches start + N years.
describe("term length guards", () => {
    it("adds whole years, clamping 29 Feb like java.time plusYears", async () => {
        const { addYearsIso } = await import("../leaseTerm");
        expect(addYearsIso("2026-06-01", 50)).toBe("2076-06-01");
        expect(addYearsIso("2028-02-29", 1)).toBe("2029-02-28");
    });

    it("exactly N years is not over N; one more day is", async () => {
        const { termExceedsYears } = await import("../leaseTerm");
        expect(termExceedsYears("2026-06-01", "2076-05-31", 50)).toBe(false);
        expect(termExceedsYears("2026-06-01", "2076-06-01", 50)).toBe(true);
        expect(termExceedsYears("2026-06-01", "2999-06-01", 50)).toBe(true);
        expect(termExceedsYears("2026-06-01", "2031-05-31", 5)).toBe(false);
        expect(termExceedsYears("2026-06-01", "2031-06-01", 5)).toBe(true);
        expect(termExceedsYears("", "2031-06-01", 5)).toBe(false);
    });

    it("counts a term's whole years", async () => {
        const { termYears } = await import("../leaseTerm");
        expect(termYears("2026-06-01", "2027-05-31")).toBe(1);
        expect(termYears("2026-06-01", "2032-05-31")).toBe(6);
        expect(termYears("2026-06-01", "2999-06-01")).toBe(973);
    });
});
