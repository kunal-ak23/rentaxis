import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";
import type { LeaseDetail, SettlementResponse, SettlementStatement } from "@/lib/api/leasing";

/**
 * The move-out statement (spec §9.2).
 *
 * Every gate here is a Java rule wearing a UI:
 *
 *  - a refund needs a bank account — `SettlementService.finalizeSettlement`
 *    :452-454 / `requireRefundBank` :800-804, and only when `netRefund > 0`;
 *  - a refund paid while the register still holds something needs the
 *    accountant to say so on purpose — `acknowledgeOutstanding`;
 *  - PENALTIES / UNPAID_RENT / PREPAID_RENT / UTILITY_OVERPAYMENT are refused
 *    as lines, so they are not in the selects at all (:737-761);
 *  - a lease that has not been terminated cannot be settled (:645-660);
 *  - **finalise does not close the contract.** `LeaseClosureService` closes it
 *    only when nothing is left on the register, so the screen reads the lease's
 *    real status back rather than promising closure.
 */

const push = vi.fn();
let role = "ACCOUNTANT";

vi.mock("next/navigation", () => ({ useParams: () => ({ id: "lease-1", locale: "en" }) }));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({
    default: ({ value, onChange }: { value: string | null; onChange: (id: string) => void }) => (
        <button type="button" data-testid="account-picker" data-value={value ?? ""} onClick={() => onChange("acc-9")}>
            pick
        </button>
    ),
}));
vi.mock("@/components/finance/SettlementAccountPicker", () => ({
    default: ({ value, onChange }: { value: string | null; onChange: (id: string) => void }) => (
        <button type="button" data-testid="refund-bank-picker" data-value={value ?? ""} onClick={() => onChange("bank-1")}>
            pick bank
        </button>
    ),
}));

const api = vi.hoisted(() => ({
    lease: vi.fn(),
    statement: vi.fn(),
    get: vi.fn(),
    saveDraft: vi.fn(),
    finalize: vi.fn(),
    fiscal: vi.fn(),
}));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: { ...m.leaseApi, get: api.lease },
        settlementApi: {
            ...m.settlementApi,
            statement: api.statement,
            get: api.get,
            saveDraft: api.saveDraft,
            finalize: api.finalize,
        },
    };
});
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscal: { ...m.ledgerApi.fiscal, get: api.fiscal } } };
});

import SettlementPage from "../page";
import { ApiError } from "@/lib/api/leasing";

const LEASE: LeaseDetail = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "204", renterName: "Prabhjot Singh",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "TERMINATED",
    rentAmount: 120000, depositAmount: 10000, ejariNumber: null, paymentTerms: 4,
    installmentDistribution: "UNIFORM", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
    paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
    hasContract: true, contractNumber: 15, displayContractNumber: "TCO-26/15",
    agreementDate: null, rentVatApplicable: false, contractDate: "2026-01-01", totalDays: 365,
    gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null,
    renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
    postingJournalId: "j0", postedAt: "2026-01-01T00:00:00Z", contractValue: 120000,
    terminatedOn: "2026-06-30", terminationJournalId: "j1", terminationNotes: null,
    lines: [],
};

function statement(over: Partial<SettlementStatement> = {}): SettlementStatement {
    return {
        asOf: "2026-07-05",
        earnedRent: 59835.62, receivedTotal: 60000, receivableBalance: -164.38,
        depositsHeld: 10000, penaltiesOutstanding: 0,
        instrumentsOutstanding: 0, outstandingInstruments: [],
        deductions: [], additions: [],
        totalDeductions: 0, totalAdditions: 0,
        netRefund: 10164.38, unrecognisedEntries: 0,
        ...over,
    };
}

function stored(over: Partial<SettlementResponse> = {}): SettlementResponse {
    return {
        id: "s1", leaseId: "lease-1", depositAmount: 10000,
        totalDeductions: 0, totalAdditions: 0, refundAmount: 10164.38,
        notes: null, status: "DRAFT", settledBy: null, settledByName: null, settledAt: null,
        createdAt: "2026-07-01T00:00:00Z", settlementDate: null,
        earnedRent: 59835.62, receivedTotal: 60000, receivableBalance: -164.38,
        depositsHeld: 10000, penaltiesOutstanding: 0, balanceDue: 0, refundBankAccountId: null,
        journalId: null, journalNumber: null, collectionChequeId: null,
        deductions: [],
        ...over,
    };
}

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <SettlementPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role = "ACCOUNTANT";
    push.mockClear();
    api.lease.mockResolvedValue(LEASE);
    api.statement.mockResolvedValue(statement());
    api.get.mockResolvedValue(stored());
    api.saveDraft.mockImplementation(async () => stored());
    api.finalize.mockResolvedValue(
        stored({ status: "FINALIZED", journalId: "j9", journalNumber: "STL/2026/0004", settlementDate: "2026-07-05" }),
    );
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Settlement statement", () => {
    it("shows the ledger figures the statement carries", async () => {
        renderPage();
        expect(await screen.findByTestId("settlement-earned-rent")).toHaveTextContent("59,835.62");
        expect(screen.getByTestId("settlement-deposits-held")).toHaveTextContent("10,000.00");
        expect(screen.getByTestId("settlement-receivable-balance")).toHaveTextContent("-164.38");
        expect(screen.getByTestId("settlement-net-refund")).toHaveTextContent("10,164.38");
    });

    it("keeps Finalize disabled until a refund bank is chosen when the settlement refunds", async () => {
        renderPage();
        // Wait for the settlement date to be filled in first: it is the OTHER
        // thing Finalize waits on, and asserting "disabled" before it lands
        // would pass whether or not the refund-bank rule exists at all.
        await waitFor(() => expect(screen.getByTestId("settlement-date")).not.toHaveValue(""));

        expect(screen.getByTestId("settlement-finalize")).toBeDisabled();
        expect(screen.getByText("A settlement that refunds needs a bank or cash account to pay from.")).toBeInTheDocument();

        fireEvent.click(screen.getByTestId("refund-bank-picker"));
        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());
    });

    it("will not finalize figures that are not the ones on screen", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByTestId("settlement-date")).not.toHaveValue(""));
        fireEvent.click(screen.getByTestId("refund-bank-picker"));
        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());

        // Finalise posts the STORED lines. An unsaved edit means the net refund
        // on screen is not the one the STL would carry.
        fireEvent.click(screen.getByTestId("settlement-add-deduction"));

        expect(screen.getByTestId("settlement-unsaved")).toBeInTheDocument();
        expect(screen.getByTestId("settlement-finalize")).toBeDisabled();
    });

    it("needs no refund bank when the renter owes money instead", async () => {
        api.statement.mockResolvedValue(statement({ depositsHeld: 0, receivableBalance: 5000, netRefund: -5000 }));
        renderPage();

        expect(await screen.findByTestId("settlement-balance-due")).toHaveTextContent("5,000.00");
        expect(screen.queryByTestId("refund-bank-picker")).toBeNull();
        // `waitFor`, not a bare assertion: the settlement date is filled in by
        // an effect that runs after the statement's own render, and Finalize
        // waits on it.
        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());
    });

    it("asks the accountant to acknowledge a refund paid over an outstanding register", async () => {
        api.statement.mockResolvedValue(
            statement({
                instrumentsOutstanding: 12750,
                outstandingInstruments: [{
                    id: "c5", seqNo: 5, mode: "PDC", chequeNumber: "000105",
                    chequeDate: "2026-08-01", amount: 12750, status: "REGISTERED", penaltyCollection: false,
                }],
            }),
        );
        renderPage();

        fireEvent.click(await screen.findByTestId("refund-bank-picker"));
        const ack = screen.getByTestId("settlement-acknowledge");
        expect(ack).not.toBeChecked();
        expect(screen.getByTestId("settlement-finalize")).toBeDisabled();
        expect(screen.getByTestId("settlement-outstanding-c5")).toHaveTextContent("12,750.00");

        fireEvent.click(ack);
        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());

        fireEvent.click(screen.getByTestId("settlement-finalize"));
        fireEvent.click(await screen.findByTestId("settlement-finalize-confirm"));
        await waitFor(() =>
            expect(api.finalize).toHaveBeenCalledWith("lease-1", expect.objectContaining({
                refundBankAccountId: "bank-1",
                acknowledgeOutstanding: true,
            })),
        );
    });

    it("never asks for an acknowledgement the server would ignore", async () => {
        api.statement.mockResolvedValue(
            statement({ depositsHeld: 0, receivableBalance: 5000, netRefund: -5000, instrumentsOutstanding: 12750 }),
        );
        renderPage();
        await screen.findByTestId("settlement-balance-due");
        // A balance-due settlement pays nothing out, so the rule does not apply.
        expect(screen.queryByTestId("settlement-acknowledge")).toBeNull();
    });

    it("never offers a category the server refuses as a line", async () => {
        renderPage();
        fireEvent.click(await screen.findByTestId("settlement-add-deduction"));
        const deduction = within(screen.getByTestId("settlement-category-0"));
        expect(deduction.queryByText("Penalties")).toBeNull();
        expect(deduction.queryByText("Unpaid rent")).toBeNull();
        expect(deduction.getByText("Cleaning")).toBeInTheDocument();

        fireEvent.click(screen.getByTestId("settlement-add-addition"));
        const addition = within(screen.getByTestId("settlement-category-1"));
        expect(addition.queryByText("Prepaid rent")).toBeNull();
        expect(addition.queryByText("Utility overpayment")).toBeNull();
        expect(addition.getByText("Deposit interest")).toBeInTheDocument();
    });

    it("tells the user to run the close first when rent is still unrecognised", async () => {
        api.statement.mockResolvedValue(statement({ unrecognisedEntries: 3 }));
        renderPage();

        const banner = await screen.findByTestId("settlement-unrecognised");
        expect(banner).toHaveTextContent("3 periods of rent have not been recognised");
        expect(within(banner).getByRole("link")).toHaveAttribute("href", "/dashboard/finance/recognition");
    });

    it("refuses to settle a contract that is still running, and points at termination", async () => {
        api.lease.mockResolvedValue({ ...LEASE, status: "ACTIVE", terminatedOn: null });
        renderPage();

        expect(await screen.findByTestId("settlement-not-settleable")).toHaveTextContent("this one is ACTIVE");
        expect(screen.queryByTestId("settlement-finalize")).toBeNull();
        expect(screen.getByTestId("settlement-terminate-link")).toHaveAttribute(
            "href",
            "/dashboard/leases/lease-1/terminate",
        );
    });

    it("keeps the settlement date on or after the termination date", async () => {
        renderPage();
        expect(await screen.findByTestId("settlement-date")).toHaveAttribute("min", "2026-06-30");
    });

    it("reports the STL and does not promise closure while cheques remain", async () => {
        api.lease
            .mockResolvedValueOnce(LEASE)
            // Refetched after finalise: LeaseClosureService left it TERMINATED
            // because the register still holds something.
            .mockResolvedValueOnce({ ...LEASE, status: "TERMINATED" });
        renderPage();

        fireEvent.click(await screen.findByTestId("refund-bank-picker"));
        fireEvent.click(screen.getByTestId("settlement-finalize"));
        fireEvent.click(await screen.findByTestId("settlement-finalize-confirm"));

        expect(await screen.findByTestId("settlement-journal")).toHaveTextContent("STL/2026/0004");
        expect(screen.getByTestId("settlement-closure")).toHaveTextContent(
            "The contract stays open until the remaining cheques clear.",
        );
    });

    it("says the contract closed when the refetch says so", async () => {
        api.lease.mockResolvedValueOnce(LEASE).mockResolvedValueOnce({ ...LEASE, status: "CLOSED" });
        renderPage();

        fireEvent.click(await screen.findByTestId("refund-bank-picker"));
        fireEvent.click(screen.getByTestId("settlement-finalize"));
        fireEvent.click(await screen.findByTestId("settlement-finalize-confirm"));

        expect(await screen.findByTestId("settlement-closure")).toHaveTextContent("The contract is now CLOSED.");
    });

    it("is read-only once finalized", async () => {
        api.get.mockResolvedValue(
            stored({
                status: "FINALIZED", settledAt: "2026-07-05T09:00:00Z", settledByName: "Aisha",
                settlementDate: "2026-07-05", journalId: "j9", journalNumber: "STL/2026/0004",
            }),
        );
        renderPage();

        expect(await screen.findByTestId("settlement-read-only")).toBeInTheDocument();
        expect(screen.queryByTestId("settlement-finalize")).toBeNull();
        expect(screen.queryByTestId("settlement-save-draft")).toBeNull();
        expect(screen.queryByTestId("settlement-add-deduction")).toBeNull();
    });

    it("lets a PROPERTY_MANAGER read the statement but never write it", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();

        await screen.findByTestId("settlement-earned-rent");
        expect(screen.getByTestId("settlement-read-only-role")).toBeInTheDocument();
        expect(screen.queryByTestId("settlement-finalize")).toBeNull();
        expect(screen.queryByTestId("settlement-save-draft")).toBeNull();
    });

    it("treats a 404 from the stored settlement as 'no draft yet', not as a failure", async () => {
        api.get.mockRejectedValue(new ApiError(404, "Not found"));
        renderPage();

        await screen.findByTestId("settlement-earned-rent");
        expect(screen.queryByRole("alert")).toBeNull();
        expect(screen.getByTestId("settlement-save-draft")).toBeInTheDocument();
    });

    it("surfaces a real failure on the statement instead of an empty screen", async () => {
        api.statement.mockRejectedValue(new ApiError(500, "boom"));
        renderPage();
        expect(await screen.findByRole("alert")).toHaveTextContent("boom");
    });
});
