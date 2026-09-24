import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../messages/ar.json";
import en from "../../../../../messages/en.json";
import StatementView from "../StatementView";
import type { PropertyStatement } from "@/lib/api/propertyReports";

const statement: PropertyStatement = {
    propertyId: "p1", propertyName: "Marina Tower", propertyNameAr: "برج المارينا", emirate: "DUBAI",
    from: "2026-09-01", to: "2026-09-30",
    sections: [
        {
            key: "pnl", number: 1, source: "LEDGER",
            figures: [{ key: "noi", amount: 78991.78, count: null }],
            tables: [{ key: "lines", columns: ["type", "line", "lineAr", "amount", "prior", "delta"],
                rows: [["INCOME", "Rental income", "إيرادات الإيجار", 82191.78, 82191.78, 0]] }],
            notes: [], meta: { priorFrom: "2026-08-01", priorTo: "2026-08-31" },
        },
        {
            key: "instalments", number: 2, source: "REGISTER",
            figures: [{ key: "gross", amount: 55000, count: 3 }],
            tables: [], notes: ["register"], meta: {},
        },
        {
            key: "collected", number: 3, source: "LEDGER",
            figures: [{ key: "collected", amount: 26000, count: null }],
            tables: [{ key: "byMode", columns: ["mode", "amount"], rows: [["PDC", 30000], ["OTHER", -4000]] }],
            notes: [], meta: {},
        },
    ],
    footer: { isFinal: false, booksLockedThrough: null, generatedAt: "2026-09-30T08:00:00Z", generatedBy: "Kunal" },
};

function renderView(locale: "en" | "ar") {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <div dir={locale === "ar" ? "rtl" : "ltr"}><StatementView data={statement} locale={locale} /></div>
        </NextIntlClientProvider>,
    );
}

afterEach(cleanup);

describe("StatementView", () => {
    it("labels each section with its source and says the figures are provisional", () => {
        renderView("en");
        expect(within(screen.getByTestId("section-instalments")).getByText("Cheque register")).toBeTruthy();
        expect(within(screen.getByTestId("section-instalments")).getByText(/not a ledger balance/)).toBeTruthy();
        expect(within(screen.getByTestId("section-collected")).getByText("Cheque")).toBeTruthy();
        expect(within(screen.getByTestId("section-collected")).getByText("-4,000.00")).toBeTruthy();
        expect(screen.getByTestId("statement-footer").textContent).toContain("Provisional");
    });

    it("prints the Arabic line label under ar, with LTR amounts", () => {
        renderView("ar");
        const pnl = screen.getByTestId("section-pnl");
        expect(within(pnl).getByText("إيرادات الإيجار")).toBeTruthy();
        expect(within(pnl).queryByText("Rental income")).toBeNull();
        expect(within(pnl).getAllByText("82,191.78")[0].tagName).toBe("BDI");
        expect(within(screen.getByTestId("section-collected")).getByText("شيك")).toBeTruthy();
    });
});
