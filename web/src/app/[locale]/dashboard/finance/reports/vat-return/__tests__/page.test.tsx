import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../../../messages/en.json";

const api = vi.hoisted(() => ({ get: vi.fn(), filings: vi.fn(), file: vi.fn(), reopen: vi.fn(), documents: vi.fn(),
    pdfUrl: () => "#", csvUrl: () => "#" }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/lib/api/vatReturns", async (orig) => {
    const real = await orig<typeof import("@/lib/api/vatReturns")>();
    return { ...real, vatReturnsApi: api };
});

import VatReturnPage from "../page";

/** PR #361 R1: filing and re-opening confirm in the app dialog; the reason is typed there. No window.confirm/prompt. */
const base = { id: null, periodStart: "2026-04-01", periodEnd: "2026-06-30", status: "OPEN", filedAt: null, filedByName: null,
    filingReference: null, boxes: [], netVat: 0, outputCheck: null, commercialWithoutVat: 0, inputVatOther: 800,
    inputVatOnExempt: 50, canFile: true, cannotFileReason: null };

describe("VAT return page", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("files through the confirm dialog and shows the input-VAT check lines", async () => {
        api.get.mockResolvedValue(base);
        api.filings.mockResolvedValue([]);
        api.file.mockResolvedValue({ ...base, status: "FILED" });
        const confirmSpy = vi.spyOn(window, "confirm");
        render(<NextIntlClientProvider locale="en" messages={en}><VatReturnPage /></NextIntlClientProvider>);
        expect(await screen.findByTestId("vat-input-other")).toBeTruthy();
        expect(screen.getByTestId("vat-input-exempt")).toBeTruthy();
        fireEvent.click(screen.getByTestId("vat-file"));
        fireEvent.click(await screen.findByTestId("vat-confirm"));
        await waitFor(() => expect(api.file).toHaveBeenCalled());
        expect(confirmSpy).not.toHaveBeenCalled();
    });

    it("asks for the re-open reason in the dialog", async () => {
        api.get.mockResolvedValue({ ...base, id: "r1", status: "FILED", filedAt: "2026-07-10T00:00:00Z", canFile: false });
        api.filings.mockResolvedValue([]);
        api.reopen.mockResolvedValue(base);
        render(<NextIntlClientProvider locale="en" messages={en}><VatReturnPage /></NextIntlClientProvider>);
        fireEvent.click(await screen.findByTestId("vat-reopen"));
        const confirm = await screen.findByTestId("vat-confirm");
        expect((confirm as HTMLButtonElement).disabled).toBe(true);
        fireEvent.change(screen.getByTestId("vat-reopen-reason"), { target: { value: "Late invoice" } });
        fireEvent.click(confirm);
        await waitFor(() => expect(api.reopen).toHaveBeenCalledWith("r1", "Late invoice"));
    });

    it("shows dd/mm/yyyy dates and who filed (F15-16, F15-17)", async () => {
        api.get.mockResolvedValue({ ...base, id: "r1", status: "FILED", filedAt: "2026-07-10T00:00:00Z", filedByName: "Platform Admin", canFile: false });
        api.filings.mockResolvedValue([{ id: "r1", periodStart: "2026-04-01", periodEnd: "2026-06-30", status: "FILED", netVat: 10,
            filingReference: null, filedAt: "2026-07-10T00:00:00Z", filedByName: "Platform Admin", reopenedAt: null, reopenReason: null }]);
        render(<NextIntlClientProvider locale="en" messages={en}><VatReturnPage /></NextIntlClientProvider>);
        const history = await screen.findByTestId("vat-history");
        expect(history.textContent).toContain("01/04/2026 – 30/06/2026");
        expect(history.textContent).toContain("filed by Platform Admin");
        expect(screen.getByTestId("vat-status").textContent).toContain("10/07/2026");
        const quarter = screen.getByTestId("vat-quarter") as HTMLSelectElement;
        expect([...quarter.options].map(o => o.textContent)).not.toContain("2026-04-01");
    });
});
