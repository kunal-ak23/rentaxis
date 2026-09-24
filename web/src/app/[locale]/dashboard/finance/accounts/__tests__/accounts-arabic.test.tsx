import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import ar from "../../../../../../../messages/ar.json";
import { leftoverLatinWords, visibleText } from "@/test/latinText";

/**
 * #81: the chart of accounts showed its type labels (ASSET, LIABILITY…), its
 * sub-types, the Group/System badges, the account counts and the form
 * placeholders in English under /ar.
 */

import AccountsPage from "../page";

const account = (over: Record<string, unknown>) => ({
    id: "x", code: "X", name: "X", nameEn: "X", nameAr: null, alias: null,
    accountType: "ASSET", accountSubType: null, parentId: null, propertyId: null,
    description: null, system: false, group: false, active: true, displayOrder: 1, ...over,
});

const CHART = [
    account({ id: "g-a", code: "A", nameEn: "Assets", nameAr: "الأصول", group: true, system: true }),
    account({ id: "a-1", code: "A-01", nameEn: "PDC Receivable", nameAr: "شيكات آجلة", parentId: "g-a", accountSubType: "PDC_RECEIVABLE" }),
    account({ id: "g-l", code: "B", nameEn: "Liabilities", nameAr: "الخصوم", accountType: "LIABILITY", group: true, system: true }),
    account({ id: "l-1", code: "B-01", nameEn: "Security Deposits", nameAr: "تأمينات", accountType: "LIABILITY", parentId: "g-l", accountSubType: "DEPOSIT_HELD" }),
    account({ id: "i-1", code: "C-01", nameEn: "Rent", nameAr: "الإيجار", accountType: "INCOME", accountSubType: "RENTAL_INCOME" }),
    account({ id: "e-1", code: "D-01", nameEn: "Maintenance", nameAr: "الصيانة", accountType: "EXPENSE", accountSubType: "DIRECT_EXPENSE" }),
    account({ id: "q-1", code: "F-01", nameEn: "Capital", nameAr: "رأس المال", accountType: "EQUITY", accountSubType: "CAPITAL" }),
];
// Account codes and the example code in the placeholder are data.
const DATA = ["A-01", "B-01", "C-01", "D-01", "F-01"];

beforeEach(() => {
    global.fetch = vi.fn(async (input: RequestInfo | URL) => {
        const body = String(input).includes("/v1/finance/accounts") ? CHART : [];
        return { ok: true, json: async () => body, text: async () => JSON.stringify(body) } as unknown as Response;
    }) as unknown as typeof fetch;
});

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const renderAr = () =>
    render(<NextIntlClientProvider locale="ar" messages={ar}><AccountsPage /></NextIntlClientProvider>);

describe("chart of accounts in Arabic", () => {
    it("tree view: types, sub-types, badges and counts carry no English", async () => {
        const { container } = renderAr();
        await screen.findByText("شيكات آجلة");

        expect(screen.getAllByText(ar.Finance.accountSubTypes.PDC_RECEIVABLE).length).toBeGreaterThan(0);
        expect(screen.getAllByText(ar.Finance.systemBadge).length).toBe(2);
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("flat view: the type headings are Arabic", async () => {
        const { container } = renderAr();
        await screen.findByText("شيكات آجلة");
        fireEvent.click(screen.getByText(ar.Finance.flatView));

        for (const type of ["ASSET", "LIABILITY", "INCOME", "EXPENSE", "EQUITY"] as const) {
            expect(screen.getAllByText(ar.Ledger.accountTypes[type]).length).toBeGreaterThan(0);
        }
        expect(leftoverLatinWords(visibleText(container), DATA)).toEqual([]);
    });

    it("the add-account form carries no English", async () => {
        const { container } = renderAr();
        await screen.findByText("شيكات آجلة");
        fireEvent.click(screen.getAllByText(ar.Finance.addAccount)[0]);

        expect(screen.getByText(ar.Finance.addAccountDesc)).toBeInTheDocument();
        expect(leftoverLatinWords(visibleText(container), [...DATA, "A-01"])).toEqual([]);
    });
});
