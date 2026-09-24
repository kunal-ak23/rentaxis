import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { TaxInvoice, VatTaxPoint } from "@/lib/api/leasing";

const api = vi.hoisted(() => ({ schedule: vi.fn(), leaseInvoices: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, vatApi: { ...m.vatApi, ...api } };
});

import VatScheduleTab from "../VatScheduleTab";

function point(over: Partial<VatTaxPoint> & { id: string }): VatTaxPoint {
    return {
        leaseId: "l1", chequeId: "c1", chequeSeqNo: 1, chequeNumber: "000101", propertyId: "p1",
        propertyName: "Tower", unitNumber: "A-101", kind: "INSTALMENT", taxPointDate: "2026-05-01",
        taxableAmount: 30000, vatAmount: 1500, status: "PLANNED", journalId: null, journalNumber: null,
        invoiceId: null, invoiceNumber: null,
        ...over,
    };
}

const invoice: TaxInvoice = {
    id: "i1", invoiceNumber: "TI-26/1", kind: "TAX_INVOICE", issueDate: "2026-05-01",
    periodStart: "2026-05-01", periodEnd: "2026-07-31", leaseId: "l1", chequeId: "c1",
    propertyName: "Tower", unitNumber: "A-101", customerName: "Renter",
    taxableAmount: 30000, vatRate: 0.05, vatAmount: 1500, totalAmount: 31500,
};

function renderTab(messages = en, locale = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={messages}>
            <VatScheduleTab leaseId="l1" contractVat={6000} />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("VatScheduleTab", () => {
    it("lists each tax point with its journal and invoice, and checks Σ against the contract's VAT", async () => {
        api.schedule.mockResolvedValue([
            point({ id: "p1", status: "POSTED", journalId: "j1", journalNumber: "VTP-26/1", invoiceId: "i1", invoiceNumber: "TI-26/1" }),
            point({ id: "p2", chequeSeqNo: 2, taxPointDate: "2026-08-01" }),
            point({ id: "p3", chequeSeqNo: 3, taxPointDate: "2026-11-01" }),
            point({ id: "p4", chequeSeqNo: 4, taxPointDate: "2027-02-01" }),
            point({ id: "old", status: "CANCELLED", vatAmount: 999 }),
        ]);
        api.leaseInvoices.mockResolvedValue([invoice]);
        renderTab();

        await waitFor(() => expect(screen.getByTestId("vat-schedule")).toBeInTheDocument());
        expect(screen.getByText("VTP-26/1")).toBeInTheDocument();
        expect(screen.getAllByText("TI-26/1").length).toBeGreaterThan(0);
        expect(screen.getByTestId("vat-schedule-total")).toHaveTextContent("6,000.00");
        expect(screen.getByTestId("vat-schedule-check")).toHaveTextContent("Matches the contract's VAT of 6,000.00");
        expect(screen.getByTestId("tax-invoice-download-0")).toHaveAttribute("href", "/api/proxy/v1/tax-invoices/i1/pdf");
    });

    it("says so when a live point is missing", async () => {
        api.schedule.mockResolvedValue([point({ id: "p1" })]);
        api.leaseInvoices.mockResolvedValue([]);
        renderTab();
        await waitFor(() => expect(screen.getByTestId("vat-schedule-check"))
            .toHaveTextContent("Differs from the contract's VAT by 4,500.00"));
        expect(screen.getByText("No tax invoices have been issued yet.")).toBeInTheDocument();
    });

    it("labels a termination adjustment, in Arabic too", async () => {
        api.schedule.mockResolvedValue([point({ id: "t", kind: "TERMINATION_ADJUSTMENT", chequeId: null, vatAmount: -261.99, status: "POSTED" })]);
        api.leaseInvoices.mockResolvedValue([{ ...invoice, kind: "CREDIT_NOTE", invoiceNumber: "TCN-27/1" }]);
        renderTab(ar, "ar");
        await waitFor(() => expect(screen.getByText("تسوية الإنهاء")).toBeInTheDocument());
        expect(screen.getByText("إشعار دائن ضريبي")).toBeInTheDocument();
    });

    it("counts a CONTRACT-timed tax point into the live total (F14-54)", async () => {
        api.schedule.mockResolvedValue([
            point({ id: "c1", kind: "CONTRACT", vatAmount: 6000, status: "POSTED" }),
        ]);
        api.leaseInvoices.mockResolvedValue([]);
        renderTab();
        await waitFor(() => expect(screen.getByTestId("vat-schedule-total")).toHaveTextContent("6,000.00"));
        // Matches the contract's VAT exactly — not a 6,000.00 difference, which is
        // what it showed while CONTRACT points were excluded from the sum.
        expect(screen.getByTestId("vat-schedule-check")).toHaveTextContent("Matches the contract's VAT of 6,000.00");
    });
});
