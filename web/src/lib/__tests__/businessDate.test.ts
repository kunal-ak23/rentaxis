import { afterEach, describe, expect, it, vi } from "vitest";
import { businessTodayIso } from "../businessDate";

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
