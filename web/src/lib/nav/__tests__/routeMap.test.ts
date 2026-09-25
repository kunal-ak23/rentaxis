// src/lib/nav/__tests__/routeMap.test.ts
import { describe, expect, it } from "vitest";
import { ROUTE_MOVES, canonicalHref, legacyRedirect, matchRoute } from "../routeMap";

const redirectOf = (href: string): string | null => {
    const out = legacyRedirect(new URL(href, "http://localhost:3000"));
    return out ? out.pathname + out.search : null;
};

/** One row per ROUTE_MOVES entry — the last test fails if a move has no row. */
const CASES: [string, string][] = [
    ["/en/dashboard/my-unit", "/en/dashboard"],
    ["/en/dashboard/settings/account-template", "/en/dashboard/finance/account-template"],
    ["/en/dashboard/settings/fiscal", "/en/dashboard/finance/fiscal"],
    ["/en/dashboard/settings/charge-types", "/en/dashboard/finance/charge-types"],
    ["/en/dashboard/settings/fines", "/en/dashboard/settings?section=rent"],
    ["/en/dashboard/settings/rent-settings", "/en/dashboard/settings?section=rent"],
    ["/en/dashboard/settings/gateway", "/en/dashboard/settings?section=payments"],
    ["/en/dashboard/finance/cheques", "/en/dashboard/collections?tab=all"],
    ["/en/dashboard/finance/cheques/collection", "/en/dashboard/collections?tab=deposit"],
    ["/en/dashboard/finance/cheques/return-replace", "/en/dashboard/collections?tab=returned"],
    ["/en/dashboard/finance/cheques/post-dated", "/en/dashboard/collections?tab=post-dated"],
    ["/en/dashboard/finance/penalties", "/en/dashboard/collections?tab=penalties"],
];

describe("legacyRedirect", () => {
    it.each(CASES)("%s → %s", (from, to) => {
        expect(redirectOf(from)).toBe(to);
    });

    it("keeps the Arabic prefix", () => {
        expect(redirectOf("/ar/dashboard/settings/fines")).toBe("/ar/dashboard/settings?section=rent");
    });

    it("works without a locale prefix", () => {
        expect(redirectOf("/dashboard/settings/gateway")).toBe("/dashboard/settings?section=payments");
    });

    it("tolerates a trailing slash", () => {
        expect(redirectOf("/en/dashboard/settings/fines/")).toBe("/en/dashboard/settings?section=rent");
    });

    it("keeps every incoming query parameter, decoded values intact", () => {
        const out = legacyRedirect(new URL("http://localhost:3000/ar/dashboard/settings/rent-settings?propertyId=p1&q=a%20b"))!;
        expect(out.pathname).toBe("/ar/dashboard/settings");
        expect(out.searchParams.get("propertyId")).toBe("p1");
        expect(out.searchParams.get("q")).toBe("a b");
        expect(out.searchParams.get("section")).toBe("rent");
    });

    it("keeps a ?tab= deep link on a page that moved without a query of its own", () => {
        expect(redirectOf("/en/dashboard/settings/fiscal?tab=close")).toBe("/en/dashboard/finance/fiscal?tab=close");
    });

    it("lets the new home's own parameter win over the same incoming key", () => {
        expect(redirectOf("/en/dashboard/settings/gateway?section=users")).toBe("/en/dashboard/settings?section=payments");
    });

    it("keeps a register bookmark's filters and lease", () => {
        const out = legacyRedirect(new URL("http://x/ar/dashboard/finance/cheques?status=BOUNCED&leaseId=l1&propertyId=p1"))!;
        expect(out.pathname).toBe("/ar/dashboard/collections");
        expect(Object.fromEntries(out.searchParams)).toEqual({ status: "BOUNCED", leaseId: "l1", propertyId: "p1", tab: "all" });
    });

    it("does not move the cheque pages that stay (a lease's issued cheques, payables)", () => {
        expect(redirectOf("/en/dashboard/finance/payables/issued-cheques")).toBeNull();
        expect(redirectOf("/en/dashboard/collections?tab=all")).toBeNull();
    });

    it("returns null for routes that did not move", () => {
        for (const href of ["/en/dashboard", "/en/dashboard/leases", "/en/dashboard/staff", "/en/dashboard/settings", "/en/dashboard/finance/journals/123", "/en"]) {
            expect(redirectOf(href), href).toBeNull();
        }
    });

    it("never redirects into another move (no chains, no loops)", () => {
        for (const move of ROUTE_MOVES) {
            const target = move.to.replace(/:([A-Za-z]+)/g, "x");
            expect(redirectOf(`/en${target}`), move.from).toBeNull();
        }
    });

    it("has a case for every move", () => {
        const uncovered = ROUTE_MOVES.filter(m => !CASES.some(([from]) => matchRoute(m.from, from.replace(/^\/(en|ar)/, "").replace(/\?.*$/, ""))));
        expect(uncovered.map(m => m.from)).toEqual([]);
    });
});

describe("canonicalHref", () => {
    it("drops the locale, follows moves and sorts the query", () => {
        expect(canonicalHref("/ar/dashboard/settings/gateway?b=2&a=1")).toBe("/dashboard/settings?a=1&b=2&section=payments");
        expect(canonicalHref("/dashboard/leases")).toBe("/dashboard/leases");
    });
});
