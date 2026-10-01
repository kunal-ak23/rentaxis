import { describe, expect, it } from "vitest";
import { belowExpectedRent } from "../belowExpectedRent";

describe("belowExpectedRent (#254)", () => {
    it("compares a year's contract rent with the asking rent", () => {
        expect(belowExpectedRent(60000, 54000, "2026-01-01", "2026-12-31")).toEqual({ expected: 60000, annual: 54000, gap: 6000 });
        expect(belowExpectedRent(60000, 60000, "2026-01-01", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(60000, 65000, "2026-01-01", "2026-12-31")).toBeNull();
    });

    it("annualises a shorter or longer term instead of warning on every one", () => {
        // Six months at the asking rate is not below it.
        expect(belowExpectedRent(60000, 30000, "2026-01-01", "2026-07-01")).toBeNull();
        // Two years at 100,000 is 50,000 a year — below 60,000.
        const two = belowExpectedRent(60000, 100000, "2026-01-01", "2027-12-31");
        expect(two?.annual).toBeCloseTo(50000, -1);
    });

    it("says nothing without an asking rent, a rent or a valid term", () => {
        expect(belowExpectedRent(null, 1000, "2026-01-01", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(0, 1000, "2026-01-01", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(60000, 0, "2026-01-01", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(60000, 1000, "", "2026-12-31")).toBeNull();
        expect(belowExpectedRent(60000, 1000, "2026-12-31", "2026-01-01")).toBeNull();
    });
});
