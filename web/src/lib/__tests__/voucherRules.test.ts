import { describe, expect, it } from "vitest";
import type { Account } from "@/lib/api/ledger";
import {
    ALLOWED_VAT_RATES,
    ATTACHMENT_ACCEPT,
    ATTACHMENT_MAX_BYTES,
    attachmentRefusal,
    canAmendVoucher,
    canEditVoucher,
    canManageAttachments,
    draftRefusal,
    isDateLocked,
    isLineAccountAllowed,
    isPaymentAccountAllowed,
    lineAccountTypes,
} from "@/lib/voucherRules";
import { hasPermission } from "@/lib/rbac";

/**
 * One test per server rule, because the discipline this file exists for is
 * "the UI never offers what the server always refuses" — plans 2 and 3 found
 * seventeen screens that did. Each block names the Java it mirrors.
 */

function account(over: Partial<Account> = {}): Account {
    return {
        id: "a1",
        code: "510100",
        name: "Maintenance",
        nameEn: "Maintenance",
        nameAr: null,
        alias: null,
        accountType: "EXPENSE",
        accountSubType: "DIRECT_EXPENSE",
        parentId: null,
        propertyId: null,
        system: false,
        group: false,
        active: true,
        displayOrder: 0,
        description: null,
        ...over,
    };
}

describe("role gate", () => {
    // VoucherController.java:55 — @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT')")
    it("admits exactly the three roles the controller admits", () => {
        for (const role of ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"] as const) {
            expect(hasPermission(role, "canManageVouchers")).toBe(true);
        }
        for (const role of ["PROPERTY_MANAGER", "TENANT_USER", "RENTER", "SECURITY_GUARD"] as const) {
            expect(hasPermission(role, "canManageVouchers")).toBe(false);
        }
    });
});

describe("line accounts", () => {
    // VoucherService.validate :404-409 and requirePostable :301-305
    it("allows only an active EXPENSE or ASSET leaf on a purchase-invoice line", () => {
        expect(isLineAccountAllowed("PISR", account({ accountType: "EXPENSE" }))).toBe(true);
        expect(isLineAccountAllowed("PISR", account({ accountType: "ASSET" }))).toBe(true);
        expect(isLineAccountAllowed("PISR", account({ accountType: "INCOME" }))).toBe(false);
        expect(isLineAccountAllowed("PISR", account({ accountType: "LIABILITY" }))).toBe(false);
        expect(isLineAccountAllowed("PISR", account({ group: true }))).toBe(false);
        expect(isLineAccountAllowed("PISR", account({ active: false }))).toBe(false);
    });

    // VoucherService.java:324-325 — "A BPV line may be any leaf".
    it("allows any active leaf on a payment-voucher line", () => {
        expect(isLineAccountAllowed("BPV", account({ accountType: "LIABILITY" }))).toBe(true);
        expect(isLineAccountAllowed("BPV", account({ group: true }))).toBe(false);
        expect(isLineAccountAllowed("BPV", account({ active: false }))).toBe(false);
    });

    it("reports the account types a line picker may offer", () => {
        expect(lineAccountTypes("PISR")).toEqual(["EXPENSE", "ASSET"]);
        expect(lineAccountTypes("BPV")).toBeUndefined();
    });
});

describe("payment account", () => {
    // ChequeService.isSettlementAccount (ChequeService.java:1437-1441)
    it("accepts only an active ASSET bank or cash leaf", () => {
        expect(isPaymentAccountAllowed(account({ accountType: "ASSET", accountSubType: "BANK" }))).toBe(true);
        expect(isPaymentAccountAllowed(account({ accountType: "ASSET", accountSubType: "CASH" }))).toBe(true);
        expect(isPaymentAccountAllowed(account({ accountType: "ASSET", accountSubType: "RECEIVABLE" }))).toBe(false);
        expect(isPaymentAccountAllowed(account({ accountType: "EXPENSE", accountSubType: "BANK" }))).toBe(false);
        expect(isPaymentAccountAllowed(account({ accountType: "ASSET", accountSubType: "BANK", group: true }))).toBe(false);
        expect(isPaymentAccountAllowed(account({ accountType: "ASSET", accountSubType: "BANK", active: false }))).toBe(false);
        expect(isPaymentAccountAllowed(null)).toBe(false);
    });
});

describe("VAT rates", () => {
    // VoucherService.ALLOWED_VAT_RATES :56-57 and BPV_VAT_REFUSAL :64-65
    it("offers only 0 and 5", () => {
        expect(ALLOWED_VAT_RATES).toEqual([0, 5]);
    });
});

describe("draftRefusal", () => {
    const expenseLine = { accountId: "a1", amount: 100, vatRate: 5 };

    it("passes a complete purchase invoice", () => {
        expect(
            draftRefusal({ type: "PISR", vendorId: "v1", paymentAccountId: null, lines: [expenseLine] }),
        ).toBeNull();
    });

    // VoucherService.validate :371-379
    it("refuses a purchase invoice with no vendor", () => {
        expect(
            draftRefusal({ type: "PISR", vendorId: "", paymentAccountId: null, lines: [expenseLine] }),
        ).toBe("vendorRequired");
    });

    // VoucherService.validate :380-383
    it("refuses a payment voucher with no payment account", () => {
        expect(
            draftRefusal({ type: "BPV", vendorId: "", paymentAccountId: null, lines: [{ ...expenseLine, vatRate: 0 }] }),
        ).toBe("paymentAccountRequired");
    });

    // VoucherLineInputDTO's @NotNull @Positive amount, and validate :398-400
    it("refuses a line with no account or a non-positive amount", () => {
        expect(
            draftRefusal({ type: "PISR", vendorId: "v1", paymentAccountId: null, lines: [{ accountId: "", amount: 100, vatRate: 5 }] }),
        ).toBe("lineAccountRequired");
        expect(
            draftRefusal({ type: "PISR", vendorId: "v1", paymentAccountId: null, lines: [{ accountId: "a1", amount: 0, vatRate: 5 }] }),
        ).toBe("lineAmountRequired");
    });

    // VoucherInputDTO's @NotEmpty lines
    it("refuses a voucher with no lines", () => {
        expect(draftRefusal({ type: "PISR", vendorId: "v1", paymentAccountId: null, lines: [] })).toBe("noLines");
    });

    // VoucherService.BPV_VAT_REFUSAL — mirrored so the BPV form can never send one.
    it("refuses VAT on a payment-voucher line", () => {
        expect(
            draftRefusal({ type: "BPV", vendorId: "", paymentAccountId: "p1", lines: [{ accountId: "a1", amount: 100, vatRate: 5 }] }),
        ).toBe("bpvNoVat");
    });

    /**
     * Landing in the backend shortly: a BPV line on a vendor's payable account
     * must be this voucher's OWN vendor's payable, and a BPV that settles a
     * payable needs a vendor. Mirrored ahead of the server so the form cannot
     * build the document that is about to be refused.
     */
    it("refuses another vendor's payable on a payment-voucher line", () => {
        const payableOwners = { "pay-v1": "v1", "pay-v2": "v2" };
        expect(
            draftRefusal({
                type: "BPV", vendorId: "v1", paymentAccountId: "p1",
                lines: [{ accountId: "pay-v2", amount: 100, vatRate: 0 }], payableOwners,
            }),
        ).toBe("otherVendorPayable");
        expect(
            draftRefusal({
                type: "BPV", vendorId: "", paymentAccountId: "p1",
                lines: [{ accountId: "pay-v1", amount: 100, vatRate: 0 }], payableOwners,
            }),
        ).toBe("payableNeedsVendor");
        expect(
            draftRefusal({
                type: "BPV", vendorId: "v1", paymentAccountId: "p1",
                lines: [{ accountId: "pay-v1", amount: 100, vatRate: 0 }], payableOwners,
            }),
        ).toBeNull();
    });
});

describe("status", () => {
    // VoucherService.requireDraft :353-359
    it("lets only a DRAFT be edited or deleted", () => {
        expect(canEditVoucher("DRAFT")).toBe(true);
        expect(canEditVoucher("POSTED")).toBe(false);
        expect(canEditVoucher("REVERSED")).toBe(false);
    });

    // VoucherService.amend :220-223
    it("lets only a POSTED voucher be amended", () => {
        expect(canAmendVoucher("POSTED")).toBe(true);
        expect(canAmendVoucher("DRAFT")).toBe(false);
        expect(canAmendVoucher("REVERSED")).toBe(false);
    });

    // VoucherAttachmentService.requireMutable :134-140
    it("freezes attachments once the voucher is REVERSED", () => {
        expect(canManageAttachments("DRAFT")).toBe(true);
        expect(canManageAttachments("POSTED")).toBe(true);
        expect(canManageAttachments("REVERSED")).toBe(false);
    });
});

describe("period lock", () => {
    // TenantFiscalSettingsService.assertOpen :53-59 — refuses a date NOT AFTER the lock.
    it("treats the lock date itself as locked", () => {
        expect(isDateLocked("2026-08-31", "2026-08-31")).toBe(true);
        expect(isDateLocked("2026-08-30", "2026-08-31")).toBe(true);
        expect(isDateLocked("2026-09-01", "2026-08-31")).toBe(false);
        expect(isDateLocked("2026-09-01", null)).toBe(false);
    });
});

describe("attachments", () => {
    // VoucherAttachmentService :49-52, :73-83
    it("refuses an oversized file and an unlisted type before uploading it", () => {
        expect(ATTACHMENT_MAX_BYTES).toBe(25 * 1024 * 1024);
        expect(ATTACHMENT_ACCEPT).toContain("application/pdf");
        expect(ATTACHMENT_ACCEPT).not.toContain("video/mp4");

        const big = new File([""], "scan.pdf", { type: "application/pdf" });
        Object.defineProperty(big, "size", { value: ATTACHMENT_MAX_BYTES + 1 });
        expect(attachmentRefusal(big, 0)).toBe("attachmentTooBig");

        const wrong = new File(["x"], "clip.mp4", { type: "video/mp4" });
        expect(attachmentRefusal(wrong, 0)).toBe("attachmentWrongType");

        const ok = new File(["x"], "scan.pdf", { type: "application/pdf" });
        expect(attachmentRefusal(ok, 0)).toBeNull();
        expect(attachmentRefusal(ok, 10)).toBe("attachmentTooMany");
    });
});
