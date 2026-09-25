import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../messages/ar.json";
import en from "../../../../../messages/en.json";

const documents = vi.fn();
vi.mock("@/lib/api/vatReturns", () => ({ vatReturnsApi: { documents: (...a: unknown[]) => documents(...a) } }));

import VatReturnView from "../VatReturnView";
import type { VatReturn } from "@/lib/api/vatReturns";

/** #55: the VAT 201 boxes, EN/AR, and the drill from a box to its documents. */
const data: VatReturn = {
    id: null, periodStart: "2026-04-01", periodEnd: "2026-06-30", status: "OPEN", filedAt: null, filedByName: null,
    filingReference: null, netVat: 1450, commercialWithoutVat: 0, canFile: true, cannotFileReason: null,
    outputCheck: { documents: 1500, ledger: 1500, difference: 0, ok: true },
    boxes: [
        { code: "1b", key: "standard.DUBAI", amount: 30000, vat: 1500, documents: 1, total: false },
        { code: "5", key: "exempt", amount: 6100, vat: null, documents: 2, total: false },
        { code: "9", key: "standardExpenses", amount: 1000, vat: 50, documents: 1, total: false },
        { code: "14", key: "netPayable", amount: null, vat: 1450, documents: 0, total: true },
    ],
};

function renderIn(locale: "en" | "ar") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <VatReturnView data={data} locale={locale} />
        </NextIntlClientProvider>,
    );
}

describe("VatReturnView", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("lists the boxes and opens a box's documents", async () => {
        documents.mockResolvedValue([{ kind: "TAX_INVOICE", id: "i1", number: "TI-26/1", date: "2026-05-01",
            party: "Renter A", partyAr: null, amount: 30000, vat: 1500, journalId: null, entryNumber: null, leaseId: null }]);
        renderIn("en");
        expect(screen.getByText("Standard rated supplies in Dubai")).toBeTruthy();
        expect(screen.getByTestId("vat-box-14").textContent).toContain("1,450.00");
        fireEvent.click(screen.getByTestId("vat-drill-1b"));
        expect(await screen.findByText("TI-26/1")).toBeTruthy();
        expect(documents).toHaveBeenCalledWith("2026-04-01", "1b");
    });

    it("renders the Arabic labels", () => {
        renderIn("ar");
        expect(screen.getByText("التوريدات الخاضعة للنسبة الأساسية في دبي")).toBeTruthy();
        expect(screen.getByText("صافي الضريبة المستحقة الدفع (السالب: قابل للاسترداد)")).toBeTruthy();
    });
});
