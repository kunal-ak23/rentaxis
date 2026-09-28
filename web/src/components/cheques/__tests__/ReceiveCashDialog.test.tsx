import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { LeaseDetail, LeaseStatus } from "@/lib/api/leasing";

/**
 * The register's "Cash receipt" raises a NEW row, so it is gated by
 * `ChequeService.POSTED` ({ACTIVE, NOTICE_GIVEN, RENEWED}) and not by the wider
 * `COLLECTABLE` that lets an existing row move. Task 7's fix round withdrew
 * EXPIRED from `POSTED`, and this dialog's lease search was never swept: it
 * offered every lease `GET /leases/paged` returned, so picking a DRAFT, EXPIRED,
 * TERMINATED or CLOSED one answered
 * "This lease is EXPIRED; use the cheque grid to add rows until it is posted."
 */

const api = vi.hoisted(() => ({ paged: vi.fn(), get: vi.fn(), cashReceipt: vi.fn(), statsByLeases: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: { ...m.leaseApi, paged: api.paged, get: api.get },
        chequeApi: { ...m.chequeApi, cashReceipt: api.cashReceipt, statsByLeases: api.statsByLeases },
    };
});

vi.mock("@/components/finance/SettlementAccountPicker", () => ({
    default: () => <div data-testid="account-picker" />,
}));

import ReceiveCashDialog from "../ReceiveCashDialog";
import { businessTodayIso } from "@/lib/businessDate";

function lease(id: string, status: LeaseStatus, unit: string): LeaseDetail {
    return {
        id, unitId: `u-${id}`, renterId: `r-${id}`, unitIdentifier: unit, renterName: `Renter ${unit}`,
        startDate: "2026-01-01", endDate: "2026-12-31", status,
        rentAmount: 60000, depositAmount: null, ejariNumber: null, paymentTerms: 4,
        installmentDistribution: "LAST_LARGER", paymentMethod: "CHEQUE", depositPaymentMethod: "CHEQUE",
        paymentReferenceNumber: null, propertyId: "p1", propertyName: "L'Olivier", propertyCode: "OLV",
        hasContract: false, contractNumber: null, displayContractNumber: `TCO-26/${unit}`,
        agreementDate: null, rentVatApplicable: true, contractDate: "2026-01-01", totalDays: 365,
        gracePeriodDays: 5, firstDueDate: "2026-01-01", renterAcceptedAt: null,
        renewedFromLeaseId: null, chainId: "chain-1", receivableAccountId: null, incomeAccountId: null,
        postingJournalId: null, postedAt: "2026-01-01T00:00:00Z", contractValue: 60000,
        terminatedOn: null, terminationJournalId: null, terminationNotes: null,
        lines: [],
    };
}

const PAGE = [
    lease("l-active", "ACTIVE", "A-101"),
    lease("l-notice", "NOTICE_GIVEN", "A-102"),
    lease("l-renewed", "RENEWED", "A-103"),
    lease("l-expired", "EXPIRED", "A-104"),
    lease("l-terminated", "TERMINATED", "A-105"),
    lease("l-closed", "CLOSED", "A-106"),
    lease("l-draft", "DRAFT", "A-107"),
];

function renderDialog(props: Partial<React.ComponentProps<typeof ReceiveCashDialog>> = {}) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ReceiveCashDialog open onClose={vi.fn()} onDone={vi.fn()} {...props} />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    api.paged.mockResolvedValue({ content: PAGE, totalElements: PAGE.length, totalPages: 1, number: 0, size: 8 });
    api.get.mockImplementation(async (id: string) => PAGE.find(l => l.id === id) ?? null);
    // 60,000 on the register, 15,000 of it cleared: 45,000 still outstanding.
    api.statsByLeases.mockImplementation(async (ids: string[]) => ids.map(leaseId => ({
        leaseId, total: 4, cleared: 1, uncleared: 3, bounced: 0,
        totalAmount: 60000, clearedAmount: 15000, dueAmount: 15000,
        unclearedAmount: 45000, liveCount: 4, liveAmount: 60000,
    })));
    api.cashReceipt.mockResolvedValue({ id: "c-new" });
});

afterEach(() => {
    vi.useRealTimers();
    cleanup();
    vi.clearAllMocks();
});

async function search(text: string) {
    fireEvent.change(screen.getByTestId("cash-receipt-lease-search"), { target: { value: text } });
    await vi.advanceTimersByTimeAsync(400);
}

describe("ReceiveCashDialog lease search", () => {
    it("offers only the leases ChequeService.POSTED admits", async () => {
        renderDialog();
        await search("A-1");

        await waitFor(() => expect(screen.getByTestId("cash-receipt-lease-option-l-active")).toBeInTheDocument());
        expect(screen.getByTestId("cash-receipt-lease-option-l-notice")).toBeInTheDocument();
        expect(screen.getByTestId("cash-receipt-lease-option-l-renewed")).toBeInTheDocument();

        // EXPIRED was withdrawn from POSTED by task 7's fix round.
        expect(screen.queryByTestId("cash-receipt-lease-option-l-expired")).not.toBeInTheDocument();
        expect(screen.queryByTestId("cash-receipt-lease-option-l-terminated")).not.toBeInTheDocument();
        expect(screen.queryByTestId("cash-receipt-lease-option-l-closed")).not.toBeInTheDocument();
        expect(screen.queryByTestId("cash-receipt-lease-option-l-draft")).not.toBeInTheDocument();
    });

    it("says why the other matches are missing rather than just hiding them", async () => {
        renderDialog();
        await search("A-1");
        await waitFor(() => expect(screen.getByTestId("cash-receipt-lease-filtered")).toBeInTheDocument());
        expect(screen.getByTestId("cash-receipt-lease-filtered")).toHaveTextContent(
            "4 contracts that have ended or are not posted yet are not shown",
        );
    });

    it("refuses a lease handed in on the URL that cannot take a new row", async () => {
        renderDialog({ initialLeaseId: "l-expired" });
        await waitFor(() => expect(screen.getByTestId("cash-receipt-not-posted")).toBeInTheDocument());
        expect(screen.getByTestId("cash-receipt-not-posted")).toHaveTextContent("Expired");
        expect(screen.getByTestId("cash-receipt-confirm")).toBeDisabled();
    });

    it("takes the receipt on a lease that is still running", async () => {
        renderDialog({ initialLeaseId: "l-active" });
        await waitFor(() => expect(screen.getByTestId("cash-receipt-selected-lease")).toBeInTheDocument());
        expect(screen.queryByTestId("cash-receipt-not-posted")).not.toBeInTheDocument();
        fireEvent.change(screen.getByTestId("cash-receipt-amount"), { target: { value: "1500" } });
        expect(screen.getByTestId("cash-receipt-confirm")).toBeEnabled();
    });
});

/**
 * Break-it round 1 (money): F1 1000.555 was saved as 1000.56; F4 a receipt could
 * be dated a century ahead; F3 999,999,999,999.99 was taken on a 65,000 contract
 * without a word. Advance payments are legitimate, so above-outstanding asks
 * for a second click rather than refusing.
 */
describe("ReceiveCashDialog money guards", () => {
    async function openOnActive() {
        renderDialog({ initialLeaseId: "l-active" });
        await waitFor(() => expect(screen.getByTestId("cash-receipt-selected-lease")).toBeInTheDocument());
        await waitFor(() => expect(api.statsByLeases).toHaveBeenCalled());
    }

    it("refuses three decimals instead of rounding them", async () => {
        await openOnActive();
        fireEvent.change(screen.getByTestId("cash-receipt-amount"), { target: { value: "1000.555" } });
        expect(screen.getByTestId("money-input-error")).toHaveTextContent(en.MoneyInput.decimals);
        expect(screen.getByTestId("cash-receipt-confirm")).toBeDisabled();
        expect(api.cashReceipt).not.toHaveBeenCalled();
    });

    it("refuses a date after today (break-it R3 money3 N2: money that arrived)", async () => {
        await openOnActive();
        fireEvent.change(screen.getByTestId("cash-receipt-amount"), { target: { value: "1500" } });
        fireEvent.change(screen.getByTestId("cash-receipt-date"), { target: { value: "2126-09-28" } });
        expect(screen.getByTestId("cash-receipt-date-error").textContent).toMatch(/\(\d{2}\/\d{2}\/\d{4}\)/);
        expect(screen.getByTestId("cash-receipt-confirm")).toBeDisabled();
        const today = businessTodayIso();
        expect(screen.getByTestId("cash-receipt-date").getAttribute("max")).toBe(today);
        const d = new Date(`${today}T00:00:00Z`);
        d.setUTCDate(d.getUTCDate() + 1);
        fireEvent.change(screen.getByTestId("cash-receipt-date"), { target: { value: d.toISOString().slice(0, 10) } });
        expect(screen.getByTestId("cash-receipt-date-error")).toBeInTheDocument();
        expect(screen.getByTestId("cash-receipt-confirm")).toBeDisabled();
        fireEvent.change(screen.getByTestId("cash-receipt-date"), { target: { value: today } });
        expect(screen.queryByTestId("cash-receipt-date-error")).toBeNull();
    });

    it("asks before recording more than is outstanding, then records it as an advance", async () => {
        await openOnActive();
        fireEvent.change(screen.getByTestId("cash-receipt-amount"), { target: { value: "50000" } });
        fireEvent.click(screen.getByTestId("cash-receipt-confirm"));
        expect(api.cashReceipt).not.toHaveBeenCalled();
        expect(screen.getByTestId("cash-receipt-above-outstanding")).toHaveTextContent("45,000.00");
        fireEvent.click(screen.getByTestId("cash-receipt-confirm"));
        await waitFor(() => expect(api.cashReceipt).toHaveBeenCalledTimes(1));
        expect(api.cashReceipt.mock.calls[0][1].amount).toBe(50000);
    });

    it("records a receipt within the outstanding amount on the first click", async () => {
        await openOnActive();
        fireEvent.change(screen.getByTestId("cash-receipt-amount"), { target: { value: "45000" } });
        fireEvent.click(screen.getByTestId("cash-receipt-confirm"));
        await waitFor(() => expect(api.cashReceipt).toHaveBeenCalledTimes(1));
        expect(screen.queryByTestId("cash-receipt-above-outstanding")).not.toBeInTheDocument();
    });
});
