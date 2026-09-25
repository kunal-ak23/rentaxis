// src/lib/__tests__/terminology.test.ts
import { describe, expect, it } from "vitest";
import ar from "../../../messages/ar.json";
import en from "../../../messages/en.json";

type Tree = { [k: string]: string | Tree };
const flatten = (tree: Tree, prefix = ""): [string, string][] =>
    Object.entries(tree).flatMap(([k, v]) =>
        typeof v === "string" ? [[prefix + k, v] as [string, string]] : flatten(v, `${prefix}${k}.`));
const EN = new Map(flatten(en as Tree));
const AR = new Map(flatten(ar as Tree));

/** Spec 2026-09-25 "Terminology" table, key by key. */
export const TERMS: Record<string, { en: string; ar: string }> = {
    // Organisation, never "Tenant"
    "Navigation.tenants": { en: "Organisations", ar: "المؤسسات" },
    "Roles.TENANT_ADMIN": { en: "Company Admin", ar: "مدير المؤسسة" },
    "Roles.TENANT_USER": { en: "Company User", ar: "مستخدم المؤسسة" },
    "Ledger.tenantWide": { en: "Company-wide", ar: "على مستوى المؤسسة" },
    "Ledger.defaultAccountsDesc": { en: "Company-wide accounts used when a property has no mapping.", ar: "الحسابات على مستوى المؤسسة تُستخدم عندما لا يوجد ربط للعقار." },
    // Renter → Tenant, Lease → Tenancy Contract
    "Roles.RENTER": { en: "Tenant", ar: "مستأجر" },
    "MasterData.renters": { en: "Tenants", ar: "المستأجرون" },
    "MasterData.renter": { en: "Tenant", ar: "المستأجر" },
    "MasterData.addRenter": { en: "Add Tenant", ar: "إضافة مستأجر" },
    "MasterData.leases": { en: "Tenancy Contracts", ar: "عقود الإيجار" },
    "Navigation.contracts": { en: "Contracts", ar: "العقود" },
    "Dashboard.newLease": { en: "New Contract", ar: "عقد جديد" },
    "Leasing.extend": { en: "Extend Contract", ar: "تمديد العقد" },
    "Leasing.renew": { en: "Renew", ar: "تجديد" },
    "Leasing.terminate": { en: "Terminate", ar: "إنهاء" },
    "Leasing.ledger": { en: "Ledger", ar: "دفتر الأستاذ" },
    "Ledger.tenantLedger": { en: "Tenant Ledger", ar: "دفتر أستاذ المستأجر" },
    // Particulars grid
    "Leasing.particulars": { en: "Particulars", ar: "البيان" },
    "Leasing.creditAccount": { en: "Credit A/c", ar: "الحساب الدائن" },
    "Leasing.grossAmount": { en: "Rent Amount", ar: "مبلغ الإيجار" },
    "Leasing.discount": { en: "Discount Amount", ar: "مبلغ الخصم" },
    "Leasing.afterDiscount": { en: "After Discount Amount", ar: "المبلغ بعد الخصم" },
    "Leasing.narration": { en: "Narration", ar: "البيان" },
    // Cheque grid
    "Leasing.postingDate": { en: "Posting Date", ar: "تاريخ القيد" },
    "Leasing.chequeNo": { en: "Cheque No", ar: "رقم الشيك" },
    "Leasing.chequeDate": { en: "Date", ar: "التاريخ" },
    "Leasing.payeeBank": { en: "Payee Bank", ar: "بنك المستفيد" },
    "Leasing.debitAccount": { en: "Debit A/c", ar: "الحساب المدين" },
    "Leasing.amount": { en: "Amount", ar: "المبلغ" },
    // Vouchers and reports
    "Cheques.receipt": { en: "Receipt Voucher", ar: "سند قبض" },
    "Vouchers.paymentVoucher": { en: "Payment Voucher", ar: "سند صرف" },
    "Vouchers.purchaseInvoice": { en: "Purchase Invoice", ar: "فاتورة مشتريات" },
    "Ledger.journals": { en: "Journal Voucher", ar: "سند قيد" },
    "PropertyReports.propertyPl": { en: "Property Profit Report", ar: "تقرير أرباح العقار" },
    "PropertyReports.companyPl": { en: "Profit & Loss", ar: "الأرباح والخسائر" },
    "PropertyReports.propertyStatement": { en: "Owner Statement", ar: "كشف حساب المالك" },
    "Ledger.fiscal": { en: "Year End Closing", ar: "إقفال نهاية السنة" },
    // Shell
    "Navigation.home": { en: "Home", ar: "الرئيسية" },
    "Navigation.collections": { en: "Cheque / Cash Collection", ar: "تحصيل الشيكات والنقد" },
    "Navigation.accounting": { en: "Accounting", ar: "المحاسبة" },
    "Navigation.settings": { en: "Settings", ar: "الإعدادات" },
    "Navigation.more": { en: "More", ar: "المزيد" },
    "Navigation.sectionAdmin": { en: "Administration", ar: "الإدارة" },
    "Navigation.expandSection": { en: "Show {name} pages", ar: "عرض صفحات {name}" },
    "Navigation.openUnitsPage": { en: "Open the full units page", ar: "فتح صفحة الوحدات الكاملة" },
    "TenantSwitcher.organization": { en: "Organisation", ar: "المؤسسة" },
};

describe("PACT terminology", () => {
    it.each(Object.entries(TERMS))("%s carries the PACT label in both locales", (key, want) => {
        expect(EN.get(key), `en ${key}`).toBe(want.en);
        expect(AR.get(key), `ar ${key}`).toBe(want.ar);
    });

    it("never shows the organisation as 'Tenant' in English", () => {
        // Keys whose value legitimately names the renter (the PACT meaning).
        const renterMeaning = /^(Ledger\.(tenant|tenantName|tenantLedger)|Cheques\.tenant|MasterData\.(currentTenant|renter|renters|addRenter)|Roles\.RENTER)$/;
        const orgy = [...EN].filter(([k, v]) => /\bTenant\s*(Admin|User|-wide|wide)|\bTenants\b.*organi[sz]ation/i.test(v) && !renterMeaning.test(k));
        expect(orgy).toEqual([]);
    });

    it("says Tenant / Contract, not Renter / Lease, in every English label", () => {
        // Allowlist: product names and API-facing copy that must keep the word.
        const allow = /^(Common\.errors\.)/;
        // ICU argument names ({renter}, {leases, plural, …}) are code, not copy.
        const copy = (v: string) => v.replace(/\{\s*\w+/g, "{");
        const stale = [...EN].filter(([k, v]) => !allow.test(k) && /\b(Renters?|renters?|Leases?|leases?)\b/.test(copy(v)));
        expect(stale.map(([k, v]) => `${k}: ${v}`)).toEqual([]);
    });

    it("calls a listing interest an Enquiry", () => {
        const stale = [...EN].filter(([k, v]) => k.startsWith("Listings.") && /\binterests?\b/i.test(v));
        expect(stale.map(([k, v]) => `${k}: ${v}`)).toEqual([]);
    });
});
