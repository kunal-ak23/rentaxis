import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../../messages/en.json";
import type { FiscalYear } from "@/lib/api/ledger";
import { ApiError } from "@/lib/api/facilities";

/**
 * Break-it R4 money4 F4: a stale "Re-open fiscal year" is refused (409
 * fiscal.changed, R3 N6), but the page's reload swapped the whole page for a
 * spinner, unmounting the card — the dialog and its message vanished and the user
 * was told nothing. The page refreshes in place instead, so the message stays.
 */

const fiscal = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn(), lock: vi.fn() }));
const years = vi.hoisted(() => ({ list: vi.fn(), preview: vi.fn(), close: vi.fn(), reopen: vi.fn() }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscal, fiscalYears: years } };
});
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => <a href={String(href)} {...props}>{children}</a>,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/components/finance/bankrec/BankLocksCard", () => ({ BankLocksCard: () => null }));

import FiscalSettingsPage from "../page";

const year = (fy: number, status: FiscalYear["status"]): FiscalYear => ({
    fiscalYear: fy, periodStart: `${fy}-01-01`, periodEnd: `${fy}-12-31`, status, netResult: 0,
    journalId: status === "CLOSED" ? "j" : null, journalNumber: status === "CLOSED" ? `YEC-${String(fy).slice(2)}/1` : null,
    closedAt: null, closedBy: null, reopenedAt: null, reopenedBy: null, reopenReason: null,
});

beforeEach(() => {
    fiscal.get.mockResolvedValueOnce({ fiscalYearStartMonth: 1, booksStartDate: "2024-01-01", booksLockedThrough: "2025-12-31" })
        // The reload takes a moment, as it does over the network: long enough for a
        // page that shows a spinner meanwhile to unmount the card.
        .mockImplementation(() => new Promise(r => setTimeout(() => r(
            { fiscalYearStartMonth: 1, booksStartDate: "2024-01-01", booksLockedThrough: "2026-06-30" }), 30)));
    years.list.mockResolvedValue([year(2026, "OPEN"), year(2025, "CLOSED")]);
    const body = JSON.stringify({ error: true, status: 409, code: "fiscal.changed", message: "The period lock changed" });
    years.reopen.mockRejectedValue(new ApiError(409, "The period lock changed", body));
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("Fiscal page: a stale re-open", () => {
    it("keeps the dialog and the refusal on screen and shows the new lock", async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <FiscalSettingsPage />
            </NextIntlClientProvider>,
        );
        fireEvent.click(await screen.findByTestId("fiscal-year-reopen-2025"));
        fireEvent.change(screen.getByTestId("fiscal-reopen-reason"), { target: { value: "Missed invoice" } });
        fireEvent.click(screen.getByTestId("fiscal-reopen-confirm"));

        await waitFor(() => expect(years.reopen).toHaveBeenCalled());
        await waitFor(() => expect(fiscal.get).toHaveBeenCalledTimes(2));
        // The page has settled on the new lock...
        await waitFor(() => expect(screen.getByTestId("fiscal-years-table")).toBeTruthy());
        await new Promise(r => setTimeout(r, 50));
        expect(screen.getAllByText("30/06/2026").length).toBeGreaterThan(0);
        // ...and the refusal and the dialog are still there.
        expect(screen.getByText(en.Common.errors.fiscal.changed)).toBeTruthy();
        expect(screen.getByTestId("fiscal-reopen-confirm")).toBeTruthy();
    });
});
