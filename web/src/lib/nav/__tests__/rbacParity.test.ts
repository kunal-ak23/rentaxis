// src/lib/nav/__tests__/rbacParity.test.ts
import { describe, expect, it } from "vitest";
import type { UserRole } from "../../rbac";
import { buildNav, flattenNav } from "../navModel";
import { canonicalHref } from "../routeMap";
import { LEGACY_NAV } from "./legacyNav.fixture";

/** Always in the top header for every signed-in user (Help moved there from the sidebar). */
const HEADER = ["/dashboard/help", "/dashboard/notifications", "/dashboard/profile"];

/** Destinations that are new, each justified by a page the role could already reach. */
const NEW_DESTINATIONS: Record<string, string> = {
    "/dashboard/settings?section=organisation": "/dashboard/settings?section=payments",
    "/dashboard/collections?tab=due": "/dashboard/finance/cheques",
    "/dashboard/collections?tab=overdue": "/dashboard/finance/cheques",
    // Journal Entries › Credit Note: the page was already one click away on Vouchers (same canManageVouchers gate).
    "/dashboard/finance/vouchers/credit-note": "/dashboard/finance/vouchers",
    // SUPER_ADMIN keeps /superadmin/users too; Settings › Users & staff embeds that same component.
    "/dashboard/settings?section=users": "/superadmin/users",
};

/**
 * A tenant admin's "Users" link used to open /superadmin/users; the same
 * component now lives in Settings › Users & staff. Same screen, same gate
 * (canManageUsers), so the two are one destination for parity.
 */
function canonical(href: string, role: UserRole): string {
    if (href === "/superadmin/users" && role !== "SUPER_ADMIN") return canonicalHref("/dashboard/settings?section=users");
    return canonicalHref(href);
}

const pathOnly = (h: string) => h.split("?")[0];
const HUBS = new Set(["/dashboard/settings", "/dashboard/collections"]);

function reachable(role: UserRole): Set<string> {
    const rail = buildNav({ role, isEnabled: () => true, tenantSlug: "acme", booksLive: false });
    return new Set([...flattenNav(rail), ...HEADER].map(h => canonical(h.split("#")[0], role)));
}

const ROLES = Object.keys(LEGACY_NAV) as UserRole[];

describe("RBAC parity — every role reaches exactly what it reached before", () => {
    it.each(ROLES)("%s loses nothing", role => {
        const before = new Set([...LEGACY_NAV[role], ...HEADER].map(h => canonical(h, role)));
        const after = reachable(role);
        expect([...before].filter(h => !after.has(h))).toEqual([]);
    });

    it.each(ROLES)("%s gains nothing", role => {
        const before = new Set([...LEGACY_NAV[role], ...HEADER].map(h => canonical(h, role)));
        const beforePaths = new Set([...before].map(pathOnly));
        const gained = [...reachable(role)].filter(h => {
            if (before.has(h)) return false;
            const why = NEW_DESTINATIONS[h];
            if (why && before.has(canonical(why, role))) return false;
            // A saved view is a filter on a page the role already had (not a hub, where the query IS the page).
            if (!HUBS.has(pathOnly(h)) && beforePaths.has(pathOnly(h))) return false;
            return true;
        });
        expect(gained).toEqual([]);
    });
});
