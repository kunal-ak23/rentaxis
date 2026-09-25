// src/lib/nav/__tests__/navModel.test.ts
import { describe, expect, it } from "vitest";
import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";
import type { UserRole } from "../../rbac";
import { activeNav, buildNav, flattenNav, type RailSection } from "../navModel";

const ROLES: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT", "TENANT_USER", "RENTER", "SECURITY_GUARD"];
const FLAGS = ["LISTINGS", "MEETINGS", "GATEPASS"] as const;
const ctx = (role: UserRole, on: readonly string[] = FLAGS, booksLive = true) =>
    ({ role, isEnabled: (f: string) => on.includes(f), tenantSlug: "acme", booksLive });
const shape = (rail: RailSection[]) =>
    rail.map(s => [s.id, s.groups.flatMap(g => g.items.map(i => i.id))]);

describe("buildNav — rail sections and panel items per role (all flags on)", () => {
    it("TENANT_ADMIN", () => {
        const rail = buildNav(ctx("TENANT_ADMIN"));
        expect(shape(rail).map(([id]) => id)).toEqual(["home", "leasing", "collection", "accounting", "operations", "settings", "more"]);
        expect(shape(rail)).toEqual([
            ["home", ["today", "unit-status"]],
            ["leasing", ["contracts", "tenants", "properties", "enquiry"]],
            ["collection", ["deposit", "due", "overdue", "returned", "post-dated", "penalties", "all"]],
            ["accounting", ["accounts", "opening-balances", "vouchers", "payment-runs", "issued-cheques", "journals", "credit-note",
                "general-ledger", "tenant-ledger", "trial-balance", "cheque-registers", "aging", "opening-items", "vendors",
                "recognition", "penalties", "bank-accounts", "bank-reconciliation", "balance-sheet", "company-pl", "property-pl",
                "property-statement", "vat-return", "fiscal", "account-template", "charge-types", "import-batches", "reconciliation"]],
            ["operations", ["tickets", "bookings", "staff"]],
            ["settings", ["organisation", "users", "rent", "payments"]],
            ["more", ["meetings", "gatepass", "promotions"]],
        ]);
    });

    it("SUPER_ADMIN adds the Administration group to Settings", () => {
        const settings = buildNav(ctx("SUPER_ADMIN")).find(s => s.id === "settings")!;
        expect(settings.groups.map(g => [g.id, g.items.map(i => i.href)])).toEqual([
            ["sections", ["/dashboard/settings?section=organisation", "/dashboard/settings?section=users", "/dashboard/settings?section=rent", "/dashboard/settings?section=payments"]],
            ["admin", ["/superadmin/tenants", "/superadmin/users"]],
        ]);
    });

    it("PROPERTY_MANAGER", () => {
        expect(shape(buildNav(ctx("PROPERTY_MANAGER")))).toEqual([
            ["home", ["today", "unit-status"]],
            ["leasing", ["contracts", "tenants", "properties", "enquiry"]],
            ["collection", ["deposit", "due", "overdue", "returned", "post-dated", "penalties", "all"]],
            ["accounting", ["aging", "penalties", "balance-sheet", "property-pl", "property-statement"]],
            ["operations", ["tickets", "bookings"]],
            ["more", ["meetings", "gatepass"]],
        ]);
    });

    it("ACCOUNTANT sees Home, Leasing (contracts only), Collection and Accounting", () => {
        const rail = buildNav(ctx("ACCOUNTANT"));
        expect(rail.map(s => s.id)).toEqual(["home", "leasing", "collection", "accounting"]);
        expect(rail[1].groups.flatMap(g => g.items.map(i => i.id))).toEqual(["contracts"]);
    });

    it("TENANT_USER and SECURITY_GUARD see Home only — no My Unit link", () => {
        for (const role of ["TENANT_USER", "SECURITY_GUARD"] as UserRole[]) {
            const rail = buildNav(ctx(role));
            expect(rail.map(s => s.id), role).toEqual(["home"]);
            expect(flattenNav(rail), role).not.toContain("/dashboard/my-unit");
        }
    });

    it("RENTER keeps its own list under Home", () => {
        expect(flattenNav(buildNav(ctx("RENTER")))).toEqual([
            "/dashboard", "/dashboard/renter-portal", "/dashboard/renter-portal/payments",
            "/dashboard/renter-portal/penalties", "/dashboard/tickets", "/marketplace/acme", "/dashboard/meetings",
        ]);
    });

    it("never links a tenant admin to /superadmin/users", () => {
        expect(flattenNav(buildNav(ctx("TENANT_ADMIN")))).not.toContain("/superadmin/users");
    });
});

describe("flags", () => {
    it("hides More when none of its pages is open to the role", () => {
        expect(buildNav(ctx("ACCOUNTANT", [])).map(s => s.id)).not.toContain("more");
        expect(buildNav(ctx("TENANT_USER", [...FLAGS])).map(s => s.id)).not.toContain("more");
    });
    it("keeps Gate pass role-gated, NOT flag-gated (ruling 2026-09-25: tenants use it without the flag)", () => {
        const ids = (on: string[]) => buildNav(ctx("TENANT_ADMIN", on)).find(s => s.id === "more")!.groups[0].items.map(i => i.id);
        expect(ids([])).toEqual(["gatepass", "promotions"]);
        expect(ids(["MEETINGS"])).toEqual(["meetings", "gatepass", "promotions"]);
        expect(ids(["GATEPASS"])).toEqual(["gatepass", "promotions"]);
        // A property manager with every flag off still reaches Gate pass (today's sidebar shows it by role).
        expect(buildNav(ctx("PROPERTY_MANAGER", [])).find(s => s.id === "more")!.groups[0].items.map(i => i.id)).toEqual(["gatepass"]);
    });
    it("gates Meetings on MEETINGS", () => {
        const more = buildNav(ctx("PROPERTY_MANAGER", ["LISTINGS"])).find(s => s.id === "more")!;
        expect(more.groups[0].items.map(i => i.id)).not.toContain("meetings");
    });
    it("gates Enquiry on LISTINGS", () => {
        const leasing = buildNav(ctx("TENANT_ADMIN", [])).find(s => s.id === "leasing")!;
        expect(leasing.groups[0].items.map(i => i.id)).toEqual(["contracts", "tenants", "properties"]);
    });
});

describe("shape guarantees", () => {
    it("never yields an empty section or group for any role × flag combination", () => {
        const combos = [[], ["LISTINGS"], ["MEETINGS"], ["GATEPASS"], ["LISTINGS", "MEETINGS"], ["LISTINGS", "GATEPASS"], ["MEETINGS", "GATEPASS"], [...FLAGS]];
        for (const role of ROLES) {
            for (const on of combos) {
                for (const booksLive of [true, false]) {
                    for (const s of buildNav(ctx(role, on, booksLive))) {
                        expect(s.groups.length, `${role} ${on} ${s.id}`).toBeGreaterThan(0);
                        for (const g of s.groups) expect(g.items.length, `${role} ${on} ${s.id}/${g.id}`).toBeGreaterThan(0);
                        expect(flattenNav([s]), `${role} ${s.id} href`).toContain(s.href);
                    }
                }
            }
        }
    });

    it("lands each rail icon on a page of its own panel", () => {
        for (const role of ROLES) {
            for (const s of buildNav(ctx(role))) {
                const own = s.groups.flatMap(g => g.items.map(i => i.href));
                expect(own, `${role} ${s.id}`).toContain(s.href);
            }
        }
    });

    it("resolves every label in both catalogs", () => {
        const has = (cat: unknown, ns: string, key: string) =>
            typeof (cat as Record<string, Record<string, unknown>>)[ns]?.[key] === "string";
        for (const role of ROLES) {
            for (const s of buildNav(ctx(role))) {
                const labels = [s.label, ...s.groups.flatMap(g => [g.label, ...g.items.map(i => i.label)]), ...s.savedViews.map(v => v.label)]
                    .filter((l): l is { ns: string; key: string } => l !== null);
                for (const l of labels) {
                    expect(has(en, l.ns, l.key), `en ${l.ns}.${l.key}`).toBe(true);
                    expect(has(ar, l.ns, l.key), `ar ${l.ns}.${l.key}`).toBe(true);
                }
            }
        }
    });

    it("badges only Collection and only for cheque roles", () => {
        expect(buildNav(ctx("TENANT_ADMIN")).filter(s => s.badge).map(s => s.id)).toEqual(["collection"]);
        expect(buildNav(ctx("TENANT_USER")).filter(s => s.badge)).toEqual([]);
    });
});

describe("activeNav", () => {
    const rail = buildNav(ctx("TENANT_ADMIN"));
    it.each([
        ["/en/dashboard", "home", "today"],
        ["/ar/dashboard/leases/abc", "leasing", "contracts"],
        ["/en/dashboard/finance/journals/new", "accounting", "journals"],
        ["/en/dashboard/collections", "collection", "deposit"],
        ["/en/dashboard/staff", "operations", "staff"],
        ["/en/dashboard/notifications", null, null],
    ])("%s → %s / %s", (path, section, item) => {
        expect(activeNav(path, rail)).toEqual({ section, item });
    });
});

describe("activeNav — the Collection hub's pills", () => {
    const rail = buildNav(ctx("TENANT_ADMIN"));
    it.each([["?tab=overdue", "overdue"], ["?tab=all&status=BOUNCED", "all"], ["?tab=penalties", "penalties"], ["", "deposit"]])(
        "/ar/dashboard/collections%s lights %s", (search, item) => {
            expect(activeNav("/ar/dashboard/collections", rail, search)).toEqual({ section: "collection", item });
        });
    it("offers Overdue as a saved view", () => {
        expect(buildNav(ctx("TENANT_ADMIN")).find(s => s.id === "collection")!.savedViews.map(v => v.href)).toEqual(["/dashboard/collections?tab=overdue"]);
    });
});

describe("activeNav — pages that share a path and differ by query", () => {
    const rail = buildNav(ctx("TENANT_ADMIN"));
    it.each([
        ["?section=payments", "payments"],
        ["?section=rent&propertyId=p1", "rent"],
        ["?section=users", "users"],
        ["", "organisation"],
        ["?section=nonsense", "organisation"],
    ])("/en/dashboard/settings%s lights %s", (search, item) => {
        expect(activeNav("/en/dashboard/settings", rail, search)).toEqual({ section: "settings", item });
    });
});

describe("rail labels", () => {
    it("gives Collection a short rail label and keeps the PACT name for its panel", () => {
        const collection = buildNav(ctx("TENANT_ADMIN")).find(s => s.id === "collection")!;
        expect(collection.label).toEqual({ ns: "Navigation", key: "collections" });
        expect(collection.railLabel).toEqual({ ns: "Navigation", key: "collectionShort" });
        expect(en.Navigation.collectionShort).toBe("Collection");
        expect(ar.Navigation.collectionShort).toBe("التحصيل");
    });
});
