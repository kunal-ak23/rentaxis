import { describe, expect, it } from "vitest";
import { nextDay, renewalTerm, termMonths } from "../renewalDates";

describe("renewal proposed from a meeting", () => {
    it("starts the day after the current contract ends", () => {
        expect(renewalTerm("2026-12-31", 12)).toEqual({ start: "2027-01-01", end: "2027-12-31" });
        expect(renewalTerm("2026-12-31", null)).toEqual({ start: "2027-01-01", end: null });
    });

    it("handles month ends and leap years", () => {
        expect(nextDay("2028-02-28")).toBe("2028-02-29");
        expect(renewalTerm("2026-01-30", 1)).toEqual({ start: "2026-01-31", end: "2026-02-27" });
        expect(renewalTerm("2026-06-30", 6)).toEqual({ start: "2026-07-01", end: "2026-12-31" });
    });

    it("counts a 1 Jan–31 Dec term as 12 months", () => {
        expect(termMonths("2027-01-01", "2027-12-31")).toBe(12);
        expect(termMonths("2026-07-01", "2026-12-31")).toBe(6);
        // A proposal saved before the fix started on the old end date.
        expect(termMonths("2026-12-31", "2027-12-31")).toBe(12);
    });
});
