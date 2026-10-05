import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";
import { pendingChequeSummary } from "@/components/leases/pendingCheques";
import type { Cheque, LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * Demo feedback 2026-09-29: the contract header carries "Pending cheques: N · AED X"
 * — REGISTERED + DEPOSITED rows not yet cleared — and, apart from it, "Returned:
 * N · AED Y" for bounced rows not yet replaced. Clicking it opens the Cheques tab.
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

function cheque(seqNo: number, status: Cheque["status"], amount: number, extra: Partial<Cheque> = {}): Cheque {
    return {
        id: `c${seqNo}`, leaseId: "lease-1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "Desert Rose Gardens", unitIdentifier: "A-101", renterName: "Omar Siddiqui", seqNo,
        postingDate: "2026-01-01", chequeNumber: String(100000 + seqNo), chequeDate: "2026-01-01",
        payeeBank: null, payerName: null, debitAccountId: null, debitAccountName: null, amount,
        narration: null, mode: "PDC", status, failureReason: null, replacesId: null, replacedById: null,
        imageUrl: null, depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
        ...extra,
    } as Cheque;
}

let CHEQUES: Cheque[] = [];
const MIXED: Cheque[] = [
    cheque(1, "CLEARED", 15000),
    cheque(2, "DEPOSITED", 15000),
    cheque(3, "REGISTERED", 15000.5),
    cheque(4, "REGISTERED", 14999.5),
    cheque(5, "BOUNCED", 5000),                              // returned, not replaced
    cheque(6, "BOUNCED", 7000, { replacedById: "c7" }),      // replaced: its successor counts
    cheque(7, "REGISTERED", 7000, { replacesId: "c6" }),
    cheque(8, "REGISTERED", 3000, { ledgerSettled: true }),  // absorbed into a settlement
    cheque(9, "CANCELLED", 1000),
    cheque(10, "ONLINE_PENDING", 900),
];

function renderPage(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
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
    CHEQUES = MIXED;
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

describe("pendingChequeSummary", () => {
    it("counts REGISTERED + DEPOSITED as pending and unreplaced BOUNCED as returned, apart", () => {
        expect(pendingChequeSummary(MIXED)).toEqual({
            pending: { count: 4, amount: 52000 },
            returned: { count: 1, amount: 5000, face: 5000 },
        });
        expect(pendingChequeSummary([])).toEqual({ pending: { count: 0, amount: 0 }, returned: { count: 0, amount: 0, face: 0 } });
    });

    it("counts a returned cheque at what is still owed on it, face value apart (PR #397 R1-P3-2)", () => {
        expect(pendingChequeSummary([cheque(1, "BOUNCED", 12750, { openAmount: 7510.27 })]).returned)
            .toEqual({ count: 1, amount: 7510.27, face: 12750 });
    });
});

describe("contract header — pending cheques strip", () => {
    it("shows pending and returned counts and amounts on a posted contract", async () => {
        renderPage();
        const strip = await screen.findByTestId("lease-pending-cheques");
        await vi.waitFor(() => expect(screen.getByTestId("lease-pending-cheques-pending")).toHaveTextContent("Pending cheques: 4 · AED 52,000.00"));
        expect(screen.getByTestId("lease-pending-cheques-returned")).toHaveTextContent("Returned: 1 · AED 5,000.00");
        expect(strip.tagName).toBe("BUTTON");
    });

    it("shows what a partly paid returned cheque still owes, with its face value (PR #397 R1-P3-2)", async () => {
        CHEQUES = [cheque(1, "REGISTERED", 1000), cheque(2, "BOUNCED", 12750, { openAmount: 7510.27 })];
        renderPage();
        await vi.waitFor(() => expect(screen.getByTestId("lease-pending-cheques-returned"))
            .toHaveTextContent("Returned: 1 · AED 7,510.27 owed of AED 12,750.00"));
    });

    it("leaves Returned out when nothing bounced unreplaced", async () => {
        CHEQUES = [cheque(1, "REGISTERED", 1000), cheque(2, "CLEARED", 1000)];
        renderPage();
        await vi.waitFor(() => expect(screen.getByTestId("lease-pending-cheques-pending")).toHaveTextContent("Pending cheques: 1 · AED 1,000.00"));
        expect(screen.queryByTestId("lease-pending-cheques-returned")).not.toBeInTheDocument();
    });

    it("opens the Cheques tab when clicked", async () => {
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-pending-cheques"));
        expect(screen.getByTestId("lease-tab-payments")).toHaveAttribute("aria-selected", "true");
        const url = new URL(window.location.href);
        expect(url.searchParams.get("tab")).toBe("payments");
        expect(url.searchParams.get("section")).toBe("cheques");
    });

    it("is not shown on a draft (its rows are proposals, not instruments)", async () => {
        onLease("DRAFT");
        CHEQUES = [cheque(1, "DRAFT", 1000)];
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(screen.queryByTestId("lease-pending-cheques")).not.toBeInTheDocument();
    });

    it("reads in Arabic with the amount isolated left-to-right", async () => {
        renderPage("ar");
        await vi.waitFor(() => expect(screen.getByTestId("lease-pending-cheques-pending")).toHaveTextContent("الشيكات المعلقة: 4"));
        const amount = screen.getByTestId("lease-pending-cheques-pending").querySelector("bdi");
        expect(amount).toHaveAttribute("dir", "ltr");
        expect(amount).toHaveTextContent("52,000.00");
        expect(screen.getByTestId("lease-pending-cheques-returned")).toHaveTextContent("المرتجعة: 1");
    });
});
