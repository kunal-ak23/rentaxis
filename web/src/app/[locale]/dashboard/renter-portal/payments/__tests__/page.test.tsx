import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { RenterCheque } from "@/lib/api/leasing";

/**
 * The renter's payments screen, rebuilt on `onlinePayApi.myPayments`
 * (`RenterChequeDTO[]`) rather than v1's payment-schedule shape. The one
 * thing this screen must never get wrong: "Pay" only ever shows on a row the
 * gateway would actually take — due, the property's own online-enabled, and
 * flagged `payableOnline` by the server (a PDC or an ONLINE row; never a CASH
 * or TRANSFER instalment) — never on history.
 */

const api = vi.hoisted(() => ({ myPayments: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, onlinePayApi: { ...m.onlinePayApi, ...api } };
});

import RenterPaymentsPage from "../page";

function row(over: Partial<RenterCheque> = {}): RenterCheque {
    return {
        id: "c1", leaseId: "l1", installmentNumber: 1, dueDate: "2026-06-01",
        amount: 12500, status: "REGISTERED", mode: "PDC", chequeNumber: "CHQ-1",
        bankName: "ENBD", narration: null, propertyName: "Sample Vista", unitIdentifier: "A-204",
        renterName: "Tenant", due: true, overdue: false, daysOverdue: 0, gracePeriodDays: 5,
        penaltyOutstanding: 0, payable: 12500, payableOnline: true, onlineEnabled: true, penaltyAssessmentId: null,
        failureReason: null, clearedAt: null, statusChangedAt: null,
        ...over,
    };
}

function renderPage() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RenterPaymentsPage />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("RenterPaymentsPage — due rows", () => {
    it("shows Pay only on a due, online-enabled row — never on a due-but-offline row, never on history", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "due-online", due: true, onlineEnabled: true, status: "REGISTERED" }),
            row({ id: "due-offline", due: true, onlineEnabled: false, status: "REGISTERED", dueDate: "2026-06-05" }),
            row({ id: "cleared", due: false, onlineEnabled: true, status: "CLEARED", dueDate: "2026-04-01" }),
        ]);
        renderPage();

        await waitFor(() => expect(screen.getByTestId("due-row-due-online")).toBeInTheDocument());
        expect(screen.getByTestId("pay-online-due-online")).toBeInTheDocument();

        expect(screen.getByTestId("due-row-due-offline")).toBeInTheDocument();
        expect(screen.queryByTestId("pay-online-due-offline")).not.toBeInTheDocument();

        expect(screen.getByTestId("history-row-cleared")).toBeInTheDocument();
        expect(screen.queryByTestId("pay-online-cleared")).not.toBeInTheDocument();
    });

    it("never shows Pay on a CASH instalment the gateway would refuse", async () => {
        // `payableOnline` is the server's own predicate
        // (ChequeService.registerOnlinePending, :656-662); the row is due,
        // online-enabled and payable, and still must not offer Pay.
        api.myPayments.mockResolvedValue([
            row({ id: "cash", due: true, mode: "CASH", payableOnline: false }),
        ]);
        renderPage();
        await waitFor(() => expect(screen.getByTestId("due-row-cash")).toBeInTheDocument());
        expect(screen.queryByTestId("pay-online-cash")).not.toBeInTheDocument();
    });

    it("never shows Pay on a fully-paid (payable = 0) row even if flagged due", async () => {
        api.myPayments.mockResolvedValue([row({ id: "zero", due: true, onlineEnabled: true, payable: 0 })]);
        renderPage();
        await waitFor(() => expect(screen.getByTestId("due-row-zero")).toBeInTheDocument());
        expect(screen.queryByTestId("pay-online-zero")).not.toBeInTheDocument();
    });

    it("totals the due rows' payable amounts into the Amount due tile", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "d1", due: true, payable: 12500, dueDate: "2026-06-01" }),
            row({ id: "d2", due: true, payable: 500, dueDate: "2026-06-10" }),
        ]);
        renderPage();
        await waitFor(() => expect(screen.getByTestId("amount-due-total")).toHaveTextContent("13,000.00"));
    });
});

describe("RenterPaymentsPage — history", () => {
    it("offers a receipt link on a CLEARED row", async () => {
        api.myPayments.mockResolvedValue([row({ id: "c2", due: false, status: "CLEARED" })]);
        renderPage();
        const link = await screen.findByTestId("receipt-link-c2");
        expect(link).toHaveAttribute("href", "/api/proxy/v1/cheques/c2/receipt");
    });

    it("flags a BOUNCED row instead of offering a receipt", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "b1", due: false, status: "BOUNCED", failureReason: "BOUNCE", statusChangedAt: "2026-05-01" }),
        ]);
        renderPage();
        await waitFor(() => expect(screen.getByTestId("history-row-b1")).toBeInTheDocument());
        expect(screen.queryByTestId("receipt-link-b1")).not.toBeInTheDocument();
        expect(screen.getByTestId("history-row-b1")).toHaveTextContent("Bounced");
    });
});

describe("RenterPaymentsPage — empty and error states", () => {
    it("shows the all-caught-up state when nothing is due", async () => {
        api.myPayments.mockResolvedValue([row({ id: "c3", due: false, status: "CLEARED" })]);
        renderPage();
        expect(await screen.findByText(/All caught up/i)).toBeInTheDocument();
    });

    it("surfaces a load failure instead of rendering an empty screen", async () => {
        const { ApiError } = await import("@/lib/api/leasing");
        api.myPayments.mockRejectedValue(new ApiError(500, "boom"));
        renderPage();
        expect(await screen.findByText("boom")).toBeInTheDocument();
    });
});
