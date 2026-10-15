import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * Sandbox role test (2026-10-15) M2 and M4: the contract page offers a role only
 * what its endpoints answer.
 *  - "View posting journal" and the Journals section read JournalController,
 *    which admits SUPER_ADMIN, TENANT_ADMIN and ACCOUNTANT — a property manager
 *    who clicked it landed on "Access denied".
 *  - The Attachments section reads LeaseAttachmentController#list, which admits
 *    SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER and RENTER — an accountant's
 *    page fired a 403 for it on every load.
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

const attachmentCalls = () =>
    (global.fetch as unknown as ReturnType<typeof vi.fn>).mock.calls
        .map(c => String(c[0]))
        .filter(u => u.includes("/attachments"));

describe("contract page — role gates (sandbox role test M2, M4)", () => {
    it.each(["TENANT_ADMIN", "ACCOUNTANT", "SUPER_ADMIN"])("offers %s the posting journal link", async r => {
        role = r;
        renderPage();
        const link = await screen.findByTestId("lease-posting-journal");
        expect(link).toHaveAttribute("href", "/dashboard/finance/journals/j-tco");
    });

    it("hides the posting journal link and the Journals section from a property manager", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-tab-payments"));
        // The page has rendered the posted contract's header by now.
        expect(await screen.findByTestId("lease-tab-payments")).toHaveAttribute("aria-selected", "true");
        expect(screen.queryByTestId("lease-posting-journal")).toBeNull();
        expect(screen.queryByTestId("lease-section-journals")).toBeNull();
    });

    it("keeps the Journals section for an accountant", async () => {
        role = "ACCOUNTANT";
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-tab-payments"));
        expect(await screen.findByTestId("lease-section-journals")).toBeInTheDocument();
    });

    it("does not fetch or show attachments for an accountant", async () => {
        role = "ACCOUNTANT";
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-tab-documents"));
        expect(await screen.findByTestId("lease-section-contract")).toBeInTheDocument();
        expect(screen.queryByTestId("lease-section-attachments")).toBeNull();
        expect(attachmentCalls()).toEqual([]);
    });

    it.each(["PROPERTY_MANAGER", "TENANT_ADMIN"])("loads and shows attachments for %s", async r => {
        role = r;
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-tab-documents"));
        expect(await screen.findByTestId("lease-section-attachments")).toBeInTheDocument();
        await waitFor(() => expect(attachmentCalls()).toContain("/api/proxy/v1/leases/lease-1/attachments"));
    });
});
