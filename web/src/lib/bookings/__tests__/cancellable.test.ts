import { describe, expect, it } from "vitest";
import { dubaiNowStamp, tenantCanCancel } from "../cancellable";

const amenity = (over: Record<string, unknown>) => ({
    status: "APPROVED", resourceType: "AMENITY", preferredDate: "2026-10-10", preferredStartTime: "18:00:00", ...over,
});

describe("a Tenant cancelling their own booking", () => {
    it("reads now in Dubai", () => {
        expect(dubaiNowStamp(new Date("2026-10-05T20:30:00Z"))).toBe("2026-10-06T00:30");
    });

    it("may cancel any pending request", () => {
        expect(tenantCanCancel({ status: "PENDING", resourceType: "PARKING_SPOT", preferredDate: null })).toBe(true);
    });

    it("may cancel an approved amenity booking until its slot starts", () => {
        expect(tenantCanCancel(amenity({}), "2026-10-10T17:59")).toBe(true);
        expect(tenantCanCancel(amenity({}), "2026-10-10T18:00")).toBe(false);
        expect(tenantCanCancel(amenity({ preferredStartTime: null }), "2026-10-09T23:59")).toBe(true);
        expect(tenantCanCancel(amenity({ preferredStartTime: null }), "2026-10-10T00:00")).toBe(false);
        expect(tenantCanCancel(amenity({ preferredDate: null }), "2026-10-10T00:00")).toBe(true);
    });

    it("may not cancel approved parking (it is released) or a decided request", () => {
        expect(tenantCanCancel(amenity({ resourceType: "PARKING_SPOT" }), "2026-10-01T00:00")).toBe(false);
        expect(tenantCanCancel(amenity({ status: "REJECTED" }), "2026-10-01T00:00")).toBe(false);
    });
});
