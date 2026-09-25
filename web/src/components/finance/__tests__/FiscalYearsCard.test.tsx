import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { FiscalYear, YearClosePreview } from "@/lib/api/ledger";

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
        expect(screen.getByText("2 draft voucher(s) are dated inside the year; closing will lock them out of it.")).toBeTruthy();
        const confirm = screen.getByTestId("fiscal-close-confirm");
        expect(confirm).toBeDisabled();
        fireEvent.click(screen.getByTestId("fiscal-close-override"));
        fireEvent.click(confirm);
        await waitFor(() => expect(api.close).toHaveBeenCalledWith(2025, true));
    });

    it("shows blockers and never lets them be overridden", async () => {
        api.preview.mockResolvedValue({ ...PREVIEW, warnings: [],
            blockers: [{ code: "previousOpen", message: "x", args: { year: "2024" } }] });
        renderCard();
        fireEvent.click(await screen.findByTestId("fiscal-year-close-2025"));
        expect(await screen.findByTestId("fiscal-close-blockers")).toHaveTextContent("Close fiscal year 2024 first");
        expect(screen.getByTestId("fiscal-close-confirm")).toBeDisabled();
    });

    it("re-opens only the latest closed year, with a reason, for an admin", async () => {
        api.reopen.mockResolvedValue(year(2024, "REOPENED"));
        renderCard();
        fireEvent.click(await screen.findByTestId("fiscal-year-reopen-2024"));
        expect(screen.getByText(/unlocks every period after 31\/12\/2023/)).toBeTruthy();
        const confirm = screen.getByTestId("fiscal-reopen-confirm");
        expect(confirm).toBeDisabled();
        fireEvent.change(screen.getByTestId("fiscal-reopen-reason"), { target: { value: "Missed invoice" } });
        fireEvent.click(confirm);
        await waitFor(() => expect(api.reopen).toHaveBeenCalledWith(2024, "Missed invoice"));
        cleanup();
        renderCard(false);
        await screen.findByTestId("fiscal-years-table");
        expect(screen.queryByTestId("fiscal-year-reopen-2024")).toBeNull();
    });

    it("reads in Arabic", async () => {
        renderCard(true, "ar");
        expect(await screen.findByText("السنوات المالية")).toBeTruthy();
        expect(screen.getAllByText("مفتوحة").length).toBeGreaterThan(0);
    });
});
