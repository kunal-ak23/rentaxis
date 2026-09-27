import { describe, expect, it } from "vitest";
import { findRoute, routeDecision } from "../routeGuard";
import { buildNav, flattenNav } from "../navModel";
import type { UserRole } from "../../rbac";

const ROLES: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "SECURITY_GUARD", "TENANT_USER", "RENTER", "ACCOUNTANT"];

describe("findRoute", () => {
    it("matches a locale-prefixed path to its registry entry, static segments before [params]", () => {
        expect(findRoute("/en/dashboard/finance/journals/new")?.path).toBe("/dashboard/finance/journals/new");
        expect(findRoute("/ar/dashboard/finance/journals/5f0c")?.path).toBe("/dashboard/finance/journals/[id]");
        expect(findRoute("/en/superadmin/users")?.path).toBe("/superadmin/users");
        expect(findRoute("/en/dashboard")?.path).toBe("/dashboard");
        expect(findRoute("/dashboard/leases/abc/terminate")?.path).toBe("/dashboard/leases/[id]/terminate");
    });

    it("returns null for a path the registry does not know (left to the page / Next 404)", () => {
        expect(findRoute("/en/dashboard/nope/at/all")).toBeNull();
    });
});

describe("routeDecision — role rules (F8)", () => {
    it.each([
        "/en/superadmin/users",
        "/en/superadmin/tenants",
        "/en/dashboard/finance/accounts",
        "/en/dashboard/finance/bank-accounts",
        "/en/dashboard/staff",
        "/ar/dashboard/finance/journals",
        "/ar/dashboard/leases",
        "/en/dashboard/settings",
    ])("denies TENANT_USER %s", (path) => {
        expect(routeDecision(path, "TENANT_USER", true)).toBe("denied");
    });

    it("lets TENANT_USER keep the pages they work in", () => {
        for (const p of ["/en/dashboard", "/en/dashboard/tickets", "/en/dashboard/tickets/t1", "/en/dashboard/profile", "/en/dashboard/notifications", "/en/dashboard/help"]) {
            expect([p, routeDecision(p, "TENANT_USER", true)]).toEqual([p, "allow"]);
        }
    });

    it("keeps the pages that serve roles beyond their nav gate", () => {
        expect(routeDecision("/en/dashboard/tickets/t1", "RENTER", true)).toBe("allow");
        expect(routeDecision("/en/dashboard/tickets", "ACCOUNTANT", true)).toBe("allow");
        expect(routeDecision("/en/dashboard/meetings/m1", "RENTER", true)).toBe("allow");
        expect(routeDecision("/en/dashboard/renters/r1", "ACCOUNTANT", true)).toBe("allow");
        expect(routeDecision("/en/dashboard/properties/p1/units", "ACCOUNTANT", true)).toBe("allow");
    });

    it("allows every link each role's navigation shows (no functionality lost)", () => {
        for (const role of ROLES) {
            const hrefs = flattenNav(buildNav({ role, isEnabled: () => true, tenantSlug: "acme", booksLive: true }))
                .filter(h => h.startsWith("/dashboard") || h.startsWith("/superadmin"));
            const denied = hrefs.filter(h => routeDecision(h.split(/[?#]/)[0], role, true) !== "allow");
            expect([role, denied]).toEqual([role, []]);
        }
    });

    it("allows unknown paths (left to the page or Next's not-found)", () => {
        expect(routeDecision("/en/dashboard/whatever", "TENANT_USER", true)).toBe("allow");
    });
});

describe("routeDecision — super admin Global View (F7)", () => {
    it("asks for an organisation on org-scoped pages when none is selected", () => {
        expect(routeDecision("/en/dashboard", "SUPER_ADMIN", false)).toBe("selectOrg");
        expect(routeDecision("/en/dashboard/finance/journals", "SUPER_ADMIN", false)).toBe("selectOrg");
        expect(routeDecision("/en/dashboard/leases/abc", "SUPER_ADMIN", false)).toBe("selectOrg");
    });

    it("keeps the global and personal pages usable in Global View", () => {
        for (const p of ["/en/superadmin/tenants", "/en/superadmin/users", "/en/dashboard/profile", "/en/dashboard/help", "/en/dashboard/help/x", "/en/dashboard/notifications"]) {
            expect([p, routeDecision(p, "SUPER_ADMIN", false)]).toEqual([p, "allow"]);
        }
    });

    it("allows everything once an organisation is selected", () => {
        expect(routeDecision("/en/dashboard/finance/journals", "SUPER_ADMIN", true)).toBe("allow");
    });

    it("never asks other roles to pick an organisation (no cookie = their home org)", () => {
        expect(routeDecision("/en/dashboard/leases", "TENANT_ADMIN", false)).toBe("allow");
    });
});
