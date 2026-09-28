import { afterEach, describe, expect, it, vi } from "vitest";
import { businessTodayIso, isBeyondManualPostingWindow, maxManualPostingDateIso } from "../businessDate";

/**
 * The server reads "today" in Asia/Dubai (UTC+4). A form default taken from the
 * browser's own date disagrees near midnight — an IST browser (UTC+5:30) is
 * already on the next day between 00:00 and 01:30 local, and the server refuses
 * that date as in the future.
 */
describe("businessTodayIso", () => {
    afterEach(() => {
        vi.useRealTimers();
    });

    it("is still the 23rd in Dubai at 19:00 UTC (23:00 Dubai, 00:30 IST next day)", () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date("2026-09-23T19:00:00Z"));
        expect(businessTodayIso()).toBe("2026-09-23");
    });

    it("rolls over to the 24th once it is past midnight in Dubai", () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date("2026-09-23T20:30:00Z"));
        expect(businessTodayIso()).toBe("2026-09-24");
    });
});

/**
 * Break-it round 1 (money) F4: a manual posting (JV, voucher, cash receipt) may be
 * dated at most one year after the business today — the server's
 * `ManualPostingDates` rule, mirrored including Java's 29 February → 28 February.
 */
describe("maxManualPostingDateIso / isBeyondManualPostingWindow", () => {
    afterEach(() => {
        vi.useRealTimers();
    });

    it("is one year after the business today", () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date("2026-09-28T08:00:00Z"));
        expect(maxManualPostingDateIso()).toBe("2027-09-28");
        expect(isBeyondManualPostingWindow("2027-09-28")).toBe(false);
        expect(isBeyondManualPostingWindow("2027-09-29")).toBe(true);
        expect(isBeyondManualPostingWindow("2126-09-28")).toBe(true);
        expect(isBeyondManualPostingWindow("1999-01-01")).toBe(false);
        expect(isBeyondManualPostingWindow("")).toBe(false);
        expect(isBeyondManualPostingWindow("20260-01-01")).toBe(true);
    });

    it("maps 29 February to 28 February, as LocalDate.plusYears does", () => {
        vi.useFakeTimers();
        vi.setSystemTime(new Date("2028-02-29T08:00:00Z"));
        expect(maxManualPostingDateIso()).toBe("2029-02-28");
    });
});
