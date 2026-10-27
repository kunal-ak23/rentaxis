import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";
import type { LeaseDetail, SettlementResponse, SettlementStatement } from "@/lib/api/leasing";

/**
 * #100-d: the lease left behind by a unit transfer shows deposits held = 0. The
 * statement now says where the deposit went ("Deposit carried to <unit>", linked
 * to the new lease) as an info line that is not refundable here.
 */

let role = "ACCOUNTANT";

vi.mock("next/navigation", () => ({ useParams: () => ({ id: "lease-1", locale: "en" }) }));
vi.mock("@/i18n/routing", () => ({
    useRouter: () => ({ push: vi.fn() }),
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role } } }) }));
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => null }));

const api = vi.hoisted(() => ({
    lease: vi.fn(), statement: vi.fn(), get: vi.fn(), fiscal: vi.fn(), badDebts: vi.fn(),
}));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: { ...m.leaseApi, get: api.lease },
        settlementApi: { ...m.settlementApi, statement: api.statement, get: api.get },
    };
});
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscal: { ...m.ledgerApi.fiscal, get: api.fiscal } } };
});
vi.mock("@/lib/api/badDebts", async orig => {
    const m = await orig<typeof import("@/lib/api/badDebts")>();
    return { ...m, badDebtsApi: { ...m.badDebtsApi, forLease: api.badDebts } };
});

import SettlementPage from "../page";

const LEASE = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "204", renterName: "Sample Renter One",
    startDate: "2026-01-01", endDate: "2026-12-31", status: "TERMINATED", propertyId: "p1",
    rentAmount: 120000, depositAmount: 10000, terminatedOn: "2026-06-30", lines: [],
} as unknown as LeaseDetail;

function finalStored(over: Partial<SettlementResponse> = {}): SettlementResponse {
    return {
        id: "s1", leaseId: "lease-1", depositAmount: 10000,
        totalDeductions: 0, totalAdditions: 0, refundAmount: 0,
        notes: null, status: "FINALIZED", settledBy: null, settledByName: "Aisha",
        settledAt: "2026-07-05T09:00:00Z", createdAt: "2026-07-01T00:00:00Z", settlementDate: "2026-07-05",
        earnedRent: 70000, receivedTotal: 60000, receivableBalance: 10000,
        depositsHeld: 0, penaltiesOutstanding: 0, balanceDue: 10000, refundBankAccountId: null,
        journalId: "j9", journalNumber: "STL/2026/0004", collectionChequeId: null,
        deductions: [],
        ...over,
    } as SettlementResponse;
}

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <SettlementPage />
        </NextIntlClientProvider>,
    );
}

function statement(depositCarriedTo: SettlementStatement["depositCarriedTo"]): SettlementStatement {
    return {
        asOf: "2026-07-05", earnedRent: 70000, receivedTotal: 60000, receivableBalance: 10000,
        depositsHeld: 0, penaltiesOutstanding: 0, deductions: [], additions: [],
        totalDeductions: 0, totalAdditions: 0, netRefund: -10000, unrecognisedEntries: 0,
        depositCarriedTo,
    } as SettlementStatement;
}

beforeEach(() => {
    role = "ACCOUNTANT";
    api.lease.mockResolvedValue(LEASE);
    api.get.mockResolvedValue(finalStored());
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
    api.badDebts.mockResolvedValue([]);
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Deposit carried to the new lease (#100-d)", () => {
    it("names the new unit, links the new lease and shows the amount", async () => {
        api.statement.mockResolvedValue(statement({ leaseId: "lease-2", unitIdentifier: "B-310", amount: 3000 }));
        renderPage();
        const line = await screen.findByTestId("settlement-deposit-carried");
        expect(line).toHaveTextContent("Deposit carried to B-310");
        expect(line).toHaveTextContent("not refundable");
        expect(screen.getByTestId("settlement-deposit-carried-link").getAttribute("href")).toBe("/dashboard/leases/lease-2");
        expect(screen.getByTestId("settlement-deposit-carried-amount")).toHaveTextContent("3,000.00");
        // Display only: the stored refund figures are untouched.
        expect(screen.getByTestId("settlement-deposits-held")).toHaveTextContent("0.00");
    });

    it("explains the zero without a link when the new lease is not readable", async () => {
        api.statement.mockResolvedValue(statement({ leaseId: null, unitIdentifier: null, amount: 3000 }));
        renderPage();
        const line = await screen.findByTestId("settlement-deposit-carried");
        expect(line).toHaveTextContent("Deposit carried to the new contract");
        expect(screen.queryByTestId("settlement-deposit-carried-link")).toBeNull();
    });

    it("shows nothing when no deposit was carried", async () => {
        api.statement.mockResolvedValue(statement(null));
        renderPage();
        await screen.findByTestId("settlement-deposits-held");
        await waitFor(() => expect(api.statement).toHaveBeenCalled());
        expect(screen.queryByTestId("settlement-deposit-carried")).toBeNull();
    });
});
