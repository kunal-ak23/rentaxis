import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../messages/ar.json";
import en from "../../../../../messages/en.json";
import BalanceSheetTable from "../BalanceSheetTable";
import type { BalanceSheet, PnlAmount } from "@/lib/api/propertyReports";

/** F14-10: the balance sheet table — sections, the open years' result, the comparative and the check row, EN and AR. */

const P1 = "11111111-1111-1111-1111-111111111111";
const cols = [P1, "UNASSIGNED", "TOTAL"];
const a = (amount: number, prior: number | null = null): PnlAmount => ({ amount, prior, delta: null, deltaPct: null });
const cells = (v: number, prior: number | null = null) => Object.fromEntries(cols.map(k => [k, a(k === "UNASSIGNED" ? 0 : v, prior)]));

function sheet(ok: boolean, compare = false): BalanceSheet {
    const prior = compare ? 1 : null;
    return {
        asAt: "2026-09-30", compareAt: compare ? "2026-08-31" : null, fiscalYearStart: "2026-01-01", scoped: false,
        columns: cols.map(k => k === P1
            ? { key: k, propertyId: k, kind: "PROPERTY" as const, name: "Marina Tower", nameAr: "برج المارينا" }
            : { key: k, propertyId: null, kind: k as "UNASSIGNED" | "TOTAL", name: k, nameAr: null }),
        sections: [
            { type: "ASSET", total: cells(1000, prior), groups: [{ groupId: "a", code: "A-02", name: "Current Assets", nameAr: "الأصول المتداولة",
                accountType: "ASSET", subtotal: cells(1000, prior),
                rows: [{ key: "RENT_RECEIVABLE", reportLine: "RENT_RECEIVABLE", label: "Rent receivable", labelAr: "إيجارات مستحقة", accountIds: [], cells: cells(1000, prior) }] }] },
            { type: "LIABILITY", total: cells(600, prior), groups: [{ groupId: "l", code: "B-01", name: "Current Liability", nameAr: null,
                accountType: "LIABILITY", subtotal: cells(600, prior),
                rows: [{ key: "ADVANCE_RENT", reportLine: "ADVANCE_RENT", label: "Advance rent", labelAr: "إيجار مقدم", accountIds: [], cells: cells(600, prior) }] }] },
            { type: "EQUITY", total: cells(400, prior), groups: [] },
        ],
        currentYearResult: cells(400, prior),
        earlierYearsResult: cells(0, prior),
        liabilitiesAndEquity: cells(1000, prior),
        check: cells(ok ? 0 : 5, prior),
        ok,
        ledgerImbalance: 0,
    };
}

function renderIn(locale: "en" | "ar", data: BalanceSheet) {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <div dir={locale === "ar" ? "rtl" : "ltr"}><BalanceSheetTable data={data} locale={locale} /></div>
        </NextIntlClientProvider>,
    );
}

describe("BalanceSheetTable", () => {
    afterEach(cleanup);

    it("shows the three sections, the year's result in equity and a zero check", () => {
        renderIn("en", sheet(true));
        expect(screen.getByText("Assets")).toBeTruthy();
        expect(screen.getByText("Total liabilities and equity")).toBeTruthy();
        expect(within(screen.getByTestId("current-year-result")).getByText("Current year result (from 01/01/2026)")).toBeTruthy();
        const check = screen.getByTestId("bs-check-row");
        expect(check.className).toContain("text-success");
        expect(within(check).getAllByText("0.00").length).toBeGreaterThan(0);
    });

    it("marks an out-of-balance check and renders Arabic with the comparative", () => {
        renderIn("ar", sheet(false, true));
        expect(screen.getByText("الأصول")).toBeTruthy();
        expect(screen.getByText("برج المارينا")).toBeTruthy();
        expect(screen.getByText("إيجارات مستحقة")).toBeTruthy();
        expect(screen.getAllByText("2026-08-31").length).toBeGreaterThan(0);
        expect(screen.getByTestId("bs-check-row").className).toContain("text-error");
    });
});
