import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { VoucherDetail, VoucherStatus } from "@/lib/api/vouchers";

/**
 * The purchase-invoice and payment-voucher form (spec §10.1, §11).
 *
 * Every assertion here is a server rule wearing a UI shape, because the rule
 * this screen exists under is: **never offer what the server always refuses.**
 *
 *  - VAT is per line, HALF_UP, then summed (`VoucherMath.vat`) — the total the
 *    accountant watches while typing must be the total that posts.
 *  - A BPV line carries no VAT at all (`VoucherService.BPV_VAT_REFUSAL`), so the
 *    payment form has no VAT column and sends no rate.
 *  - Only a DRAFT is editable (`VoucherService.requireDraft`).
 *  - A doc date inside a locked period cannot post
 *    (`TenantFiscalSettingsService.assertOpen`).
 */

/** What the next picker click selects. One mock covers both pickers: SettlementAccountPicker wraps AccountPicker. */
const picked = vi.hoisted(() => ({ id: "acct-1" }));

/** The chart the form loads for its second-layer account checks. */
const chart = vi.hoisted(() => ({
    rows: [] as { id: string; accountType: string; accountSubType: string | null; group: boolean; active: boolean }[],
}));

vi.mock("@/components/finance/AccountPicker", () => ({
    __esModule: true,
    default: ({
        value,
        onChange,
        placeholder,
        disabled,
    }: {
        value: string | null;
        onChange: (id: string) => void;
        placeholder?: string;
        disabled?: boolean;
    }) => (
        <button
            data-testid="account-picker"
            aria-label={placeholder}
            disabled={disabled}
            onClick={() => onChange(picked.id)}
        >
            {value ?? "pick"}
        </button>
    ),
    loadAccounts: async () => chart.rows,
    invalidateAccounts: () => {},
}));

vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));

const api = vi.hoisted(() => ({
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    post: vi.fn(),
    remove: vi.fn(),
    amend: vi.fn(),
    void: vi.fn(),
    attachList: vi.fn(async () => []),
    attachUpload: vi.fn(),
    attachRemove: vi.fn(),
    fiscal: vi.fn(),
    defaults: vi.fn(),
}));

vi.mock("@/lib/api/vouchers", async orig => {
    const m = await orig<typeof import("@/lib/api/vouchers")>();
    return {
        ...m,
        voucherApi: {
            list: vi.fn(),
            get: api.get,
            create: api.create,
            update: api.update,
            remove: api.remove,
            post: api.post,
            amend: api.amend,
            void: api.void,
            attachments: {
                list: api.attachList,
                upload: api.attachUpload,
                remove: api.attachRemove,
                downloadUrl: (id: string) => `/api/proxy/v1/finance/vouchers/attachments/${id}/download`,
            },
        },
    };
});

vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return {
        ...m,
        ledgerApi: {
            ...m.ledgerApi,
            fiscal: { ...m.ledgerApi.fiscal, get: api.fiscal },
            defaults: { ...m.ledgerApi.defaults, get: api.defaults },
        },
    };
});

import VoucherForm from "../VoucherForm";

function detail(over: Partial<VoucherDetail> = {}): VoucherDetail {
    return {
        id: "v1",
        docType: "PISR",
        docDate: "2026-09-20",
        vendorId: "ven-1",
        vendorName: "Emirates Facilities",
        invoiceNumber: "INV-9",
        narration: "September maintenance",
        propertyId: null,
        unitId: null,
        paymentAccountId: null,
        paymentAccountName: null,
        chequeNumber: null,
        chequeDate: null,
        status: "DRAFT",
        journalId: null,
        voucherNumber: null,
        amendedFromId: null,
        netTotal: 1000,
        vatTotal: 50,
        grossTotal: 1050,
        postedAt: null,
        lines: [
            {
                lineNo: 1, accountId: "acct-1", accountCode: "510100", accountName: "Maintenance",
                description: "Chillers", amount: 1000, vatRate: 5, vatAmount: 50,
                propertyId: null, unitId: null,
            },
        ],
        attachments: [],
        ...over,
    };
}

const VENDORS = [
    {
        id: "ven-1", nameEn: "Emirates Facilities", nameAr: "الإمارات للمرافق", active: true,
        trn: "100123456700003", paymentTermsDays: 30,
        payableAccount: { id: "pay-1", code: "210101", name: "Emirates Facilities" },
    },
    {
        id: "ven-2", nameEn: "Gulf Cooling", nameAr: "الخليج للتبريد", active: true,
        trn: "100765432100003", paymentTermsDays: 45,
        payableAccount: { id: "pay-2", code: "210102", name: "Gulf Cooling" },
    },
];

/** `GET /v1/properties` returns portfolio-summary rows that wrap the property. */
const PROPERTIES = [
    { property: { id: "prop-1", nameEn: "L'Olivier", nameAr: "لوليفييه" } },
    { property: { id: "prop-2", nameEn: "Marina Heights", nameAr: "مرسى هايتس" } },
];

const UNITS = [
    { id: "unit-a1", unitNumber: "A-101", property: { id: "prop-1", nameEn: "L'Olivier" } },
    { id: "unit-a2", unitNumber: "A-102", property: { id: "prop-1", nameEn: "L'Olivier" } },
    { id: "unit-b1", unitNumber: "B-201", property: { id: "prop-2", nameEn: "Marina Heights" } },
];

function renderForm(type: "PISR" | "BPV" | "PCN" = "PISR", props: {
    voucherId?: string;
    refundPrefill?: { settlementId: string; renterName: string; unitLabel: string; amount: number } | null;
} = {}) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <VoucherForm type={type} {...props} />
        </NextIntlClientProvider>,
    );
}

/**
 * Every line picker carries the "Account" accessible name and the payment
 * picker carries its own, which is how the two are told apart here — and, not
 * incidentally, the reason a screen reader can tell them apart too.
 */
function pickLineAccount(i: number, id = "acct-1") {
    picked.id = id;
    fireEvent.click(screen.getAllByLabelText(en.Ledger.account)[i]);
}

function pickPaymentAccount(id = "bank-1") {
    picked.id = id;
    fireEvent.click(screen.getByLabelText(en.Vouchers.selectPaymentAccount));
}

/** Finance-ops spec §2: a purchase invoice needs the supplier's invoice number before it can post. */
function fillInvoiceNumber(value = "INV-100") {
    fireEvent.change(screen.getByTestId("invoice-number"), { target: { value } });
}

function fillLine(i: number, amount: string, rate?: string, accountId = "acct-1") {
    pickLineAccount(i, accountId);
    fireEvent.change(screen.getByTestId(`line-amount-${i}`), { target: { value: amount } });
    if (rate !== undefined) fireEvent.change(screen.getByTestId(`line-vat-rate-${i}`), { target: { value: rate } });
}

beforeEach(() => {
    vi.clearAllMocks();
    picked.id = "acct-1";
    chart.rows = [];
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
    api.defaults.mockResolvedValue([]);
    api.create.mockResolvedValue(detail({ id: "v-new", lines: [], netTotal: 0, vatTotal: 0, grossTotal: 0 }));
    api.update.mockResolvedValue(detail());
    api.post.mockResolvedValue(detail({ status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1" }));
    vi.stubGlobal(
        "fetch",
        vi.fn(async (url: string) => {
            const u = String(url);
            const body = u.includes("/vendors")
                ? VENDORS
                : u.includes("/units")
                  ? UNITS
                  : u.includes("/properties")
                    ? PROPERTIES
                    : [];
            return new Response(JSON.stringify(body), {
                status: 200,
                headers: { "Content-Type": "application/json" },
            });
        }),
    );
});

afterEach(cleanup);

describe("VoucherForm — vendor load failure", () => {
    /**
     * Finance-ops audit S1: a `GET /v1/vendors` 403 (or any non-OK response)
     * used to be swallowed into `[]` — indistinguishable from "this tenant has
     * no vendors". An ACCOUNTANT hit exactly this before VendorController
     * admitted the role: an empty, unexplained dropdown with no way to tell
     * whether to type a vendor name or give up. The form must say the load
     * failed instead of rendering a silently empty picker.
     */
    it("shows a load-error banner, not a silently empty vendor dropdown, on a failed fetch", async () => {
        vi.stubGlobal(
            "fetch",
            vi.fn(async (url: string) => {
                const u = String(url);
                if (u.includes("/vendors")) {
                    return new Response(JSON.stringify({ message: "Forbidden" }), { status: 403 });
                }
                const body = u.includes("/units") ? UNITS : u.includes("/properties") ? PROPERTIES : [];
                return new Response(JSON.stringify(body), {
                    status: 200,
                    headers: { "Content-Type": "application/json" },
                });
            }),
        );
        renderForm();
        expect(await screen.findByRole("alert")).toHaveTextContent(en.Common.loadFailed);
        expect(screen.getByTestId("vendor")).toBeInTheDocument();
    });
});

describe("VoucherForm — totals", () => {
    it("shows zero totals before anything is entered", async () => {
        renderForm();
        expect(await screen.findByTestId("net-total")).toHaveTextContent("0.00");
        expect(screen.getByTestId("vat-total")).toHaveTextContent("0.00");
        expect(screen.getByTestId("gross-total")).toHaveTextContent("0.00");
    });

    it("computes per-line VAT and the header totals as the user types", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fillLine(0, "2000", "5");

        await waitFor(() => expect(screen.getByTestId("line-vat-amount-0")).toHaveTextContent("100.00"));
        expect(screen.getByTestId("net-total")).toHaveTextContent("2,000.00");
        expect(screen.getByTestId("vat-total")).toHaveTextContent("100.00");
        expect(screen.getByTestId("gross-total")).toHaveTextContent("2,100.00");
    });

    it("sums per-line VAT rather than taking VAT of the total", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        for (const i of [0, 1, 2]) {
            if (i > 0) fireEvent.click(screen.getByTestId("add-line"));
            fillLine(i, "100.10", "5");
        }
        // 3 x round(5.005) = 3 x 5.01 = 15.03, not 5% of 300.30 = 15.02.
        await waitFor(() => expect(screen.getByTestId("vat-total")).toHaveTextContent("15.03"));
        expect(screen.getByTestId("gross-total")).toHaveTextContent("315.33");
    });

    it("removes a line and recomputes the totals", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fillLine(0, "500", "0");
        fireEvent.click(screen.getByTestId("add-line"));
        fillLine(1, "300", "0");
        await waitFor(() => expect(screen.getByTestId("net-total")).toHaveTextContent("800.00"));
        fireEvent.click(screen.getByTestId("remove-line-1"));
        await waitFor(() => expect(screen.getByTestId("net-total")).toHaveTextContent("500.00"));
    });
});

describe("VoucherForm — the BPV carries no VAT", () => {
    /** VoucherService.BPV_VAT_REFUSAL: a field whose only legal value is zero is not a field. */
    it("renders no VAT column and no VAT total on a payment voucher", async () => {
        renderForm("BPV");
        expect(await screen.findByTestId("net-total")).toBeInTheDocument();
        expect(screen.queryByTestId("line-vat-rate-0")).not.toBeInTheDocument();
        expect(screen.queryByTestId("line-vat-amount-0")).not.toBeInTheDocument();
        expect(screen.queryByTestId("vat-total")).not.toBeInTheDocument();
    });

    it("says a cheque dated after the voucher is held in PDC payable (finance-ops spec §2)", async () => {
        renderForm("BPV");
        await screen.findByTestId("line-amount-0");
        pickPaymentAccount("bank-1");
        fireEvent.change(screen.getByTestId("payment-method"), { target: { value: "CHEQUE" } });
        fireEvent.change(screen.getByTestId("doc-date"), { target: { value: "2026-08-15" } });
        fireEvent.change(await screen.findByTestId("cheque-date"), { target: { value: "2026-08-15" } });
        expect(screen.queryByTestId("pdc-banner")).not.toBeInTheDocument();
        fireEvent.change(screen.getByTestId("cheque-date"), { target: { value: "2026-09-28" } });
        expect(screen.getByTestId("pdc-banner")).toHaveTextContent(en.Vouchers.pdcBanner);
    });

    it("sends vatRate 0 on every line of a payment voucher", async () => {
        renderForm("BPV");
        await screen.findByTestId("line-amount-0");
        pickPaymentAccount("bank-1");
        pickLineAccount(0, "acct-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "750" } });

        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.create).toHaveBeenCalled());
        const body = api.create.mock.calls.at(-1)![0];
        expect(body.docType).toBe("BPV");
        expect(body.paymentAccountId).toBe("bank-1");
        expect(body.lines.every((l: { vatRate: number }) => l.vatRate === 0)).toBe(true);
    });
});

describe("VoucherForm — Post gating", () => {
    it("keeps Post disabled until every line has an account and an amount", async () => {
        renderForm();
        expect(await screen.findByTestId("post-voucher")).toBeDisabled();
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fillInvoiceNumber();
        pickLineAccount(0);
        expect(screen.getByTestId("post-voucher")).toBeDisabled();
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "2000" } });
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
    });

    it("names the reason a purchase invoice cannot post without a vendor", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fillLine(0, "2000", "5");
        await waitFor(() =>
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(en.Vouchers.vendorRequired),
        );
        expect(screen.getByTestId("post-voucher")).toBeDisabled();
    });

    /**
     * TenantFiscalSettingsService.assertOpen refuses a date that is NOT AFTER
     * books_locked_through — so the lock date itself is closed.
     */
    it("disables Post for a doc date in a locked period and says why", async () => {
        api.fiscal.mockResolvedValue({
            fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: "2026-08-31",
        });
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fillInvoiceNumber();
        fillLine(0, "2000", "5");
        fireEvent.change(screen.getByTestId("doc-date"), { target: { value: "2026-08-31" } });

        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeDisabled());
        // F14-46: the lock date shown to the user is dd/mm/yyyy, not raw ISO.
        expect(screen.getByTestId("voucher-blocker")).toHaveTextContent("31/08/2026");
        expect(screen.getByTestId("voucher-blocker")).not.toHaveTextContent("2026-08-31");

        // The day after the lock is open again.
        fireEvent.change(screen.getByTestId("doc-date"), { target: { value: "2026-09-01" } });
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
    });

    it("refuses another vendor's payable on a payment-voucher line", async () => {
        renderForm("BPV");
        await screen.findByTestId("line-amount-0");
        await waitFor(() => expect(screen.getByTestId("vendor")).toBeInTheDocument());
        pickPaymentAccount("bank-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "500" } });
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        // Line 0 sits on vendor 2's payable while vendor 1 is on the header.
        pickLineAccount(0, "pay-2");

        await waitFor(() =>
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(
                en.Vouchers.otherVendorPayable.replace("{line}", "1"),
            ),
        );
        expect(screen.getByTestId("post-voucher")).toBeDisabled();

        // The voucher's own vendor's payable is fine.
        pickLineAccount(0, "pay-1");
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
    });

    it("refuses a payable line on a payment voucher with no vendor", async () => {
        renderForm("BPV");
        await screen.findByTestId("line-amount-0");
        await waitFor(() => expect(screen.getByTestId("vendor")).toBeInTheDocument());
        pickPaymentAccount("bank-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "500" } });
        pickLineAccount(0, "pay-1");

        await waitFor(() =>
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(
                en.Vouchers.payableNeedsVendor.replace("{line}", "1"),
            ),
        );
    });
});

describe("VoucherForm — status", () => {
    it("lets a DRAFT be edited, saved and deleted", async () => {
        api.get.mockResolvedValue(detail({ status: "DRAFT" }));
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("line-amount-0")).toHaveValue("1000"));
        expect(screen.getByTestId("line-amount-0")).not.toBeDisabled();
        expect(screen.getByTestId("save-draft")).toBeInTheDocument();
        expect(screen.getByTestId("delete-draft")).toBeInTheDocument();
        expect(screen.queryByTestId("amend-voucher")).not.toBeInTheDocument();
        // The number only exists once a journal has been written.
        expect(screen.queryByTestId("voucher-number")).not.toBeInTheDocument();
    });

    it("locks a POSTED voucher to read-only and offers Amend instead", async () => {
        api.get.mockResolvedValue(
            detail({ status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1" }),
        );
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("line-amount-0")).toBeDisabled());
        expect(screen.getByTestId("doc-date")).toBeDisabled();
        expect(screen.queryByTestId("save-draft")).not.toBeInTheDocument();
        expect(screen.queryByTestId("post-voucher")).not.toBeInTheDocument();
        expect(screen.queryByTestId("delete-draft")).not.toBeInTheDocument();
        expect(screen.getByTestId("amend-voucher")).toBeInTheDocument();
        expect(screen.getByTestId("voucher-number")).toHaveTextContent("PISR/2026/0007");
    });

    it("leaves a REVERSED voucher read-only with no action at all", async () => {
        api.get.mockResolvedValue(
            detail({ status: "REVERSED", voucherNumber: "PISR/2026/0007", journalId: "j1" }),
        );
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("line-amount-0")).toBeDisabled());
        expect(screen.queryByTestId("amend-voucher")).not.toBeInTheDocument();
        expect(screen.queryByTestId("save-draft")).not.toBeInTheDocument();
        expect(screen.queryByTestId("post-voucher")).not.toBeInTheDocument();
        // VoucherAttachmentService.requireMutable freezes the paper trail too.
        expect(screen.queryByTestId("attachment-input")).not.toBeInTheDocument();
        expect(screen.getByTestId("attachments-frozen")).toBeInTheDocument();
    });

    it.each<[VoucherStatus, boolean]>([
        ["DRAFT", true],
        ["POSTED", false],
        ["REVERSED", false],
    ])("reports %s on the badge and gates editing on it", async (status, editable) => {
        api.get.mockResolvedValue(detail({ status, voucherNumber: status === "DRAFT" ? null : "PISR/1" }));
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("voucher-status")).toHaveAttribute("data-status", status));
        const amount = screen.getByTestId("line-amount-0");
        expect(amount.hasAttribute("disabled")).toBe(!editable);
    });
});

describe("VoucherForm — amend actually amends", () => {
    /**
     * `VoucherService.amend` exists to "post a fresh voucher carrying the
     * CORRECTED figures". Before fix round 1 the form was fully disabled the
     * moment a voucher was POSTED, so the replacement it submitted was always
     * byte-for-byte the original — and it reported success, which is worse than
     * failing.
     */
    const posted = () =>
        detail({ status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1" });

    async function enterAmendMode() {
        api.get.mockResolvedValue(posted());
        renderForm("PISR", { voucherId: "v1" });
        fireEvent.click(await screen.findByTestId("amend-voucher"));
        return screen.findByTestId("amend-banner");
    }

    it("re-enables the figures and swaps the action row", async () => {
        await enterAmendMode();
        expect(screen.getByTestId("line-amount-0")).not.toBeDisabled();
        expect(screen.getByTestId("doc-date")).not.toBeDisabled();
        expect(screen.getByTestId("add-line")).toBeInTheDocument();
        expect(screen.getByTestId("post-amendment")).toBeInTheDocument();
        expect(screen.getByTestId("cancel-amendment")).toBeInTheDocument();
        // The draft actions are not what an amendment does.
        expect(screen.queryByTestId("save-draft")).not.toBeInTheDocument();
        expect(screen.queryByTestId("post-voucher")).not.toBeInTheDocument();
        expect(screen.queryByTestId("delete-draft")).not.toBeInTheDocument();
        expect(screen.queryByTestId("amend-voucher")).not.toBeInTheDocument();
    });

    it("keeps Post amendment disabled until something actually changed", async () => {
        await enterAmendMode();
        expect(screen.getByTestId("post-amendment")).toBeDisabled();
        expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(en.Vouchers.amendNoChanges);

        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "1200" } });
        await waitFor(() => expect(screen.getByTestId("post-amendment")).toBeEnabled());

        // Typing it back makes it unchanged again.
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "1000" } });
        await waitFor(() => expect(screen.getByTestId("post-amendment")).toBeDisabled());
    });

    it("sends the EDITED figures as the replacement", async () => {
        api.amend.mockResolvedValue(
            detail({ id: "v2", status: "POSTED", voucherNumber: "PISR/2026/0008", journalId: "j2", amendedFromId: "v1" }),
        );
        await enterAmendMode();
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "1200" } });
        fireEvent.change(screen.getByTestId("line-description-0"), { target: { value: "Chillers, corrected" } });
        await waitFor(() => expect(screen.getByTestId("post-amendment")).toBeEnabled());

        fireEvent.click(screen.getByTestId("post-amendment"));
        fireEvent.change(await screen.findByTestId("amend-date"), { target: { value: "2026-09-30" } });
        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "wrong amount" } });
        fireEvent.click(screen.getByTestId("confirm-amend"));

        await waitFor(() => expect(api.amend).toHaveBeenCalled());
        const [id, body] = api.amend.mock.calls.at(-1)!;
        expect(id).toBe("v1");
        expect(body.reversalDate).toBe("2026-09-30");
        expect(body.reason).toBe("wrong amount");
        // The whole point: the replacement carries the corrected figures.
        expect(body.replacement.lines[0].amount).toBe(1200);
        expect(body.replacement.lines[0].description).toBe("Chillers, corrected");
    });

    it("lands on the replacement and links back to the original", async () => {
        api.amend.mockResolvedValue(
            detail({ id: "v2", status: "POSTED", voucherNumber: "PISR/2026/0008", journalId: "j2", amendedFromId: "v1" }),
        );
        await enterAmendMode();
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "1200" } });
        await waitFor(() => expect(screen.getByTestId("post-amendment")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-amendment"));
        fireEvent.change(await screen.findByTestId("amend-reason"), { target: { value: "correction" } });
        fireEvent.click(screen.getByTestId("confirm-amend"));

        expect(await screen.findByTestId("voucher-posted")).toHaveTextContent("PISR/2026/0008");
        expect(screen.getByTestId("voucher-number")).toHaveTextContent("PISR/2026/0008");
        expect(screen.getByTestId("amended-from")).toHaveAttribute(
            "href",
            "/dashboard/finance/vouchers/purchase-invoice?id=v1",
        );
        // And it is read-only again.
        expect(screen.getByTestId("line-amount-0")).toBeDisabled();
    });

    it("restores the posted values on Cancel amendment", async () => {
        await enterAmendMode();
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "9999" } });
        fireEvent.change(screen.getByTestId("narration"), { target: { value: "scribbled" } });
        await waitFor(() => expect(screen.getByTestId("line-amount-0")).toHaveValue("9999"));

        fireEvent.click(screen.getByTestId("cancel-amendment"));
        await waitFor(() => expect(screen.getByTestId("line-amount-0")).toHaveValue("1000"));
        expect(screen.getByTestId("narration")).toHaveValue("September maintenance");
        expect(screen.getByTestId("line-amount-0")).toBeDisabled();
        expect(screen.getByTestId("amend-voucher")).toBeInTheDocument();
        expect(api.amend).not.toHaveBeenCalled();
    });

    /** VoucherService.amend calls fiscal.assertOpen(reversalDate) before it writes anything. */
    it("refuses a reversal date inside a locked period, with the reason", async () => {
        api.fiscal.mockResolvedValue({
            fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: "2026-09-30",
        });
        await enterAmendMode();
        // The replacement's own doc date is posted too, so move it clear of the
        // lock first — otherwise that is what blocks, and this test would pass
        // for the wrong reason.
        fireEvent.change(screen.getByTestId("doc-date"), { target: { value: "2026-10-05" } });
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "1200" } });
        await waitFor(() => expect(screen.getByTestId("post-amendment")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-amendment"));

        fireEvent.change(await screen.findByTestId("amend-date"), { target: { value: "2026-09-30" } });
        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "correction" } });
        await waitFor(() => expect(screen.getByTestId("confirm-amend")).toBeDisabled());
        // F14-46: dd/mm/yyyy, not raw ISO.
        expect(screen.getByTestId("amend-blocker")).toHaveTextContent("30/09/2026");

        fireEvent.change(screen.getByTestId("amend-date"), { target: { value: "2026-10-01" } });
        await waitFor(() => expect(screen.getByTestId("confirm-amend")).toBeEnabled());
    });

    it.each<VoucherStatus>(["DRAFT", "REVERSED"])("never offers Amend on a %s voucher", async status => {
        api.get.mockResolvedValue(detail({ status, voucherNumber: status === "DRAFT" ? null : "PISR/1" }));
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("voucher-status")).toHaveAttribute("data-status", status));
        expect(screen.queryByTestId("amend-voucher")).not.toBeInTheDocument();
        expect(screen.queryByTestId("post-amendment")).not.toBeInTheDocument();
    });
});

describe("VoucherForm — posting", () => {
    it("saves then posts, and reports the number the server assigned", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fillInvoiceNumber();
        fillLine(0, "1000", "5");
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());

        fireEvent.click(screen.getByTestId("post-voucher"));
        fireEvent.click(await screen.findByTestId("confirm-post"));

        await waitFor(() => expect(api.post).toHaveBeenCalledWith("v-new", undefined, expect.any(Object)));
        expect(api.create).toHaveBeenCalledTimes(1);
        expect(await screen.findByTestId("voucher-posted")).toHaveTextContent("PISR/2026/0007");
    });

    it("surfaces a tenant-less 400 instead of crashing", async () => {
        const { ApiError } = await import("@/lib/api/facilities");
        // VoucherController.requireTenantSelected — a SUPER_ADMIN with no
        // organisation picked gets this 400 from every voucher endpoint.
        api.get.mockRejectedValue(new ApiError(400, "Select an organisation first"));
        renderForm("PISR", { voucherId: "v1" });
        expect(await screen.findByRole("alert")).toHaveTextContent("Select an organisation first");
    });

    it("shows bank.statementCovers with a checkbox and resends with notOnStatement (F14-20)", async () => {
        const { ApiError } = await import("@/lib/api/facilities");
        const covered = new ApiError(400, "covered", JSON.stringify({
            code: "bank.statementCovers",
            args: { bank: "Emirates Islamic 0123", from: "01/09/2026", to: "30/09/2026", date: "20/09/2026" },
            message: "covered",
        }));
        api.post.mockReset();
        api.post.mockRejectedValueOnce(covered)
            .mockResolvedValueOnce(detail({ status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1" }));
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fillInvoiceNumber();
        fillLine(0, "1000", "5");
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());

        fireEvent.click(screen.getByTestId("post-voucher"));
        fireEvent.click(await screen.findByTestId("confirm-post"));
        expect(await screen.findByTestId("voucher-post-notice")).toHaveTextContent("Emirates Islamic 0123");

        fireEvent.click(screen.getByTestId("voucher-post-not-on-statement"));
        fireEvent.click(screen.getByTestId("confirm-post"));
        await waitFor(() => expect(api.post).toHaveBeenCalledTimes(2));
        expect(api.post).toHaveBeenLastCalledWith(expect.any(String), undefined, expect.objectContaining({ notOnStatement: true }));
    });

    it("shows voucher.cashNegative with a Post anyway checkbox and resends allowNegativeCash (F14-42)", async () => {
        const { ApiError } = await import("@/lib/api/facilities");
        const negative = new ApiError(400, "negative", JSON.stringify({
            code: "voucher.cashNegative",
            args: { account: "Cash in hand", balance: "500.00", date: "20/09/2026", amount: "800.00", after: "-300.00" },
            message: "negative",
        }));
        api.post.mockReset();
        api.post.mockRejectedValueOnce(negative)
            .mockResolvedValueOnce(detail({ status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1" }));
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fillInvoiceNumber();
        fillLine(0, "1000", "5");
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());

        fireEvent.click(screen.getByTestId("post-voucher"));
        fireEvent.click(await screen.findByTestId("confirm-post"));
        expect(await screen.findByTestId("voucher-cash-negative-notice")).toHaveTextContent("Cash in hand");
        // The button refuses a second try until the checkbox is ticked.
        expect(screen.getByTestId("confirm-post")).toBeDisabled();

        fireEvent.click(screen.getByTestId("voucher-allow-negative-cash"));
        expect(screen.getByTestId("confirm-post")).toBeEnabled();
        fireEvent.click(screen.getByTestId("confirm-post"));
        await waitFor(() => expect(api.post).toHaveBeenCalledTimes(2));
        expect(api.post).toHaveBeenLastCalledWith(expect.any(String), undefined, expect.objectContaining({ allowNegativeCash: true }));
    });
});

describe("VoucherForm — void (F14-42)", () => {
    const posted = () =>
        detail({ status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1", docDate: "2026-09-20" });

    it("offers Void on a POSTED voucher, requires a reason, and sends date+reason", async () => {
        api.get.mockResolvedValue(posted());
        api.void.mockResolvedValue(detail({ status: "VOID", voucherNumber: "PISR/2026/0007", journalId: "j1" }));
        renderForm("PISR", { voucherId: "v1" });
        fireEvent.click(await screen.findByTestId("void-voucher"));
        expect(screen.getByTestId("confirm-void")).toBeDisabled();

        fireEvent.change(screen.getByTestId("void-reason"), { target: { value: "posted by mistake" } });
        fireEvent.change(screen.getByTestId("void-date"), { target: { value: "2026-09-25" } });
        expect(screen.getByTestId("confirm-void")).toBeEnabled();
        fireEvent.click(screen.getByTestId("confirm-void"));
        await waitFor(() => expect(api.void).toHaveBeenCalledWith("v1", { date: "2026-09-25", reason: "posted by mistake" }));
        await waitFor(() => expect(screen.getByTestId("voucher-status")).toHaveAttribute("data-status", "VOID"));
    });

    it("refuses a void date before the voucher's own document date", async () => {
        api.get.mockResolvedValue(posted());
        renderForm("PISR", { voucherId: "v1" });
        fireEvent.click(await screen.findByTestId("void-voucher"));
        fireEvent.change(screen.getByTestId("void-reason"), { target: { value: "posted by mistake" } });
        fireEvent.change(screen.getByTestId("void-date"), { target: { value: "2026-09-19" } });
        expect(screen.getByTestId("confirm-void")).toBeDisabled();
        expect(screen.getByTestId("void-date-before-doc")).toHaveTextContent("PISR/2026/0007");
    });

    it("never offers Void on a DRAFT or REVERSED voucher", async () => {
        api.get.mockResolvedValue(detail({ status: "REVERSED", voucherNumber: "PISR/2026/0007" }));
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("voucher-status")).toHaveAttribute("data-status", "REVERSED"));
        expect(screen.queryByTestId("void-voucher")).not.toBeInTheDocument();
    });
});

describe("VoucherForm — amend reason and date (F14-41)", () => {
    const posted = () =>
        detail({ status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1", docDate: "2026-09-20" });

    it("disables Post amendment until a reason is typed, with a hint", async () => {
        api.get.mockResolvedValue(posted());
        renderForm("PISR", { voucherId: "v1" });
        fireEvent.click(await screen.findByTestId("amend-voucher"));
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "1200" } });
        fireEvent.click(await screen.findByTestId("post-amendment"));
        expect(screen.getByTestId("confirm-amend")).toBeDisabled();
        expect(screen.getByTestId("amend-reason-hint")).toBeInTheDocument();

        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "wrong amount" } });
        expect(screen.getByTestId("confirm-amend")).toBeEnabled();
    });

    it("refuses a reversal date before the voucher's own document date", async () => {
        api.get.mockResolvedValue(posted());
        renderForm("PISR", { voucherId: "v1" });
        fireEvent.click(await screen.findByTestId("amend-voucher"));
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "1200" } });
        fireEvent.click(await screen.findByTestId("post-amendment"));
        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "wrong amount" } });
        fireEvent.change(screen.getByTestId("amend-date"), { target: { value: "2026-09-19" } });
        expect(screen.getByTestId("confirm-amend")).toBeDisabled();
        expect(screen.getByTestId("amend-date-before-doc")).toHaveTextContent("PISR/2026/0007");
    });
});

describe("VoucherForm — the chart is the second layer behind the pickers", () => {
    /**
     * A line loaded from a saved draft was never offered by a picker — it was
     * already on the row. If its account has since been reclassified, only this
     * check stands between the accountant and a 400 on submit.
     * VoucherService.validate:404-409.
     */
    it("blocks Post when a saved line's account was reclassified, naming the line", async () => {
        chart.rows = [
            { id: "acct-1", accountType: "INCOME", accountSubType: "OTHER_INCOME", group: false, active: true },
        ];
        api.get.mockResolvedValue(detail({ status: "DRAFT" }));
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() =>
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(
                en.Vouchers.lineAccountNotAllowed.replace("{line}", "1"),
            ),
        );
        expect(screen.getByTestId("post-voucher")).toBeDisabled();
    });

    it("lets the same line through once the account is an expense again", async () => {
        chart.rows = [
            { id: "acct-1", accountType: "EXPENSE", accountSubType: "DIRECT_EXPENSE", group: false, active: true },
        ];
        api.get.mockResolvedValue(detail({ status: "DRAFT" }));
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
        expect(screen.queryByTestId("voucher-blocker")).not.toBeInTheDocument();
    });
});

describe("VoucherForm — line dimensions", () => {
    /**
     * `VoucherLineInputDTO` carries `propertyId` and `unitId`, and
     * `VoucherService.apply` writes them onto the journal line. They are what put
     * a maintenance invoice on ONE building's ledger. The form used to overwrite
     * every line with the header property and never send a unit at all, so a
     * two-property invoice collapsed onto one and a unit-level cost lost its unit
     * the first time the voucher was saved.
     */
    const twoProperties = () =>
        detail({
            propertyId: "prop-1",
            lines: [
                {
                    lineNo: 1, accountId: "acct-1", accountCode: "510100", accountName: "Maintenance",
                    description: "Chillers", amount: 1000, vatRate: 5, vatAmount: 50,
                    propertyId: "prop-1", unitId: "unit-a1",
                },
                {
                    lineNo: 2, accountId: "acct-2", accountCode: "510200", accountName: "Cleaning",
                    description: "Common areas", amount: 500, vatRate: 5, vatAmount: 25,
                    propertyId: "prop-2", unitId: null,
                },
            ],
        });

    it("round-trips each line's own property and unit through Save", async () => {
        api.get.mockResolvedValue(twoProperties());
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("line-property-0")).toHaveValue("prop-1"));
        expect(screen.getByTestId("line-unit-0")).toHaveValue("unit-a1");
        expect(screen.getByTestId("line-property-1")).toHaveValue("prop-2");
        expect(screen.getByTestId("line-unit-1")).toHaveValue("");

        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.update).toHaveBeenCalled());
        const body = api.update.mock.calls.at(-1)![1];
        expect(body.lines[0]).toMatchObject({ propertyId: "prop-1", unitId: "unit-a1" });
        expect(body.lines[1]).toMatchObject({ propertyId: "prop-2", unitId: null });
    });

    it("sends an edited line dimension, not the header's", async () => {
        api.get.mockResolvedValue(twoProperties());
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("line-property-1")).toHaveValue("prop-2"));

        fireEvent.change(screen.getByTestId("line-unit-1"), { target: { value: "unit-b1" } });
        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.update).toHaveBeenCalled());
        const body = api.update.mock.calls.at(-1)![1];
        expect(body.lines[1]).toMatchObject({ propertyId: "prop-2", unitId: "unit-b1" });
    });

    it("offers only the chosen property's units, and clearing the property clears the unit", async () => {
        api.get.mockResolvedValue(twoProperties());
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("line-unit-0")).toHaveValue("unit-a1"));

        const unit0 = screen.getByTestId("line-unit-0") as HTMLSelectElement;
        const offered = Array.from(unit0.options).map(o => o.value).filter(Boolean);
        expect(offered).toEqual(["unit-a1", "unit-a2"]);

        fireEvent.change(screen.getByTestId("line-property-0"), { target: { value: "" } });
        await waitFor(() => expect(screen.getByTestId("line-unit-0")).toHaveValue(""));
        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.update).toHaveBeenCalled());
        expect(api.update.mock.calls.at(-1)![1].lines[0]).toMatchObject({ propertyId: null, unitId: null });
    });

    it("moves the unit off a line whose property changed", async () => {
        api.get.mockResolvedValue(twoProperties());
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("line-unit-0")).toHaveValue("unit-a1"));
        fireEvent.change(screen.getByTestId("line-property-0"), { target: { value: "prop-2" } });
        await waitFor(() => expect(screen.getByTestId("line-unit-0")).toHaveValue(""));

        // Asserted on the PAYLOAD, not on the select: a select whose value has no
        // matching option renders as "" while state still holds the stale unit,
        // and it is the state that gets sent. A unit belongs to one property, so
        // A-101 on a Marina Heights line is a journal nobody can explain.
        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.update).toHaveBeenCalled());
        expect(api.update.mock.calls.at(-1)![1].lines[0]).toMatchObject({
            propertyId: "prop-2",
            unitId: null,
        });
    });

    it("defaults a new line to the header's property", async () => {
        api.get.mockResolvedValue(twoProperties());
        renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(screen.getByTestId("line-property-0")).toBeInTheDocument());
        fireEvent.click(screen.getByTestId("add-line"));
        await waitFor(() => expect(screen.getByTestId("line-property-2")).toHaveValue("prop-1"));
    });

    it("carries line dimensions through Post amendment", async () => {
        api.get.mockResolvedValue({ ...twoProperties(), status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1" });
        api.amend.mockResolvedValue(detail({ id: "v2", status: "POSTED", voucherNumber: "PISR/2026/0008" }));
        renderForm("PISR", { voucherId: "v1" });
        fireEvent.click(await screen.findByTestId("amend-voucher"));
        await screen.findByTestId("amend-banner");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "1100" } });
        await waitFor(() => expect(screen.getByTestId("post-amendment")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-amendment"));
        fireEvent.change(await screen.findByTestId("amend-reason"), { target: { value: "correction" } });
        fireEvent.click(screen.getByTestId("confirm-amend"));

        await waitFor(() => expect(api.amend).toHaveBeenCalled());
        const replacement = api.amend.mock.calls.at(-1)![1].replacement;
        expect(replacement.lines[0]).toMatchObject({ propertyId: "prop-1", unitId: "unit-a1", amount: 1100 });
        expect(replacement.lines[1]).toMatchObject({ propertyId: "prop-2", unitId: null });
    });
});

describe("VoucherForm — two vendors sharing one payable account", () => {
    /**
     * `VoucherService.requirePayableLinesMatchTheVendor` compares on the ACCOUNT,
     * not on the owner: "is this line my vendor's payable account?", not "is my
     * vendor the only vendor who answers to it?". Nothing in the schema stops two
     * vendors sharing one leaf. The client used to build a last-write-wins
     * accountId → vendorId map, so with a shared account one of the two vendors
     * was refused a payment the server would have accepted.
     */
    const SHARED = [
        { id: "ven-1", nameEn: "Emirates Facilities", nameAr: "", active: true, payableAccount: { id: "pay-shared", code: "210101", name: "Group payable" } },
        { id: "ven-2", nameEn: "Gulf Cooling", nameAr: "", active: true, payableAccount: { id: "pay-shared", code: "210101", name: "Group payable" } },
        { id: "ven-3", nameEn: "Al Shirawi", nameAr: "", active: true, payableAccount: { id: "pay-3", code: "210103", name: "Al Shirawi" } },
    ];

    beforeEach(() => {
        vi.stubGlobal(
            "fetch",
            vi.fn(async (url: string) => {
                const u = String(url);
                const body = u.includes("/vendors")
                    ? SHARED
                    : u.includes("/units")
                      ? UNITS
                      : u.includes("/properties")
                        ? PROPERTIES
                        : [];
                return new Response(JSON.stringify(body), {
                    status: 200,
                    headers: { "Content-Type": "application/json" },
                });
            }),
        );
    });

    it.each(["ven-1", "ven-2"])("lets %s pay through the account they share", async vendorId => {
        renderForm("BPV");
        await screen.findByTestId("line-amount-0");
        await waitFor(() => expect(screen.getByTestId("vendor")).toBeInTheDocument());
        pickPaymentAccount("bank-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "500" } });
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: vendorId } });
        pickLineAccount(0, "pay-shared");

        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
        expect(screen.queryByTestId("voucher-blocker")).not.toBeInTheDocument();
    });

    it("still refuses a third vendor's own payable", async () => {
        renderForm("BPV");
        await screen.findByTestId("line-amount-0");
        await waitFor(() => expect(screen.getByTestId("vendor")).toBeInTheDocument());
        pickPaymentAccount("bank-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "500" } });
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        pickLineAccount(0, "pay-3");

        await waitFor(() =>
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(
                en.Vouchers.otherVendorPayable.replace("{line}", "1"),
            ),
        );
    });
});

describe("VoucherForm — deleting a draft", () => {
    it("deletes behind the confirmation and calls the API with the voucher id", async () => {
        api.get.mockResolvedValue(detail({ status: "DRAFT" }));
        api.remove.mockResolvedValue(undefined);
        const onDeleted = vi.fn();
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <VoucherForm type="PISR" voucherId="v1" onDeleted={onDeleted} />
            </NextIntlClientProvider>,
        );
        fireEvent.click(await screen.findByTestId("delete-draft"));
        fireEvent.click(await screen.findByTestId("confirm-delete"));
        await waitFor(() => expect(api.remove).toHaveBeenCalledWith("v1"));
        expect(onDeleted).toHaveBeenCalledTimes(1);
    });
});

describe("VoucherForm — the disabled reason is announced", () => {
    /** A disabled button whose reason is only visually adjacent tells a screen reader nothing. */
    it("associates the blocker with the Post button", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fillLine(0, "2000", "5");
        const blocker = await screen.findByTestId("voucher-blocker");
        expect(screen.getByTestId("post-voucher")).toHaveAttribute("aria-describedby", blocker.id);
        expect(blocker.id).toBeTruthy();
    });
});

describe("VoucherForm — attachments", () => {
    it("refuses an oversized file before it is uploaded", async () => {
        api.get.mockResolvedValue(detail({ status: "DRAFT" }));
        renderForm("PISR", { voucherId: "v1" });
        const input = (await screen.findByTestId("attachment-input")) as HTMLInputElement;

        const big = new File(["x"], "scan.pdf", { type: "application/pdf" });
        Object.defineProperty(big, "size", { value: 11 * 1024 * 1024 });
        Object.defineProperty(input, "files", { value: [big] });
        fireEvent.change(input);

        await waitFor(() =>
            expect(screen.getByTestId("attachment-error")).toHaveTextContent(en.Vouchers.attachmentTooBig),
        );
        expect(api.attachUpload).not.toHaveBeenCalled();
    });

    it("uploads an accepted file and lists it", async () => {
        api.get.mockResolvedValue(detail({ status: "DRAFT" }));
        api.attachUpload.mockResolvedValue({
            id: "att-1", voucherId: "v1", name: "scan.pdf",
            fileType: "application/pdf", fileSize: 12, uploadedAt: "2026-09-20T10:00:00Z",
        });
        renderForm("PISR", { voucherId: "v1" });
        const input = (await screen.findByTestId("attachment-input")) as HTMLInputElement;

        const ok = new File(["x"], "scan.pdf", { type: "application/pdf" });
        Object.defineProperty(input, "files", { value: [ok] });
        fireEvent.change(input);

        await waitFor(() => expect(api.attachUpload).toHaveBeenCalledWith("v1", "scan.pdf", ok));
        expect(await screen.findByTestId("attachment-row-att-1")).toHaveTextContent("scan.pdf");
    });

    /**
     * Ruling R24. An attachment needs a voucher to hang off, so uploading one
     * from a brand-new document saves a draft first. While a save is ALREADY in
     * flight `savedId` is still null — so picking a file during it sent a SECOND
     * `POST /vouchers` and the accountant ended up with two drafts of the same
     * invoice, one of them holding the attachment.
     */
    it("takes no file while a save is in flight, so a second draft cannot be created", async () => {
        let finishCreate: (v: unknown) => void = () => {};
        api.create.mockReturnValue(new Promise(r => { finishCreate = r; }));
        renderForm("PISR");
        await screen.findByTestId("line-amount-0");
        fillLine(0, "1000", "5");
        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.create).toHaveBeenCalledTimes(1));

        // The save has not answered, so there is still no voucher to attach to.
        const input = (await screen.findByTestId("attachment-input")) as HTMLInputElement;
        expect(input).toBeDisabled();
        const file = new File(["x"], "scan.pdf", { type: "application/pdf" });
        Object.defineProperty(input, "files", { value: [file], configurable: true });
        fireEvent.change(input);

        // No second draft, and nothing uploaded against one that does not exist.
        expect(api.create).toHaveBeenCalledTimes(1);
        expect(api.attachUpload).not.toHaveBeenCalled();

        finishCreate(detail({ id: "v-new" }));
        await waitFor(() => expect(screen.getByTestId("attachment-input")).not.toBeDisabled());
    });
});

describe("VoucherForm — one load, never a reload over unsaved lines", () => {
    /**
     * The plan 3 trap (commit a2bbd01f): a second load landing on a live form
     * empties whatever has been typed into it.
     */
    it("fetches the voucher exactly once", async () => {
        api.get.mockResolvedValue(detail({ status: "DRAFT" }));
        const { rerender } = renderForm("PISR", { voucherId: "v1" });
        await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1));

        rerender(
            <NextIntlClientProvider locale="en" messages={en}>
                <VoucherForm type="PISR" voucherId="v1" />
            </NextIntlClientProvider>,
        );
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "4321" } });
        await waitFor(() => expect(screen.getByTestId("line-amount-0")).toHaveValue("4321"));
        expect(api.get).toHaveBeenCalledTimes(1);
    });
});

/**
 * Finance-ops spec §1 (S12/O8): an income or expense line with no property drops
 * out of every property report, so the form — like VoucherService.validate —
 * asks for one, or for "Shared / head office", before it will post.
 */
describe("VoucherForm — line property", () => {
    beforeEach(() => {
        chart.rows = [{
            id: "acct-1", code: "510100", name: "Bank charges", accountType: "EXPENSE", accountSubType: "OTHER_EXPENSE",
            group: false, active: true, propertyId: null,
        } as (typeof chart.rows)[number]];
    });

    it("refuses an expense line with no property until Shared / head office is chosen, and sends shared", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fillInvoiceNumber();
        fillLine(0, "100", "0");
        await waitFor(() =>
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent("Line 1: choose a property"),
        );
        expect(screen.getByTestId("post-voucher")).toBeDisabled();

        const picker = screen.getByTestId("line-property-0");
        expect(screen.getAllByRole("option", { name: en.Vouchers.sharedHeadOffice }).length).toBeGreaterThan(0);
        fireEvent.change(picker, { target: { value: "__shared__" } });
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());

        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.create).toHaveBeenCalled());
        const body = api.create.mock.calls.at(-1)![0];
        expect(body.lines[0]).toMatchObject({ propertyId: null, shared: true });
    });

    it("accepts a line that names a property and does not send shared", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fillInvoiceNumber();
        fillLine(0, "100", "0");
        await waitFor(() => expect(screen.getAllByRole("option", { name: "L'Olivier" }).length).toBeGreaterThan(0));
        fireEvent.change(screen.getByTestId("line-property-0"), { target: { value: "prop-1" } });
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.create).toHaveBeenCalled());
        const body = api.create.mock.calls.at(-1)![0];
        expect(body.lines[0].propertyId).toBe("prop-1");
        expect(body.lines[0]).not.toHaveProperty("shared");
    });
});

describe("VoucherForm — supplier AP (finance-ops spec §2)", () => {
    const NO_TRN_VENDOR = {
        id: "ven-3", nameEn: "Handyman Co", nameAr: null, active: true, trn: null, paymentTermsDays: 30,
        payableAccount: { id: "pay-3", code: "210103", name: "Handyman Co" },
    };
    const OPEN_ITEMS = [
        { kind: "PISR", id: "inv-90", vendorId: "ven-1", vendorName: "Emirates Facilities", docNumber: "PISR-26/21",
          invoiceNumber: "INV-7790", docDate: "2026-08-20", invoiceDate: "2026-08-20", dueDate: "2026-09-19",
          daysOverdue: 0, bucket: "CURRENT", gross: 2100, allocated: 0, open: 2100, status: "OPEN", propertyId: null },
        { kind: "PISR", id: "inv-81", vendorId: "ven-1", vendorName: "Emirates Facilities", docNumber: "PISR-26/20",
          invoiceNumber: "INV-7781", docDate: "2026-08-01", invoiceDate: "2026-08-01", dueDate: "2026-08-31",
          daysOverdue: 0, bucket: "CURRENT", gross: 1450, allocated: 0, open: 1450, status: "OPEN", propertyId: null },
    ];
    let duplicate: string | null;
    let allocations: unknown[];

    beforeEach(() => {
        duplicate = null;
        allocations = [];
        vi.stubGlobal(
            "fetch",
            vi.fn(async (url: string) => {
                const u = String(url);
                const body = u.includes("/duplicate-check")
                    ? { duplicateOf: duplicate }
                    : u.includes("/open-items")
                      ? OPEN_ITEMS
                      : u.includes("/allocations")
                        ? allocations
                        : u.includes("/vendors")
                          ? [...VENDORS, NO_TRN_VENDOR]
                          : u.includes("/units")
                            ? UNITS
                            : u.includes("/properties")
                              ? PROPERTIES
                              : [];
                return new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
            }),
        );
    });

    it("needs the invoice number before a purchase invoice can post", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fillLine(0, "1000", "5");
        await waitFor(() =>
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(en.Vouchers.invoiceNumberRequired));
        fillInvoiceNumber("INV-7781");
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
    });

    it("defaults the due date from the supplier date and the vendor's terms, and sends both", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("doc-date"), { target: { value: "2026-08-20" } });
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-2" } });   // 45 days
        fireEvent.change(screen.getByTestId("supplier-invoice-date"), { target: { value: "2026-08-15" } });
        await waitFor(() => expect(screen.getByTestId("due-date")).toHaveValue("2026-09-29"));
        expect(screen.getByTestId("vendor-terms")).toHaveTextContent("45");
        fillInvoiceNumber("GC-1");
        fillLine(0, "100", "0");
        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.create).toHaveBeenCalled());
        const body = api.create.mock.calls.at(-1)![0];
        expect(body).toMatchObject({ supplierInvoiceDate: "2026-08-15", dueDate: "2026-09-29" });
    });

    it("says inline that the invoice is already posted, and will not post it", async () => {
        duplicate = "PISR-26/20";
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fillInvoiceNumber("inv 7781");
        fillLine(0, "100", "0");
        expect(await screen.findByTestId("duplicate-invoice", {}, { timeout: 3000 }))
            .toHaveTextContent("inv 7781 from Emirates Facilities is already posted as PISR-26/20");
        expect(screen.getByTestId("post-voucher")).toBeDisabled();
    });

    it("disables VAT with an explanation for a vendor with no TRN", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-3" } });
        await waitFor(() => expect(screen.getByTestId("line-vat-rate-0")).toBeDisabled());
        expect(screen.getByTestId("vat-blocked")).toHaveTextContent("Handyman Co has no TRN");
        expect(screen.getByTestId("line-vat-rate-0")).toHaveValue("0");
    });

    it("allocates a payment oldest-first and posts the allocations with it", async () => {
        api.create.mockResolvedValue(detail({ id: "bpv-new", docType: "BPV", lines: [] }));
        api.post.mockResolvedValue(detail({ id: "bpv-new", docType: "BPV", status: "POSTED", voucherNumber: "BPV-26/55" }));
        renderForm("BPV");
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        pickPaymentAccount("bank-1");
        fireEvent.change(screen.getByTestId("payment-reference"), { target: { value: "TRF-7781" } });
        pickLineAccount(0, "pay-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "2050" } });

        await screen.findByTestId("allocate-row-INV-7781");
        fireEvent.click(screen.getByTestId("auto-allocate"));
        expect(screen.getByTestId("allocate-amount-INV-7781")).toHaveValue("1450.00");
        expect(screen.getByTestId("allocate-amount-INV-7790")).toHaveValue("600.00");
        expect(screen.getByTestId("allocate-summary")).toHaveTextContent("0.00");

        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-voucher"));
        fireEvent.click(await screen.findByTestId("confirm-post"));
        await waitFor(() => expect(api.post).toHaveBeenCalled());
        const [id, allocs] = api.post.mock.calls.at(-1)!;
        expect(id).toBe("bpv-new");
        expect(allocs).toEqual([{ invoiceId: "inv-90", amount: 600 }, { invoiceId: "inv-81", amount: 1450 }]);
        const created = api.create.mock.calls.at(-1)![0];
        expect(created).toMatchObject({ paymentMethod: "TRANSFER", paymentReference: "TRF-7781", chequeNumber: null });
    });

    it("looks like a PISR (vendor, credit-note number, VAT lines) with no supplier/due date or payment fields (F14-40)", async () => {
        renderForm("PCN");
        await screen.findByTestId("line-amount-0");
        expect(screen.getByLabelText("Credit note no")).toBeInTheDocument();
        expect(screen.queryByTestId("supplier-invoice-date")).not.toBeInTheDocument();
        expect(screen.queryByTestId("due-date")).not.toBeInTheDocument();
        expect(screen.queryByTestId("payment-method")).not.toBeInTheDocument();
        expect(screen.getByTestId("account-picker")).toBeInTheDocument(); // the line account, PISR-shaped
        // The vendor is required, same as a PISR — not the BPV "No vendor" default.
        expect(screen.getByText("Select a vendor")).toBeInTheDocument();
    });

    it("allocates a PCN against the vendor's open invoices and posts it with them, allocating against its own gross total", async () => {
        api.create.mockResolvedValue(detail({ id: "pcn-new", docType: "PCN", lines: [] }));
        api.post.mockResolvedValue(detail({ id: "pcn-new", docType: "PCN", status: "POSTED", voucherNumber: "PCN-26/3" }));
        renderForm("PCN");
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fireEvent.change(screen.getByLabelText("Credit note no"), { target: { value: "CN-100" } });
        pickLineAccount(0, "acct-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "2050" } });

        await screen.findByTestId("allocate-row-INV-7781");
        fireEvent.click(screen.getByTestId("auto-allocate"));
        expect(screen.getByTestId("allocate-amount-INV-7781")).toHaveValue("1450.00");
        expect(screen.getByTestId("allocate-amount-INV-7790")).toHaveValue("600.00");

        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-voucher"));
        fireEvent.click(await screen.findByTestId("confirm-post"));
        await waitFor(() => expect(api.post).toHaveBeenCalled());
        const [id, allocs] = api.post.mock.calls.at(-1)!;
        expect(id).toBe("pcn-new");
        expect(allocs).toEqual([{ invoiceId: "inv-90", amount: 600 }, { invoiceId: "inv-81", amount: 1450 }]);
    });

    it("refuses to post a PCN whose allocations exceed its own total", async () => {
        renderForm("PCN");
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        fireEvent.change(screen.getByLabelText("Credit note no"), { target: { value: "CN-100" } });
        pickLineAccount(0, "acct-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "500" } });

        fireEvent.change(await screen.findByTestId("allocate-amount-INV-7781"), { target: { value: "600" } });
        await waitFor(() => expect(screen.getByTestId("voucher-blocker"))
            .toHaveTextContent(en.Vouchers.allocationsExceedPayment));
        expect(screen.getByTestId("post-voucher")).toBeDisabled();
    });

    it("counts days overdue in the allocation grid to the voucher's own date, not today (F14-46)", async () => {
        renderForm("BPV");
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        pickPaymentAccount("bank-1");
        pickLineAccount(0, "pay-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "500" } });

        const row = await screen.findByTestId("allocate-row-INV-7781"); // due 2026-08-31
        // Formatted dd/mm/yyyy, not raw ISO.
        expect(row).toHaveTextContent("31/08/2026");
        expect(row).not.toHaveTextContent("2026-08-31");

        fireEvent.change(screen.getByTestId("doc-date"), { target: { value: "2026-09-15" } });
        await waitFor(() => expect(row).toHaveTextContent("15 days overdue"));
    });

    it("refuses allocations beyond the payment", async () => {
        renderForm("BPV");
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });
        pickPaymentAccount("bank-1");
        pickLineAccount(0, "pay-1");
        fireEvent.change(screen.getByTestId("line-amount-0"), { target: { value: "500" } });
        fireEvent.change(await screen.findByTestId("allocate-amount-INV-7781"), { target: { value: "600" } });
        await waitFor(() =>
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(en.Vouchers.allocationsExceedPayment));
    });

    it("starts a payment's amendment from what it settles and sends that by default", async () => {
        allocations = [{ id: "a1", live: true, invoiceNumber: "INV-7781", invoiceVoucherId: "inv-81", amount: 1450 },
            { id: "a2", live: true, invoiceNumber: "INV-7790", invoiceVoucherId: "inv-90", amount: 600 }];
        api.get.mockResolvedValue(detail({
            docType: "BPV", status: "POSTED", voucherNumber: "BPV-26/55", paymentAccountId: "bank-1",
            paymentMethod: "TRANSFER", paymentReference: "TRF-7781",
            lines: [{ lineNo: 1, accountId: "pay-1", accountCode: "210101", accountName: "Emirates Facilities",
                description: null, amount: 2050, vatRate: 0, vatAmount: 0, propertyId: null, unitId: null }],
            settlement: { amount: 2050, allocated: 2050, open: 0, status: null },
        }));
        api.amend.mockResolvedValue(detail({ id: "v2", docType: "BPV", status: "POSTED", voucherNumber: "BPV-26/56" }));
        renderForm("BPV", { voucherId: "v1" });
        await waitFor(() => expect(screen.queryByTestId("settlements-panel")).toBeInTheDocument());
        fireEvent.click(await screen.findByTestId("amend-voucher"));
        // The panel starts from what the payment settles today (review P3-3).
        expect(await screen.findByTestId("allocate-amount-INV-7781")).toHaveValue("1450.00");
        expect(screen.getByTestId("allocate-amount-INV-7790")).toHaveValue("600.00");
        // A reference-only amend is not "no change"… but nothing is re-opened.
        fireEvent.change(screen.getByTestId("payment-reference"), { target: { value: "TRF-7781B" } });
        await waitFor(() => expect(screen.getByTestId("post-amendment")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-amendment"));
        await screen.findByTestId("amend-date");
        expect(screen.queryByTestId("amend-releases")).not.toBeInTheDocument();
        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "correction" } });
        fireEvent.click(screen.getByTestId("confirm-amend"));
        await waitFor(() => expect(api.amend).toHaveBeenCalled());
        expect(api.amend.mock.calls.at(-1)![1].allocations).toEqual([
            { invoiceId: "inv-90", amount: 600 }, { invoiceId: "inv-81", amount: 1450 }]);
    });

    it("names only the invoices an amended payment would leave less settled", async () => {
        allocations = [{ id: "a1", live: true, invoiceNumber: "INV-7781", invoiceVoucherId: "inv-81", amount: 1450 },
            { id: "a2", live: true, invoiceNumber: "INV-7790", invoiceVoucherId: "inv-90", amount: 600 }];
        api.get.mockResolvedValue(detail({
            docType: "BPV", status: "POSTED", voucherNumber: "BPV-26/55", paymentAccountId: "bank-1",
            paymentMethod: "TRANSFER", paymentReference: "TRF-7781",
            lines: [{ lineNo: 1, accountId: "pay-1", accountCode: "210101", accountName: "Emirates Facilities",
                description: null, amount: 2050, vatRate: 0, vatAmount: 0, propertyId: null, unitId: null }],
        }));
        renderForm("BPV", { voucherId: "v1" });
        await waitFor(() => expect(screen.queryByTestId("settlements-panel")).toBeInTheDocument());
        fireEvent.click(screen.getByTestId("amend-voucher"));
        fireEvent.change(await screen.findByTestId("allocate-amount-INV-7790"), { target: { value: "" } });
        await waitFor(() => expect(screen.getByTestId("post-amendment")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-amendment"));
        expect(await screen.findByTestId("amend-releases"))
            .toHaveTextContent("This payment settles INV-7790. They will show as unpaid.");
    });

    it("warns about an invoice the panel no longer lists, because it will not be sent (re-review N2)", async () => {
        allocations = [{ id: "a1", live: true, invoiceNumber: "INV-7781", invoiceVoucherId: "inv-81", amount: 1450 },
            { id: "a2", live: true, invoiceNumber: "INV-7790", invoiceVoucherId: "inv-90", amount: 600 }];
        // INV-7781 is no longer one of this vendor's items (reversed elsewhere, or the
        // vendor changed): the panel cannot list it, so the amendment will not send it.
        const onlyInv90 = OPEN_ITEMS.filter(i => i.id === "inv-90");
        const base = (globalThis.fetch as unknown as ReturnType<typeof vi.fn>).getMockImplementation() as
            (url: string) => Promise<Response>;
        vi.stubGlobal("fetch", vi.fn(async (url: string) => String(url).includes("/open-items")
            ? new Response(JSON.stringify(onlyInv90), { status: 200, headers: { "Content-Type": "application/json" } })
            : base(url)));
        api.get.mockResolvedValue(detail({
            docType: "BPV", status: "POSTED", voucherNumber: "BPV-26/55", paymentAccountId: "bank-1",
            paymentMethod: "TRANSFER", paymentReference: "TRF-7781",
            lines: [{ lineNo: 1, accountId: "pay-1", accountCode: "210101", accountName: "Emirates Facilities",
                description: null, amount: 2050, vatRate: 0, vatAmount: 0, propertyId: null, unitId: null }],
        }));
        api.amend.mockResolvedValue(detail({ id: "v2", docType: "BPV", status: "POSTED", voucherNumber: "BPV-26/56" }));
        renderForm("BPV", { voucherId: "v1" });
        await waitFor(() => expect(screen.queryByTestId("settlements-panel")).toBeInTheDocument());
        fireEvent.click(screen.getByTestId("amend-voucher"));
        expect(await screen.findByTestId("allocate-amount-INV-7790")).toHaveValue("600.00");
        expect(screen.queryByTestId("allocate-amount-INV-7781")).not.toBeInTheDocument();
        await waitFor(() => expect(screen.getByTestId("post-amendment")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-amendment"));
        expect(await screen.findByTestId("amend-releases"))
            .toHaveTextContent("This payment settles INV-7781. They will show as unpaid.");
        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "correction" } });
        fireEvent.click(screen.getByTestId("confirm-amend"));
        await waitFor(() => expect(api.amend).toHaveBeenCalled());
        expect(api.amend.mock.calls.at(-1)![1].allocations).toEqual([{ invoiceId: "inv-90", amount: 600 }]);
    });

    it("releases an allocation with a reason, and not one inside the lock", async () => {
        api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: "2026-08-31" });
        allocations = [{ id: "a1", live: true, invoiceNumber: "INV-7781", invoiceVoucherId: "inv-81", amount: 1450, allocatedOn: "2026-09-10" },
            { id: "a2", live: true, invoiceNumber: "INV-7702", invoiceVoucherId: "inv-02", amount: 500, allocatedOn: "2026-08-15" }];
        const bpv = detail({
            docType: "BPV", status: "POSTED", voucherNumber: "BPV-26/55", paymentAccountId: "bank-1",
            lines: [{ lineNo: 1, accountId: "pay-1", accountCode: "210101", accountName: "Emirates Facilities",
                description: null, amount: 1950, vatRate: 0, vatAmount: 0, propertyId: null, unitId: null }],
        });
        api.get.mockResolvedValue(bpv);
        const release = vi.fn(async (_url: string, _init?: RequestInit) =>
            new Response(JSON.stringify({ id: "a1", live: false }), { status: 200 }));
        const base = global.fetch as unknown as (u: string, i?: RequestInit) => Promise<Response>;
        vi.stubGlobal("fetch", vi.fn(async (u: string, init?: RequestInit) =>
            init?.method === "DELETE" ? release(u, init) : base(u, init)));
        renderForm("BPV", { voucherId: "v1" });
        expect(await screen.findByTestId("release-a2")).toBeDisabled();
        fireEvent.click(screen.getByTestId("release-a1"));
        expect(screen.getByTestId("release-confirm")).toBeDisabled();
        fireEvent.change(screen.getByTestId("release-reason"), { target: { value: "wrong invoice" } });
        fireEvent.click(screen.getByTestId("release-confirm"));
        await waitFor(() => expect(release).toHaveBeenCalled());
        const [url, init] = release.mock.calls[0];
        expect(url).toContain("/finance/voucher-allocations/a1");
        expect(JSON.parse(String(init?.body))).toEqual({ reason: "wrong invoice" });
    });

    it("keeps the supplier date on the posting date until it is typed, and dates the due date from it", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        fireEvent.change(screen.getByTestId("vendor"), { target: { value: "ven-1" } });   // 30 days
        fireEvent.change(screen.getByTestId("doc-date"), { target: { value: "2026-08-05" } });
        await waitFor(() => expect(screen.getByTestId("supplier-invoice-date")).toHaveValue("2026-08-05"));
        await waitFor(() => expect(screen.getByTestId("due-date")).toHaveValue("2026-09-04"));
        fillInvoiceNumber("AUG-1");
        fillLine(0, "1000", "5");
        fireEvent.click(screen.getByTestId("save-draft"));
        await waitFor(() => expect(api.create).toHaveBeenCalled());
        expect(api.create.mock.calls.at(-1)![0]).toMatchObject({
            docDate: "2026-08-05", supplierInvoiceDate: "2026-08-05", dueDate: "2026-09-04" });
    });

    it("re-derives the header property from the lines until it is chosen by hand", async () => {
        renderForm();
        await screen.findByTestId("line-amount-0");
        await waitFor(() => expect(screen.getAllByRole("option", { name: "L'Olivier" }).length).toBeGreaterThan(0));
        fireEvent.change(screen.getByTestId("line-property-0"), { target: { value: "prop-1" } });
        await waitFor(() => expect(screen.getByTestId("property")).toHaveValue("prop-1"));
        fireEvent.change(screen.getByTestId("line-property-0"), { target: { value: "prop-2" } });
        await waitFor(() => expect(screen.getByTestId("property")).toHaveValue("prop-2"));
        fireEvent.click(screen.getByTestId("add-line"));
        fireEvent.change(screen.getByTestId("line-property-1"), { target: { value: "prop-1" } });
        // Two properties: no single header property.
        await waitFor(() => expect(screen.getByTestId("property")).toHaveValue(""));
        // Chosen by hand, it stays.
        fireEvent.change(screen.getByTestId("property"), { target: { value: "prop-1" } });
        fireEvent.change(screen.getByTestId("line-property-1"), { target: { value: "prop-2" } });
        await new Promise(r => setTimeout(r, 50));
        expect(screen.getByTestId("property")).toHaveValue("prop-1");
    });

    it("shows a posted invoice's settlement status", async () => {
        api.get.mockResolvedValue(detail({
            status: "POSTED", voucherNumber: "PISR-26/21", settlement: { amount: 2100, allocated: 600, open: 1500, status: "PART_PAID" },
        }));
        renderForm("PISR", { voucherId: "v1" });
        const chip = await screen.findByTestId("settlement-status");
        expect(chip).toHaveAttribute("data-status", "PART_PAID");
        expect(chip).toHaveTextContent("Part-paid");
        expect(chip).toHaveTextContent("1,500.00");
    });
});

describe("VoucherForm — deposit refund (F14-36)", () => {
    const REFUND = {
        settlementId: "stl-1", renterName: "Prabhjot Singh", unitLabel: "A-101", amount: 6164.38,
    };

    beforeEach(() => {
        api.defaults.mockResolvedValue([
            { role: "RENTER_REFUND_PAYABLE", accountId: "acc-refund", accountCode: "210500", accountName: "Refunds payable – renters", inherited: false },
        ]);
        // A cash leaf so the refund payment-account picker has something to select.
        chart.rows = [
            { id: "acc-cash", accountType: "ASSET", accountSubType: "CASH", group: false, active: true },
        ] as (typeof chart.rows)[number][];
    });

    it("prefills the narration and the one locked line, and shows 'Refund to <renter>' instead of a vendor picker", async () => {
        renderForm("BPV", { refundPrefill: REFUND });
        await waitFor(() => expect(screen.getByTestId("line-amount-0")).toHaveValue("6164.38"));

        expect(screen.getByTestId("narration")).toHaveValue("Deposit refund – Prabhjot Singh – A-101");
        expect(screen.getByTestId("refund-to")).toHaveTextContent("Refund to Prabhjot Singh");
        expect(screen.queryByTestId("vendor")).not.toBeInTheDocument();
        // The line's account is locked to the resolved refund-payable leaf.
        expect(screen.getByTestId("account-picker")).toBeInTheDocument();
        expect(screen.getByText("Refunds payable – renters")).toBeInTheDocument();
        // No second line, and no way to add one.
        expect(screen.queryByTestId("add-line")).not.toBeInTheDocument();

        expect(screen.getByTestId("refund-payment-account")).toBeInTheDocument();
    });

    it("sends settlementId on create/post", async () => {
        renderForm("BPV", { refundPrefill: REFUND });
        await waitFor(() => expect(screen.getByTestId("line-amount-0")).toHaveValue("6164.38"));
        fireEvent.click(screen.getByTestId("account-picker")); // stub selects acc-9; irrelevant to this assertion
        await waitFor(() => expect(screen.getByTestId("refund-payment-account").querySelector('option[value="acc-cash"]')).toBeInTheDocument());
        fireEvent.change(screen.getByTestId("refund-payment-account"), { target: { value: "acc-cash" } });
        fireEvent.change(screen.getByTestId("payment-method"), { target: { value: "CASH" } });
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-voucher"));
        fireEvent.click(await screen.findByTestId("confirm-post"));
        await waitFor(() => expect(api.create).toHaveBeenCalled());
        expect(api.create.mock.calls.at(-1)![0]).toMatchObject({ settlementId: "stl-1", vendorId: null });
    });

    it("shows the translated refusal when the amount exceeds what the settlement owes", async () => {
        const { ApiError } = await import("@/lib/api/facilities");
        api.post.mockRejectedValueOnce(new ApiError(400, "exceeds", JSON.stringify({
            code: "voucher.refundExceedsOwed", args: { owed: "6,164.38", amount: "7,000.00" }, message: "exceeds",
        })));
        renderForm("BPV", { refundPrefill: REFUND });
        await waitFor(() => expect(screen.getByTestId("line-amount-0")).toHaveValue("6164.38"));
        await waitFor(() => expect(screen.getByTestId("refund-payment-account").querySelector('option[value="acc-cash"]')).toBeInTheDocument());
        fireEvent.change(screen.getByTestId("refund-payment-account"), { target: { value: "acc-cash" } });
        fireEvent.change(screen.getByTestId("payment-method"), { target: { value: "CASH" } });
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());
        fireEvent.click(screen.getByTestId("post-voucher"));
        fireEvent.click(await screen.findByTestId("confirm-post"));
        expect(await screen.findByTestId("voucher-error")).toHaveTextContent(
            "This settlement owes 6,164.38; 7,000.00 is more than that.",
        );
    });
});
