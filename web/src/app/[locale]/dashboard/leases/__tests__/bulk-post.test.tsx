import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LeaseDetail } from "@/lib/api/leasing";

/**
 * Posting a batch of drafts from the list.
 *
 * Sequential, and every row's outcome reported: each post writes journals and
 * takes an entry number from one tenant-wide sequence, so they cannot go out
 * together, and one refusal must not stop the rest from being attempted.
 * "3 of 7 posted" with no word on which three is not an answer an accountant
 * can act on.
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
vi.mock("@/components/ui/Pagination", () => ({ Pagination: () => null }));

const api = vi.hoisted(() => ({ paged: vi.fn(), post: vi.fn(), statsByLeases: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: { ...m.leaseApi, paged: api.paged, post: api.post },
        chequeApi: { ...m.chequeApi, statsByLeases: api.statsByLeases },
    };
});

import LeasesPage from "../page";

function lease(over: Partial<LeaseDetail> & { id: string }): LeaseDetail {
    return {
        unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
        startDate: "2026-01-01", endDate: "2026-12-31", status: "DRAFT",
        rentAmount: null, depositAmount: null, ejariNumber: null, paymentTerms: 4,
        installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
        paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
        hasContract: false, contractNumber: null, displayContractNumber: null,
        agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
        gracePeriodDays: 0, firstDueDate: null, renterAcceptedAt: null,
        renewedFromLeaseId: null, chainId: "chain-aaaaaaaa", receivableAccountId: null,
        incomeAccountId: null, postingJournalId: null, postedAt: null, contractValue: 60000,
    terminatedOn: null, terminationJournalId: null, terminationNotes: null,
        lines: [],
        ...over,
    };
}

const ROWS = [
    lease({ id: "l1", unitIdentifier: "A-101" }),
    lease({ id: "l2", unitIdentifier: "A-102" }),
    lease({ id: "l3", unitIdentifier: "A-103", status: "ACTIVE" }),
    lease({ id: "l4", unitIdentifier: "A-104", status: "RENEWED", renewedFromLeaseId: "l0" }),
];

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeasesPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role = "ACCOUNTANT";
    // A real server, filtering by `status` when the list sends it — so a test
    // that flips the filter and finds one row proves the param made the round
    // trip, not that a client-side `.filter()` ran on the page it already had.
    api.paged.mockImplementation(async (q: { status?: string } = {}) => {
        const content = q.status ? ROWS.filter(l => l.status === q.status) : ROWS;
        return { content, totalElements: content.length, totalPages: 1, number: 0, size: 25 };
    });
    api.statsByLeases.mockImplementation(async () => []);
    api.post.mockReset();
    push.mockClear();
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(cleanup);

describe("Leases list — renter acceptance (#79)", () => {
    it("badges a PENDING_SIGNATURE lease the renter has accepted, and no other", async () => {
        const rows = [
            lease({ id: "p1", unitIdentifier: "A-201", status: "PENDING_SIGNATURE", renterAcceptedAt: "2026-09-20T08:00:00Z" }),
            lease({ id: "p2", unitIdentifier: "A-202", status: "PENDING_SIGNATURE", renterAcceptedAt: null }),
        ];
        api.paged.mockImplementation(async () => ({ content: rows, totalElements: 2, totalPages: 1, number: 0, size: 25 }));
        renderPage();
        const row = await screen.findByTestId("lease-row-p1");
        expect(row).toHaveTextContent("Accepted by tenant 20/09/2026");
        expect(screen.getByTestId("lease-row-p2")).not.toHaveTextContent("Accepted by tenant");
    });
});

describe("Leases list — bulk post", () => {
    it("posts each selected draft in turn and lists every outcome", async () => {
        api.post
            .mockResolvedValueOnce({ lease: ROWS[0], tcoJournalId: "j1", tcoEntryNumber: "TCO-26/15", cheques: [] })
            .mockRejectedValueOnce(
                new (await import("@/lib/api/leasing")).ApiError(400, "Line 2 (ADMIN_FEE): credit account 400100 is inactive."),
            );

        renderPage();
        fireEvent.click(await screen.findByTestId("bulk-post-select-l1"));
        fireEvent.click(screen.getByTestId("bulk-post-select-l2"));
        fireEvent.click(screen.getByTestId("bulk-post"));

        await waitFor(() => expect(screen.getByTestId("bulk-post-results")).toBeInTheDocument());
        expect(api.post).toHaveBeenNthCalledWith(1, "l1");
        expect(api.post).toHaveBeenNthCalledWith(2, "l2");

        const ok = screen.getByTestId("bulk-post-result-l1");
        expect(ok).toHaveAttribute("data-ok", "true");
        expect(ok).toHaveTextContent("Posted as TCO-26/15");

        const failed = screen.getByTestId("bulk-post-result-l2");
        expect(failed).toHaveAttribute("data-ok", "false");
        expect(failed).toHaveTextContent("credit account 400100 is inactive.");
    });

    it("offers a checkbox only on drafts, and none at all without the permission", async () => {
        renderPage();
        await screen.findByTestId("bulk-post-select-l1");
        // ACTIVE and RENEWED rows are not postable, so they carry no checkbox.
        expect(screen.queryByTestId("bulk-post-select-l3")).not.toBeInTheDocument();
        expect(screen.queryByTestId("bulk-post-select-l4")).not.toBeInTheDocument();

        cleanup();
        role = "PROPERTY_MANAGER";
        renderPage();
        await screen.findByTestId("lease-row-l1");
        expect(screen.queryByTestId("bulk-post-select-l1")).not.toBeInTheDocument();
        expect(screen.queryByTestId("bulk-post")).not.toBeInTheDocument();
    });

    it("sends the status filter to the server, RENEWED among the choices, and shows the chain", async () => {
        renderPage();
        await screen.findByTestId("lease-row-l1");
        expect(screen.getAllByTestId(/^lease-row-/)).toHaveLength(4);

        fireEvent.change(screen.getByTestId("lease-status-filter"), { target: { value: "RENEWED" } });

        // The filter is a fetch, not a re-slice of the page already on screen —
        // GET /leases/paged took the status itself (LeaseController, since b70f770b).
        await waitFor(() => expect(api.paged).toHaveBeenLastCalledWith(expect.objectContaining({ status: "RENEWED" })));
        const rows = await screen.findAllByTestId(/^lease-row-/);
        expect(rows).toHaveLength(1);
        expect(rows[0]).toHaveAttribute("data-testid", "lease-row-l4");
        expect(rows[0]).toHaveTextContent("chain-aa");
        expect(rows[0]).toHaveTextContent("Renewed");
    });

    it("clears the filter back to an unfiltered fetch", async () => {
        renderPage();
        await screen.findByTestId("lease-row-l1");

        fireEvent.change(screen.getByTestId("lease-status-filter"), { target: { value: "RENEWED" } });
        await waitFor(() => expect(screen.getAllByTestId(/^lease-row-/)).toHaveLength(1));

        fireEvent.change(screen.getByTestId("lease-status-filter"), { target: { value: "" } });
        await waitFor(() => expect(api.paged).toHaveBeenLastCalledWith(expect.objectContaining({ status: undefined })));
        expect(await screen.findAllByTestId(/^lease-row-/)).toHaveLength(4);
    });
});
