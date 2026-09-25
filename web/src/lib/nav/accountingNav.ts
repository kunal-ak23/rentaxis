// src/lib/nav/accountingNav.ts
import type { FiscalSettings } from "../api/ledger";
import { hasPermission, type Permission, type UserRole } from "../rbac";
import { buildCollectionsTabs } from "./collectionsModel";
import type { Label } from "./types";

export type AccountingGroupId =
    | "accounts" | "receiptsPayments" | "journalEntries" | "registers"
    | "receivablesPayables" | "bank" | "finalReports" | "yearEnd" | "setup";
export interface AccountingItem { id: string; href: string; label: Label; testId: string; crossLink?: boolean }
export interface AccountingGroup { id: AccountingGroupId; label: Label; items: AccountingItem[]; defaultOpen: boolean }

type ItemDef = AccountingItem & { allow: Permission };
const F = "/dashboard/finance";
const item = (id: string, href: string, ns: string, key: string, testId: string, allow: Permission, crossLink = false): ItemDef =>
    ({ id, href, label: { ns, key }, testId, allow, ...(crossLink ? { crossLink } : {}) });

/** Cheques and penalties live in Collections; the Accounting menu links there. */
function collectionsHref(id: "all" | "penalties"): string {
    const tabs = buildCollectionsTabs("SUPER_ADMIN");
    return tabs.find(t => t.id === id)!.href;
}

/**
 * The Accounting area, grouped the way the PACT RevenU Finance ribbon groups
 * it (spec "Terminology"). Every item keeps the exact gate its sidebar link
 * had before (MvpSidebar.tsx at 2026-09-25), so no role gains or loses a page.
 * Debit Note is not listed: there is no debit-note page to link to.
 */
const GROUPS: { id: AccountingGroupId; key: string; items: ItemDef[] }[] = [
    { id: "accounts", key: "groupAccounts", items: [
        item("accounts", `${F}/accounts`, "MasterData", "chartOfAccounts", "sidebar-accounts", "canAccessFinance"),
        item("opening-balances", `${F}/opening-balances`, "Cutover", "openingBalances", "sidebar-opening-balances", "canManageOpeningBalances"),
    ] },
    { id: "receiptsPayments", key: "groupReceiptsPayments", items: [
        item("vouchers", `${F}/vouchers`, "AccountingNav", "receiptPaymentVouchers", "sidebar-vouchers", "canManageVouchers"),
        item("payment-runs", `${F}/payables/payment-runs`, "Payables", "paymentRuns", "sidebar-payables-runs", "canManagePayables"),
        item("issued-cheques", `${F}/payables/issued-cheques`, "Payables", "issuedCheques", "sidebar-payables-issued-cheques", "canManagePayables"),
    ] },
    { id: "journalEntries", key: "groupJournalEntries", items: [
        item("journals", `${F}/journals`, "Ledger", "journals", "sidebar-journals", "canAccessFinance"),
        item("credit-note", `${F}/vouchers/credit-note`, "AccountingNav", "creditNote", "sidebar-credit-note", "canManageVouchers"),
    ] },
    { id: "registers", key: "groupRegisters", items: [
        item("general-ledger", `${F}/general-ledger`, "Ledger", "generalLedger", "sidebar-general-ledger", "canAccessFinance"),
        item("tenant-ledger", `${F}/tenant-ledger`, "Ledger", "tenantLedger", "sidebar-tenant-ledger", "canAccessFinance"),
        item("trial-balance", `${F}/trial-balance`, "Ledger", "trialBalance", "sidebar-trial-balance", "canAccessFinance"),
        item("cheque-registers", collectionsHref("all"), "AccountingNav", "chequeRegisters", "sidebar-cheques-register", "canManageCheques", true),
    ] },
    { id: "receivablesPayables", key: "groupReceivablesPayables", items: [
        item("aging", `${F}/payables/aging`, "Payables", "aging", "sidebar-payables-aging", "canViewPayablesAging"),
        item("opening-items", `${F}/payables/opening-items`, "Payables", "openingItems", "sidebar-payables-opening", "canManagePayables"),
        item("vendors", `${F}/vendors`, "Vendors", "title", "sidebar-vendors", "canManageVendors"),
        item("recognition", `${F}/recognition`, "Recognition", "title", "sidebar-recognition", "canRunRecognition"),
        item("penalties", collectionsHref("penalties"), "AccountingNav", "penalties", "sidebar-penalties", "canProposePenalties", true),
    ] },
    { id: "bank", key: "groupBank", items: [
        item("bank-accounts", `${F}/bank-accounts`, "BankAccounts", "title", "sidebar-bank-accounts", "canAccessFinanceOps"),
        item("bank-reconciliation", `${F}/bank-reconciliation`, "BankRec", "sidebar", "sidebar-bank-reconciliation", "canReconcileBank"),
    ] },
    { id: "finalReports", key: "groupFinalReports", items: [
        item("balance-sheet", `${F}/reports/balance-sheet`, "PropertyReports", "balanceSheet", "sidebar-balance-sheet", "canViewPropertyReports"),
        item("company-pl", `${F}/reports/company-pl`, "PropertyReports", "companyPl", "sidebar-company-pl", "canViewCompanyReports"),
        item("property-pl", `${F}/reports/property-pl`, "PropertyReports", "propertyPl", "sidebar-property-pl", "canViewPropertyReports"),
        item("property-statement", `${F}/reports/property-statement`, "PropertyReports", "propertyStatement", "sidebar-property-statement", "canViewPropertyReports"),
        item("vat-return", `${F}/reports/vat-return`, "PropertyReports", "vatReturn", "sidebar-vat-return", "canViewVatReturn"),
    ] },
    { id: "yearEnd", key: "groupYearEnd", items: [
        item("fiscal", `${F}/fiscal`, "Ledger", "fiscal", "sidebar-fiscal", "canManageAccountSetup"),
    ] },
    { id: "setup", key: "groupSetup", items: [
        item("account-template", `${F}/account-template`, "Ledger", "accountTemplate", "sidebar-account-template", "canManageAccountSetup"),
        item("charge-types", `${F}/charge-types`, "Ledger", "chargeTypes", "sidebar-charge-types", "canManageAccountSetup"),
        item("import-batches", `${F}/import-batches`, "Cutover", "importBatches", "sidebar-import-batches", "canManageImportBatches"),
        item("reconciliation", `${F}/reconciliation`, "AccountingNav", "cutoverReconciliation", "sidebar-reconciliation", "canManageOpeningBalances"),
    ] },
];

export function buildAccountingNav(role: UserRole | undefined, opts: { booksLive: boolean }): AccountingGroup[] {
    return GROUPS
        .map(g => ({
            id: g.id,
            label: { ns: "AccountingNav", key: g.key },
            items: g.items.filter(i => hasPermission(role, i.allow))
                .map(({ id, href, label, testId, crossLink }) => ({ id, href, label, testId, ...(crossLink ? { crossLink } : {}) })),
            defaultOpen: g.id === "setup" && !opts.booksLive,
        }))
        .filter(g => g.items.some(i => !i.crossLink));
}

/** Where the Accounting entry lands: Journal Voucher, else the Property Profit Report, else the first own page. */
export function accountingHome(role: UserRole | undefined): string | null {
    const own = buildAccountingNav(role, { booksLive: true }).flatMap(g => g.items).filter(i => !i.crossLink);
    return (own.find(i => i.id === "journals") ?? own.find(i => i.id === "property-pl") ?? own[0])?.href ?? null;
}

/** The books are live once the period lock reaches the cut-over (books-start) date. */
export function isBooksLive(fs: Pick<FiscalSettings, "booksStartDate" | "booksLockedThrough"> | null): boolean {
    if (!fs?.booksStartDate || !fs.booksLockedThrough) return false;
    return fs.booksLockedThrough >= fs.booksStartDate;
}
