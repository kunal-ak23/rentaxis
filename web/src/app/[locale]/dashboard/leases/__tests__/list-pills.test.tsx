import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * Spec §1a/§7 on the contract list: status pills with counts, a property filter
 * and the search box up front; the all-status select behind Filters (with a
 * removable chip); one ⋯ menu per row that keeps every action and its gate.
 */

const push = vi.fn();
let role = "ACCOUNTANT";

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("../LeaseWizard", () => ({ default: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => <div data-testid="pagination" /> }));
vi.mock("@/lib/businessDate", () => ({ businessTodayIso: () => "2026-09-25" }));

const api = vi.hoisted(() => ({ paged: vi.fn(), statsByLeases: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: { ...m.leaseApi, paged: api.paged },
        chequeApi: { ...m.chequeApi, statsByLeases: api.statsByLeases },
    };
});

import LeasesPage from "../page";

function lease(id: string, status: LeaseStatus, unit: string): LeaseDetail {
    return {
        id, unitId: `u-${id}`, renterId: `r-${id}`, unitIdentifier: unit, renterName: `Renter ${unit}`,
        startDate: "2026-01-01", endDate: "2026-12-31", status,
        rentAmount: 60000, depositAmount: null, ejariNumber: null, paymentTerms: 4,
        installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
        paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
        hasContract: false, contractNumber: null, displayContractNumber: null,
        agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
        gracePeriodDays: 0, firstDueDate: null, renterAcceptedAt: null,
        renewedFromLeaseId: null, chainId: "chain-a", receivableAccountId: null,
        incomeAccountId: null, postingJournalId: null, postedAt: "2026-01-01T00:00:00Z",
        contractValue: 60000, terminatedOn: null, terminationJournalId: null, terminationNotes: null,
        lines: [],
    };
}

const DRAFT = lease("l-draft", "DRAFT", "A-100");
const ACTIVE = { ...lease("l-active", "ACTIVE", "A-101"), endDate: "2026-10-15", startDate: "2025-10-16" };
const LATER = lease("l-later", "ACTIVE", "A-105");
const TOTALS: Record<string, number> = { "": 13, DRAFT: 2, ACTIVE: 5, NOTICE_GIVEN: 1, TERMINATED: 1, EXPIRED: 1, RENEWED: 0, CLOSED: 3 };

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeasesPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    // The list keeps its filters in the URL, and jsdom keeps the URL between tests.
    window.history.replaceState(null, "", "/en/dashboard/leases");
    role = "TENANT_ADMIN";
    api.paged.mockImplementation(async (q: { status?: string; size?: number; sort?: string } = {}) => {
        const content = q.size === 1 ? [] : q.sort === "endDate,asc" ? [ACTIVE, LATER] : q.status === "DRAFT" ? [DRAFT] : [DRAFT, ACTIVE];
        return { content, totalElements: TOTALS[q.status ?? ""] ?? 0, totalPages: 1, number: 0, size: q.size ?? 25 };
    });
    api.statsByLeases.mockImplementation(async () => []);
    global.fetch = vi.fn(async (u: RequestInfo | URL) => ({
        ok: true, status: 200,
        json: async () => (String(u).endsWith("/v1/properties") ? [{ property: { id: "p1", nameEn: "Marina" } }, { property: { id: "p2", nameEn: "Olivier" } }] : []),
    })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("contract list pills", () => {
    it("shows the six pills with counts from the paged endpoint", async () => {
        renderPage();
        const pill = (v: string) => screen.getByTestId(`contract-pill-${v}`);
        await waitFor(() => expect(pill("ended")).toHaveTextContent("5"));
        expect(pill("all")).toHaveTextContent("13");
        expect(pill("draft")).toHaveTextContent("2");
        expect(pill("active")).toHaveTextContent("5");
        expect(pill("notice")).toHaveTextContent("1");
        // Expiring: the bounded end-date read, narrowed to 60 days — A-101 ends 15/10, A-105 on 31/12.
        expect(pill("expiring")).toHaveTextContent("1");
        expect(pill("all")).toHaveAttribute("aria-pressed", "true");
    });

    it("filters on the server and writes the pill into the URL", async () => {
        renderPage();
        await screen.findByTestId("lease-row-l-draft");
        fireEvent.click(screen.getByTestId("contract-pill-draft"));
        await waitFor(() => expect(api.paged).toHaveBeenLastCalledWith(expect.objectContaining({ status: "DRAFT", size: 25 })));
        expect(new URL(window.location.href).searchParams.get("status")).toBe("DRAFT");
        expect(screen.getByTestId("contract-pill-draft")).toHaveAttribute("aria-pressed", "true");
    });

    it("opens on the pill the URL names (Home's pipeline links here)", async () => {
        window.history.replaceState(null, "", "/en/dashboard/leases?view=expiring");
        renderPage();
        await waitFor(() => expect(api.paged).toHaveBeenCalledWith(expect.objectContaining({ status: "ACTIVE", sort: "endDate,asc", size: 100, page: 0 })));
        expect(await screen.findByTestId("lease-row-l-active")).toBeInTheDocument();
        expect(screen.queryByTestId("lease-row-l-later")).toBeNull();
        expect(screen.queryByTestId("pagination")).toBeNull();
        expect(screen.getByTestId("lease-expiring-note")).toBeInTheDocument();
    });

    it("sends the property filter with every read", async () => {
        renderPage();
        await screen.findByTestId("lease-row-l-draft");
        await waitFor(() => expect(within(screen.getByTestId("lease-property-filter")).getAllByRole("option")).toHaveLength(3));
        fireEvent.change(screen.getByTestId("lease-property-filter"), { target: { value: "p2" } });
        await waitFor(() => expect(api.paged).toHaveBeenCalledWith(expect.objectContaining({ propertyId: "p2", size: 25 })));
        await waitFor(() => expect(api.paged).toHaveBeenCalledWith(expect.objectContaining({ propertyId: "p2", status: "DRAFT", size: 1 })));
        expect(new URL(window.location.href).searchParams.get("propertyId")).toBe("p2");
    });
});

describe("filters and row menus", () => {
    it("moves the all-status select behind Filters and shows a chip when used", async () => {
        renderPage();
        await screen.findByTestId("lease-row-l-draft");
        expect(screen.getByTestId("lease-status-filter")).not.toBeVisible();
        fireEvent.click(screen.getByTestId("filters-button"));
        expect(screen.getByTestId("lease-status-filter")).toBeVisible();
        fireEvent.change(screen.getByTestId("lease-status-filter"), { target: { value: "PENDING_SIGNATURE" } });
        expect(await screen.findByTestId("lease-filter-chip-status")).toHaveTextContent("Pending Signature");
        await waitFor(() => expect(api.paged).toHaveBeenLastCalledWith(expect.objectContaining({ status: "PENDING_SIGNATURE" })));
        fireEvent.click(screen.getByTestId("lease-filter-chip-status-remove"));
        await waitFor(() => expect(screen.queryByTestId("lease-filter-chip-status")).toBeNull());
        expect(new URL(window.location.href).searchParams.get("status")).toBeNull();
    });

    it("collapses row actions into one menu, keeping every action and its test id", async () => {
        renderPage();
        const row = await screen.findByTestId("lease-row-l-active");
        const menu = within(row).getByTestId("lease-actions-menu-panel-l-active");
        expect(menu).not.toBeVisible();
        expect(within(row).getByTestId("lease-list-terminate-l-active")).not.toBeVisible();
        expect(Array.from(menu.querySelectorAll('[role="menuitem"]')).map(e => e.getAttribute("data-testid")))
            .toEqual(["lease-action-view-l-active", "lease-list-terminate-l-active", "lease-action-docs-l-active"]);
        const draft = screen.getByTestId("lease-actions-menu-panel-l-draft");
        expect(Array.from(draft.querySelectorAll('[role="menuitem"]')).map(e => e.getAttribute("data-testid"))).toEqual([
            "lease-action-view-l-draft", "lease-action-edit-l-draft", "lease-action-generate-l-draft", "lease-action-post-l-draft",
            "lease-action-docs-l-draft", "lease-action-delete-l-draft",
        ]);
        fireEvent.click(within(row).getByTestId("lease-actions-menu-l-active"));
        expect(menu).toBeVisible();
        fireEvent.click(within(menu).getByTestId("lease-list-terminate-l-active"));
        expect(push).toHaveBeenCalledWith("/dashboard/leases/l-active/terminate");
    });
});
