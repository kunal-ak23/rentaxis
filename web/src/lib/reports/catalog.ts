// src/lib/reports/catalog.ts
import { hasPermission, type Permission, type UserRole } from "../rbac";
import type { Label } from "../nav/types";

export type ReportFileFormat = "xlsx" | "pdf" | "csv";
export type ReportGroup = "registers" | "final";

/**
 * One report the product can run. The Reports hub lists these; the Accounting
 * nav takes its Registers and Final Reports items from the entries with a `nav`
 * slot. `permission` is the page's own gate — the same key its route entry in
 * routeRegistry.ts carries (catalog.test.ts holds the two together).
 */
export interface ReportEntry {
    key: string;
    group: ReportGroup;
    title: Label;
    /** A key in the `Reports` namespace. */
    descKey: string;
    href: string;
    permission: Permission;
    /** The file formats the page exports. */
    formats: ReportFileFormat[];
    /** A property manager sees only their assigned properties on it (narrowed server-side). */
    pmScoped: boolean;
    /** Where the entry sits in the Accounting nav (`key` is the item id), or null when the nav lists it by hand. */
    nav: { group: "registers" | "finalReports"; testId: string } | null;
}

const F = "/dashboard/finance";

const entry = (
    key: string, group: ReportGroup, title: Label, descKey: string, href: string, permission: Permission,
    formats: ReportFileFormat[], pmScoped: boolean, nav: ReportEntry["nav"],
): ReportEntry => ({ key, group, title, descKey, href, permission, formats, pmScoped, nav });

/** Every report, in the Accounting nav's order. */
export const REPORT_CATALOG: ReportEntry[] = [
    entry("general-ledger", "registers", { ns: "Ledger", key: "generalLedger" }, "descGeneralLedger", `${F}/general-ledger`,
        "canAccessFinance", ["csv"], false, { group: "registers", testId: "sidebar-general-ledger" }),
    entry("tenant-ledger", "registers", { ns: "Ledger", key: "tenantLedger" }, "descTenantLedger", `${F}/tenant-ledger`,
        "canAccessFinance", ["xlsx", "pdf", "csv"], false, { group: "registers", testId: "sidebar-tenant-ledger" }),
    entry("trial-balance", "registers", { ns: "Ledger", key: "trialBalance" }, "descTrialBalance", `${F}/trial-balance`,
        "canAccessFinance", ["csv"], false, { group: "registers", testId: "sidebar-trial-balance" }),
    entry("balance-sheet", "final", { ns: "PropertyReports", key: "balanceSheet" }, "descBalanceSheet", `${F}/reports/balance-sheet`,
        "canViewPropertyReports", ["pdf", "csv"], true, { group: "finalReports", testId: "sidebar-balance-sheet" }),
    entry("company-pl", "final", { ns: "PropertyReports", key: "companyPl" }, "descCompanyPl", `${F}/reports/company-pl`,
        "canViewCompanyReports", ["pdf", "csv"], false, { group: "finalReports", testId: "sidebar-company-pl" }),
    entry("property-pl", "final", { ns: "PropertyReports", key: "propertyPl" }, "descPropertyPl", `${F}/reports/property-pl`,
        "canViewPropertyReports", ["pdf", "csv"], true, { group: "finalReports", testId: "sidebar-property-pl" }),
    entry("property-statement", "final", { ns: "PropertyReports", key: "propertyStatement" }, "descPropertyStatement", `${F}/reports/property-statement`,
        "canViewPropertyReports", ["pdf", "csv"], true, { group: "finalReports", testId: "sidebar-property-statement" }),
    entry("vat-return", "final", { ns: "PropertyReports", key: "vatReturn" }, "descVatReturn", `${F}/reports/vat-return`,
        "canViewVatReturn", ["pdf", "csv"], false, { group: "finalReports", testId: "sidebar-vat-return" }),
    // Listed by hand under Receivables & Payables in the nav, where PACT keeps it.
    entry("payables-aging", "final", { ns: "Payables", key: "aging" }, "descPayablesAging", `${F}/payables/aging`,
        "canViewPayablesAging", ["csv"], true, null),
];

/** The reports a role can open, in catalog order. */
export function visibleReports(role: UserRole | undefined): ReportEntry[] {
    return REPORT_CATALOG.filter(e => hasPermission(role, e.permission));
}

/** The Accounting nav items for one group, unfiltered — the nav filters by `allow`. */
export function catalogNavItems(group: "registers" | "finalReports"): { id: string; href: string; label: Label; testId: string; allow: Permission }[] {
    return REPORT_CATALOG.flatMap(e => e.nav?.group === group
        ? [{ id: e.key, href: e.href, label: e.title, testId: e.nav.testId, allow: e.permission }]
        : []);
}
