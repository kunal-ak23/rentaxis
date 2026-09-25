// src/lib/__tests__/rentSettings.test.ts
import { describe, expect, it } from "vitest";
import { DEFAULT_RENT_SETTINGS, readRentSettings, toRentSettingsBody } from "../rentSettings";

describe("rent settings helpers", () => {
    it("treats 204 as defaults", async () => {
        const s = await readRentSettings({ status: 204, ok: true } as Response, "p1");
        expect(s).toEqual({ ...DEFAULT_RENT_SETTINGS, propertyId: "p1" });
    });
    it("merges a 200 body over the defaults", async () => {
        const s = await readRentSettings({ status: 200, ok: true, json: async () => ({ dueDayOfMonth: 5, onlinePaymentEnabled: true }) } as unknown as Response, "p1");
        expect(s?.dueDayOfMonth).toBe(5);
        expect(s?.gracePeriodDays).toBe(DEFAULT_RENT_SETTINGS.gracePeriodDays);
    });
    it("answers null on an error status", async () => {
        expect(await readRentSettings({ status: 500, ok: false } as Response, "p1")).toBeNull();
    });
    it("sends every field the save endpoint takes, onlinePaymentEnabled included", () => {
        const body = toRentSettingsBody({ ...DEFAULT_RENT_SETTINGS, propertyId: "p1", id: "x", onlinePaymentEnabled: true });
        expect(Object.keys(body).sort()).toEqual([
            "dueDayOfMonth", "fineAccountClosedAmount", "fineBounceAmount", "fineGraceDays", "finePerDayRate", "fineSignatureMismatchAmount",
            "gracePeriodDays", "onlinePaymentEnabled", "penaltyAmount", "penaltyType", "renewalIncreaseWarnPercent",
        ]);
        expect(body.onlinePaymentEnabled).toBe(true);
    });
});
