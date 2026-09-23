import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { TrialBalanceRow } from "@/lib/api/ledger";

/**
 * Gap #68: under /ar the trial balance laid out RTL but printed every account
 * name in English. A row carries `nameAr` when the account has one; the Arabic
 * locale shows it and falls back to the English name when it is missing.
 */

const locale = vi.hoisted(() => ({ current: "ar" }));

vi.mock("next-intl", () => ({
    useTranslations: () => (key: string) => key,
    useLocale: () => locale.current,
}));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ name: () => "", options: [], loading: false }),
}));

const rows: TrialBalanceRow[] = [
    {
        accountId: "a1", code: "100001", name: "Rent Receivable - Miftah Residences",
        nameAr: "إيجارات مستحقة - مفتاح ريزيدنسز", accountType: "ASSET",
        parentId: null, propertyId: null, debit: 1000, credit: 0, balance: 1000,
    },
    {
        accountId: "a2", code: "100002", name: "Rental Income - Miftah Residences",
        nameAr: null, accountType: "INCOME",
        parentId: null, propertyId: null, debit: 0, credit: 1000, balance: -1000,
    },
];

vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, trialBalance: vi.fn(async () => rows) } };
});

import TrialBalancePage from "../page";

afterEach(() => {
    cleanup();
    locale.current = "ar";
});

describe("TrialBalancePage account names", () => {
    it("shows the Arabic name under ar and falls back to English when there is none", async () => {
        render(<TrialBalancePage />);
        expect(await screen.findByText("إيجارات مستحقة - مفتاح ريزيدنسز")).toBeTruthy();
        expect(screen.queryByText("Rent Receivable - Miftah Residences")).toBeNull();
        expect(screen.getByText("Rental Income - Miftah Residences")).toBeTruthy();
    });

    it("keeps the English name under en", async () => {
        locale.current = "en";
        render(<TrialBalancePage />);
        expect(await screen.findByText("Rent Receivable - Miftah Residences")).toBeTruthy();
        expect(screen.queryByText("إيجارات مستحقة - مفتاح ريزيدنسز")).toBeNull();
    });
});
