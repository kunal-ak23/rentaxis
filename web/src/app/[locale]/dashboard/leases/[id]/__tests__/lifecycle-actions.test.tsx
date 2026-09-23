import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail, LeaseStatus } from "@/lib/api/leasing";
import { ApiError } from "@/lib/api/facilities";

/**
 * The lease detail page's lifecycle action bar, against the Java sets it
 * mirrors:
 *
 *  - **Give notice** — `LeaseService.giveNotice` (:1016) is ACTIVE-only, and
 *    `LeaseController` :250-251 is SA/TA/ACCOUNTANT/PM. Until this wave nothing
 *    in the product called `POST /leases/{id}/notice` at all, so NOTICE_GIVEN —
 *    the status plan 3 re-derived three rule sets around — was unreachable.
 *  - **Renew** — `LeaseRenewalService.RENEWABLE` (:74-75) is {ACTIVE, EXPIRED,
 *    NOTICE_GIVEN}. The page offered it on ACTIVE alone, which hid the ordinary
 *    renew-at-expiry flow entirely.
 *  - **Amend / Extend** really are ACTIVE-only (`LeasePostingService` :315,
 *    `LeaseRenewalService` :271) and must stay that way.
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

describe("Give notice", () => {
    it("is offered on an ACTIVE contract and posts /notice", async () => {
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-give-notice"));
        fireEvent.click(await screen.findByTestId("lease-give-notice-confirm"));
        // #27: the notice carries its date (today, Dubai) and party (renter by default).
        await waitFor(() => expect(api.notice).toHaveBeenCalledWith("lease-1", expect.objectContaining({
            givenBy: "RENTER", noticeDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/), intendedMoveOutDate: null,
        })));
        // The page re-reads the lease rather than assuming the new status.
        await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
    });

    it("is gone once notice has been given — giveNotice refuses anything but ACTIVE", async () => {
        onLease("NOTICE_GIVEN");
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(screen.queryByTestId("lease-give-notice")).not.toBeInTheDocument();
    });

    it("is offered to a PROPERTY_MANAGER — one role wider than Terminate", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();
        expect(await screen.findByTestId("lease-give-notice")).toBeInTheDocument();
        // A manager may price a move-out but not post its journals, so the
        // Terminate link is there and the termination page re-gates the button.
        expect(screen.getByTestId("lease-terminate")).toBeInTheDocument();
    });

    it("is hidden from a role the endpoint refuses", async () => {
        role = "TENANT_USER";
        renderPage();
        await waitFor(() => expect(screen.getByTestId("lease-access-denied")).toBeInTheDocument());
    });
});

describe("Renew, against LeaseRenewalService.RENEWABLE", () => {
    for (const status of ["ACTIVE", "NOTICE_GIVEN", "EXPIRED"] as const) {
        it(`is offered on ${status}`, async () => {
            onLease(status);
            renderPage();
            expect(await screen.findByTestId("lease-renew")).toBeInTheDocument();
        });
    }

    for (const status of ["RENEWED", "TERMINATED", "CLOSED", "DRAFT"] as const) {
        it(`is not offered on ${status}`, async () => {
            onLease(status);
            renderPage();
            await screen.findByTestId("lease-actions");
            expect(screen.queryByTestId("lease-renew")).not.toBeInTheDocument();
        });
    }

    it("leaves Amend and Extend ACTIVE-only", async () => {
        onLease("EXPIRED");
        renderPage();
        await screen.findByTestId("lease-renew");
        expect(screen.queryByTestId("lease-amend")).not.toBeInTheDocument();
        expect(screen.queryByTestId("lease-extend")).not.toBeInTheDocument();
    });
});

describe("Settle link", () => {
    it("is offered on RENEWED, which SettlementService.SETTLEABLE now admits", async () => {
        onLease("RENEWED");
        renderPage();
        expect(await screen.findByTestId("lease-settle")).toBeInTheDocument();
    });

    it("is still offered on CLOSED, for the finalised statement it is read for", async () => {
        onLease("CLOSED");
        renderPage();
        expect(await screen.findByTestId("lease-settle")).toBeInTheDocument();
    });

    it("is not offered while the contract is still running", async () => {
        renderPage();
        await screen.findByTestId("lease-actions");
        expect(screen.queryByTestId("lease-settle")).not.toBeInTheDocument();
    });
});

describe("Give notice — particulars (#27)", () => {
    it("records a landlord's notice with its date and move-out", async () => {
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-give-notice"));
        fireEvent.change(screen.getByLabelText(en.Leasing.noticeGivenBy), { target: { value: "LANDLORD" } });
        fireEvent.change(screen.getByLabelText(en.Leasing.noticeDate), { target: { value: "2026-09-01" } });
        fireEvent.change(screen.getByLabelText(en.Leasing.intendedMoveOut), { target: { value: "2027-09-01" } });
        expect(screen.getByText(en.Leasing.landlordNoticeHint)).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("lease-give-notice-confirm"));

        await waitFor(() => expect(api.notice).toHaveBeenCalledWith("lease-1", {
            givenBy: "LANDLORD", noticeDate: "2026-09-01", intendedMoveOutDate: "2027-09-01", notes: null,
        }));
    });

    // Web review M8: a refused notice keeps the dialog, and what was typed, open.
    it("keeps the dialog and its input open when the server refuses the notice", async () => {
        api.notice.mockRejectedValue(new ApiError(400, "Notice date is before the lease start"));
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-give-notice"));
        fireEvent.change(screen.getByLabelText(en.Leasing.noticeDate), { target: { value: "2026-09-01" } });
        fireEvent.change(screen.getByLabelText(en.Leasing.noticeNotes), { target: { value: "Called on Monday" } });
        fireEvent.click(screen.getByTestId("lease-give-notice-confirm"));

        expect(await screen.findByTestId("give-notice-error")).toHaveTextContent("Notice date is before the lease start");
        expect(screen.getByTestId("lease-give-notice-confirm")).toBeInTheDocument();
        expect(screen.getByLabelText(en.Leasing.noticeDate)).toHaveValue("2026-09-01");
        expect(screen.getByLabelText(en.Leasing.noticeNotes)).toHaveValue("Called on Monday");
    });

    it("will not take a move-out before the notice date", async () => {
        renderPage();
        fireEvent.click(await screen.findByTestId("lease-give-notice"));
        fireEvent.change(screen.getByLabelText(en.Leasing.noticeDate), { target: { value: "2026-09-01" } });
        fireEvent.change(screen.getByLabelText(en.Leasing.intendedMoveOut), { target: { value: "2026-08-01" } });

        expect(screen.getByTestId("lease-give-notice-confirm")).toBeDisabled();
    });

    it("shows who gave notice, when, and the move-out on the lease", async () => {
        api.get.mockImplementation(async () => ({
            ...lease("NOTICE_GIVEN"), noticeDate: "2026-09-01", noticeGivenBy: "LANDLORD", intendedMoveOutDate: "2027-09-01",
        }));
        renderPage();

        const summary = await screen.findByTestId("lease-notice-summary");
        expect(summary).toHaveTextContent("Notice given by Landlord on 01/09/2026");
        expect(summary).toHaveTextContent("move-out 01/09/2027");
    });
});
