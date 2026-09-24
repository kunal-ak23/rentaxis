import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { TaxInvoice } from "@/lib/api/leasing";

const api = vi.hoisted(() => ({ myInvoices: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, vatApi: { ...m.vatApi, ...api } };
});

import RenterTaxInvoices from "../RenterTaxInvoices";

const invoice: TaxInvoice = {
    id: "i1", invoiceNumber: "TI-26/1", kind: "TAX_INVOICE", issueDate: "2026-05-01",
    periodStart: "2026-05-01", periodEnd: "2026-07-31", leaseId: "l1", chequeId: "c1",
    propertyName: "Tower", unitNumber: "A-101", customerName: "Renter",
    taxableAmount: 30000, vatRate: 0.05, vatAmount: 1500, totalAmount: 31500,
};

function renderIt() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RenterTaxInvoices />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("RenterTaxInvoices", () => {
    it("lists the renter's own invoices with a PDF link", async () => {
        api.myInvoices.mockResolvedValue([invoice]);
        renderIt();
        await waitFor(() => expect(screen.getByTestId("renter-tax-invoices")).toBeInTheDocument());
        expect(screen.getByText("My tax invoices")).toBeInTheDocument();
        expect(screen.getByTestId("tax-invoice-download-0")).toHaveAttribute("href", "/api/proxy/v1/tax-invoices/i1/pdf");
    });

    it("renders nothing for a renter with no invoices, or when the list cannot load", async () => {
        api.myInvoices.mockResolvedValue([]);
        const { container } = renderIt();
        await waitFor(() => expect(api.myInvoices).toHaveBeenCalled());
        expect(container).toBeEmptyDOMElement();

        cleanup();
        api.myInvoices.mockRejectedValue(new Error("boom"));
        const second = renderIt();
        await waitFor(() => expect(api.myInvoices).toHaveBeenCalledTimes(2));
        expect(second.container).toBeEmptyDOMElement();
    });
});
