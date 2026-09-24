import { describe, expect, it } from "vitest";
import type { Account } from "@/lib/api/ledger";
import { autoAllocate, dueDateFrom, overdueOf } from "@/lib/api/payables";
import { draftRefusal } from "@/lib/voucherRules";
import { hasPermission } from "@/lib/rbac";

/** Supplier AP rules the web mirrors (finance-ops spec §2). */

const expense = { accountId: "a1", amount: 1000, vatRate: 5 };
const account = (over: Partial<Account>): Account => ({
    id: "x", code: "X", name: "X", accountType: "ASSET", accountSubType: "BANK", group: false, active: true,
    ...over,
} as Account);

describe("purchase invoice rules", () => {
    it("needs the supplier's invoice number, ignoring spaces and hyphens", () => {
        const base = { type: "PISR" as const, vendorId: "v1", paymentAccountId: null, lines: [expense] };
        expect(draftRefusal({ ...base, invoiceNumber: " - " })?.key).toBe("invoiceNumberRequired");
        expect(draftRefusal({ ...base, invoiceNumber: "INV-7781" })).toBeNull();
        // A caller that does not know the number yet is not refused on it.
        expect(draftRefusal(base)).toBeNull();
    });

    it("refuses input VAT for a vendor known to have no TRN", () => {
        const base = { type: "PISR" as const, vendorId: "v1", paymentAccountId: null, invoiceNumber: "INV-1" };
        expect(draftRefusal({ ...base, lines: [expense], vendorHasTrn: false })?.key).toBe("vatNeedsTrn");
        expect(draftRefusal({ ...base, lines: [{ ...expense, vatRate: 0 }], vendorHasTrn: false })).toBeNull();
        expect(draftRefusal({ ...base, lines: [expense], vendorHasTrn: true })).toBeNull();
    });

    it("defaults the due date to the supplier date plus the terms, across a month end", () => {
        expect(dueDateFrom("2026-08-01", 30)).toBe("2026-08-31");
        expect(dueDateFrom("2026-08-20", 30)).toBe("2026-09-19");
        expect(dueDateFrom("2026-12-15", 45)).toBe("2027-01-29");
        expect(dueDateFrom("2026-08-20", null)).toBe("2026-09-19");
        expect(dueDateFrom("", 30)).toBe("");
    });
});

describe("payment voucher rules", () => {
    const line = { accountId: "pay-1", amount: 2050, vatRate: 0 };
    const accounts = {
        bank: account({ id: "bank", accountSubType: "BANK" }),
        cash: account({ id: "cash", accountSubType: "CASH" }),
    };

    it("matches the method to the payment account", () => {
        expect(draftRefusal({ type: "BPV", vendorId: "v1", paymentAccountId: "bank", lines: [line], accounts,
            paymentMethod: "CASH" })?.key).toBe("cashNeedsCashAccount");
        expect(draftRefusal({ type: "BPV", vendorId: "v1", paymentAccountId: "cash", lines: [line], accounts,
            paymentMethod: "TRANSFER" })?.key).toBe("methodNeedsBankAccount");
        expect(draftRefusal({ type: "BPV", vendorId: "v1", paymentAccountId: "bank", lines: [line], accounts,
            paymentMethod: "CHEQUE", chequeNumber: " " })?.key).toBe("chequeNumberRequired");
        expect(draftRefusal({ type: "BPV", vendorId: "v1", paymentAccountId: "bank", lines: [line], accounts,
            paymentMethod: "CHEQUE", chequeNumber: "000031" })).toBeNull();
    });

    it("refuses allocations beyond what the voucher pays the vendor, to the fil", () => {
        const base = { type: "BPV" as const, vendorId: "v1", paymentAccountId: "bank", lines: [line] };
        expect(draftRefusal({ ...base, allocationTotal: 2050.01, payableTotal: 2050 })?.key).toBe("allocationsExceedPayment");
        expect(draftRefusal({ ...base, allocationTotal: 0.1 + 0.2, payableTotal: 0.3 })).toBeNull();
    });
});

describe("the Allocate panel's auto-fill", () => {
    const items = [
        { id: "INV-7790", open: 2100, dueDate: "2026-09-19", invoiceDate: "2026-08-20" },
        { id: "INV-7781", open: 1450, dueDate: "2026-08-31", invoiceDate: "2026-08-01" },
    ];

    it("settles the oldest due first and stops when the payment runs out", () => {
        expect(autoAllocate(items, 2050)).toEqual({ "INV-7781": 1450, "INV-7790": 600 });
        expect(autoAllocate(items, 1000)).toEqual({ "INV-7781": 1000 });
        expect(autoAllocate(items, 9999)).toEqual({ "INV-7781": 1450, "INV-7790": 2100 });
        expect(autoAllocate(items, 0)).toEqual({});
    });

    it("sums overdue across every past-due bucket", () => {
        expect(overdueOf({ current: 3150, d1to30: 1500, d31to60: 10, d61to90: 0, d90plus: 5,
            advances: 0, openTotal: 4665, ledgerBalance: 4665, delta: 0 })).toBe(1515);
    });
});

describe("roles", () => {
    it("opens payables to finance and aging to a property manager too", () => {
        for (const r of ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"] as const) {
            expect(hasPermission(r, "canManagePayables")).toBe(true);
            expect(hasPermission(r, "canViewPayablesAging")).toBe(true);
        }
        expect(hasPermission("PROPERTY_MANAGER", "canManagePayables")).toBe(false);
        expect(hasPermission("PROPERTY_MANAGER", "canViewPayablesAging")).toBe(true);
        expect(hasPermission("RENTER", "canViewPayablesAging")).toBe(false);
    });
});
