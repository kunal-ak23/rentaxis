import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import ar from "../../../../../../../messages/ar.json";
import { businessTodayIso } from "@/lib/businessDate";

/**
 * Break-it R2 money2 F3: a period that has not happened cannot be locked (2062
 * typed for 2026 locked the organisation out), and moving the lock more than
 * twelve months forward asks for a tick before it is sent.
 */

const api = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn(), lock: vi.fn() }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscal: api } };
});
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
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

beforeEach(() => {
    api.get.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: "2020-12-31" });
    api.lock.mockImplementation(async (through: string) => ({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: through }));
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Period lock date", () => {
    it("states the rule and refuses a date after today before anything is sent", async () => {
        renderPage();
        const input = await screen.findByTestId("fiscal-lock-through");
        expect(input).toHaveAttribute("max", businessTodayIso());
        expect(screen.getByTestId("fiscal-lock-rule")).toBeInTheDocument();
        fireEvent.change(input, { target: { value: "2062-09-30" } });
        expect(screen.getByTestId("fiscal-lock-after-today")).toBeInTheDocument();
        expect(screen.getByTestId("fiscal-lock-open")).toBeDisabled();
    });

    it("asks for a tick before moving the lock more than twelve months forward", async () => {
        renderPage("ar");
        fireEvent.change(await screen.findByTestId("fiscal-lock-through"), { target: { value: "2022-06-30" } });
        fireEvent.click(screen.getByTestId("fiscal-lock-open"));
        expect(await screen.findByTestId("fiscal-lock-big-jump")).toBeInTheDocument();
        const confirm = screen.getByTestId("fiscal-lock-confirm");
        expect(confirm).toBeDisabled();
        fireEvent.click(screen.getByTestId("fiscal-lock-big-jump-ack"));
        expect(confirm).toBeEnabled();
        fireEvent.click(confirm);
        await waitFor(() => expect(api.lock).toHaveBeenCalledWith("2022-06-30"));
    });

    it("asks nothing extra for a move of twelve months or less", async () => {
        api.get.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: "2022-01-31" });
        renderPage();
        fireEvent.change(await screen.findByTestId("fiscal-lock-through"), { target: { value: "2022-12-31" } });
        fireEvent.click(screen.getByTestId("fiscal-lock-open"));
        expect(await screen.findByTestId("fiscal-lock-confirm")).toBeEnabled();
        expect(screen.queryByTestId("fiscal-lock-big-jump")).toBeNull();
    });
});
