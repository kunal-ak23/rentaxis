import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * Owner ruling 2026-09-29: one date, the contract date. The General tab shows an
 * Agreement date only when it differs from the contract date (an imported or an
 * older contract); the server now defaults it to the contract date otherwise.
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
        id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Omar Siddiqui",
        startDate: "2026-01-01", endDate: "2026-12-31", status,
        rentAmount: 60000, depositAmount: null, ejariNumber: null, paymentTerms: 4,
        installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
        paymentReferenceNumber: null, propertyId: "p1", propertyName: "Desert Rose Gardens", propertyCode: "DRG",
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

describe("contract General tab — agreement date", () => {
    it("is hidden when it is the contract date", async () => {
        onLease("ACTIVE", { contractDate: "2026-01-01", agreementDate: "2026-01-01" });
        renderPage();
        expect(await screen.findByText("Contract Date")).toBeInTheDocument();
        expect(screen.queryByText("Agreement Date")).not.toBeInTheDocument();
    });

    it("is hidden when there is none", async () => {
        onLease("ACTIVE", { agreementDate: null });
        renderPage();
        expect(await screen.findByText("Contract Date")).toBeInTheDocument();
        expect(screen.queryByText("Agreement Date")).not.toBeInTheDocument();
    });

    it("is shown when it differs (an imported contract)", async () => {
        onLease("ACTIVE", { contractDate: "2026-01-01", agreementDate: "2025-12-20" });
        renderPage();
        expect(await screen.findByText("Agreement Date")).toBeInTheDocument();
    });
});
