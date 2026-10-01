import { describe, expect, it } from "vitest";
import { findRoute, homeRedirect, renterHomeRedirect, routeDecision } from "../routeGuard";
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

    /**
     * Review fix 3: before the layout guard, a page with no role check of its
     * own rendered for anyone, with whatever its main GET returned. Every
     * such page and the roles its backend read admits (@PreAuthorize on the
     * controller, read 2026-09-28) must stay reachable for those roles.
     * Pages that already refused a role themselves are not listed — the
     * guard changes nothing for them.
     */
    const REACHED_BEFORE: [string, string, UserRole[]][] = [
        // path, backend read, roles it admits
        ["/en/dashboard/properties", "GET /properties", ["TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT"]],
        ["/en/dashboard/properties/p1", "GET /properties/{id}", ["TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT"]],
        ["/en/dashboard/properties/p1/units", "GET /units/paged", ["TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT"]],
        ["/en/dashboard/renters", "GET /renters/paged", ["TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT"]],
        ["/en/dashboard/renters/r1", "GET /renters/{id} (page gate canViewLeases)", ["TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT"]],
        ["/en/dashboard/tickets", "GET /tickets (isAuthenticated), /tickets/paged", ["TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT", "TENANT_USER", "RENTER"]],
        ["/en/dashboard/tickets/t1", "GET /tickets/{id} (isAuthenticated)", ["TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT", "TENANT_USER", "RENTER"]],
        ["/en/dashboard/tickets/reports", "GET /tickets/reports", ["TENANT_ADMIN", "PROPERTY_MANAGER"]],
        ["/en/dashboard/listings", "UnitListingController", ["TENANT_ADMIN", "PROPERTY_MANAGER"]],
        ["/en/dashboard/listings/l1", "UnitListingController", ["TENANT_ADMIN", "PROPERTY_MANAGER"]],
        ["/en/dashboard/staff", "StaffController", ["TENANT_ADMIN"]],
        ["/en/dashboard/finance/bank-accounts", "BankAccountController", ["TENANT_ADMIN"]],
        ["/en/dashboard/finance/accounts", "AccountController", ["TENANT_ADMIN", "ACCOUNTANT"]],
        ["/en/dashboard/finance/vendors", "VendorController", ["TENANT_ADMIN", "ACCOUNTANT"]],
        ["/en/dashboard/meetings", "meetings (renter portal links)", ["TENANT_ADMIN", "PROPERTY_MANAGER", "RENTER"]],
    ];
    it.each(REACHED_BEFORE)("still admits every role the backend serves at %s (%s)", (path, _read, roles) => {
        const refused = roles.filter(role => routeDecision(path, role, true) !== "allow");
        expect(refused).toEqual([]);
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

// Break-it R3 portal3 F10: a renter who lands on the staff dashboard is sent to their own home.
describe("renterHomeRedirect", () => {
    it("sends a RENTER on the dashboard home to the renter portal, in either locale", () => {
        for (const p of ["/dashboard", "/en/dashboard", "/ar/dashboard", "/en/dashboard/"]) {
            expect(renterHomeRedirect(p, "RENTER"), p).toBe("/dashboard/renter-portal");
        }
    });

    it("leaves other pages and other roles alone", () => {
        expect(renterHomeRedirect("/en/dashboard/tickets", "RENTER")).toBeNull();
        expect(renterHomeRedirect("/en/dashboard/renter-portal", "RENTER")).toBeNull();
        for (const role of ROLES.filter(r => r !== "RENTER")) {
            expect(renterHomeRedirect("/en/dashboard", role), role).toBeNull();
        }
        expect(renterHomeRedirect("/en/dashboard", undefined)).toBeNull();
    });
});

describe("homeRedirect", () => {
    it("sends a renter to the renter home and a guard to the gate desk from the dashboard home", () => {
        for (const p of ["/dashboard", "/en/dashboard", "/ar/dashboard/"]) {
            expect(homeRedirect(p, "RENTER"), p).toBe("/dashboard/renter-portal");
            expect(homeRedirect(p, "SECURITY_GUARD"), p).toBe("/dashboard/gatepass/gate");
        }
    });
    it("leaves every other page and every staff role alone", () => {
        expect(homeRedirect("/en/dashboard/gatepass/gate", "SECURITY_GUARD")).toBeNull();
        expect(homeRedirect("/en/dashboard/help", "SECURITY_GUARD")).toBeNull();
        for (const role of ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT", "TENANT_USER"] as const) {
            expect(homeRedirect("/en/dashboard", role), role).toBeNull();
        }
    });
});

describe("gate-pass screens (bug 25)", () => {
    const ALL = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "SECURITY_GUARD", "TENANT_USER", "RENTER", "ACCOUNTANT"] as const;
    const allowed = (path: string) => ALL.filter(r => routeDecision(path, r, true) === "allow");
    it("admits each screen to exactly the roles its endpoints admit", () => {
        expect(allowed("/en/dashboard/renter-portal/gate-passes")).toEqual(["RENTER"]);
        expect(allowed("/en/dashboard/gatepass/gate")).toEqual(["SECURITY_GUARD"]);
        expect(allowed("/en/dashboard/gatepass/approvals")).toEqual(["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "SECURITY_GUARD"]);
        expect(allowed("/en/dashboard/gatepass/settings")).toEqual(["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"]);
        // The report keeps its own rule — a tenant and a guard still cannot open it.
        expect(allowed("/en/dashboard/gatepass")).toEqual(["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"]);
    });
});
