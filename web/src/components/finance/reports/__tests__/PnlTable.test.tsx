import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../messages/ar.json";
import en from "../../../../../messages/en.json";
import PnlTable from "../PnlTable";
import type { PnlAmount, PropertyPnl } from "@/lib/api/propertyReports";

/**
 * Finance-ops spec §1, the P&L table: the pivot to one column, the comparison
 * sub-columns, the manager's view (no Unassigned / Total), and RTL. The real
 * catalogs, not a mocked `t`, so a missing Arabic key fails here.
 */

const P1 = "11111111-1111-1111-1111-111111111111";
const P2 = "22222222-2222-2222-2222-222222222222";

const amt = (amount: number, prior: number | null = null): PnlAmount => ({
    amount,
    prior,
    delta: prior === null ? null : amount - prior,
    deltaPct: prior === null || prior === 0 ? null : Math.round(((amount - prior) / Math.abs(prior)) * 10000) / 100,
});

function pnl(opts: { columns: string[]; compare: boolean; scoped?: boolean }): PropertyPnl {
    const withPrior = opts.compare;
    const cells = (m: Record<string, [number, number]>) =>
        Object.fromEntries(opts.columns.map(k => [k, withPrior ? amt(...(m[k] ?? [0, 0])) : amt((m[k] ?? [0, 0])[0])]));
    const all = {
        [P1]: { name: "Marina Tower", nameAr: "برج المارينا" },
        [P2]: { name: "Palm Residence", nameAr: null },
    } as Record<string, { name: string; nameAr: string | null }>;
    return {
        from: "2026-09-01", to: "2026-09-30", compare: withPrior ? "PREVIOUS" : "NONE",
        priorFrom: withPrior ? "2026-08-01" : null, priorTo: withPrior ? "2026-08-31" : null,
        scoped: !!opts.scoped,
        columns: opts.columns.map(k => k === "UNASSIGNED" || k === "TOTAL"
            ? { key: k, propertyId: null, kind: k, name: k, nameAr: null }
            : { key: k, propertyId: k, kind: "PROPERTY" as const, ...all[k] }),
        groups: [
            {
                groupId: "g1", code: "C-01", name: "Direct Income", nameAr: "الإيرادات المباشرة", accountType: "INCOME",
                rows: [{
                    key: "RENTAL_INCOME", reportLine: "RENTAL_INCOME", label: "Rental income", labelAr: "إيرادات الإيجار",
                    accountIds: ["a1", "a2"], cells: cells({ [P1]: [82191.78, 82191.78], TOTAL: [82191.78, 82191.78] }),
                }],
                subtotal: cells({ [P1]: [82191.78, 82191.78], TOTAL: [82191.78, 82191.78] }),
            },
            {
                groupId: "g2", code: "D-02", name: "Indirect Expense", nameAr: "المصروفات غير المباشرة", accountType: "EXPENSE",
                rows: [{
                    key: "bank", reportLine: null, label: "Bank Charges", labelAr: "رسوم بنكية",
                    accountIds: ["a3"], cells: cells({ UNASSIGNED: [50, 0], TOTAL: [50, 0] }),
                }],
                subtotal: cells({ UNASSIGNED: [50, 0], TOTAL: [50, 0] }),
            },
        ],
        income: cells({ [P1]: [82191.78, 82191.78], TOTAL: [82191.78, 82191.78] }),
        expenses: cells({ UNASSIGNED: [50, 0], TOTAL: [50, 0] }),
        noi: cells({ [P1]: [78991.78, 82791.78], UNASSIGNED: [-50, 0], TOTAL: [78941.78, 82791.78] }),
        allocation: null,
        check: opts.scoped ? null : { ledgerNet: 78941.78, reportNet: 78941.78, difference: 0, ok: true },
        dataQuality: { lineAccountPropertyMismatches: 0 },
    };
}

function renderTable(data: PropertyPnl, locale: "en" | "ar" = "en", onDrill = vi.fn()) {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <div dir={locale === "ar" ? "rtl" : "ltr"}>
                <PnlTable data={data} locale={locale} onDrill={onDrill} />
            </div>
        </NextIntlClientProvider>,
    );
    return onDrill;
}

afterEach(cleanup);

describe("PnlTable", () => {
    it("shows every property plus Unassigned and Total on a multi-property report", () => {
        renderTable(pnl({ columns: [P1, P2, "UNASSIGNED", "TOTAL"], compare: false }));
        const table = screen.getByTestId("pnl-table");
        expect(table.dataset.pivot).toBe("multi");
        expect(screen.getByTestId("col-UNASSIGNED").textContent).toBe("Unassigned");
        expect(screen.getByTestId("col-TOTAL").textContent).toBe("Total");
        expect(screen.getAllByTestId("col-PROPERTY").map(c => c.textContent)).toEqual(["Marina Tower", "Palm Residence"]);
        // No comparison: one figure per column, no Prior header.
        expect(screen.queryByText("Prior")).toBeNull();
    });

    it("pivots to a single column with its comparison when one property is on the report", () => {
        renderTable(pnl({ columns: [P1, "UNASSIGNED", "TOTAL"], compare: true }));
        expect(screen.getByTestId("pnl-table").dataset.pivot).toBe("single");
        expect(screen.queryByTestId("col-UNASSIGNED")).toBeNull();
        expect(screen.queryByTestId("col-TOTAL")).toBeNull();
        const noi = screen.getByTestId("noi-row");
        const cells = within(noi).getAllByRole("cell").map(c => c.textContent);
        expect(cells).toEqual(["NOI", "78,991.78", "82,791.78", "-3,800.00", "-4.59%"]);
    });

    it("gives every column the This period | Prior | Δ | Δ% group under a comparison", () => {
        renderTable(pnl({ columns: [P1, P2, "UNASSIGNED", "TOTAL"], compare: true }));
        expect(screen.getAllByText("This period")).toHaveLength(4);
        expect(screen.getAllByText("Prior")).toHaveLength(4);
        expect(screen.getAllByText("Δ%")).toHaveLength(4);
        // Δ% is blank on a zero base.
        const bank = screen.getByTestId("row-bank");
        expect(within(bank).getAllByRole("cell").map(c => c.textContent)).toContain("");
    });

    it("has no Unassigned or Total column in a manager's scoped report", () => {
        renderTable(pnl({ columns: [P1, P2], compare: false, scoped: true }));
        expect(screen.queryByTestId("col-UNASSIGNED")).toBeNull();
        expect(screen.queryByTestId("col-TOTAL")).toBeNull();
        expect(screen.queryByText("Unassigned")).toBeNull();
    });

    it("drills from a figure with the row's leaves and the column", () => {
        const onDrill = renderTable(pnl({ columns: [P1, P2, "UNASSIGNED", "TOTAL"], compare: false }));
        fireEvent.click(screen.getByTestId(`drill-RENTAL_INCOME-${P1}`));
        expect(onDrill).toHaveBeenCalledWith(expect.objectContaining({
            accountIds: ["a1", "a2"], column: expect.objectContaining({ key: P1 }),
        }));
        // A zero is not a link.
        expect(screen.queryByTestId(`drill-RENTAL_INCOME-${P2}`)).toBeNull();
    });

    it("renders Arabic labels right to left with Latin-digit amounts isolated LTR", () => {
        renderTable(pnl({ columns: [P1, P2, "UNASSIGNED", "TOTAL"], compare: true }), "ar");
        expect(screen.getByTestId("col-UNASSIGNED").textContent).toBe("غير مخصص");
        expect(screen.getAllByTestId("col-PROPERTY")[0].textContent).toBe("برج المارينا");
        // No Arabic name: the English one.
        expect(screen.getAllByTestId("col-PROPERTY")[1].textContent).toBe("Palm Residence");
        expect(screen.getByText("إيرادات الإيجار")).toBeTruthy();
        const amounts = screen.getByTestId("noi-row").querySelectorAll("bdi");
        expect([...amounts].every(b => b.getAttribute("dir") === "ltr")).toBe(true);
        expect(amounts[0].textContent).toBe("78,991.78");
        expect(screen.getByTestId("pnl-table").closest("[dir]")?.getAttribute("dir")).toBe("rtl");
        expect(screen.getByTestId("pnl-table").outerHTML).toMatchSnapshot();
    });
});
