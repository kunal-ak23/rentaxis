// src/lib/finance/__tests__/ledgerReport.test.ts
import { describe, expect, it } from "vitest";
import type { AccountLedger, LedgerRow } from "@/lib/api/ledger";
import { buildLedgerReport, drCr } from "../ledgerReport";

const row = (n: string, debit: number, credit: number, balance: number): LedgerRow => ({
    entryId: `e${n}`, entryNumber: `JV-${n}`, entryDate: "2026-09-01", docType: "JV", particular: "Rent", narration: "",
    debit, credit, balance, propertyId: "p1", unitId: "u1", leaseId: "l1", renterId: "r1", chequeId: null,
});
const ledger = (id: string, code: string, opening: number, rows: LedgerRow[], extra: Partial<AccountLedger> = {}): AccountLedger => ({
    accountId: id, accountCode: code, accountName: `Account ${code}`, accountType: "ASSET", openingBalance: opening, rows,
    totalDebit: rows.reduce((s, r) => s + r.debit, 0), totalCredit: rows.reduce((s, r) => s + r.credit, 0),
    closingBalance: opening + rows.reduce((s, r) => s + r.debit - r.credit, 0), truncated: false, ...extra,
});

describe("buildLedgerReport", () => {
    it("keeps one group per account, in account-code order (numeric)", () => {
        const r = buildLedgerReport([ledger("b", "1200", 0, []), ledger("a", "1100", 0, []), ledger("c", "900", 0, [])]);
        expect(r.groups.map(g => g.code)).toEqual(["900", "1100", "1200"]);
    });

    it("sub-totals each account from its own rows: Σdebit, Σcredit, brought forward + Σdebit − Σcredit", () => {
        const g = buildLedgerReport([ledger("a", "1100", 100, [row("1", 500, 0, 600), row("2", 0, 200, 400)])]).groups[0];
        expect(g.opening).toBe(100);
        expect(g.subTotal).toEqual({ debit: 500, credit: 200, balance: 400 });
    });

    it("carries a credit balance brought forward into a Cr sub-total", () => {
        const g = buildLedgerReport([ledger("a", "125620", -51000, [row("1", 991.67, 0, -50008.33), row("2", 4250, 0, -45758.33)])]).groups[0];
        expect(drCr(g.opening)).toBe("51,000.00 Cr");
        expect(drCr(g.subTotal.balance)).toBe("45,758.33 Cr");
    });

    it("falls back to the server totals when the rows were truncated", () => {
        const l = ledger("a", "1100", 0, [row("1", 10, 0, 10)], { truncated: true, totalDebit: 999, totalCredit: 1, closingBalance: 998 });
        expect(buildLedgerReport([l]).groups[0].subTotal).toEqual({ debit: 999, credit: 1, balance: 998 });
    });

    it("totals the report across accounts", () => {
        const r = buildLedgerReport([
            ledger("a", "1100", 0, [row("1", 500, 0, 500)]),
            ledger("b", "2100", 0, [row("2", 0, 300, -300)]),
        ]);
        expect(r.total).toEqual({ debit: 500, credit: 300, balance: 200 });
    });

    it("rounds sums to fils so floating error never shows", () => {
        const r = buildLedgerReport([ledger("a", "1100", 0, [row("1", 0.1, 0, 0.1), row("2", 0.2, 0, 0.3)])]);
        expect(r.groups[0].subTotal.debit).toBe(0.3);
    });
});

describe("drCr", () => {
    it("suffixes a debit balance Dr, a credit balance Cr, and zero plain", () => {
        expect(drCr(1234.5)).toBe("1,234.50 Dr");
        expect(drCr(-10)).toBe("10.00 Cr");
        expect(drCr(0)).toBe("0.00");
        expect(drCr(0.004)).toBe("0.00");
    });
});
