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
// `data` is null until NextAuth has resolved the session, which is the state
// the page has to survive without fetching — see "waits for the session".
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: role ? { user: { role } } : null }) }));
vi.mock("@/components/finance/AccountPicker", () => ({
    default: ({ value, onChange }: { value: string | null; onChange: (id: string) => void }) => (
        <button type="button" data-testid="account-picker" data-value={value ?? ""} onClick={() => onChange("acc-9")}>
            pick
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

    it("needs no refund bank to finalize a settlement that refunds (F14-36)", async () => {
        renderPage();
        // Finalize waits on the settlement date landing, not on any refund
        // bank — F14-36 removed that field and requirement entirely.
        await waitFor(() => expect(screen.getByTestId("settlement-date")).not.toHaveValue(""));
        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());
        expect(screen.queryByTestId("refund-bank-picker")).not.toBeInTheDocument();
        expect(screen.queryByText("A settlement that refunds needs a bank or cash account to pay from.")).not.toBeInTheDocument();
    });

    it("waits for the session before it loads, so a second load cannot discard an edit", async () => {
        // Found by the plan 3 walkthrough. The page used to fetch on mount with
        // no role yet, and then fetch AGAIN when NextAuth resolved — and the
        // second load resets `rows`, `notes` and `refundBankAccountId` from the
        // stored settlement. By then the Save/Finalize controls are on screen
        // (they need `canSettle`, which needs the role), so an accountant can
        // have typed a deduction into a grid that an in-flight reload is about
        // to empty. One load, once the role is known.
        role = "";
        const { rerender } = renderPage();
        await new Promise(resolve => setTimeout(resolve, 0));
        expect(api.statement, "nothing is fetched before the session resolves").not.toHaveBeenCalled();
        expect(api.lease).not.toHaveBeenCalled();

        role = "ACCOUNTANT";
        rerender(
            <NextIntlClientProvider locale="en" messages={en}>
                <SettlementPage />
            </NextIntlClientProvider>,
        );

        await waitFor(() => expect(screen.getByTestId("settlement-earned-rent")).toBeInTheDocument());
        expect(api.statement, "and exactly once afterwards").toHaveBeenCalledTimes(1);
    });

    it("will not finalize figures that are not the ones on screen", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByTestId("settlement-date")).not.toHaveValue(""));
        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());

        // Finalise posts the STORED lines. An unsaved edit means the net refund
        // on screen is not the one the STL would carry.
        fireEvent.click(screen.getByTestId("settlement-add-deduction"));

        expect(screen.getByTestId("settlement-unsaved")).toBeInTheDocument();
        expect(screen.getByTestId("settlement-finalize")).toBeDisabled();
    });

    it("finalizes fine when the renter owes money instead", async () => {
        api.statement.mockResolvedValue(statement({ depositsHeld: 0, receivableBalance: 5000, netRefund: -5000 }));
        renderPage();

        expect(await screen.findByTestId("settlement-balance-due")).toHaveTextContent("5,000.00");
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

        const ack = await screen.findByTestId("settlement-acknowledge");
        expect(ack).not.toBeChecked();
        expect(screen.getByTestId("settlement-finalize")).toBeDisabled();
        expect(screen.getByTestId("settlement-outstanding-c5")).toHaveTextContent("12,750.00");

        fireEvent.click(ack);
        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());

        fireEvent.click(screen.getByTestId("settlement-finalize"));
        fireEvent.click(await screen.findByTestId("settlement-finalize-confirm"));
        await waitFor(() =>
            expect(api.finalize).toHaveBeenCalledWith("lease-1", expect.objectContaining({
                acknowledgeOutstanding: true,
            })),
        );
        // F14-36: finalize never sends a refund bank at all.
        expect(api.finalize.mock.calls.at(-1)![1]).not.toHaveProperty("refundBankAccountId");
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

    it("gives every line-grid control an accessible name", async () => {
        renderPage();
        fireEvent.click(await screen.findByTestId("settlement-add-deduction"));

        // A screen reader reaching these gets "Category"/"Description"/"Amount",
        // not three unlabelled controls in a row of a table.
        expect(screen.getByTestId("settlement-category-0")).toHaveAccessibleName("Category");
        expect(screen.getByTestId("settlement-description-0")).toHaveAccessibleName("Description");
        expect(screen.getByTestId("settlement-amount-0")).toHaveAccessibleName("Amount");
        for (const header of screen.getAllByRole("columnheader")) {
            expect(header).toHaveAttribute("scope", "col");
        }
    });

    it("tells a screen reader why Finalize is disabled", async () => {
        renderPage();
        await waitFor(() => expect(screen.getByTestId("settlement-date")).not.toHaveValue(""));
        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());

        fireEvent.click(screen.getByTestId("settlement-add-deduction"));
        expect(screen.getByTestId("settlement-finalize")).toBeDisabled();
        expect(screen.getByTestId("settlement-finalize")).toHaveAccessibleDescription(
            /save the draft/i,
        );
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

    it("counts a single unrecognised period in the singular", async () => {
        // The commonest shape by far: a termination truncates the month it
        // falls in, so exactly one period is left waiting for the close.
        api.statement.mockResolvedValue(statement({ unrecognisedEntries: 1 }));
        renderPage();

        expect(await screen.findByTestId("settlement-unrecognised")).toHaveTextContent(
            "1 period of rent has not been recognised",
        );
    });

    it("refuses to settle a contract that is still running, and points at termination", async () => {
        api.lease.mockResolvedValue({ ...LEASE, status: "ACTIVE", terminatedOn: null });
        renderPage();

        // The label, not the Java enum: in Arabic this sentence read
        // "هذا العقد ACTIVE." with the only Latin token in the banner.
        expect(await screen.findByTestId("settlement-not-settleable")).toHaveTextContent("this one is Active");
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

        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());
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

        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());
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

    it("names the back arrow, which was icon-only", async () => {
        renderPage();
        expect(await screen.findByTestId("settlement-back")).toHaveAccessibleName("Back to contract");
    });

    it("translates the outstanding instruments' mode and status", async () => {
        api.statement.mockResolvedValue(
            statement({
                instrumentsOutstanding: 12750,
                outstandingInstruments: [{
                    id: "c5", seqNo: 5, mode: "PDC", chequeNumber: null,
                    chequeDate: "2026-08-01", amount: 12750, status: "DEPOSITED", penaltyCollection: false,
                }],
            }),
        );
        renderPage();
        const row = await screen.findByTestId("settlement-outstanding-c5");
        expect(row).toHaveTextContent("Post-Dated Cheque");
        expect(row).toHaveTextContent("Deposited");
        expect(row).not.toHaveTextContent("DEPOSITED");
    });
});

/**
 * `SettlementService.SETTLEABLE` (:144-145) is now {TERMINATED, EXPIRED,
 * RENEWED}: a predecessor that settles instead of carrying its deposit forward
 * is settled like any other (spec §6.6), and CLOSED was withdrawn — closure
 * already requires a FINALIZED settlement, so a CLOSED lease is a statement to
 * read, never one to finalise.
 */
describe("Which contracts may be settled", () => {
    it("settles a RENEWED predecessor", async () => {
        api.lease.mockResolvedValue({ ...LEASE, status: "RENEWED" });
        renderPage();
        await screen.findByTestId("settlement-earned-rent");
        expect(screen.queryByTestId("settlement-not-settleable")).toBeNull();
        expect(screen.getByTestId("settlement-finalize")).toBeInTheDocument();
    });

    it("reads a CLOSED contract's statement without offering to finalise or terminate it", async () => {
        api.lease.mockResolvedValue({ ...LEASE, status: "CLOSED" });
        renderPage();
        await screen.findByTestId("settlement-earned-rent");
        expect(screen.queryByTestId("settlement-finalize")).toBeNull();
        // Gap #51: a CLOSED contract has nothing left to terminate.
        expect(screen.queryByTestId("settlement-not-settleable")).toBeNull();
        expect(screen.queryByTestId("settlement-terminate-link")).toBeNull();
    });

    it("drops the terminate-first prompt once the settlement is finalized (#51)", async () => {
        api.lease.mockResolvedValue({ ...LEASE, status: "ACTIVE" });
        api.get.mockResolvedValue(
            stored({
                status: "FINALIZED", settledAt: "2026-04-05T09:00:00Z", settledByName: "System Admin",
                settlementDate: "2026-04-05", journalId: "j9", journalNumber: "STL-26/1",
            }),
        );
        renderPage();
        expect(await screen.findByTestId("settlement-read-only")).toBeInTheDocument();
        expect(screen.queryByTestId("settlement-not-settleable")).toBeNull();
        expect(screen.queryByTestId("settlement-terminate-link")).toBeNull();
    });
});

/**
 * M-6 / M-7 — the two edges of `instrumentsOutstanding`.
 */
describe("The instruments-outstanding tile", () => {
    it("is hidden after FINALIZED rather than drifting away from the document", async () => {
        api.statement.mockResolvedValue(statement({ instrumentsOutstanding: 12750 }));
        api.get.mockResolvedValue(
            stored({
                status: "FINALIZED", settledAt: "2026-07-05T09:00:00Z", settledByName: "Aisha",
                settlementDate: "2026-07-05", journalId: "j9", journalNumber: "STL/2026/0004",
            }),
        );
        renderPage();

        await screen.findByTestId("settlement-read-only");
        // Every other figure switched to the stored snapshot; this one had
        // nowhere to switch to, so it kept reading the live statement and a
        // cheque clearing afterwards silently changed a frozen document.
        expect(screen.queryByTestId("settlement-instruments")).toBeNull();
        expect(screen.getByTestId("settlement-instruments-frozen")).toBeInTheDocument();
    });

    it("is shown as of today while the settlement is still a draft", async () => {
        api.statement.mockResolvedValue(statement({ instrumentsOutstanding: 12750 }));
        renderPage();
        expect(await screen.findByTestId("settlement-instruments")).toHaveTextContent("12,750.00");
        expect(screen.queryByTestId("settlement-instruments-frozen")).toBeNull();
    });
});

describe("An acknowledgement the screen could not know about", () => {
    it("reveals the checkbox with the server's own amount when finalise is refused for it", async () => {
        // The statement does not carry the field at all — an older backend, or
        // a shape the client must not read as "nothing outstanding".
        const withoutField = statement();
        delete withoutField.instrumentsOutstanding;
        api.statement.mockResolvedValue(withoutField);
        api.finalize.mockRejectedValue(
            new ApiError(400, "AED 12,750.00 is still outstanding on the cheque register; acknowledge it to refund the deposit anyway"),
        );
        renderPage();

        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());
        // Nothing to acknowledge as far as the screen knows.
        expect(screen.queryByTestId("settlement-acknowledge")).toBeNull();

        fireEvent.click(screen.getByTestId("settlement-finalize"));
        fireEvent.click(await screen.findByTestId("settlement-finalize-confirm"));

        // The refusal now comes with the control that satisfies it, carrying
        // the server's own sentence rather than a guessed figure.
        const ack = await screen.findByTestId("settlement-acknowledge");
        expect(screen.getByTestId("settlement-finalize-error")).toHaveTextContent("AED 12,750.00 is still outstanding");
        expect(screen.getByTestId("settlement-finalize")).toBeDisabled();

        fireEvent.click(ack);
        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());

        api.finalize.mockResolvedValue(
            stored({ status: "FINALIZED", settlementDate: "2026-07-05", journalNumber: "STL/2026/0004" }),
        );
        fireEvent.click(screen.getByTestId("settlement-finalize"));
        fireEvent.click(await screen.findByTestId("settlement-finalize-confirm"));
        await waitFor(() =>
            expect(api.finalize).toHaveBeenLastCalledWith("lease-1", expect.objectContaining({
                acknowledgeOutstanding: true,
            })),
        );
    });

    it("leaves an unrelated refusal alone — no checkbox for a locked period", async () => {
        api.finalize.mockRejectedValue(new ApiError(400, "Books are locked through 2026-07-31."));
        renderPage();

        await waitFor(() => expect(screen.getByTestId("settlement-finalize")).toBeEnabled());
        fireEvent.click(screen.getByTestId("settlement-finalize"));
        fireEvent.click(await screen.findByTestId("settlement-finalize-confirm"));

        await screen.findByTestId("settlement-finalize-error");
        expect(screen.queryByTestId("settlement-acknowledge")).toBeNull();
    });
});

describe("VAT on recharges (F14-37 / F14-61)", () => {
    const lines = (vat: number) => [
        { id: "d1", category: "PROPERTY_DAMAGE", description: "Wall", amount: 400, autoCalculated: false,
          type: "DEDUCTION", additionCategory: null, accountId: "acc-1", accountName: "Damage recovery",
          attachments: [], vatAmount: vat, grossAmount: 400 + vat },
        { id: "d2", category: "UTILITY_ARREARS", description: "DEWA", amount: 200, autoCalculated: false,
          type: "DEDUCTION", additionCategory: null, accountId: "acc-2", accountName: "Utilities",
          attachments: [], vatAmount: 0, grossAmount: 200 },
    ];

    it("prices a draft's VAT-able line with the statement's rule, and the refund nets it off", async () => {
        api.statement.mockResolvedValue(statement({
            vatRate: 0.05, vatableCategories: ["PROPERTY_DAMAGE", "CLEANING", "KEY_REPLACEMENT"],
        }));
        // The stored draft carries no VAT: the screen must not depend on it.
        api.get.mockResolvedValue(stored({ totalDeductions: 600, deductions: lines(0) as never }));
        renderPage();

        expect(await screen.findByTestId("settlement-line-vat-0")).toHaveTextContent("+ VAT 20.00 = 420.00");
        expect(screen.queryByTestId("settlement-line-vat-1")).not.toBeInTheDocument();
        expect(screen.getByTestId("settlement-total-deductions")).toHaveTextContent("- 600.00");
        expect(screen.getByTestId("settlement-total-deduction-vat")).toHaveTextContent("- 20.00");
        expect(screen.getByTestId("settlement-total-deductions-gross")).toHaveTextContent("- 620.00");
        // 10,000 held − (−164.38) receivable − 600 − 20 VAT
        expect(screen.getByTestId("settlement-net-refund")).toHaveTextContent("9,544.38");
    });

    it("prices a half-fil line as the server books it: 80.30 carries 4.02 (R1 P2-1)", async () => {
        api.statement.mockResolvedValue(statement({
            vatRate: 0.05, vatableCategories: ["PROPERTY_DAMAGE", "CLEANING", "KEY_REPLACEMENT"],
        }));
        api.get.mockResolvedValue(stored({ totalDeductions: 80.3, deductions: [
            { id: "d1", category: "PROPERTY_DAMAGE", description: "Wall", amount: 80.3, autoCalculated: false,
              type: "DEDUCTION", additionCategory: null, accountId: "acc-1", accountName: "Damage recovery",
              attachments: [], vatAmount: 0 },
        ] as never }));
        renderPage();

        expect(await screen.findByTestId("settlement-line-vat-0")).toHaveTextContent("+ VAT 4.02 = 84.32");
        // 10,000 held − (−164.38) receivable − 80.30 − 4.02 VAT
        expect(screen.getByTestId("settlement-net-refund")).toHaveTextContent("10,080.06");
    });

    it("a finalized settlement's figures add up: net, VAT, gross and the booked refund", async () => {
        api.get.mockResolvedValue(stored({
            status: "FINALIZED", journalId: "j9", journalNumber: "STL-26/8", settlementDate: "2026-07-05",
            totalDeductions: 600, totalDeductionVat: 20, totalDeductionsGross: 620, refundAmount: 9544.38,
            deductions: lines(20) as never,
        }));
        renderPage();

        expect(await screen.findByTestId("settlement-line-vat-0")).toHaveTextContent("+ VAT 20.00 = 420.00");
        expect(screen.getByTestId("settlement-total-deduction-vat")).toHaveTextContent("- 20.00");
        expect(screen.getByTestId("settlement-total-deductions-gross")).toHaveTextContent("- 620.00");
        expect(screen.getByTestId("settlement-net-refund")).toHaveTextContent("9,544.38");
    });

    it("shows neither row when no deduction carries VAT", async () => {
        renderPage();
        await screen.findByTestId("settlement-total-deductions");
        expect(screen.queryByTestId("settlement-total-deduction-vat")).not.toBeInTheDocument();
        expect(screen.queryByTestId("settlement-total-deductions-gross")).not.toBeInTheDocument();
    });
});

describe("Paying a deposit refund out (F14-36)", () => {
    it("shows refund owed/paid/outstanding once finalized, and a Pay refund link when something is still owed", async () => {
        api.get.mockResolvedValue(stored({
            status: "FINALIZED", journalId: "j9", journalNumber: "STL/2026/0004", settlementDate: "2026-07-05",
            refundAmount: 10164.38, refundPaid: 4000, refundOutstanding: 6164.38,
        }));
        renderPage();

        expect(await screen.findByTestId("settlement-refund-owed")).toHaveTextContent("10,164.38");
        expect(screen.getByTestId("settlement-refund-paid")).toHaveTextContent("4,000.00");
        expect(screen.getByTestId("settlement-refund-outstanding")).toHaveTextContent("6,164.38");
        const link = screen.getByTestId("settlement-pay-refund");
        expect(link).toHaveAttribute("href", expect.stringContaining("/dashboard/finance/vouchers/payment"));
        expect(link).toHaveAttribute("href", expect.stringContaining("settlementId=s1"));
        expect(link).toHaveAttribute("href", expect.stringContaining("amount=6164.38"));
    });

    it("hides Pay refund once the outstanding amount reaches zero", async () => {
        api.get.mockResolvedValue(stored({
            status: "FINALIZED", journalId: "j9", journalNumber: "STL/2026/0004", settlementDate: "2026-07-05",
            refundAmount: 10164.38, refundPaid: 10164.38, refundOutstanding: 0,
        }));
        renderPage();

        expect(await screen.findByTestId("settlement-refund-outstanding")).toHaveTextContent("0.00");
        expect(screen.queryByTestId("settlement-pay-refund")).not.toBeInTheDocument();
    });

    it("shows no refund-status block at all when nothing was ever owed", async () => {
        api.get.mockResolvedValue(stored({
            status: "FINALIZED", journalId: "j9", journalNumber: "STL/2026/0004", settlementDate: "2026-07-05",
            refundAmount: 0,
        }));
        renderPage();

        await screen.findByTestId("settlement-journal");
        expect(screen.queryByTestId("settlement-refund-status")).not.toBeInTheDocument();
    });
});
