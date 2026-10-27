// Tenant Ledger filter bar: an account chip reads in the screen's language, the
// party-ledger pick (any other account) as well as a receivable one.
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Account } from "@/lib/api/ledger";
import type { ReceivableAccount } from "@/lib/api/reports";

const locale = vi.hoisted(() => ({ current: "ar" }));
const tr = vi.hoisted(() => (key: string, args?: Record<string, string>) => (args?.name ? `${key}: ${args.name}` : key));
vi.mock("next-intl", () => ({ useTranslations: () => tr, useLocale: () => locale.current }));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [], name: () => "", loading: false }) }));
vi.mock("@/components/pickers/RenterPicker", () => ({ RenterPicker: () => null }));
vi.mock("@/components/pickers/LeasePicker", () => ({ LeasePicker: () => null }));

const RECEIVABLE: ReceivableAccount[] = [
    { id: "a1", code: "1200", name: "Rent Receivable - Sample Tower", nameAr: "ذمم الإيجار - برج العينة", propertyId: "p1", propertyName: "Sample Tower" },
];
const account = (id: string, code: string, name: string, nameAr: string | null): Account => ({
    id, code, name, nameEn: name, nameAr, alias: null, accountType: "LIABILITY", accountSubType: null, parentId: null,
    propertyId: null, system: false, group: false, active: true, displayOrder: 0, description: null,
});
const OTHERS: Account[] = [
    account("o1", "2100", "Sample Supplier Payable", "ذمم المورد الدائنة"),
    account("o2", "2200", "Sample Deposit Held", null),
];
vi.mock("@/lib/api/reports", async orig => {
    const m = await orig<typeof import("@/lib/api/reports")>();
    return { ...m, reportsApi: { ...m.reportsApi, tenantLedger: { ...m.reportsApi.tenantLedger, accounts: vi.fn(async () => RECEIVABLE) } } };
});
vi.mock("@/components/finance/AccountPicker", () => ({ default: () => null, loadAccounts: async () => OTHERS }));

import TenantLedgerFilters from "../_components/TenantLedgerFilters";

afterEach(() => { cleanup(); locale.current = "ar"; });

const chips = () => [...screen.getByTestId("tl-account-chips").querySelectorAll("bdi")].map(b => b.textContent);

function renderBar() {
    render(<TenantLedgerFilters value={{ from: "2026-01-01", to: "2026-12-31", accountIds: ["a1", "o1", "o2"] }} onChange={() => {}} onApply={() => {}} />);
}

describe("Tenant Ledger filters — account chip names", () => {
    it("shows the Arabic name under ar, for a party-ledger account as for a receivable one", async () => {
        renderBar();
        // An Arabic name where the account has one, the English name where it has none.
        await waitFor(() => expect(chips()).toEqual(["1200 — ذمم الإيجار - برج العينة", "2100 — ذمم المورد الدائنة", "2200 — Sample Deposit Held"]));
        // The remove button names the chip as it reads.
        expect(screen.getByRole("button", { name: "tlRemoveAccount: 2100 — ذمم المورد الدائنة" })).toBeInTheDocument();
    });

    it("keeps the English names under en", async () => {
        locale.current = "en";
        renderBar();
        await waitFor(() => expect(chips()).toEqual(["1200 — Rent Receivable - Sample Tower", "2100 — Sample Supplier Payable", "2200 — Sample Deposit Held"]));
    });
});
