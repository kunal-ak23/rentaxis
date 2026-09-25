// src/lib/nav/__tests__/models.test.ts
import { describe, expect, it } from "vitest";
import type { UserRole } from "../../rbac";
import { accountingHome, buildAccountingNav, isBooksLive } from "../accountingNav";
import { buildCollectionsTabs } from "../collectionsModel";
import { buildSettingsSections } from "../settingsModel";

const ROLES: UserRole[] = ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "ACCOUNTANT", "TENANT_USER", "RENTER", "SECURITY_GUARD"];
const hrefs = (role: UserRole, booksLive = true) =>
    buildAccountingNav(role, { booksLive }).flatMap(g => g.items.map(i => i.href));

describe("buildAccountingNav", () => {
    it("groups a tenant admin's pages the PACT Finance ribbon's way", () => {
        const groups = buildAccountingNav("TENANT_ADMIN", { booksLive: false });
        expect(groups.map(g => [g.id, g.items.map(i => i.id)])).toEqual([
            ["accounts", ["accounts", "opening-balances"]],
            ["receiptsPayments", ["vouchers", "payment-runs", "issued-cheques"]],
            ["journalEntries", ["journals", "credit-note"]],
            ["registers", ["general-ledger", "tenant-ledger", "trial-balance", "cheque-registers"]],
            ["receivablesPayables", ["aging", "opening-items", "vendors", "recognition", "penalties"]],
            ["bank", ["bank-accounts", "bank-reconciliation"]],
            ["finalReports", ["balance-sheet", "company-pl", "property-pl", "property-statement", "vat-return"]],
            ["yearEnd", ["fiscal"]],
            ["setup", ["account-template", "charge-types", "import-batches", "reconciliation"]],
        ]);
    });

    it("offers an accountant NO link whose controller refuses the role", () => {
        const links = hrefs("ACCOUNTANT");
        expect(links).not.toContain("/dashboard/finance/bank-accounts");
        expect(links).toEqual(expect.arrayContaining([
            "/dashboard/finance/payables/payment-runs",
            "/dashboard/finance/payables/issued-cheques",
            "/dashboard/finance/account-template",
            "/dashboard/finance/fiscal",
        ]));
    });

    it("offers a property manager the property reports, balance sheet and payables aging only", () => {
        const own = buildAccountingNav("PROPERTY_MANAGER", { booksLive: true })
            .flatMap(g => g.items).filter(i => !i.crossLink).map(i => i.href);
        expect(own.sort()).toEqual([
            "/dashboard/finance/payables/aging",
            "/dashboard/finance/reports/balance-sheet",
            "/dashboard/finance/reports/property-pl",
            "/dashboard/finance/reports/property-statement",
        ]);
    });

    it("never yields an empty group", () => {
        for (const role of ROLES) {
            for (const booksLive of [true, false]) {
                for (const g of buildAccountingNav(role, { booksLive })) {
                    expect(g.items.length, `${role} ${g.id}`).toBeGreaterThan(0);
                }
            }
        }
    });

    it("never yields a group of cross-links alone", () => {
        for (const role of ROLES) {
            for (const g of buildAccountingNav(role, { booksLive: true })) {
                expect(g.items.some(i => !i.crossLink), `${role} ${g.id}`).toBe(true);
            }
        }
    });

    it("opens One-time setup until the books are live, then collapses it", () => {
        const setup = (live: boolean) => buildAccountingNav("TENANT_ADMIN", { booksLive: live }).find(g => g.id === "setup")!;
        expect(setup(false).defaultOpen).toBe(true);
        expect(setup(true).defaultOpen).toBe(false);
    });

    it("lands on Journal Voucher for finance roles and the Property Profit Report for a manager", () => {
        expect(accountingHome("TENANT_ADMIN")).toBe("/dashboard/finance/journals");
        expect(accountingHome("ACCOUNTANT")).toBe("/dashboard/finance/journals");
        expect(accountingHome("PROPERTY_MANAGER")).toBe("/dashboard/finance/reports/property-pl");
        expect(accountingHome("TENANT_USER")).toBeNull();
        expect(accountingHome("RENTER")).toBeNull();
        expect(accountingHome(undefined)).toBeNull();
    });
});

describe("isBooksLive", () => {
    it("is live once the lock date reaches the cut-over date", () => {
        expect(isBooksLive(null)).toBe(false);
        expect(isBooksLive({ booksStartDate: null, booksLockedThrough: null })).toBe(false);
        expect(isBooksLive({ booksStartDate: "2026-01-01", booksLockedThrough: null })).toBe(false);
        expect(isBooksLive({ booksStartDate: "2026-01-01", booksLockedThrough: "2025-12-31" })).toBe(false);
        expect(isBooksLive({ booksStartDate: "2026-01-01", booksLockedThrough: "2026-01-01" })).toBe(true);
    });
});

describe("buildCollectionsTabs", () => {
    it("gives cheque roles the cheque tabs and the penalty queue", () => {
        for (const role of ["TENANT_ADMIN", "ACCOUNTANT", "PROPERTY_MANAGER", "SUPER_ADMIN"] as UserRole[]) {
            expect(buildCollectionsTabs(role).map(t => t.id), role).toEqual(["deposit", "due", "overdue", "returned", "post-dated", "penalties", "all"]);
        }
    });
    it("points every tab at the hub", () => {
        expect(buildCollectionsTabs("TENANT_ADMIN").map(t => t.href)).toEqual(
            ["deposit", "due", "overdue", "returned", "post-dated", "penalties", "all"].map(id => `/dashboard/collections?tab=${id}`));
    });
    it("gives everyone else nothing", () => {
        for (const role of ["TENANT_USER", "RENTER", "SECURITY_GUARD"] as UserRole[]) {
            expect(buildCollectionsTabs(role), role).toEqual([]);
        }
        expect(buildCollectionsTabs(undefined)).toEqual([]);
    });
});

describe("buildSettingsSections", () => {
    it("gives a tenant admin all four sections", () => {
        expect(buildSettingsSections("TENANT_ADMIN").map(s => s.href)).toEqual([
            "/dashboard/settings?section=organisation",
            "/dashboard/settings?section=users",
            "/dashboard/settings?section=rent",
            "/dashboard/settings?section=payments",
        ]);
    });
    it("gives no other role a section", () => {
        for (const role of ["PROPERTY_MANAGER", "ACCOUNTANT", "TENANT_USER", "RENTER", "SECURITY_GUARD"] as UserRole[]) {
            expect(buildSettingsSections(role), role).toEqual([]);
        }
    });
});
