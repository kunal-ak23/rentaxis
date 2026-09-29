import { describe, expect, it } from "vitest";
import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";
import { ApiError, currentContracts, errorText, localInstant, scanReasonKey, toE164, type MyContract } from "../gatepass";

const c = (over: Partial<MyContract>): MyContract => ({
    id: "l", unitId: "u", unitIdentifier: "A-1", propertyId: "p", propertyName: "P",
    status: "ACTIVE", startDate: "2026-01-01", endDate: "2026-12-31", ...over,
});

describe("currentContracts — mirrors LeaseRepository#findCurrentForRenterUser", () => {
    const today = "2026-09-29";
    it("keeps a live status with today inside the term", () => {
        for (const status of ["ACTIVE", "NOTICE_GIVEN", "RENEWED"]) {
            expect(currentContracts([c({ status })], today), status).toHaveLength(1);
        }
    });
    it("drops drafts, ended terms and future terms", () => {
        expect(currentContracts([c({ status: "DRAFT" })], today)).toHaveLength(0);
        expect(currentContracts([c({ status: "PENDING_SIGNATURE" })], today)).toHaveLength(0);
        expect(currentContracts([c({ endDate: "2026-09-28" })], today)).toHaveLength(0);
        expect(currentContracts([c({ startDate: "2026-09-30" })], today)).toHaveLength(0);
    });
    it("counts the first and last day of the term as current", () => {
        expect(currentContracts([c({ startDate: today })], today)).toHaveLength(1);
        expect(currentContracts([c({ endDate: today })], today)).toHaveLength(1);
    });
});

describe("toE164 — the walk-in desk's phone rule", () => {
    it("normalises spacing, 00 and Arabic-Indic digits", () => {
        expect(toE164("+971 50 123 4567")).toBe("+971501234567");
        expect(toE164("00971-50-123-4567")).toBe("+971501234567");
        expect(toE164("+٩٧١٥٠١٢٣٤٥٦٧")).toBe("+971501234567");
    });
    it("refuses a number without a country code or out of length", () => {
        expect(toE164("0501234567")).toBeNull();
        expect(toE164("+12345")).toBeNull();
        expect(toE164("+1234567890123456")).toBeNull();
    });
});

describe("localInstant — gate times are UAE time", () => {
    it("reads the picked time as Asia/Dubai (+04:00), whatever the browser zone", () => {
        expect(localInstant("2026-10-02", "09:00")).toBe("2026-10-02T05:00:00.000Z");
        expect(localInstant("2026-10-02", "00:00")).toBe("2026-10-01T20:00:00.000Z");
    });
});

describe("errorText — known refusals in the user's language", () => {
    const t = (k: string) => `T:${k}`;
    it("maps known server sentences and scan reasons to keys present in both catalogues", () => {
        for (const [msg, key] of [["Gate pass is not pending approval", "errNoLongerPending"],
            ["A fresh visitor photo is required at this gate", "errPhotoRequired"],
            ["Walk-in requests must be decided by the resident", "errResidentDecides"],
            ["outside validity window", "reasonOutsideWindow"]]) {
            expect(errorText(new ApiError(400, msg), "fb", t), msg).toBe(`T:${key}`);
            expect((en.GatePass as Record<string, unknown>)[key], `en ${key}`).toBeTypeOf("string");
            expect((ar.GatePass as Record<string, unknown>)[key], `ar ${key}`).toBeTypeOf("string");
        }
    });
    it("shows an unknown server sentence as sent, and the fallback for a bare status", () => {
        expect(errorText(new ApiError(403, "Access denied"), "fb", t)).toBe("Access denied");
        expect(errorText(new ApiError(500, "Request failed (status 500)"), "fb", t)).toBe("fb");
        expect(errorText(new Error("x"), "fb", t)).toBe("fb");
    });
});

describe("scanReasonKey", () => {
    it("maps every refusal the scan service sends to a key present in both catalogues", () => {
        const reasons = ["not found", "not authorized for this property", "no entry recorded", "pending approval",
            "cancelled", "expired", "already used", "outside validity window", "scan in progress, please retry"];
        for (const r of reasons) {
            const key = scanReasonKey(r)!;
            expect(key, r).toBeTruthy();
            expect((en.GatePass as Record<string, unknown>)[key], `en ${key}`).toBeTypeOf("string");
            expect((ar.GatePass as Record<string, unknown>)[key], `ar ${key}`).toBeTypeOf("string");
        }
    });
    it("returns null for an unknown or empty reason", () => {
        expect(scanReasonKey("something new")).toBeNull();
        expect(scanReasonKey(null)).toBeNull();
    });
});
