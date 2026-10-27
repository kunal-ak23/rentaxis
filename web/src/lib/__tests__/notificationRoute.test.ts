import { describe, expect, it } from "vitest";
import { notificationRoute } from "../notificationRoute";

const n = (referenceType: string | null, referenceId: string | null = "ref-1", type = "X") => ({ type, referenceType, referenceId });

describe("notificationRoute", () => {
    it("keeps the destinations the bell and the page always had", () => {
        expect(notificationRoute(n("TICKET"), "TENANT_ADMIN")).toBe("/dashboard/tickets/ref-1");
        expect(notificationRoute(n("LEASE"), "RENTER")).toBe("/dashboard/leases/ref-1");
        expect(notificationRoute(n("PAYMENT"), "RENTER")).toBe("/dashboard/collections?tab=all");
        expect(notificationRoute(n("TICKET", "t", "LEASE_RENEWAL_REMINDER"), "RENTER")).toBe("/dashboard/renter-portal/renewals");
    });

    it("goes nowhere without a reference, or for a reference it does not know", () => {
        expect(notificationRoute(n(null, null), "TENANT_ADMIN")).toBeUndefined();
        expect(notificationRoute(n("TICKET", null), "TENANT_ADMIN")).toBeUndefined();
        expect(notificationRoute(n("MEETING"), "TENANT_ADMIN")).toBeUndefined();
    });

    it.each([
        ["TENANT_ADMIN", "/dashboard/promotions"],
        ["SUPER_ADMIN", "/dashboard/promotions"],
        ["PROMOTER", "/dashboard/promoter"],
    ])("sends a promotion ad's notification to %s's own page", (role, route) => {
        expect(notificationRoute(n("PROMO_AD"), role)).toBe(route);
    });

    it.each(["PROPERTY_MANAGER", "ACCOUNTANT", "RENTER", "TENANT_USER", "SECURITY_GUARD", undefined])(
        "has no promotion destination for %s, whose access to either page is refused", role => {
            expect(notificationRoute(n("PROMO_AD"), role)).toBeUndefined();
        });
});
