// Tenant Ledger: pick a tenant first, last 12 months by default, picks in the URL.
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const query = { current: "" };
const renter = vi.hoisted(() => vi.fn());
const general = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [], name: () => "", loading: false }) }));
// Scale P1-6: the tenant filter is a server-searched RenterPicker and the sub-band names the
// renter through the bounded /renters/names call — never the whole renters table.
const lookup = vi.hoisted(() => {
    const people = [
        { id: "r1", nameEn: "Samira", nameAr: null, phone: null, email: "samira@example.com" },
        { id: "r2", nameEn: "Omar", nameAr: null, phone: null, email: null },
    ];
    // Reached only through a link, never through a search, so the cache has not seen her.
    const linked = { id: "r3", nameEn: "Layla", nameAr: null, phone: null, email: null };
    return {
        searchUnits: vi.fn(async () => []),
        unitNames: vi.fn(async () => []),
        searchRenters: vi.fn(async () => people),
        renterNames: vi.fn(async (ids: string[]) => [...people, linked].filter(p => ids.includes(p.id))),
    };
});
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, ledger: { ...m.ledgerApi.ledger, renter, general } } };
});
import Page from "../page";

async function pickTenant(name: string) {
    fireEvent.click(screen.getByLabelText("Tenant"));
    fireEvent.click(await screen.findByText(name));
}
import { defaultLedgerRange } from "@/components/finance/LedgerFilters";

beforeEach(() => { query.current = ""; renter.mockResolvedValue([]); general.mockResolvedValue([]); window.history.replaceState(null, "", "/en/dashboard/finance/tenant-ledger"); });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("Tenant Ledger — on demand", () => {
    it("asks for a tenant first and fetches nothing", async () => {
        render(<Page />);
        expect(await screen.findByText("Pick a tenant to see their ledger")).toBeInTheDocument();
        expect(renter).not.toHaveBeenCalled();
    });

    it("fetches the picked tenant over the last 12 months and keeps the pick in the URL", async () => {
        render(<Page />);
        await pickTenant("Samira");
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        const range = defaultLedgerRange();
        await waitFor(() => expect(renter).toHaveBeenCalledWith("r1", { from: range.from, to: range.to }));
        const url = new URL(window.location.href);
        expect(Object.fromEntries(url.searchParams)).toEqual({ renterId: "r1", from: range.from, to: range.to });
        expect(lookup.searchRenters).toHaveBeenCalled();
    });

    it("names the linked tenant in the sub-band with one names call for that id", async () => {
        query.current = "renterId=r3";
        const row = { entryId: "e", entryNumber: "CIL-1", entryDate: "2026-02-01", docType: "CIL", particular: "Rent", narration: "", debit: 1000, credit: 0, balance: 1000,
            propertyId: null, unitId: null, leaseId: null, renterId: null, chequeId: null }; // the row names no one: the band must
        renter.mockResolvedValue([{ accountId: "a", accountCode: "1200", accountName: "Rent Receivable", accountType: "ASSET", openingBalance: 0,
            rows: [row], totalDebit: 1000, totalCredit: 0, closingBalance: 1000, truncated: false }]);
        // The name lands after the report has rendered: only the page's own lookup re-renders the band.
        let answer: () => void = () => {};
        lookup.renterNames.mockImplementationOnce((ids: string[]) => new Promise(res => { answer = () => res(ids.includes("r3") ? [{ id: "r3", nameEn: "Layla", nameAr: null, phone: null, email: null }] : []); }));
        render(<Page />);
        await waitFor(() => expect(screen.getByTestId("ledger-subtotal-a")).toBeInTheDocument());
        expect(screen.queryByText(/Tenant Name :/)).toBeNull();
        await act(async () => answer());
        // waitFor, not findBy: the rows re-render as names arrive, so a found node can detach.
        await waitFor(() => expect(screen.getByText("Tenant Name : Layla")).toBeInTheDocument());
        expect(screen.getByTestId("ledger-renter-filter")).toHaveTextContent("Layla");
        // The sub-band, the table and the picker all ask for r3; the shared cache sends it once.
        expect(lookup.renterNames).toHaveBeenCalledTimes(1);
        expect(lookup.renterNames).toHaveBeenCalledWith(["r3"]);
        expect(lookup.searchRenters).not.toHaveBeenCalled();
    });

    it("reads one contract through the general ledger filtered on tenant and contract, so brought forward is that contract's", async () => {
        query.current = "renterId=r1&leaseId=l1&from=2026-01-01&to=2026-06-30";
        // The renter ledger only says which accounts the tenant carries (renter-wide figures, never shown here).
        renter.mockResolvedValue([
            { accountId: "a", openingBalance: 9999, rows: [] },
            { accountId: "dep", openingBalance: -8000, rows: [] },
            { accountId: "other", openingBalance: 100, rows: [] },
        ]);
        const row = { entryId: "e", entryNumber: "CIL-1", entryDate: "2026-02-01", docType: "CIL", particular: "Rent", narration: "", debit: 1000, credit: 0, balance: 5500,
            propertyId: null, unitId: null, leaseId: "l1", renterId: "r1", chequeId: null };
        general.mockResolvedValue([
            { accountId: "a", accountCode: "1200", accountName: "Rent Receivable", accountType: "ASSET", openingBalance: 4500,
              rows: [row], totalDebit: 1000, totalCredit: 0, closingBalance: 5500, truncated: false },
            // #104: a deposit held — brought forward, no movement in the window — stays on the statement.
            { accountId: "dep", accountCode: "2300", accountName: "Security Deposit", accountType: "LIABILITY", openingBalance: -5000,
              rows: [], totalDebit: 0, totalCredit: 0, closingBalance: -5000, truncated: false },
            // Another contract's account: nothing for this one, so it is left out.
            { accountId: "other", accountCode: "1300", accountName: "Other", accountType: "ASSET", openingBalance: 0,
              rows: [], totalDebit: 0, totalCredit: 0, closingBalance: 0, truncated: false },
        ]);
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalledWith({ accountIds: ["a", "dep", "other"], renterId: "r1", leaseId: "l1", from: "2026-01-01", to: "2026-06-30" }));
        expect(renter).toHaveBeenCalledWith("r1", { from: "2026-01-01", to: "2026-06-30" });
        expect(await screen.findByTestId("ledger-bf-a")).toHaveTextContent("4,500.00 Dr");
        expect(screen.getByTestId("ledger-subtotal-a")).toHaveTextContent("5,500.00 Dr");
        expect(screen.getByTestId("ledger-bf-dep")).toHaveTextContent("5,000.00 Cr");
        expect(screen.queryByTestId("ledger-bf-other")).toBeNull();
    });

    it("drops the contract narrowing when another tenant is picked", async () => {
        query.current = "renterId=r1&leaseId=l1";
        renter.mockResolvedValueOnce([{ accountId: "a", openingBalance: 0, rows: [] }]);
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalledTimes(1));
        await pickTenant("Omar");
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(renter).toHaveBeenLastCalledWith("r2", expect.anything()));
        expect(new URL(window.location.href).searchParams.get("leaseId")).toBeNull();
    });
});
