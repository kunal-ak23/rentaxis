// General Ledger loads on demand (client feedback 2026-09-25).
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const query = { current: "" };
const general = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [{ id: "p1", label: "Belle Vue" }], name: () => "", loading: false }) }));
const accounts = Array.from({ length: 21 }, (_, i) => ({ id: `a${i}`, code: `${1100 + i}`, name: `Acct ${i}`, alias: null, group: false, active: true, accountType: "ASSET", accountSubType: null, propertyId: null }));
vi.mock("@/components/finance/AccountPicker", async orig => {
    const m = await orig<typeof import("@/components/finance/AccountPicker")>();
    return { ...m, loadAccounts: () => Promise.resolve(accounts) };
});
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, accounts: { ...m.ledgerApi.accounts, list: () => Promise.resolve(accounts) }, ledger: { ...m.ledgerApi.ledger, general } } };
});
// Units and renters on the rows are named per id through the bounded /names calls.
const lookup = vi.hoisted(() => ({
    searchUnits: vi.fn(async () => []),
    searchRenters: vi.fn(async () => []),
    unitNames: vi.fn(async (ids: string[]) => ({ rows: ids.map(id => ({ id, unitNumber: `Unit-${id}`, propertyId: null, propertyName: null, propertyType: null, buildingId: null, buildingName: null, status: null })), failedIds: [] })),
    renterNames: vi.fn(async (ids: string[]) => ({ rows: ids.map(id => ({ id, nameEn: `Renter-${id}`, nameAr: `مستأجر-${id}`, phone: null, email: null })), failedIds: [] })),
}));
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));
const csv = vi.hoisted(() => ({ rows: [] as (string | number)[][] }));
vi.mock("@/lib/csv", async orig => {
    const m = await orig<typeof import("@/lib/csv")>();
    return { ...m, downloadCsv: vi.fn(), toCsv: (_h: string[], rows: (string | number)[][]) => { csv.rows = rows; return ""; } };
});
import Page from "../page";
import { defaultLedgerRange } from "@/components/finance/LedgerFilters";

const ledger = { accountId: "a0", accountCode: "1100", accountName: "Acct 0", accountType: "ASSET", openingBalance: -1000,
    rows: [{ entryId: "e1", entryNumber: "JV-1", entryDate: "2026-01-02", docType: "JV", particular: "Rent", narration: "", debit: 400, credit: 0, balance: -600, propertyId: null, unitId: null, leaseId: null, renterId: null, chequeId: null }],
    totalDebit: 400, totalCredit: 0, closingBalance: -600, truncated: false };

async function pick(code: string) {
    const box = screen.getByPlaceholderText("Search account code or name");
    fireEvent.change(box, { target: { value: code } });
    fireEvent.click(await screen.findByRole("button", { name: new RegExp(`^${code}`) }));
}

beforeEach(() => { query.current = ""; general.mockResolvedValue([ledger]); window.history.replaceState(null, "", "/en/dashboard/finance/general-ledger"); });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("General Ledger — on demand", () => {
    it("fetches nothing before an account is picked, and says why", async () => {
        render(<Page />);
        expect(await screen.findByTestId("ledger-pick-prompt")).toBeInTheDocument();
        expect(within(screen.getByTestId("ledger-pick-prompt")).getByRole("link")).toHaveAttribute("href", "/dashboard/finance/trial-balance");
        await new Promise(r => setTimeout(r, 20));
        expect(general).not.toHaveBeenCalled();
    });

    it("defaults to the last 12 months ending today", () => {
        expect(defaultLedgerRange(new Date(2026, 8, 25))).toEqual({ from: "2025-09-26", to: "2026-09-25" });
        expect(defaultLedgerRange(new Date(2026, 1, 28))).toEqual({ from: "2025-03-01", to: "2026-02-28" });
    });

    it("fetches the picked accounts with the default period, and keeps the picks and period in the URL", async () => {
        render(<Page />);
        await pick("1100");
        await pick("1101");
        expect(general).not.toHaveBeenCalled();
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        const range = defaultLedgerRange();
        await waitFor(() => expect(general).toHaveBeenCalledWith(expect.objectContaining({ accountIds: ["a0", "a1"], from: range.from, to: range.to })));
        const url = new URL(window.location.href);
        expect(url.searchParams.get("accountIds")).toBe("a0,a1");
        expect(url.searchParams.get("from")).toBe(range.from);
        expect(url.searchParams.get("to")).toBe(range.to);
        // Brought forward, then the row, then the Sub Total, Dr/Cr suffixed.
        expect(await screen.findByTestId("ledger-bf-a0")).toHaveTextContent("1,000.00 Cr");
        expect(screen.getByTestId("ledger-subtotal-a0")).toHaveTextContent("600.00 Cr");
    });

    it("refuses a 21st account", async () => {
        render(<Page />);
        for (let i = 0; i < 20; i++) await pick(`${1100 + i}`);
        expect(screen.getByTestId("ledger-accounts-picked")).toHaveTextContent("20 of 20 accounts");
        await pick("1120");
        expect(screen.getByTestId("ledger-too-many-accounts")).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(general).toHaveBeenCalled());
        expect(general.mock.calls[0][0].accountIds).toHaveLength(20);
        expect(general.mock.calls[0][0].accountIds).not.toContain("a20");
    });

    it("loads straight away from a bookmarked or drill-down URL", async () => {
        query.current = "accountIds=a3,a4&from=2026-01-01&to=2026-03-31&propertyId=p1";
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalledWith(expect.objectContaining({ accountIds: ["a3", "a4"], from: "2026-01-01", to: "2026-03-31", propertyId: "p1" })));
        expect(screen.queryByTestId("ledger-pick-prompt")).toBeNull();
    });

    it("caps a URL with more than 20 accounts to the first 20 and says so", async () => {
        query.current = `accountIds=${Array.from({ length: 21 }, (_, i) => `a${i}`).join(",")}`;
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalled());
        expect(general.mock.calls[0][0].accountIds).toHaveLength(20);
        expect(screen.getByTestId("ledger-url-capped")).toHaveTextContent("This link named 21 accounts; the first 20 are shown.");
    });

    it("lets a P&L drill-down name every leaf of its cell", async () => {
        query.current = `effectiveProperty=true&propertyId=p1&accountIds=${Array.from({ length: 25 }, (_, i) => `a${i % 21}x${i}`).join(",")}`;
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalled());
        expect(general.mock.calls[0][0].accountIds).toHaveLength(25);
        expect(screen.queryByTestId("ledger-url-capped")).toBeNull();
    });

    it("exports the CSV in the table's order (account code), with the table's Sub Totals (#104)", async () => {
        query.current = "accountIds=a0,a1";
        const second = { ...ledger, accountId: "a1", accountCode: "1050", accountName: "Acct 1", openingBalance: 0,
            rows: [{ ...ledger.rows[0], entryId: "e2", debit: 50, balance: 50 }], totalDebit: 999, closingBalance: 999 };
        general.mockResolvedValue([ledger, second]);
        render(<Page />);
        await screen.findByTestId("ledger-bf-a1");
        fireEvent.click(screen.getByTestId("ledger-export-csv"));
        const codes = csv.rows.map(r => r[0]);
        expect(codes.indexOf("1050")).toBeLessThan(codes.indexOf("1100"));
        const sub1050 = csv.rows.find(r => r[0] === "1050" && r[4] === "Sub Total")!;
        expect(sub1050[5]).toBe("50.00");
    });

    it("names the rows' unit and tenant in the table and the CSV from the ids on screen only", async () => {
        query.current = "accountIds=a0";
        const named = { ...ledger, rows: [{ ...ledger.rows[0], unitId: "u7", renterId: "r7" }] };
        general.mockResolvedValue([named]);
        render(<Page />);
        await waitFor(() => expect(screen.getByText("Unit-u7")).toBeInTheDocument());
        // The EN Tenant cell is the plain English name, as the ledger has always printed it — not the picker's "English (Arabic)".
        await waitFor(() => expect(screen.getByText("Renter-r7")).toBeInTheDocument());
        expect(screen.getByText("Renter-r7").tagName).toBe("TD");
        expect(screen.getByText("Renter-r7").textContent).toBe("Renter-r7");
        expect(lookup.unitNames).toHaveBeenCalledTimes(1);
        expect(lookup.unitNames).toHaveBeenCalledWith(["u7"]);
        expect(lookup.renterNames).toHaveBeenCalledWith(["r7"]);
        fireEvent.click(screen.getByTestId("ledger-export-csv"));
        const row = csv.rows.find(r => r[3] === "JV-1")!;
        expect(row[8]).toBe("Unit-u7");
        expect(row[10]).toBe("Renter-r7");
    });
});
