import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { FiscalYear, YearClosePreview } from "@/lib/api/ledger";
import { ApiError } from "@/lib/api/facilities";

const api = vi.hoisted(() => ({ list: vi.fn(), preview: vi.fn(), close: vi.fn(), reopen: vi.fn() }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, fiscalYears: api } };
});

import FiscalYearsCard from "../FiscalYearsCard";

const year = (fy: number, status: FiscalYear["status"], net = 19200): FiscalYear => ({
    fiscalYear: fy, periodStart: `${fy}-01-01`, periodEnd: `${fy}-12-31`, status, netResult: net,
    journalId: status === "CLOSED" ? "j" : null, journalNumber: status === "CLOSED" ? `YEC-${String(fy).slice(2)}/1` : null,
    closedAt: null, closedBy: null, reopenedAt: null, reopenedBy: null, reopenReason: null,
});

const PREVIEW: YearClosePreview = {
    fiscalYear: 2025, periodStart: "2025-01-01", periodEnd: "2025-12-31", blockers: [],
    warnings: [{ code: "draftVouchers", message: "x", args: { count: "2" } }],
    lines: [], income: 18100, expense: 300, netResult: 17800,
    retainedEarnings: [{ propertyId: "p", propertyName: "Marina Heights", profit: 17800 }],
    lockBefore: "2024-12-31", lockAfter: "2025-12-31",
};

function renderCard(canReopen = true, locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <FiscalYearsCard canReopen={canReopen} />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    api.list.mockResolvedValue([year(2026, "OPEN", 0), year(2025, "OPEN", 17800), year(2024, "CLOSED")]);
});
afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

/** Spec §3: close with a preview, re-open with a reason. */
describe("FiscalYearsCard", () => {
    it("previews the close, needs the warning overridden, and closes", async () => {
        api.preview.mockResolvedValue(PREVIEW);
        api.close.mockResolvedValue(year(2025, "CLOSED", 17800));
        renderCard();
        fireEvent.click(await screen.findByTestId("fiscal-year-close-2025"));
        expect(await screen.findByTestId("fiscal-close-net")).toHaveTextContent("17,800.00");
        expect(screen.getByTestId("fiscal-close-retained")).toHaveTextContent("Marina Heights");
        expect(screen.getByTestId("fiscal-close-retained").querySelector("bdi[dir='ltr']")?.textContent).toBe("17,800.00");
        expect(screen.queryByTestId("fiscal-close-brought-forward")).toBeNull();
        expect(screen.getByText("2 draft voucher(s) are dated inside the year; closing will lock them out of it.")).toBeTruthy();
        const confirm = screen.getByTestId("fiscal-close-confirm");
        expect(confirm).toBeDisabled();
        fireEvent.click(screen.getByTestId("fiscal-close-override"));
        fireEvent.click(confirm);
        await waitFor(() => expect(api.close).toHaveBeenCalledWith(2025, true));
    });

    it("shows an earlier open year's result apart from the year's own (F15-01)", async () => {
        api.preview.mockResolvedValue({ ...PREVIEW,
            retainedEarnings: [{ propertyId: "p", propertyName: "Marina Heights", profit: 17800, broughtForward: 19200 }] });
        renderCard(true, "ar");
        fireEvent.click(await screen.findByTestId("fiscal-year-close-2025"));
        const bf = await screen.findByTestId("fiscal-close-brought-forward");
        expect(bf).toHaveTextContent(ar.FiscalYears.broughtForward);
        expect(bf.querySelector("bdi[dir='ltr']")?.textContent).toBe("19,200.00");
        expect(screen.getByTestId("fiscal-close-retained").querySelector("bdi[dir='ltr']")?.textContent).toBe("17,800.00");
    });

    it("shows blockers and never lets them be overridden", async () => {
        api.preview.mockResolvedValue({ ...PREVIEW, warnings: [],
            blockers: [{ code: "previousOpen", message: "x", args: { year: "2024" } }] });
        renderCard();
        fireEvent.click(await screen.findByTestId("fiscal-year-close-2025"));
        expect(await screen.findByTestId("fiscal-close-blockers")).toHaveTextContent("Close fiscal year 2024 first");
        expect(screen.getByTestId("fiscal-close-confirm")).toBeDisabled();
    });

    /** Break-it R2 money2 F4: the re-open states the lock it leaves, including a later manual lock it drops. */
    it("says where the re-open moves the period lock", async () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <FiscalYearsCard canReopen lockedThrough="2026-06-30" booksStartDate="2023-01-01" />
            </NextIntlClientProvider>,
        );
        fireEvent.click(await screen.findByTestId("fiscal-year-reopen-2024"));
        expect(screen.getByTestId("fiscal-reopen-lock-move"))
            .toHaveTextContent("Period lock will move from 30/06/2026 to 31/12/2023");
        cleanup();
        // Never before the books start.
        render(
            <NextIntlClientProvider locale="ar" messages={ar}>
                <FiscalYearsCard canReopen lockedThrough="2024-12-31" booksStartDate="2024-07-01" />
            </NextIntlClientProvider>,
        );
        fireEvent.click(await screen.findByTestId("fiscal-year-reopen-2024"));
        expect(screen.getByTestId("fiscal-reopen-lock-move")).toHaveTextContent("30/06/2024");
    });

    it("re-opens only the latest closed year, with a reason, for an admin", async () => {
        api.reopen.mockResolvedValue(year(2024, "REOPENED"));
        renderCard();
        fireEvent.click(await screen.findByTestId("fiscal-year-reopen-2024"));
        expect(screen.getByTestId("fiscal-reopen-warning")).toHaveTextContent("unlocks every period after 31/12/2023");
        const confirm = screen.getByTestId("fiscal-reopen-confirm");
        expect(confirm).toBeDisabled();
        fireEvent.change(screen.getByTestId("fiscal-reopen-reason"), { target: { value: "Missed invoice" } });
        fireEvent.click(confirm);
        await waitFor(() => expect(api.reopen).toHaveBeenCalledWith(2024, "Missed invoice", null));
        cleanup();
        renderCard(false);
        await screen.findByTestId("fiscal-years-table");
        expect(screen.queryByTestId("fiscal-year-reopen-2024")).toBeNull();
    });

    /**
     * Break-it R3 money3 N6: the re-open carries the lock the dialog showed; a lock
     * moved by another tab since answers 409 fiscal.changed — nothing re-opened, the
     * card and the page reload, and the user is told why.
     */
    it("sends the lock it showed and reloads when the server says it changed", async () => {
        const body = JSON.stringify({ error: true, status: 409, code: "fiscal.changed", message: "The period lock changed" });
        api.reopen.mockRejectedValue(new ApiError(409, "The period lock changed", body));
        const onChanged = vi.fn();
        render(
            <NextIntlClientProvider locale="ar" messages={ar}>
                <FiscalYearsCard canReopen lockedThrough="2025-12-31" booksStartDate="2023-01-01" onChanged={onChanged} />
            </NextIntlClientProvider>,
        );
        fireEvent.click(await screen.findByTestId("fiscal-year-reopen-2024"));
        fireEvent.change(screen.getByTestId("fiscal-reopen-reason"), { target: { value: "Missed invoice" } });
        const listCalls = api.list.mock.calls.length;
        fireEvent.click(screen.getByTestId("fiscal-reopen-confirm"));
        await waitFor(() => expect(api.reopen).toHaveBeenCalledWith(2024, "Missed invoice", "2025-12-31"));
        expect(await screen.findByText(ar.Common.errors.fiscal.changed)).toBeTruthy();
        expect(onChanged).toHaveBeenCalled();
        expect(api.list.mock.calls.length).toBeGreaterThan(listCalls);
        // Still open: the user reviews the move from the current lock.
        expect(screen.getByTestId("fiscal-reopen-confirm")).toBeTruthy();
    });

    it("reads in Arabic", async () => {
        renderCard(true, "ar");
        expect(await screen.findByText("السنوات المالية")).toBeTruthy();
        expect(screen.getAllByText("مفتوحة").length).toBeGreaterThan(0);
    });
});
