import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { JournalEntry, JournalSourceType } from "@/lib/api/ledger";

/**
 * Reverse belongs to a MANUAL journal only.
 *
 * `POST /journals/{id}/reverse` refuses any entry that belongs to a document —
 * a voucher, a lease, a cheque, a recognition period, a settlement — because
 * reversing the journal behind a document leaves the document POSTED and the
 * ledger empty, and nothing on either screen says so. Those are corrected on
 * the document's own page: a voucher through Amend, a lease through
 * termination, a cheque through cancel.
 *
 * So the button is offered on a MANUAL entry and on nothing else, and a
 * document-sourced entry links to the document instead.
 */

const role = { current: "ACCOUNTANT" };

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "j1", locale: "en" }),
    useSearchParams: () => new URLSearchParams(""),
}));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>
            {children}
        </a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: role.current } } }) }));
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ name: () => "", options: [], loading: false }),
}));

const api = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, journals: { ...m.ledgerApi.journals, get: api.get } } };
});

import JournalDetailPage from "../[id]/page";

function entry(over: Partial<JournalEntry> = {}): JournalEntry {
    return {
        id: "j1",
        entryNumber: "JV/2026/0001",
        docType: "JV",
        entryDate: "2026-09-20",
        narration: "Accrual",
        status: "POSTED",
        propertyId: null,
        unitId: null,
        leaseId: null,
        renterId: null,
        sourceType: "MANUAL",
        sourceId: null,
        reversalOfId: null,
        reversedById: null,
        importBatchId: null,
        postedBy: null,
        postedAt: "2026-09-20T09:00:00Z",
        total: 1000,
        lines: [
            {
                lineNo: 1, accountId: "a1", accountCode: "510100", accountName: "Maintenance",
                debit: 1000, credit: 0, narration: null,
                propertyId: null, unitId: null, leaseId: null, renterId: null, chequeId: null,
            },
        ],
        ...over,
    };
}

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <JournalDetailPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    vi.clearAllMocks();
    role.current = "ACCOUNTANT";
});
afterEach(cleanup);

describe("journal Reverse is MANUAL-only", () => {
    it("offers Reverse on a manual journal voucher", async () => {
        api.get.mockResolvedValue(entry({ sourceType: "MANUAL" }));
        renderPage();
        expect(await screen.findByTestId("reverse-journal")).toBeInTheDocument();
    });

    it.each<JournalSourceType>([
        "VOUCHER",
        "LEASE",
        "CHEQUE",
        "RECOGNITION",
        "PENALTY",
        "SETTLEMENT",
        "OPENING_BALANCE",
        "IMPORT",
        "REVERSAL",
    ])("hides Reverse on a %s-sourced journal", async sourceType => {
        api.get.mockResolvedValue(entry({ sourceType, sourceId: "doc-1" }));
        renderPage();
        await screen.findByRole("heading", { level: 1 });
        expect(screen.queryByTestId("reverse-journal")).not.toBeInTheDocument();
    });

    /** A null sourceType is an entry from before the column existed — not manual. */
    it("hides Reverse when the source type is unknown", async () => {
        api.get.mockResolvedValue(entry({ sourceType: null }));
        renderPage();
        await screen.findByRole("heading", { level: 1 });
        expect(screen.queryByTestId("reverse-journal")).not.toBeInTheDocument();
    });

    it("links a voucher-sourced journal to the voucher that wrote it", async () => {
        api.get.mockResolvedValue(entry({ docType: "PISR", sourceType: "VOUCHER", sourceId: "v-7" }));
        renderPage();
        expect(await screen.findByTestId("source-voucher")).toHaveAttribute(
            "href",
            "/dashboard/finance/vouchers/purchase-invoice?id=v-7",
        );
    });

    it("links a BPV journal to the payment page", async () => {
        api.get.mockResolvedValue(entry({ docType: "BPV", sourceType: "VOUCHER", sourceId: "v-8" }));
        renderPage();
        expect(await screen.findByTestId("source-voucher")).toHaveAttribute(
            "href",
            "/dashboard/finance/vouchers/payment?id=v-8",
        );
    });

    it("still refuses Reverse to a role that cannot post", async () => {
        role.current = "PROPERTY_MANAGER";
        api.get.mockResolvedValue(entry({ sourceType: "MANUAL" }));
        renderPage();
        await waitFor(() => expect(screen.getByText(en.Ledger.accessDeniedTitle)).toBeInTheDocument());
        expect(screen.queryByTestId("reverse-journal")).not.toBeInTheDocument();
    });
});
