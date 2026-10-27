import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";
import type { LeaseDetail, SettlementResponse, SettlementStatement } from "@/lib/api/leasing";

/**
 * #102: a FINALIZED move-out statement with a balance due used to show only
 * "Balance due from renter". It now carries the same written-off / still-due
 * breakdown as the lease's Settlement Summary card.
 *
 * Review P2-1: "still due" is the server's live ledger figure (`stillDue`), never
 * balance due minus write-offs — a write-off made before the move-out is already
 * out of the balance due, and a receipt after it is already in the ledger.
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

const writeOff = (amount: number, status: string, writeOffDate = "2026-08-01") =>
    ({ id: `w-${amount}-${status}`, leaseId: "lease-1", amount, status, writeOffDate });

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <SettlementPage />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    role = "ACCOUNTANT";
    api.lease.mockResolvedValue(LEASE);
    api.statement.mockResolvedValue({} as SettlementStatement);
    api.get.mockResolvedValue(finalStored({ stillDue: 6000 }));
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
    api.badDebts.mockResolvedValue([writeOff(4000, "WRITTEN_OFF"), writeOff(999, "REVERSED"), writeOff(500, "PROPOSED")]);
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Finalized statement write-off breakdown (#102)", () => {
    it("shows written off since the move-out and the ledger's still due under the balance due", async () => {
        renderPage();
        expect(await screen.findByTestId("settlement-balance-due")).toHaveTextContent("10,000.00");
        expect(await screen.findByTestId("settlement-written-off")).toHaveTextContent("4,000.00");
        expect(screen.getByTestId("settlement-still-due")).toHaveTextContent("6,000.00");
        expect(screen.getByText("Written off since the move-out")).toBeInTheDocument();
        expect(screen.getByText("Still due from tenant")).toBeInTheDocument();
        expect(screen.queryByTestId("settlement-written-off-total")).toBeNull();
        expect(api.badDebts).toHaveBeenCalledWith("lease-1");
    });

    it("never subtracts a mid-tenancy write-off again, and shows the ledger after a later receipt", async () => {
        // 3,000 written off in March (already out of the 10,000 balance due), then 2,500
        // received after the move-out: the ledger says 7,500 is still due — not 7,000 and not 4,500.
        api.badDebts.mockResolvedValue([writeOff(3000, "WRITTEN_OFF", "2026-03-10")]);
        api.get.mockResolvedValue(finalStored({ stillDue: 7500 }));
        renderPage();
        expect(await screen.findByTestId("settlement-written-off-total")).toHaveTextContent("3,000.00");
        expect(screen.getByText("Written off over the tenancy (total)")).toBeInTheDocument();
        expect(screen.queryByTestId("settlement-written-off")).toBeNull();
        expect(screen.getByTestId("settlement-still-due")).toHaveTextContent("7,500.00");
    });

    it("shows no breakdown when nothing is written off and nothing has moved", async () => {
        api.badDebts.mockResolvedValue([writeOff(500, "PROPOSED")]);
        api.get.mockResolvedValue(finalStored({ stillDue: 10000 }));
        renderPage();
        await screen.findByTestId("settlement-balance-due");
        await waitFor(() => expect(api.badDebts).toHaveBeenCalled());
        expect(screen.queryByTestId("settlement-write-off-breakdown")).toBeNull();
    });

    it("does not ask for write-offs on a refund", async () => {
        api.get.mockResolvedValue(finalStored({ balanceDue: 0, refundAmount: 2500 }));
        renderPage();
        await screen.findByTestId("settlement-net-refund");
        expect(api.badDebts).not.toHaveBeenCalled();
        expect(screen.queryByTestId("settlement-write-off-breakdown")).toBeNull();
    });

    it("does not read write-offs for a role without finance access, but shows the ledger's still due", async () => {
        role = "PROPERTY_MANAGER";
        renderPage();
        await screen.findByTestId("settlement-balance-due");
        expect(api.badDebts).not.toHaveBeenCalled();
        expect(screen.queryByTestId("settlement-written-off")).toBeNull();
        expect(screen.getByTestId("settlement-still-due")).toHaveTextContent("6,000.00");
    });

    it("keeps the balance due when the write-off read fails", async () => {
        api.badDebts.mockRejectedValue(new Error("boom"));
        api.get.mockResolvedValue(finalStored({ stillDue: 10000 }));
        renderPage();
        expect(await screen.findByTestId("settlement-balance-due")).toHaveTextContent("10,000.00");
        await waitFor(() => expect(api.badDebts).toHaveBeenCalled());
        expect(screen.queryByTestId("settlement-write-off-breakdown")).toBeNull();
    });
});
