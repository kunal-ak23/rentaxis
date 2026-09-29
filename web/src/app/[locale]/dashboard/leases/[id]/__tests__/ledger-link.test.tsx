import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * Review of #393: the Ledger link on the charge lines. A posted contract links to
 * the account's General Ledger narrowed to this contract, from its start and with
 * no end date (a settlement, refund or late clearance can post after the term).
 * A draft has no ledger rows of its own, so its account name is not a link.
 */

const push = vi.fn();
let role = "ACCOUNTANT";

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "lease-1" }),
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/leases/LeaseInteractionsPanel", () => ({ default: () => null }));
vi.mock("@/components/leases/LeaseAssignmentCard", () => ({ default: () => <div data-testid="assignment-card" /> }));
vi.mock("@/components/leases/BadDebtCard", () => ({ default: () => <div data-testid="bad-debt-card" /> }));

const api = vi.hoisted(() => ({
    get: vi.fn(),
    cheques: vi.fn(),
    notice: vi.fn(),
    settlement: vi.fn(),
}));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        penaltyApi: {
            ...m.penaltyApi,
            list: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })),
        },
        leaseApi: { ...m.leaseApi, get: api.get, cheques: api.cheques },
        terminationApi: { ...m.terminationApi, notice: api.notice },
        settlementApi: { ...m.settlementApi, get: api.settlement },
    };
});

import LeaseDetailPage from "../page";

function lease(status: LeaseStatus): LeaseDetail {
    return {
        id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
        startDate: "2026-01-01", endDate: "2026-12-31", status,
        rentAmount: 60000, depositAmount: null, ejariNumber: null, paymentTerms: 4,
        installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
        paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
        hasContract: false, contractNumber: null, displayContractNumber: "TCO-26/15",
        agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
        gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null,
        renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
        postingJournalId: "j-tco", postedAt: "2026-01-01T00:00:00Z", contractValue: 60000,
        terminatedOn: null, terminationJournalId: null, terminationNotes: null,
        lines: [],
    };
}

const CHEQUES: Cheque[] = [];

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseDetailPage />
        </NextIntlClientProvider>,
    );
}

function onLease(status: LeaseStatus, over: Partial<LeaseDetail> = {}) {
    api.get.mockImplementation(async () => ({ ...lease(status), ...over }));
}

beforeEach(() => {
    role = "ACCOUNTANT";
    onLease("ACTIVE");
    api.cheques.mockImplementation(async () => CHEQUES);
    api.settlement.mockRejectedValue(new Error("no settlement"));
    api.notice.mockImplementation(async () => lease("NOTICE_GIVEN"));
    push.mockClear();
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

const LINE = {
    id: "ln1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeName: "Rent", behaviour: "RENT",
    grossAmount: 60000, discountAmount: 0, netAmount: 60000, vatApplicable: false, narration: null,
    creditAccountId: "acc-9", creditAccountCode: "210104", creditAccountName: "Advance Rent – Desert Rose Gardens",
};

describe("contract charge lines — Ledger link", () => {
    it("links a posted contract's account to the GL for this contract, with no end date", async () => {
        role = "TENANT_ADMIN";
        onLease("ACTIVE", { lines: [LINE] as unknown as LeaseDetail["lines"], contractDate: "2025-12-20", startDate: "2026-01-01" });
        renderPage();
        const link = await screen.findByTestId("lease-line-ledger-0");
        expect(link.tagName).toBe("A");
        expect(link).toHaveAttribute("href", "/en/dashboard/finance/general-ledger?accountIds=acc-9&leaseId=lease-1&from=2025-12-20");
        expect(link.getAttribute("href")).not.toContain("to=");
    });

    it("shows a draft's account as plain text, not a link to the whole account", async () => {
        role = "TENANT_ADMIN";
        onLease("DRAFT", { lines: [LINE] as unknown as LeaseDetail["lines"], postedAt: null, postingJournalId: null });
        renderPage();
        const cell = await screen.findByTestId("lease-line-ledger-0");
        expect(cell.tagName).toBe("SPAN");
        expect(cell).toHaveTextContent("Advance Rent – Desert Rose Gardens");
    });
});
