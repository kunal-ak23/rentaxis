import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { LeaseDetail } from "@/lib/api/leasing";

/**
 * Review P2-1 (#102): the lease's Settlement Summary card used to show "still due" as
 * balance due minus every write-off. A write-off made mid-tenancy was already out of
 * the balance due, and a receipt after the move-out was ignored. The card now shows
 * the server's live ledger figure, and splits the write-offs at the settlement date.
 */

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
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/leases/LeaseInteractionsPanel", () => ({ default: () => null }));

let LEASE: LeaseDetail;
const BASE: LeaseDetail = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "ACTIVE",
    rentAmount: null, depositAmount: null, ejariNumber: null, paymentTerms: 4,
    installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
    paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
    hasContract: false, contractNumber: null, displayContractNumber: "TCO-26/15",
    agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
    gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null,
    renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
    postingJournalId: null, postedAt: null, contractValue: 60000,
    terminatedOn: null, terminationJournalId: null, terminationNotes: null,
    lines: [{
        id: "ln1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
        behaviour: "RENT", creditAccountId: "acc-1", creditAccountCode: "210100", creditAccountName: "Advance Rent",
        grossAmount: 60000, discountAmount: 0, netAmount: 60000, narration: null,
        vatApplicable: true, periodStart: null, periodEnd: null,
    }],
};


vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        penaltyApi: { ...m.penaltyApi, list: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })) },
        leaseApi: { ...m.leaseApi, get: vi.fn(async () => LEASE), cheques: vi.fn(async () => []), addenda: vi.fn(async () => []) },
        settlementApi: { ...m.settlementApi, get: vi.fn(async () => SETTLEMENT) },
    };
});
const badDebts = vi.hoisted(() => vi.fn());
vi.mock("@/lib/api/badDebts", async orig => {
    const m = await orig<typeof import("@/lib/api/badDebts")>();
    return { ...m, badDebtsApi: { ...m.badDebtsApi, forLease: badDebts } };
});

import LeaseDetailPage from "../page";

const SETTLEMENT = {
    id: "s1", leaseId: "lease-1", depositAmount: 10000, depositsHeld: 10000, totalDeductions: 0, totalAdditions: 0,
    refundAmount: 0, balanceDue: 10000, stillDue: 7500, status: "FINALIZED", settlementDate: "2026-07-05",
    journalId: "j9", journalNumber: "STL-26/4", deductions: [],
};

beforeEach(() => {
    LEASE = { ...BASE, status: "TERMINATED", postedAt: "2026-01-01T00:00:00Z", terminatedOn: "2026-06-30" };
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] }) as Response) as unknown as typeof fetch;
    badDebts.mockResolvedValue([
        { id: "w1", leaseId: "lease-1", amount: 3000, status: "WRITTEN_OFF", writeOffDate: "2026-03-10" },
        { id: "w2", leaseId: "lease-1", amount: 500, status: "WRITTEN_OFF", writeOffDate: "2026-08-01" },
    ]);
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("lease Settlement Summary card (review P2-1)", () => {
    it("shows the ledger's still due and splits write-offs at the settlement date", async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <LeaseDetailPage />
            </NextIntlClientProvider>,
        );
        const card = await screen.findByTestId("lease-settlement-summary");
        await waitFor(() => expect(within(card).getByText(en.Settlement.writtenOffSinceMoveOut)).toBeInTheDocument());
        const row = (label: string) => within(card).getByText(label).parentElement as HTMLElement;
        expect(row(en.Settlement.writtenOffSinceMoveOut)).toHaveTextContent("500");
        expect(row(en.Settlement.writtenOffTenancyTotal)).toHaveTextContent("3,500");
        // 10,000 − 3,500 would be 6,500; the ledger says 7,500.
        expect(row(en.Settlement.stillDue)).toHaveTextContent("7,500");
    });
});
