import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * #105 N2: typing in the search box starts a 350 ms debounce that writes
 * `?search=` onto whatever the URL is *when the timer fires*, not onto the
 * view the text was typed against. A pinned-view link clicked inside that
 * window changes the URL (a new view, still with no search of its own) but
 * does not touch `draftSearch`, so the old "typing" check — comparing only
 * the search param, not the whole filter context — kept the pending timer
 * alive and let it write the old text onto the new view a moment later.
 */

const push = vi.fn();
let role = "TENANT_ADMIN";

vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push }),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("../LeaseWizard", () => ({ default: () => null }));
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => <div data-testid="pagination" /> }));
vi.mock("@/lib/businessDate", () => ({ businessTodayIso: () => "2026-09-25" }));

const api = vi.hoisted(() => ({ paged: vi.fn(), statsByLeases: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, paged: api.paged }, chequeApi: { ...m.chequeApi, statsByLeases: api.statsByLeases } };
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

function renderPage() {
    return render(<NextIntlClientProvider locale="en" messages={en}><LeasesPage /></NextIntlClientProvider>);
}

beforeEach(() => {
    window.history.replaceState(null, "", "/en/dashboard/leases");
    role = "TENANT_ADMIN";
    api.statsByLeases.mockImplementation(async () => []);
    api.paged.mockImplementation(async (q: { status?: string; size?: number } = {}) => ({
        content: q.size === 1 ? [] : [lease("l1", "DRAFT", "A-1")], totalElements: 1, totalPages: 1, number: 0, size: q.size ?? 25,
    }));
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Contract list — #105 N2: a pinned-view click within the debounce window", () => {
    it("never lets text typed before the click write onto the view the click landed on", async () => {
        renderPage();
        await screen.findByTestId("lease-row-l1");

        fireEvent.change(screen.getByTestId("lease-search"), { target: { value: "abc" } });
        // Well inside the 350 ms debounce window.
        await act(async () => { await new Promise(r => setTimeout(r, 100)); });

        // A pinned-view link lands on Draft — same empty `search`, a different view.
        act(() => {
            window.history.pushState(null, "", "/en/dashboard/leases?view=draft");
            window.dispatchEvent(new PopStateEvent("popstate"));
        });

        // Let the original debounce timer's 350 ms fully elapse.
        await act(async () => { await new Promise(r => setTimeout(r, 400)); });

        expect(new URL(window.location.href).searchParams.get("search")).toBeNull();
        expect(new URL(window.location.href).searchParams.get("view")).toBe("draft");
        await waitFor(() => expect(api.paged).not.toHaveBeenCalledWith(expect.objectContaining({ search: "abc" })));
    });
});
