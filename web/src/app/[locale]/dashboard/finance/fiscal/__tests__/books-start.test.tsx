import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";
import { businessTodayIso } from "@/lib/businessDate";
import { ApiError } from "@/lib/api/facilities";

/**
 * Break-it R3 money3 N1: the books start date implies the first period lock, and
 * 2062 typed for 2026 locked the organisation out. It may be at most three months
 * ahead; a start after today asks for the same tick a big lock jump does.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn(), lock: vi.fn() }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscal: api } };
});
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/components/finance/FiscalYearsCard", () => ({ default: () => null }));
vi.mock("@/components/finance/bankrec/BankLocksCard", () => ({ BankLocksCard: () => null }));

import FiscalSettingsPage from "../page";

function renderPage(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <FiscalSettingsPage />
        </NextIntlClientProvider>,
    );
}

function plusDays(iso: string, n: number): string {
    const d = new Date(`${iso}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + n);
    return d.toISOString().slice(0, 10);
}

beforeEach(() => {
    api.get.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
    api.update.mockImplementation(async (b: { booksStartDate?: string }) => ({
        fiscalYearStartMonth: 1, booksStartDate: b.booksStartDate ?? null,
        booksLockedThrough: b.booksStartDate ? plusDays(b.booksStartDate, -1) : null,
    }));
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Books start date", () => {
    it("refuses the 2062 typo before anything is sent", async () => {
        renderPage();
        const input = await screen.findByTestId("fiscal-books-start");
        expect(input.getAttribute("max")! > businessTodayIso()).toBe(true);
        expect(screen.getByTestId("fiscal-books-start-rule")).toBeInTheDocument();
        fireEvent.change(input, { target: { value: "2062-10-01" } });
        expect(screen.getByTestId("fiscal-books-start-too-far")).toBeInTheDocument();
        expect(screen.getByTestId("fiscal-save")).toBeDisabled();
        fireEvent.change(input, { target: { value: "20260-10-01" } });
        expect(screen.getByTestId("fiscal-save")).toBeDisabled();
        expect(api.update).not.toHaveBeenCalled();
    });

    it("asks for a tick before a books start after today, then saves it", async () => {
        renderPage("ar");
        const soon = plusDays(businessTodayIso(), 10);
        fireEvent.change(await screen.findByTestId("fiscal-books-start"), { target: { value: soon } });
        expect(screen.queryByTestId("fiscal-books-start-too-far")).toBeNull();
        const save = screen.getByTestId("fiscal-save");
        expect(save).toBeDisabled();
        fireEvent.click(screen.getByTestId("fiscal-books-start-future-ack"));
        expect(save).toBeEnabled();
        fireEvent.click(save);
        await waitFor(() => expect(api.update).toHaveBeenCalledWith({ fiscalYearStartMonth: 1, booksStartDate: soon }));
    });

    it("saves a past books start with no tick (the recovery path)", async () => {
        api.get.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: "2062-10-01", booksLockedThrough: "2062-09-30" });
        renderPage();
        expect(await screen.findByTestId("fiscal-lock-ahead")).toHaveTextContent(/change the books start date/);
        fireEvent.change(screen.getByTestId("fiscal-books-start"), { target: { value: "2026-01-01" } });
        expect(screen.queryByTestId("fiscal-books-start-future")).toBeNull();
        fireEvent.click(screen.getByTestId("fiscal-save"));
        await waitFor(() => expect(api.update).toHaveBeenCalledWith({ fiscalYearStartMonth: 1, booksStartDate: "2026-01-01" }));
    });

    it("shows the server's refusal in Arabic", async () => {
        const body = JSON.stringify({ error: true, status: 400, code: "fiscal.booksStartTooFar",
            args: { date: "01/10/2062", latest: "28/12/2026" }, message: "The books cannot start on 01/10/2062" });
        api.update.mockRejectedValue(new ApiError(400, "The books cannot start on 01/10/2062", body));
        renderPage("ar");
        fireEvent.change(await screen.findByTestId("fiscal-books-start"), { target: { value: "2026-01-01" } });
        fireEvent.click(screen.getByTestId("fiscal-save"));
        expect(await screen.findByText(/لا يمكن أن تبدأ الدفاتر في 01\/10\/2062/)).toBeTruthy();
    });
});
