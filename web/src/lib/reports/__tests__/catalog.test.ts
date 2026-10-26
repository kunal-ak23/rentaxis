import { describe, expect, it } from "vitest";
import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";
import { HELP_PAGE_MAP } from "../../help";
import { buildAccountingNav } from "../../nav/accountingNav";
import { DASHBOARD_ROUTES } from "../../nav/routeRegistry";
import { PERMISSIONS } from "../../rbac";
import { REPORT_CATALOG, catalogNavItems, visibleReports } from "../catalog";

describe("report catalog", () => {
    it("every entry has a real permission, a registered route with the same gate and a help entry", () => {
        for (const e of REPORT_CATALOG) {
            expect(PERMISSIONS[e.permission], e.key).toBeDefined();
            const route = DASHBOARD_ROUTES.find(r => r.path === e.href);
            expect(route?.allow, e.key).toBe(e.permission);
            expect(HELP_PAGE_MAP[e.href], e.key).toBeDefined();
        }
        expect(DASHBOARD_ROUTES.find(r => r.path === "/dashboard/reports")?.allow).toBe("canViewReportsHub");
        expect(HELP_PAGE_MAP["/dashboard/reports"]).toBeDefined();
    });

    it("the hub admits exactly the roles that can open some report", () => {
        const roles = new Set(REPORT_CATALOG.flatMap(e => PERMISSIONS[e.permission]));
        expect(new Set(PERMISSIONS.canViewReportsHub)).toEqual(roles);
    });

    it("filters by role", () => {
        expect(visibleReports("PROPERTY_MANAGER").map(e => e.key)).toEqual(["balance-sheet", "property-pl", "property-statement", "payables-aging"]);
        expect(visibleReports("ACCOUNTANT").map(e => e.key)).toContain("tenant-ledger");
        expect(visibleReports("RENTER")).toEqual([]);
        expect(visibleReports(undefined)).toEqual([]);
    });

    it("drives the Accounting nav's report items", () => {
        expect(buildAccountingNav("TENANT_ADMIN", { booksLive: true }).find(g => g.id === "registers")!.items.map(i => i.id))
            .toEqual(["general-ledger", "tenant-ledger", "trial-balance", "cheque-registers"]);
        expect(buildAccountingNav("TENANT_ADMIN", { booksLive: true }).find(g => g.id === "finalReports")!.items.map(i => i.id))
            .toEqual(["reports-hub", "balance-sheet", "company-pl", "property-pl", "property-statement", "vat-return"]);
        expect(catalogNavItems("registers").map(i => i.testId))
            .toEqual(["sidebar-general-ledger", "sidebar-tenant-ledger", "sidebar-trial-balance"]);
    });

    it("has unique keys, and a title and description in both catalogs", () => {
        expect(new Set(REPORT_CATALOG.map(e => e.key)).size).toBe(REPORT_CATALOG.length);
        const has = (cat: unknown, ns: string, key: string) =>
            typeof (cat as Record<string, Record<string, unknown>>)[ns]?.[key] === "string";
        for (const e of REPORT_CATALOG) {
            for (const [name, cat] of [["en", en], ["ar", ar]] as const) {
                expect(has(cat, e.title.ns, e.title.key), `${name} ${e.key} title`).toBe(true);
                expect(has(cat, "Reports", e.descKey), `${name} ${e.key} description`).toBe(true);
            }
        }
    });
});
