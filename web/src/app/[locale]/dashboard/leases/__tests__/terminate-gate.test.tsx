import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * The lease LIST's Terminate affordance, in both views.
 *
 * The destination is the termination page, whose read gate is
 * `canPreviewTermination` (SA/TA/ACCOUNTANT/PM, `LeaseController` :273-274) and
 * whose write gate is `canTerminateLeases` (SA/TA/ACCOUNTANT, :287-288).
 * `canManageLeases` — the key the table view used — is SA/TA, the DRAFT-lease
 * key, which has nothing to do with ending a contract: the accountant who now
 * owns termination could not start one from the list. The card view had no key
 * of its own at all and inherited the same wrong one from the block around it.
 *
 * Both views also gated on ACTIVE alone while
 * `LeaseTerminationService.TERMINABLE` is {ACTIVE, NOTICE_GIVEN} — so the list
 * and the detail page disagreed about the same contract.
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
        paymentReferenceNumber: null, propertyId: "p1", propertyName: "Sample Heights", propertyCode: "SMH",
        hasContract: false, contractNumber: null, displayContractNumber: null,
        agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
        gracePeriodDays: 0, firstDueDate: null, renterAcceptedAt: null,
        renewedFromLeaseId: null, chainId: "chain-a", receivableAccountId: null,
        incomeAccountId: null, postingJournalId: null, postedAt: "2026-01-01T00:00:00Z",
        contractValue: 60000, terminatedOn: null, terminationJournalId: null, terminationNotes: null,
        lines: [],
    };
}

const ROWS = [
    lease("l-active", "ACTIVE", "A-101"),
    lease("l-notice", "NOTICE_GIVEN", "A-102"),
    lease("l-expired", "EXPIRED", "A-103"),
    lease("l-closed", "CLOSED", "A-104"),
];

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeasesPage />
        </NextIntlClientProvider>,
    );
}

async function showCards() {
    fireEvent.click(screen.getByTestId("lease-view-cards"));
    await waitFor(() => expect(screen.getByTestId("lease-card-terminate-l-active")).toBeInTheDocument());
}

beforeEach(() => {
    role = "ACCOUNTANT";
    api.paged.mockImplementation(async (q: { status?: string } = {}) => {
        const content = q.status ? ROWS.filter(l => l.status === q.status) : ROWS;
        return { content, totalElements: content.length, totalPages: 1, number: 0, size: 25 };
    });
    api.statsByLeases.mockImplementation(async () => []);
    push.mockClear();
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Terminate in the table view", () => {
    it("is offered to an ACCOUNTANT — the role that now owns termination", async () => {
        renderPage();
        expect(await screen.findByTestId("lease-list-terminate-l-active")).toBeInTheDocument();
    });

    it("is offered on NOTICE_GIVEN too, matching TERMINABLE and the detail page", async () => {
        renderPage();
        expect(await screen.findByTestId("lease-list-terminate-l-notice")).toBeInTheDocument();
    });

    it("is not offered once the contract has ended", async () => {
        renderPage();
        await screen.findByTestId("lease-list-terminate-l-active");
        expect(screen.queryByTestId("lease-list-terminate-l-expired")).not.toBeInTheDocument();
        expect(screen.queryByTestId("lease-list-terminate-l-closed")).not.toBeInTheDocument();
    });

    it("opens the termination page rather than terminating from the list", async () => {
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-list-terminate-l-active"));
        expect(push).toHaveBeenCalledWith("/dashboard/leases/l-active/terminate");
    });

    it("is hidden from a role the preview endpoint refuses", async () => {
        role = "TENANT_USER";
        renderPage();
        await waitFor(() => expect(screen.getByTestId("leases-access-denied")).toBeInTheDocument());
    });
});

describe("Terminate in the card view", () => {
    it("agrees with its sibling on both the status set and the key", async () => {
        renderPage();
        await screen.findByTestId("lease-list-terminate-l-active");
        await showCards();

        expect(screen.getByTestId("lease-card-terminate-l-notice")).toBeInTheDocument();
        expect(screen.queryByTestId("lease-card-terminate-l-expired")).not.toBeInTheDocument();
        expect(screen.queryByTestId("lease-card-terminate-l-closed")).not.toBeInTheDocument();
    });

    it("shows for a PROPERTY_MANAGER, who may price a move-out", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();
        await waitFor(() => expect(screen.getByTestId("lease-view-cards")).toBeInTheDocument());
        await showCards();
        expect(screen.getByTestId("lease-card-terminate-l-active")).toBeInTheDocument();
        // ...but never the draft-lease affordances, which are SA/TA only.
        expect(screen.queryByRole("button", { name: "Draft Lease" })).not.toBeInTheDocument();
        expect(screen.queryByTestId("lease-card-edit-l-active")).not.toBeInTheDocument();
    });
});
