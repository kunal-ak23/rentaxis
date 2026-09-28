// src/lib/__tests__/terminology.test.ts
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import ar from "../../../messages/ar.json";
import en from "../../../messages/en.json";
import { getAllArticles } from "../helpLoader";
import "../helpArticles";

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
    "Roles.TENANT_USER": { en: "Company User", ar: "مستخدم الشركة" },
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

    /** Renter/Lease in copy a person reads; slugs, categories and tour ids are code. */
    const STALE_WORD = /\b(Renters?|renters?|Leases?|leases?)\b/;

    it("says Tenant / Contract in every help article's title, summary and body", () => {
        const stale = getAllArticles().flatMap(a =>
            ([["title", a.title], ["description", a.description], ["content", a.content]] as const)
                .filter(([, text]) => STALE_WORD.test(text.replace(/\]\([^)]*\)/g, "]")))
                .map(([field, text]) => `${a.slug}.${field}: ${text.match(STALE_WORD)?.[0]}`));
        expect(stale).toEqual([]);
    });

    it("keeps the help articles' authoring copy on the same terms", () => {
        const dir = join(__dirname, "../../content/help");
        const stale = readdirSync(dir).filter(f => f.endsWith(".md")).flatMap(f =>
            readFileSync(join(dir, f), "utf8").split("\n")
                .filter(l => !/^(slug|category|relatedTour|roles):/.test(l))
                .filter(l => STALE_WORD.test(l.replace(/\]\([^)]*\)/g, "]")))
                .map(l => `${f}: ${l.trim()}`));
        expect(stale).toEqual([]);
    });

    /**
     * Hard-coded copy the message catalogue cannot see: JSX text between tags and
     * label/title/placeholder/description string props (the property page's "Leases"
     * tab was one of these, in English under /ar too).
     */
    it("has no hard-coded Renter / Lease copy in pages and components", () => {
        const root = join(__dirname, "../..");
        const files: string[] = [];
        const walk = (d: string) => readdirSync(d).forEach(n => {
            const p = join(d, n);
            if (statSync(p).isDirectory()) { if (n !== "__tests__") walk(p); }
            else if (p.endsWith(".tsx")) files.push(p);
        });
        walk(join(root, "app"));
        walk(join(root, "components"));
        const jsxText = />([^<>{}]*)<\//g;
        const prop = /\b(?:label|title|placeholder|aria-label|description)\s*[:=]\s*["']([^"']*)["']/g;
        // alert("…"), confirm("…"), toast.error("…"), setError("…") — messages a person reads.
        const call = /\b(?:alert|confirm|toast\.\w+|setError)\(\s*(["'`])((?:(?!\1).)*)\1/g;
        const stale = files.flatMap(f => readFileSync(f, "utf8").split("\n").flatMap((line, i) =>
            [...[...line.matchAll(jsxText), ...line.matchAll(prop)].map(m => m[1]),
                ...[...line.matchAll(call)].map(m => m[2])]
                .filter(text => STALE_WORD.test(text))
                .map(text => `${f.slice(root.length + 1)}:${i + 1}: ${text.trim()}`)));
        expect(stale).toEqual([]);
    });

    it("calls a listing interest an Enquiry", () => {
        const stale = [...EN].filter(([k, v]) => k.startsWith("Listings.") && /\binterests?\b/i.test(v));
        expect(stale.map(([k, v]) => `${k}: ${v}`)).toEqual([]);
    });
});
