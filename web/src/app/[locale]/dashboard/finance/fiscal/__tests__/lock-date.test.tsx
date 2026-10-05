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
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => <a href={String(href)} {...props}>{children}</a>,
}));
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

    /** Review M9: a first lock asks for the same tick. */
    it("asks for a tick before a first lock", async () => {
        api.get.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: null });
        renderPage();
        fireEvent.change(await screen.findByTestId("fiscal-lock-through"), { target: { value: "2022-06-30" } });
        fireEvent.click(screen.getByTestId("fiscal-lock-open"));
        expect(await screen.findByTestId("fiscal-lock-big-jump")).toHaveTextContent("first period lock");
        expect(screen.getByTestId("fiscal-lock-confirm")).toBeDisabled();
    });

    /** Review M5: a lock already after today (saved before the rule) is named on load. */
    it("names a lock that is already after today", async () => {
        api.get.mockResolvedValue({ fiscalYearStartMonth: 1, booksStartDate: null, booksLockedThrough: "2062-09-30" });
        renderPage("ar");
        expect(await screen.findByTestId("fiscal-lock-ahead")).toHaveTextContent("30/09/2062");
    });

    /** Review I2: the server's coded refusal is shown in Arabic, not its English message. */
    it("shows a coded server refusal in Arabic", async () => {
        const { ApiError } = await import("@/lib/api/facilities");
        api.lock.mockRejectedValueOnce(new ApiError(400, "The period lock date 01/01/2022 is in the future (today is ...).",
            JSON.stringify({ code: "date.inFuture", args: { what: "period lock", date: "01/01/2022", today: "31/12/2021" },
                message: "The period lock date 01/01/2022 is in the future" })));
        renderPage("ar");
        fireEvent.change(await screen.findByTestId("fiscal-lock-through"), { target: { value: "2021-06-30" } });
        fireEvent.click(screen.getByTestId("fiscal-lock-open"));
        fireEvent.click(await screen.findByTestId("fiscal-lock-confirm"));
        const alert = await screen.findByText(/لم يحدث هذا بعد/);
        expect(alert.textContent).toContain("01/01/2022");
        expect(alert.textContent).not.toContain("period lock");
    });

    /** Bug 46: a lock over PLANNED recognition is refused naming the months, with a way to the run. */
    it.each(["en", "ar"] as const)("names the planned months and links to recognition (%s)", async locale => {
        const { ApiError } = await import("@/lib/api/facilities");
        api.lock.mockRejectedValueOnce(new ApiError(400, "Run month-end recognition through 30/09/2026 first",
            JSON.stringify({ code: "fiscal.recognitionPendingForLock",
                args: { through: "30/09/2026", months: "09/2026", count: "9" }, message: "Run month-end recognition" })));
        renderPage(locale);
        fireEvent.change(await screen.findByTestId("fiscal-lock-through"), { target: { value: "2021-06-30" } });
        fireEvent.click(screen.getByTestId("fiscal-lock-open"));
        fireEvent.click(await screen.findByTestId("fiscal-lock-confirm"));
        const alert = await screen.findByTestId("fiscal-lock-dialog-error");
        expect(alert.textContent).toContain("09/2026");
        expect(alert.textContent).toContain("30/09/2026");
        expect(screen.getByTestId("fiscal-lock-run-recognition")).toHaveAttribute("href",
            expect.stringContaining("/dashboard/finance/recognition"));
    });

    it("tells the sidebar to re-read the lock after a successful lock", async () => {
        const seen = vi.fn();
        window.addEventListener("rentaxis:nav-counts-stale", seen);
        renderPage();
        fireEvent.change(await screen.findByTestId("fiscal-lock-through"), { target: { value: "2021-06-30" } });
        fireEvent.click(screen.getByTestId("fiscal-lock-open"));
        fireEvent.click(await screen.findByTestId("fiscal-lock-confirm"));
        await waitFor(() => expect(api.lock).toHaveBeenCalled());
        await waitFor(() => expect(seen).toHaveBeenCalled());
        window.removeEventListener("rentaxis:nav-counts-stale", seen);
    });
});
