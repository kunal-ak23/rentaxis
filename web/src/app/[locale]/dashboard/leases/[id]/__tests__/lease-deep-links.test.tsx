import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * Spec §5: the contract page has four tabs, and every one of the nine old
 * `?tab=` values still lands on the tab that now holds it, with its section
 * open. The address bar is rewritten to the new form.
 */

const search = vi.hoisted(() => ({ current: "" }));
const push = vi.fn();
let role = "ACCOUNTANT";

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "lease-1" }),
    useSearchParams: () => new URLSearchParams(search.current),
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
vi.mock("@/components/leases/LeaseJournalsTab", () => ({ default: () => <div data-testid="probe-journals" /> }));
vi.mock("@/components/leases/RecognitionScheduleTab", () => ({ default: () => <div data-testid="probe-recognition" /> }));
vi.mock("@/components/leases/LeasePenaltiesTab", () => ({ default: () => <div data-testid="probe-penalties" /> }));
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

describe("contract deep links", () => {
    it.each([
        ["tab=journals", "payments", "journals"],
        ["tab=recognition", "payments", "recognition"],
        ["tab=penalties", "payments", "penalties"],
        ["tab=contract", "documents", "contract"],
        ["tab=documents", "documents", "attachments"],
        ["tab=maintenance", "activity", "maintenance"],
        ["tab=interactions", "activity", "interactions"],
    ])("maps the legacy ?%s to its new tab and opens the section", async (q, tab, section) => {
        search.current = q;
        window.history.replaceState(null, "", `/en/dashboard/leases/lease-1?${q}`);
        renderPage();
        expect(await screen.findByTestId(`lease-tab-${tab}`)).toHaveAttribute("aria-selected", "true");
        expect(screen.getByTestId(`lease-section-${section}`)).toHaveAttribute("open");
        const url = new URL(window.location.href);
        expect(url.searchParams.get("tab")).toBe(tab);
        // `documents` is still a tab id (it now also holds Contract and Addenda), so it is left as typed.
        expect(url.searchParams.get("section")).toBe(q === "tab=documents" ? null : section);
    });

    it("shows exactly four tabs, General first", async () => {
        search.current = "";
        renderPage();
        await screen.findByTestId("lease-tab-overview");
        expect(screen.getAllByRole("tab").map(t => t.getAttribute("data-testid"))).toEqual(
            ["lease-tab-overview", "lease-tab-payments", "lease-tab-documents", "lease-tab-activity"]);
        expect(screen.getAllByRole("tab").map(t => t.textContent)).toEqual(["General", "Cheques", "Attachments", "Activities"]);
        expect(screen.getByTestId("lease-tab-overview")).toHaveAttribute("aria-selected", "true");
    });

    it("keeps Journals and Recognition collapsed (and unloaded) in Cheques until asked for", async () => {
        search.current = "tab=payments";
        renderPage();
        await screen.findByTestId("lease-section-cheques");
        expect(screen.getByTestId("lease-section-cheques")).toHaveAttribute("open");
        expect(screen.getByTestId("lease-section-penalties")).toHaveAttribute("open");
        expect(screen.getByTestId("lease-section-journals")).not.toHaveAttribute("open");
        expect(screen.getByTestId("lease-section-recognition")).not.toHaveAttribute("open");
        expect(screen.queryByTestId("probe-journals")).toBeNull();
        const journals = screen.getByTestId("lease-section-journals") as HTMLDetailsElement;
        journals.open = true;
        fireEvent(journals, new Event("toggle"));
        expect(await screen.findByTestId("probe-journals")).toBeInTheDocument();
    });

    it("writes the chosen tab into the address bar", async () => {
        search.current = "";
        window.history.replaceState(null, "", "/en/dashboard/leases/lease-1?posted=TCO-26%2F1");
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-tab-activity"));
        const url = new URL(window.location.href);
        expect(url.searchParams.get("tab")).toBe("activity");
        expect(url.searchParams.get("posted")).toBe("TCO-26/1");
    });
});
