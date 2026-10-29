import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { Cheque, LeaseDetail } from "@/lib/api/leasing";

/**
 * Review of #452, I2 (the contract page's half): the Post dialog prices and posts
 * the grid the server holds, so a merge — or any edit — still on screen is saved
 * when Post is pressed, before the dialog opens. A refused save keeps the dialog shut.
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

const DRAFT = {
    id: "lease-1", version: 6, unitId: "u1", renterId: "r1", unitIdentifier: "A-101", renterName: "Sample Renter One",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "DRAFT", paymentTerms: 1,
    installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
    propertyId: "p1", propertyName: "Sample Heights", propertyCode: "SMH", displayContractNumber: "TCO-26/15",
    rentVatApplicable: false, contractDate: "2026-01-01", totalDays: 365, gracePeriodDays: 5,
    firstDueDate: "2026-01-01", chainId: "chain-1", contractValue: 65000,
    lines: [],
} as unknown as LeaseDetail;

function draftCheque(over: Partial<Cheque>): Cheque {
    return {
        id: "c1", leaseId: "lease-1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "Sample Heights", unitIdentifier: "A-101", renterName: "Sample Renter One",
        seqNo: 1, postingDate: "2026-01-01", chequeNumber: "000101", chequeDate: "2026-01-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "Bank",
        amount: 60000, narration: "Rent - 1st Installment", mode: "PDC", status: "DRAFT", rowKind: "RENT", vatAmount: 0,
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
        ...over,
    };
}
const GRID = [
    draftCheque({}),
    draftCheque({ id: "c2", seqNo: 2, chequeNumber: "000102", amount: 5000, narration: "SD", rowKind: "DEPOSIT" }),
];

const api = vi.hoisted(() => ({
    get: vi.fn(), cheques: vi.fn(), saveCheques: vi.fn(), dryRunPost: vi.fn(), post: vi.fn(),
}));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        chargeTypeApi: { ...m.chargeTypeApi, list: vi.fn(async () => []) },
        penaltyApi: { ...m.penaltyApi, list: vi.fn(async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 25 })) },
        leaseApi: { ...m.leaseApi, ...api, lineAccounts: vi.fn(async () => []) },
    };
});

import LeaseDetailPage from "../page";

async function renderOnCheques() {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseDetailPage />
        </NextIntlClientProvider>,
    );
    fireEvent.click(await screen.findByTestId("lease-tab-payments"));
    await screen.findByTestId("cheque-select-1");
}

function mergeBoth() {
    fireEvent.click(screen.getByTestId("cheque-select-0"));
    fireEvent.click(screen.getByTestId("cheque-select-1"));
    fireEvent.click(screen.getByTestId("cheque-grid-merge"));
}

beforeEach(() => {
    api.get.mockImplementation(async () => DRAFT);
    api.cheques.mockImplementation(async () => GRID);
    api.dryRunPost.mockImplementation(async () => ({
        ok: true, errors: [], contractValue: 65000, contractValueInclVat: 65000, chequeTotal: 65000,
        depositCarriedForward: 0, journals: { tco: 1, tcoLines: 2, pdr: 1 },
    }));
    global.fetch = vi.fn(async () => ({ ok: true, status: 200, json: async () => [] })) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Post on a draft contract with grid edits on screen", () => {
    it("saves the merged grid first, then opens the dialog", async () => {
        api.saveCheques.mockImplementation(async () => ({
            cheques: [draftCheque({ amount: 65000, rowKind: "MIXED", narration: "Rent - 1st Installment | SD" })], version: 7,
        }));
        await renderOnCheques();
        mergeBoth();
        fireEvent.click(screen.getByTestId("lease-post"));
        await waitFor(() => expect(api.dryRunPost).toHaveBeenCalled());
        expect(api.saveCheques).toHaveBeenCalledTimes(1);
        const [, rows, version] = api.saveCheques.mock.calls[0];
        expect(rows).toEqual([expect.objectContaining({ id: "c1", amount: 65000, rowKind: "MIXED" })]);
        expect(version).toBe(6);
        expect(api.saveCheques.mock.invocationCallOrder[0]).toBeLessThan(api.dryRunPost.mock.invocationCallOrder[0]);
    });

    it("opens the dialog without a save when nothing is unsaved", async () => {
        await renderOnCheques();
        fireEvent.click(screen.getByTestId("lease-post"));
        await waitFor(() => expect(api.dryRunPost).toHaveBeenCalled());
        expect(api.saveCheques).not.toHaveBeenCalled();
    });

    // Review of #452 round 2: as Save cheques does, an amount the field refused is never saved.
    it("keeps the dialog shut and saves nothing while an amount on the grid is refused", async () => {
        await renderOnCheques();
        fireEvent.change(screen.getByLabelText("Amount 2"), { target: { value: "1,5" } });
        fireEvent.click(screen.getByTestId("lease-post"));
        await new Promise(r => setTimeout(r, 50));
        expect(api.saveCheques).not.toHaveBeenCalled();
        expect(api.dryRunPost).not.toHaveBeenCalled();
        expect(document.activeElement).toBe(screen.getByLabelText("Amount 2"));
    });

    it("keeps the dialog shut when that save is refused", async () => {
        api.saveCheques.mockRejectedValue(new Error("network"));
        await renderOnCheques();
        mergeBoth();
        fireEvent.click(screen.getByTestId("lease-post"));
        await waitFor(() => expect(api.saveCheques).toHaveBeenCalledTimes(1));
        expect(await screen.findByTestId("cheque-grid-error")).toBeInTheDocument();
        expect(api.dryRunPost).not.toHaveBeenCalled();
    });
});
