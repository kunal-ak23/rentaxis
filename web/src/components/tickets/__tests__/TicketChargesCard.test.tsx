import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";

const api = vi.hoisted(() => ({ get: vi.fn(), link: vi.fn(), unlink: vi.fn(), recharge: vi.fn() }));
vi.mock("@/lib/api/ticketCharges", () => ({ ticketChargesApi: api }));

import TicketChargesCard from "../TicketChargesCard";

/** F14-49: link the vendor bill; recharge defaults to its net amount. */
const bill = { voucherId: "v1", voucherNumber: "PISR-26/16", invoiceNumber: "GC-16", date: "2026-06-03", vendor: "Gulf Cool",
    vendorAr: null, net: 1200, vat: 60, status: "POSTED" };
const base = { ticketId: "t1", reference: "TKT-26/4", leaseId: "L1", bills: [], candidates: [bill], billsNet: 0, recharges: [] };

function renderIn(locale: "en" | "ar") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <TicketChargesCard ticketId="t1" />
        </NextIntlClientProvider>,
    );
}

describe("TicketChargesCard", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("links a bill and recharges its net amount", async () => {
        api.get.mockResolvedValue(base);
        api.link.mockResolvedValue({ ...base, bills: [bill], candidates: [], billsNet: 1200 });
        api.recharge.mockResolvedValue({ ...base, bills: [bill], candidates: [], billsNet: 1200, recharges: [] });
        renderIn("en");
        fireEvent.change(await screen.findByTestId("ticket-bill-pick"), { target: { value: "v1" } });
        fireEvent.click(screen.getByTestId("ticket-bill-link"));
        expect(await screen.findByTestId("ticket-bill")).toBeTruthy();
        fireEvent.click(screen.getByTestId("ticket-recharge-open"));
        expect((screen.getByTestId("ticket-recharge-amount") as HTMLInputElement).value).toBe("1200");
        fireEvent.click(screen.getByTestId("ticket-recharge-confirm"));
        await waitFor(() => expect(api.recharge).toHaveBeenCalledWith("t1", { amount: 1200, vatable: null }));
    });

    it("renders in Arabic", async () => {
        api.get.mockResolvedValue(base);
        renderIn("ar");
        expect(await screen.findByText("الفاتورة وإعادة التحميل")).toBeTruthy();
    });
});
