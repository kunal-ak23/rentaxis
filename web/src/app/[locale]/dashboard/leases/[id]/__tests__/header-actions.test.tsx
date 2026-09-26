import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * Spec §5: the contract header shows at most three primary buttons by status;
 * everything else sits under More actions, and the Assignment and Write off
 * cards open in drawers instead of always showing on General.
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

function onLease(status: LeaseStatus) {
    api.get.mockImplementation(async () => lease(status));
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

const primaryIds = () => Array.from(screen.getByTestId("lease-actions-primary").querySelectorAll("[data-testid]")).map(e => e.getAttribute("data-testid"));
const menuIds = () => Array.from(screen.getByTestId("lease-more-actions-menu").querySelectorAll('[role="menuitem"]')).map(e => e.getAttribute("data-testid"));

describe("contract header", () => {
    it("leads an active contract with Record payment and Renew; the rest is in More actions", async () => {
        role = "TENANT_ADMIN";
        onLease("ACTIVE");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(primaryIds()).toEqual(["lease-record-payment", "lease-renew"]);
        expect(menuIds()).toEqual(["lease-extend", "lease-amend", "lease-add-charge", "lease-transfer",
            "lease-assignment", "lease-reduce", "lease-raise-penalty", "lease-give-notice", "lease-terminate", "lease-write-off", "lease-ledger"]);
        expect(screen.getByTestId("lease-record-payment")).toHaveAttribute("href", "/dashboard/collections?tab=all&leaseId=lease-1&receive=1");
        expect(screen.getByTestId("lease-more-actions-menu")).not.toBeVisible();
        fireEvent.click(screen.getByTestId("lease-more-actions"));
        expect(screen.getByTestId("lease-more-actions-menu")).toBeVisible();
    });

    it("keeps Renew primary for a property manager and Terminate in the menu", async () => {
        role = "PROPERTY_MANAGER";
        onLease("ACTIVE");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(primaryIds()).toContain("lease-renew");
        expect(menuIds()).toContain("lease-terminate");
        expect(menuIds()).not.toContain("lease-write-off");
    });

    it("leads a draft with Edit and Post, and keeps Delete in the menu", async () => {
        role = "TENANT_ADMIN";
        api.get.mockImplementation(async () => ({ ...lease("DRAFT"), postedAt: null, postingJournalId: null }));
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(primaryIds()).toEqual(["lease-edit", "lease-post"]);
        expect(menuIds()).toEqual(["lease-delete"]);
    });

    it("opens the assignment card in a drawer instead of always showing it", async () => {
        role = "TENANT_ADMIN";
        onLease("ACTIVE");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(screen.queryByTestId("assignment-card")).toBeNull();
        expect(screen.queryByTestId("lease-assignment-drawer")).toBeNull();
        fireEvent.click(screen.getByTestId("lease-assignment"));
        expect(await screen.findByTestId("lease-assignment-drawer")).toBeInTheDocument();
        expect(screen.getByTestId("assignment-card")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("lease-assignment-drawer-close"));
        expect(screen.queryByTestId("lease-assignment-drawer")).toBeNull();
    });

    it("opens Write off in a drawer for finance roles", async () => {
        role = "ACCOUNTANT";
        onLease("ACTIVE");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(screen.queryByTestId("bad-debt-card")).toBeNull();
        fireEvent.click(screen.getByTestId("lease-write-off"));
        expect(await screen.findByTestId("lease-write-off-drawer")).toBeInTheDocument();
        expect(screen.getByTestId("bad-debt-card")).toBeInTheDocument();
    });

    it("keeps Settlement primary on a terminated contract", async () => {
        role = "ACCOUNTANT";
        onLease("TERMINATED");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(primaryIds()).toEqual(["lease-settle"]);
    });

    it("offers Download contract in the menu when a contract was generated", async () => {
        role = "TENANT_ADMIN";
        api.get.mockImplementation(async () => ({ ...lease("ACTIVE"), hasContract: true }));
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(menuIds()).toContain("lease-download-contract");
    });
});
