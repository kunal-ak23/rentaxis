import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { RenterCheque } from "@/lib/api/leasing";

/** Renter: tick several cleared receipts of one contract and download them as one PDF. */

const api = vi.hoisted(() => ({ myPayments: vi.fn(), combinedReceipt: vi.fn(), saveResponse: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        onlinePayApi: { ...m.onlinePayApi, myPayments: api.myPayments },
        chequeApi: { ...m.chequeApi, combinedReceipt: api.combinedReceipt },
    };
});
vi.mock("@/lib/download", async orig => {
    const m = await orig<typeof import("@/lib/download")>();
    return { ...m, saveResponse: api.saveResponse };
});

import RenterPaymentsPage from "../page";

function row(over: Partial<RenterCheque> = {}): RenterCheque {
    return {
        id: "c1", leaseId: "l1", installmentNumber: 1, dueDate: "2026-06-01",
        amount: 12500, status: "CLEARED", mode: "PDC", chequeNumber: "CHQ-1",
        bankName: "ENBD", narration: null, propertyName: "Sample Vista", unitIdentifier: "A-204",
        renterName: "Tenant", due: false, overdue: false, daysOverdue: 0, gracePeriodDays: 5,
        penaltyOutstanding: 0, payable: 0, payableOnline: false, onlineEnabled: false, penaltyAssessmentId: null,
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

const ok = () => ({ ok: true, json: async () => ({}) });

beforeEach(() => {
    api.combinedReceipt.mockResolvedValue(ok());
    api.saveResponse.mockResolvedValue(undefined);
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

const tick = (id: string) => fireEvent.click(screen.getByTestId(`receipt-select-${id}`));

describe("renter combined receipt", () => {
    it("one lease: tick two cleared rows and download posts their ids", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "a", dueDate: "2026-01-01" }),
            row({ id: "b", dueDate: "2026-02-01", installmentNumber: 2 }),
            row({ id: "c", dueDate: "2026-03-01", installmentNumber: 3 }),
        ]);
        renderPage();
        await screen.findByTestId("receipt-select-a");
        tick("a");
        tick("c");
        fireEvent.click(screen.getByTestId("combined-receipt-download"));
        await waitFor(() => expect(api.combinedReceipt).toHaveBeenCalledWith(["a", "c"]));
        await waitFor(() => expect(api.saveResponse).toHaveBeenCalledWith(expect.anything(), "receipts.pdf"));
        await waitFor(() => expect(screen.getByTestId("receipt-select-a")).not.toBeChecked());
        expect(screen.getByTestId("receipt-select-c")).not.toBeChecked();
    });

    it("select year ticks only that year's cleared rows", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "y25", dueDate: "2025-11-01" }),
            row({ id: "y26a", dueDate: "2026-01-01" }),
            row({ id: "y26b", dueDate: "2026-02-01" }),
        ]);
        renderPage();
        await screen.findByTestId("receipt-select-y25");
        fireEvent.change(screen.getByTestId("combined-receipt-year"), { target: { value: "2026" } });
        expect(screen.getByTestId("receipt-select-y26a")).toBeChecked();
        expect(screen.getByTestId("receipt-select-y26b")).toBeChecked();
        expect(screen.getByTestId("receipt-select-y25")).not.toBeChecked();
        fireEvent.click(screen.getByTestId("combined-receipt-download"));
        await waitFor(() => expect(api.combinedReceipt).toHaveBeenCalledWith(["y26a", "y26b"]));
    });

    it("manual tick resets the year select", async () => {
        api.myPayments.mockResolvedValue([row({ id: "a", dueDate: "2026-01-01" }), row({ id: "b", dueDate: "2026-02-01" })]);
        renderPage();
        await screen.findByTestId("receipt-select-a");
        fireEvent.change(screen.getByTestId("combined-receipt-year"), { target: { value: "2026" } });
        tick("a");
        expect((screen.getByTestId("combined-receipt-year") as HTMLSelectElement).value).toBe("");
        expect(screen.getByTestId("receipt-select-b")).toBeChecked();
    });

    it("pre-acquisition rows have no checkbox and no receipt link", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "own" }),
            row({ id: "old", settledBeforeAcquisition: true, dueDate: "2025-01-01" }),
        ]);
        renderPage();
        await screen.findByTestId("receipt-select-own");
        expect(screen.getByTestId("receipt-link-own")).toBeInTheDocument();
        expect(screen.getByTestId("history-row-old")).toBeInTheDocument();
        expect(screen.queryByTestId("receipt-select-old")).not.toBeInTheDocument();
        expect(screen.queryByTestId("receipt-link-old")).not.toBeInTheDocument();
    });

    it("two leases: rows of the other contract are not tickable, switching contract drops ticks", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "a1", leaseId: "l1", dueDate: "2026-01-01", propertyName: "Vista", unitIdentifier: "A-1" }),
            row({ id: "a2", leaseId: "l1", dueDate: "2026-02-01", propertyName: "Vista", unitIdentifier: "A-1" }),
            row({ id: "b1", leaseId: "l2", dueDate: "2026-05-01", propertyName: "Marina", unitIdentifier: "B-2" }),
            row({ id: "b2", leaseId: "l2", dueDate: "2026-06-01", propertyName: "Marina", unitIdentifier: "B-2" }),
        ]);
        renderPage();
        await screen.findByTestId("combined-receipt-contract");
        const select = screen.getByTestId("combined-receipt-contract") as HTMLSelectElement;
        // Default: the lease of the most recent cleared row.
        expect(select.value).toBe("l2");
        expect(screen.getByRole("option", { name: "Marina · B-2" })).toBeInTheDocument();
        expect(screen.queryByTestId("receipt-select-a1")).not.toBeInTheDocument();
        tick("b1");
        fireEvent.change(select, { target: { value: "l1" } });
        expect(screen.queryByTestId("receipt-select-b1")).not.toBeInTheDocument();
        expect(screen.getByTestId("combined-receipt-download")).toBeDisabled();
        tick("a2");
        fireEvent.click(screen.getByTestId("combined-receipt-download"));
        await waitFor(() => expect(api.combinedReceipt).toHaveBeenCalledWith(["a2"]));
    });

    it("server refusal shows the message and keeps ticks", async () => {
        api.combinedReceipt.mockResolvedValue({
            ok: false,
            json: async () => ({ message: "Receipt can only be generated for cleared payments" }),
        });
        api.myPayments.mockResolvedValue([row({ id: "a" }), row({ id: "b", dueDate: "2026-07-01" })]);
        renderPage();
        await screen.findByTestId("receipt-select-a");
        tick("a");
        fireEvent.click(screen.getByTestId("combined-receipt-download"));
        expect(await screen.findByText("Receipt can only be generated for cleared payments")).toBeInTheDocument();
        expect(screen.getByTestId("receipt-select-a")).toBeChecked();
        expect(api.saveResponse).not.toHaveBeenCalled();
    });

    it("disables the download above 100 and says why", async () => {
        api.myPayments.mockResolvedValue(
            Array.from({ length: 101 }, (_, i) => row({ id: `r${i}`, installmentNumber: i + 1, dueDate: "2026-01-01" })),
        );
        renderPage();
        await screen.findByTestId("receipt-select-r0");
        fireEvent.change(screen.getByTestId("combined-receipt-year"), { target: { value: "2026" } });
        expect(screen.getByTestId("combined-receipt-limit")).toBeInTheDocument();
        expect(screen.getByTestId("combined-receipt-download")).toBeDisabled();
    });

    it("no cleared rows → no picker bar", async () => {
        api.myPayments.mockResolvedValue([
            row({ id: "x", status: "REGISTERED", due: true, payable: 100 }),
            row({ id: "old", settledBeforeAcquisition: true }),
        ]);
        renderPage();
        await screen.findByTestId("history-row-old");
        expect(screen.queryByTestId("combined-receipt-bar")).not.toBeInTheDocument();
    });
});
