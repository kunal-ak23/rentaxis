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
    loadAccounts: async () => [],
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
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
    api.create.mockResolvedValue(detail({ id: "v-new", lines: [], netTotal: 0, vatTotal: 0, grossTotal: 0 }));
    api.update.mockResolvedValue(detail());
    api.post.mockResolvedValue(detail({ status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1" }));
    vi.stubGlobal(
        "fetch",
        vi.fn(async (url: string) => {
            const body = String(url).includes("/vendors") ? VENDORS : [];
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
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(en.Vouchers.otherVendorPayable),
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
            expect(screen.getByTestId("voucher-blocker")).toHaveTextContent(en.Vouchers.payableNeedsVendor),
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

describe("VoucherForm — attachments", () => {
    it("refuses an oversized file before it is uploaded", async () => {
        api.get.mockResolvedValue(detail({ status: "DRAFT" }));
        renderForm("PISR", { voucherId: "v1" });
        const input = (await screen.findByTestId("attachment-input")) as HTMLInputElement;

        const big = new File(["x"], "scan.pdf", { type: "application/pdf" });
        Object.defineProperty(big, "size", { value: 26 * 1024 * 1024 });
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
            fileUrl: "/x", fileType: "application/pdf", fileSize: 12, uploadedAt: "2026-09-20T10:00:00Z",
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
