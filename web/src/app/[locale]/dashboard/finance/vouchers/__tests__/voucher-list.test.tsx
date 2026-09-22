import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Page } from "@/lib/api/ledger";
import type { Voucher } from "@/lib/api/vouchers";

/**
 * The voucher list (spec §11).
 *
 * What it must get right: a PROPERTY_MANAGER never reaches it
 * (VoucherController's class-level @PreAuthorize), a SUPER_ADMIN with no
 * organisation selected sees the server's own sentence rather than an empty
 * table (VoucherController.requireTenantSelected), only a DRAFT row offers Edit
 * and Delete (VoucherService.requireDraft), and a posted row shows the journal
 * number it was given — never a provisional one.
 */

let role: string | null = "ACCOUNTANT";

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: role ? { user: { role } } : null }) }));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));

const api = vi.hoisted(() => ({ list: vi.fn(), remove: vi.fn() }));
vi.mock("@/lib/api/vouchers", async orig => {
    const m = await orig<typeof import("@/lib/api/vouchers")>();
    return { ...m, voucherApi: { ...m.voucherApi, list: api.list, remove: api.remove } };
});

import VoucherListPage from "../page";
import { ApiError } from "@/lib/api/facilities";

function voucher(over: Partial<Voucher> & { id: string }): Voucher {
    return {
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
        ...over,
    };
}

const ROWS: Voucher[] = [
    voucher({ id: "v-draft" }),
    voucher({
        id: "v-posted", status: "POSTED", voucherNumber: "PISR/2026/0007", journalId: "j1",
        postedAt: "2026-09-20T08:00:00Z",
    }),
    voucher({
        id: "v-pay", docType: "BPV", status: "REVERSED", voucherNumber: "BPV/2026/0002", journalId: "j2",
        vendorId: null, vendorName: null, paymentAccountId: "bank-1", paymentAccountName: "ENBD Current",
        netTotal: 750, vatTotal: 0, grossTotal: 750,
    }),
];

function page(rows: Voucher[]): Page<Voucher> {
    return { content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 25 };
}

function renderList() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <VoucherListPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    vi.clearAllMocks();
    role = "ACCOUNTANT";
    api.list.mockResolvedValue(page(ROWS));
});
afterEach(cleanup);

describe("voucher list", () => {
    it("renders one row per voucher with its totals and status", async () => {
        renderList();
        const posted = await screen.findByTestId("voucher-row-v-posted");
        expect(posted).toHaveTextContent("PISR/2026/0007");
        expect(posted).toHaveTextContent("1,050.00");
        expect(within(posted).getByTestId("voucher-status-v-posted")).toHaveAttribute("data-status", "POSTED");

        const pay = screen.getByTestId("voucher-row-v-pay");
        expect(pay).toHaveTextContent("ENBD Current");
        expect(pay).toHaveTextContent("750.00");
    });

    /** The number IS the journal's entry number, taken at posting time. */
    it("shows no document number on a draft", async () => {
        renderList();
        const draft = await screen.findByTestId("voucher-row-v-draft");
        expect(within(draft).getByTestId("voucher-number-v-draft")).toHaveTextContent("—");
    });

    it("offers Edit and Delete on a DRAFT only", async () => {
        renderList();
        await screen.findByTestId("voucher-row-v-draft");
        expect(screen.getByTestId("edit-voucher-v-draft")).toHaveAttribute(
            "href",
            "/dashboard/finance/vouchers/purchase-invoice?id=v-draft",
        );
        expect(screen.getByTestId("delete-voucher-v-draft")).toBeInTheDocument();

        for (const id of ["v-posted", "v-pay"]) {
            expect(screen.queryByTestId(`edit-voucher-${id}`)).not.toBeInTheDocument();
            expect(screen.queryByTestId(`delete-voucher-${id}`)).not.toBeInTheDocument();
        }
        // A posted document is still readable, and links to its journal.
        expect(screen.getByTestId("open-voucher-v-posted")).toBeInTheDocument();
        expect(screen.getByTestId("view-journal-v-posted")).toHaveAttribute(
            "href",
            "/dashboard/finance/journals/j1",
        );
        // A BPV opens on the payment page, not the invoice page.
        expect(screen.getByTestId("open-voucher-v-pay")).toHaveAttribute(
            "href",
            "/dashboard/finance/vouchers/payment?id=v-pay",
        );
    });

    it("deletes a draft behind a confirmation", async () => {
        api.remove.mockResolvedValue(undefined);
        renderList();
        await screen.findByTestId("voucher-row-v-draft");
        fireEvent.click(screen.getByTestId("delete-voucher-v-draft"));
        fireEvent.click(await screen.findByTestId("confirm-delete"));
        await waitFor(() => expect(api.remove).toHaveBeenCalledWith("v-draft"));
        // And the list reloads so the row goes.
        await waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
    });

    it("sends the filters and resets to the first page", async () => {
        renderList();
        await screen.findByTestId("voucher-row-v-draft");
        fireEvent.change(screen.getByTestId("filter-doc-type"), { target: { value: "BPV" } });
        await waitFor(() =>
            expect(api.list).toHaveBeenLastCalledWith(
                expect.objectContaining({ docType: "BPV", status: "", page: 0, size: 25 }),
            ),
        );
        fireEvent.change(screen.getByTestId("filter-status"), { target: { value: "POSTED" } });
        await waitFor(() =>
            expect(api.list).toHaveBeenLastCalledWith(
                expect.objectContaining({ docType: "BPV", status: "POSTED", page: 0 }),
            ),
        );
    });

    it("shows an empty state rather than a bare table", async () => {
        api.list.mockResolvedValue(page([]));
        renderList();
        expect(await screen.findByTestId("vouchers-empty")).toHaveTextContent(en.Vouchers.noVouchers);
        expect(screen.queryByTestId("vouchers-table")).not.toBeInTheDocument();
    });

    /** VoucherController.requireTenantSelected: a platform admin with no org picked. */
    it("surfaces the tenant-less 400 in a retryable banner", async () => {
        api.list.mockRejectedValue(new ApiError(400, "Select an organisation first"));
        renderList();
        expect(await screen.findByRole("alert")).toHaveTextContent("Select an organisation first");
        api.list.mockResolvedValue(page(ROWS));
        fireEvent.click(screen.getByText(en.Common.retry));
        expect(await screen.findByTestId("voucher-row-v-draft")).toBeInTheDocument();
    });

    it.each([
        ["SUPER_ADMIN", true],
        ["TENANT_ADMIN", true],
        ["ACCOUNTANT", true],
        ["PROPERTY_MANAGER", false],
        ["RENTER", false],
    ])("admits %s: %s", async (r, allowed) => {
        role = r;
        renderList();
        if (allowed) {
            expect(await screen.findByTestId("voucher-row-v-draft")).toBeInTheDocument();
        } else {
            expect(await screen.findByTestId("voucher-access-denied")).toBeInTheDocument();
            expect(api.list).not.toHaveBeenCalled();
        }
    });

    it("does not fetch until the session resolves", async () => {
        role = null;
        renderList();
        expect(screen.getByTestId("vouchers-loading")).toBeInTheDocument();
        expect(api.list).not.toHaveBeenCalled();
    });
});
