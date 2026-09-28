import { afterEach, describe, expect, it, vi } from "vitest";
import { todayIso as leaseToday } from "@/components/leases/leaseMath";
import { businessTodayIso } from "@/lib/businessDate";

/**
 * Break-it R3 money3 N4: an accountant in Honolulu (Sun 27 Sep 22:36 there, Mon 28 Sep
 * in Dubai) had JV / payment voucher / purchase invoice defaults of 27/09 — yesterday
 * in the books' zone. The shared "today" helpers now answer the Dubai date whatever the
 * browser's zone; the mirror case (a browser ahead of Dubai) is checked too.
 */
const originalTz = process.env.TZ;

afterEach(() => {
    vi.useRealTimers();
    process.env.TZ = originalTz;
});

describe("money forms default to the business (Dubai) date", () => {
    it("is Monday in Dubai while the browser in Honolulu is still on Sunday", () => {
        process.env.TZ = "Pacific/Honolulu";
        vi.useFakeTimers();
        vi.setSystemTime(new Date("2026-09-28T08:36:00Z"));
        expect(new Date().getDate()).toBe(27); // the browser's own calendar
        expect(leaseToday()).toBe("2026-09-28");
        expect(businessTodayIso()).toBe("2026-09-28");
    });

    it("is still Monday in Dubai when a browser in Kolkata has reached Tuesday", () => {
        process.env.TZ = "Asia/Kolkata";
        vi.useFakeTimers();
        vi.setSystemTime(new Date("2026-09-28T19:00:00Z")); // 00:30 Tue in India, 23:00 Mon in Dubai
        expect(new Date().getDate()).toBe(29);
        expect(leaseToday()).toBe("2026-09-28");
        expect(businessTodayIso()).toBe("2026-09-28");
    });
});
