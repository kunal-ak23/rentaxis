// Tenant Ledger URL: which view opens, the filter read from the query string and written back.
import { describe, expect, it } from "vitest";
import { defaultLedgerRange } from "@/components/finance/LedgerFilters";
import { hasScope, readQuery, toUrl, viewFrom } from "../_components/query";

describe("Tenant Ledger query", () => {
    it("keeps the lease deep link on All accounts", () => {
        expect(viewFrom(new URLSearchParams("renterId=r1&leaseId=l1"))).toBe("all");
        expect(viewFrom(new URLSearchParams("renterId=r1"))).toBe("receivable");
        expect(viewFrom(new URLSearchParams("view=receivable&leaseId=l1"))).toBe("receivable");
        expect(viewFrom(new URLSearchParams("view=all"))).toBe("all");
        expect(viewFrom(new URLSearchParams(""))).toBe("receivable");
        expect(viewFrom(new URLSearchParams("view=bogus"))).toBe("receivable");
        expect(readQuery(new URLSearchParams("accountIds=a,b&onlyWithBalance=1&page=2")).q.accountIds).toEqual(["a", "b"]);
    });

    it("reads every filter key, the last 12 months by default and a 0-based page", () => {
        const range = defaultLedgerRange();
        expect(readQuery(new URLSearchParams(""))).toEqual({
            q: { from: range.from, to: range.to, propertyId: undefined, accountIds: undefined, renterId: undefined, leaseId: undefined,
                onlyWithBalance: false, hideReversed: false },
            page: 0,
        });
        const full = readQuery(new URLSearchParams(
            "from=2026-01-01&to=2026-06-30&propertyId=p1&accountIds=a,,b&renterId=r1&leaseId=l1&onlyWithBalance=1&hideReversed=1&page=3"));
        expect(full).toEqual({
            q: { from: "2026-01-01", to: "2026-06-30", propertyId: "p1", accountIds: ["a", "b"], renterId: "r1", leaseId: "l1",
                onlyWithBalance: true, hideReversed: true },
            page: 3,
        });
        // A hand-edited page that is not a whole non-negative number opens the first page.
        for (const bad of ["-1", "x", "1.5", ""]) expect(readQuery(new URLSearchParams(`page=${bad}`)).page, bad).toBe(0);
    });

    it("writes the filter keys back, dropping empty picks and unticked switches", () => {
        expect(toUrl({ from: "2026-01-01", to: "2026-06-30" })).toEqual({
            from: "2026-01-01", to: "2026-06-30", propertyId: undefined, accountIds: undefined, renterId: undefined, leaseId: undefined,
            onlyWithBalance: undefined, hideReversed: undefined,
        });
        expect(toUrl({ from: "a", to: "b", propertyId: "p1", accountIds: ["x", "y"], renterId: "r1", leaseId: "l1", onlyWithBalance: true, hideReversed: true }))
            .toEqual({ from: "a", to: "b", propertyId: "p1", accountIds: "x,y", renterId: "r1", leaseId: "l1", onlyWithBalance: "1", hideReversed: "1" });
        expect(toUrl({ from: "a", to: "b", accountIds: [] }).accountIds).toBeUndefined();
        // Round trip.
        const sp = new URLSearchParams();
        for (const [k, v] of Object.entries(toUrl({ from: "a", to: "b", accountIds: ["x"], hideReversed: true }))) if (v) sp.set(k, v);
        expect(readQuery(sp).q).toEqual({ from: "a", to: "b", propertyId: undefined, accountIds: ["x"], renterId: undefined, leaseId: undefined,
            onlyWithBalance: false, hideReversed: true });
    });

    it("needs a tenant, a property, a contract or an account before anything runs", () => {
        expect(hasScope({ from: "a", to: "b" })).toBe(false);
        expect(hasScope({ from: "a", to: "b", accountIds: [] })).toBe(false);
        expect(hasScope({ from: "a", to: "b", onlyWithBalance: true, hideReversed: true })).toBe(false);
        expect(hasScope({ from: "a", to: "b", renterId: "r1" })).toBe(true);
        expect(hasScope({ from: "a", to: "b", propertyId: "p1" })).toBe(true);
        expect(hasScope({ from: "a", to: "b", leaseId: "l1" })).toBe(true);
        expect(hasScope({ from: "a", to: "b", accountIds: ["a1"] })).toBe(true);
    });
});
