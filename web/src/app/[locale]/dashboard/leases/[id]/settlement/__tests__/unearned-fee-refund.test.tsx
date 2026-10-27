import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { ApiError } from "@/lib/api/facilities";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../../messages/en.json";
import type { LeaseDetail, SettlementResponse, SettlementStatement } from "@/lib/api/leasing";

/**
 * Follow-up ruling R1: a cut-over lease's periodic fee taken to income at posting is
 * offered for refund of its unearned days, off by default; ticking adds the refund
 * as an addition, unticking drops it.
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
import { groupFeeRefunds } from "@/components/leases/settlementMath";

const LEASE = {
    id: "lease-1", unitId: "u1", renterId: "r1", unitIdentifier: "204", renterName: "Prabhjot Singh",
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
    api.get.mockRejectedValue(new ApiError(404, "none"));
    api.fiscal.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
    api.badDebts.mockResolvedValue([]);
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Unearned fee refund offer (ruling R1)", () => {
    it("is off by default and adds the per-day refund as an addition when ticked", async () => {
        api.statement.mockResolvedValue({
            ...statement(null), depositsHeld: 10000, receivableBalance: 0, netRefund: 10000,
            unearnedFeeRefunds: [{ leaseLineId: "l2", chargeTypeCode: "PARKING_FEE", chargeTypeName: "Parking",
                chargeTypeNameAr: "مواقف", accountId: "acc-parking", accountName: "Parking income", lineAmount: 3650,
                from: "2026-01-01", to: "2026-12-31", days: 365, unearnedDays: 184, amount: 1840 }],
        } as SettlementStatement);
        renderPage();
        const box = await screen.findByTestId("settlement-unearned-fee-acc-parking");
        expect(box).not.toBeChecked();
        expect(screen.getByTestId("settlement-unearned-fees")).toHaveTextContent("Parking: 184 of 365 days unearned");
        expect(screen.getByTestId("settlement-total-additions")).toHaveTextContent("0.00");
        fireEvent.click(box);
        expect(box).toBeChecked();
        expect(screen.getByTestId("settlement-total-additions")).toHaveTextContent("1,840.00");
        fireEvent.click(box);
        expect(screen.getByTestId("settlement-total-additions")).not.toHaveTextContent("1,840.00");
    });
});

describe("groupFeeRefunds (review P3-1)", () => {
    it("merges two fees on one income leaf into one offer and keeps others apart", () => {
        const o = (id: string, acc: string, name: string, amount: number) => ({ leaseLineId: id, chargeTypeCode: name,
            chargeTypeName: name, chargeTypeNameAr: null, accountId: acc, accountName: null, lineAmount: 0,
            from: "2026-01-01", to: "2026-12-31", days: 365, unearnedDays: 184, amount });
        const merged = groupFeeRefunds([o("a", "acc-1", "Parking", 1840), o("b", "acc-1", "Parking 2", 920.5), o("c", "acc-2", "Cooling", 10)]);
        expect(merged).toHaveLength(2);
        expect(merged[0]).toMatchObject({ accountId: "acc-1", amount: 2760.5, chargeTypeName: "Parking + Parking 2" });
        expect(merged[1]).toMatchObject({ accountId: "acc-2", amount: 10 });
    });
});
