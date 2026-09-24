import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { LeaseDetail } from "@/lib/api/leasing";

// #79: the staff lease page tells the accountant the renter has accepted a
// PENDING_SIGNATURE contract — before this, nothing on the page said so.

vi.mock("next/navigation", () => ({
    useParams: () => ({ id: "lease-1" }),
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div /> }));
vi.mock("@/components/leases/LeaseInteractionsPanel", () => ({ default: () => null }));

const LEASE: LeaseDetail = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "A-203", renterName: "Ahmed",
    startDate: "2026-10-01", endDate: "2027-09-30", status: "PENDING_SIGNATURE",
    rentAmount: null, depositAmount: null, ejariNumber: null, paymentTerms: 4,
    installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
    paymentReferenceNumber: null, propertyId: "p1", propertyName: "Tower", propertyCode: "TWR",
    hasContract: true, contractNumber: null, displayContractNumber: null,
    agreementDate: null, rentVatApplicable: false, contractDate: "2026-09-20", totalDays: 365,
    gracePeriodDays: 5, firstDueDate: "2026-10-01", renterAcceptedAt: null,
    renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
    postingJournalId: null, postedAt: null, contractValue: 60000,
    terminatedOn: null, terminationJournalId: null, terminationNotes: null,
    lines: [],
};

const api = vi.hoisted(() => ({ get: vi.fn(), cheques: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        penaltyApi: { ...m.penaltyApi, list: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })) },
        leaseApi: { ...m.leaseApi, get: api.get, cheques: api.cheques },
    };
});

import LeaseDetailPage from "../page";

beforeEach(() => {
    api.cheques.mockImplementation(async () => []);
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});
afterEach(cleanup);

const renderPage = () => render(
    <NextIntlClientProvider locale="en" messages={en}>
        <LeaseDetailPage />
    </NextIntlClientProvider>,
);

describe("Lease page — renter acceptance badge", () => {
    it("shows 'Accepted by renter <date>' on an accepted PENDING_SIGNATURE lease", async () => {
        api.get.mockImplementation(async () => ({ ...LEASE, renterAcceptedAt: "2026-09-20T08:00:00Z" }));
        renderPage();
        expect((await screen.findByTestId("lease-renter-accepted")).textContent)
            .toBe("Accepted by renter 20/09/2026");
    });

    it("shows nothing before the renter has accepted", async () => {
        api.get.mockImplementation(async () => LEASE);
        renderPage();
        await screen.findByTestId("lease-status");
        expect(screen.queryByTestId("lease-renter-accepted")).toBeNull();
    });
});
