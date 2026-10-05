import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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
import { lastQuarterStart } from "@/lib/api/vatReturns";

/** PR #361 R1: filing and re-opening confirm in the app dialog; the reason is typed there. No window.confirm/prompt. */
const base = { id: null, periodStart: "2026-04-01", periodEnd: "2026-06-30", status: "OPEN", filedAt: null, filedByName: null,
    filingReference: null, boxes: [], netVat: 0, outputCheck: null, commercialWithoutVat: 0, inputVatOther: 800,
    inputVatOnExempt: 50, canFile: true, cannotFileReason: null };

describe("VAT return page", () => {
    // The page defaults to the last completed quarter; the fixtures are Q2 2026. Pin the
    // clock (Date only — the testing-library waits keep their real timers) so the
    // suite does not change its answer when the calendar turns a quarter.
    beforeEach(() => {
        vi.useFakeTimers({ toFake: ["Date"] });
        vi.setSystemTime(new Date("2026-08-15T09:00:00Z"));
    });
    afterEach(() => { cleanup(); vi.clearAllMocks(); vi.useRealTimers(); });

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

    it("asks for a reason when the output check fails and sends it with the acknowledged difference (PR #369 R1)", async () => {
        const failing = { ...base, outputCheck: { documents: 100, ledger: 3100, difference: -3000, ok: false }, reasonRequired: true };
        api.get.mockResolvedValue(failing);
        api.filings.mockResolvedValue([]);
        api.file.mockResolvedValue({ ...failing, status: "FILED" });
        render(<NextIntlClientProvider locale="en" messages={en}><VatReturnPage /></NextIntlClientProvider>);
        expect(await screen.findByTestId("vat-override")).toBeTruthy();
        const file = screen.getByTestId("vat-file") as HTMLButtonElement;
        expect(file.disabled).toBe(true);
        fireEvent.change(screen.getByTestId("vat-override-reason"), { target: { value: "short" } });
        expect(file.disabled).toBe(true);
        fireEvent.change(screen.getByTestId("vat-override-reason"), { target: { value: "Cut-over contracts invoiced by PACT" } });
        expect(file.disabled).toBe(false);
        fireEvent.click(file);
        fireEvent.click(await screen.findByTestId("vat-confirm"));
        await waitFor(() => expect(api.file).toHaveBeenCalledWith(lastQuarterStart(), "", "Cut-over contracts invoiced by PACT", -3000));
    });

    it("shows what the filing recorded about the output check, and never a pre-check filing as tied", async () => {
        api.get.mockResolvedValue({ ...base, id: "r1", status: "FILED", filedAt: "2026-07-10T00:00:00Z", canFile: false,
            outputDifference: -3000, outputOverrideReason: "Cut-over contracts invoiced by PACT" });
        api.filings.mockResolvedValue([
            { id: "r1", periodStart: "2026-04-01", periodEnd: "2026-06-30", status: "FILED", netVat: 10, filingReference: null,
              filedAt: "2026-07-10T00:00:00Z", filedByName: null, reopenedAt: null, reopenReason: null,
              outputDifference: -3000, outputOverrideReason: "Cut-over contracts invoiced by PACT" },
            { id: "r0", periodStart: "2026-01-01", periodEnd: "2026-03-31", status: "FILED", netVat: 5, filingReference: null,
              filedAt: "2026-04-10T00:00:00Z", filedByName: null, reopenedAt: null, reopenReason: null,
              outputDifference: null, outputOverrideReason: null }]);
        render(<NextIntlClientProvider locale="en" messages={en}><VatReturnPage /></NextIntlClientProvider>);
        expect((await screen.findByTestId("vat-output-at-filing")).textContent).toContain("Cut-over contracts invoiced by PACT");
        const history = screen.getByTestId("vat-history").textContent ?? "";
        expect(history).toContain("not recorded");
        expect(history).not.toContain("tied at filing");
    });

    it("reloads the return when the output check moved since the page loaded (#369 R1-P3-2)", async () => {
        const { ApiError } = await import("@/lib/api/facilities");
        api.get.mockResolvedValueOnce(base).mockResolvedValue({ ...base,
            outputCheck: { documents: 100, ledger: 3100, difference: -3000, ok: false }, reasonRequired: true });
        api.filings.mockResolvedValue([]);
        api.file.mockRejectedValue(new ApiError(400, "The output difference is now -3000.00",
            JSON.stringify({ code: "vat.outputCheckFailed", args: { difference: "-3000.00" }, message: "x" })));
        render(<NextIntlClientProvider locale="en" messages={en}><VatReturnPage /></NextIntlClientProvider>);
        fireEvent.click(await screen.findByTestId("vat-file"));
        fireEvent.click(await screen.findByTestId("vat-confirm"));
        expect(await screen.findByTestId("vat-override")).toBeTruthy();
        expect(api.get).toHaveBeenCalledTimes(2);
    });
    it("shows the selected quarter's return even when the previous quarter's answer arrives last (tutorial 41)", async () => {
        let releaseFirst: (v: unknown) => void = () => {};
        const first = new Promise((resolve) => { releaseFirst = resolve; });
        const initial = { ...base, id: "old", status: "FILED", filedAt: "2026-07-10T00:00:00Z", filingReference: "OLD-QUARTER", canFile: false };
        const chosen = { ...base, periodStart: "2026-04-01", periodEnd: "2026-06-30", status: "OPEN" };
        api.get.mockImplementation((start: string) => (start === "2026-04-01" ? Promise.resolve(chosen) : first));
        api.filings.mockResolvedValue([]);
        render(<NextIntlClientProvider locale="en" messages={en}><VatReturnPage /></NextIntlClientProvider>);
        fireEvent.change(screen.getByTestId("vat-quarter"), { target: { value: "2026-04-01" } });
        await waitFor(() => expect(api.get).toHaveBeenCalledWith("2026-04-01"));
        await screen.findByTestId("vat-file");
        releaseFirst(initial);
        await new Promise((r) => setTimeout(r, 20));
        expect(screen.getByTestId("vat-status").textContent).not.toContain("OLD-QUARTER");
        expect(screen.queryByTestId("vat-reopen")).toBeNull();
        expect(screen.getByTestId("vat-file")).toBeTruthy();
    });
});
