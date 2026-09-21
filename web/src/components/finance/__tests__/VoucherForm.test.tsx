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
    attachList: vi.fn(async () => []),
    attachUpload: vi.fn(),
    attachRemove: vi.fn(),
    fiscal: vi.fn(),
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
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscal: { ...m.ledgerApi.fiscal, get: api.fiscal } } };
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
        payableAccount: { id: "pay-1", code: "210101", name: "Emirates Facilities" },
    },
    {
        id: "ven-2", nameEn: "Gulf Cooling", nameAr: "الخليج للتبريد", active: true,
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

function renderForm(type: "PISR" | "BPV" = "PISR", props: { voucherId?: string } = {}) {
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
        fillLine(0, "2000", "5");
        fireEvent.change(screen.getByTestId("doc-date"), { target: { value: "2026-08-31" } });

        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeDisabled());
        expect(screen.getByTestId("voucher-blocker")).toHaveTextContent("2026-08-31");

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
        fireEvent.click(await screen.findByTestId("confirm-amend"));

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
        await waitFor(() => expect(screen.getByTestId("confirm-amend")).toBeDisabled());
        expect(screen.getByTestId("amend-blocker")).toHaveTextContent("2026-09-30");

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
        fillLine(0, "1000", "5");
        await waitFor(() => expect(screen.getByTestId("post-voucher")).toBeEnabled());

        fireEvent.click(screen.getByTestId("post-voucher"));
        fireEvent.click(await screen.findByTestId("confirm-post"));

        await waitFor(() => expect(api.post).toHaveBeenCalledWith("v-new"));
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
        fireEvent.click(await screen.findByTestId("confirm-amend"));

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
