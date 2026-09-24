import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail } from "@/lib/api/leasing";

/**
 * The draft grid's Save button and `ChequeRowRules.validateGrid` have to agree.
 *
 * A PDC row with no `chequeDate` is refused by the server with "a post-dated
 * cheque needs the date written on it"; before this gate the button was live
 * and the accountant read that refusal as a raw 400 after typing the whole
 * grid. The button is now dead for exactly the rows the server refuses, and
 * the grid says which row and why.
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
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/leases/LeaseInteractionsPanel", () => ({ default: () => null }));

const DRAFT: LeaseDetail = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "DRAFT",
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

function draftCheque(over: Partial<Cheque>): Cheque {
    return {
        id: "c1", leaseId: "lease-1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
        seqNo: 1, postingDate: "2026-01-01", chequeNumber: "000101", chequeDate: "2026-01-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "Bank",
        amount: 63000, narration: null, mode: "PDC", status: "DRAFT",
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
        ...over,
    };
}

const api = vi.hoisted(() => ({ get: vi.fn(), cheques: vi.fn(), saveCheques: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        penaltyApi: { ...m.penaltyApi, list: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })) },
        leaseApi: { ...m.leaseApi, get: api.get, cheques: api.cheques, saveCheques: api.saveCheques },
    };
});

import LeaseDetailPage from "../page";

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseDetailPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    api.get.mockImplementation(async () => DRAFT);
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Save cheque grid gate on a draft contract", () => {
    it("saves a complete grid", async () => {
        api.cheques.mockImplementation(async () => [draftCheque({})]);
        renderPage();
        await waitFor(() => expect(screen.getByTestId("lease-save-cheques")).toBeEnabled());
        expect(screen.queryByTestId("cheque-grid-row-errors")).not.toBeInTheDocument();
    });

    it("refuses to save a dateless PDC row, and says which row and why", async () => {
        api.cheques.mockImplementation(async () => [draftCheque({ chequeDate: null })]);
        renderPage();
        await waitFor(() => expect(screen.getByTestId("lease-save-cheques")).toBeDisabled());
        expect(screen.getByTestId("cheque-grid-row-errors")).toHaveTextContent(
            "Row 1: a post-dated cheque needs the date written on it",
        );
    });

    it("refuses to save a row with no amount", async () => {
        api.cheques.mockImplementation(async () => [draftCheque({ amount: 0 })]);
        renderPage();
        await waitFor(() => expect(screen.getByTestId("lease-save-cheques")).toBeDisabled());
        expect(screen.getByTestId("cheque-grid-row-errors")).toHaveTextContent(
            "Row 1: amount must be greater than zero",
        );
    });
});
