import { describe, expect, it } from "vitest";
import { daysSince, isAwaitingClearing, isStaleDeposit, STALE_DEPOSIT_DAYS } from "../depositedAge";

describe("depositedAge", () => {
    it("counts whole calendar days to today, floored at zero", () => {
        const now = new Date(2026, 9, 5, 23, 30);
        expect(daysSince("2026-09-06", now)).toBe(29);
        expect(daysSince("2026-10-05", now)).toBe(0);
        expect(daysSince("2026-10-07", now)).toBe(0);
        expect(daysSince("2026-03-01", new Date(2026, 3, 1, 1))).toBe(31);
    });

    it("is null without a date", () => {
        expect(daysSince(null)).toBeNull();
        expect(daysSince("")).toBeNull();
    });

    it("only a DEPOSITED row is awaiting clearing", () => {
        expect(isAwaitingClearing({ status: "DEPOSITED" })).toBe(true);
        expect(isAwaitingClearing({ status: "REGISTERED" })).toBe(false);
        expect(isAwaitingClearing({ status: "BOUNCED" })).toBe(false);
    });

    it("a deposit is stale only past the threshold", () => {
        expect(STALE_DEPOSIT_DAYS).toBe(14);
        expect(isStaleDeposit(14)).toBe(false);
        expect(isStaleDeposit(15)).toBe(true);
        expect(isStaleDeposit(null)).toBe(false);
    });
});
