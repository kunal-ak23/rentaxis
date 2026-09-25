// Tenant Ledger: pick a tenant first, last 12 months by default, picks in the URL.
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const query = { current: "" };
const renter = vi.hoisted(() => vi.fn());
const general = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN" } } }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [{ id: "r1", label: "Samira" }, { id: "r2", label: "Omar" }], name: (id: string | null) => (id === "r1" ? "Samira" : ""), loading: false }) }));
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, ledger: { ...m.ledgerApi.ledger, renter, general } } };
});
import Page from "../page";
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
        fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "r1" } });
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        const range = defaultLedgerRange();
        await waitFor(() => expect(renter).toHaveBeenCalledWith("r1", { from: range.from, to: range.to }));
        const url = new URL(window.location.href);
        expect(Object.fromEntries(url.searchParams)).toEqual({ renterId: "r1", from: range.from, to: range.to });
    });

    it("reads one contract through the general ledger filtered on tenant and contract, so brought forward is that contract's", async () => {
        query.current = "renterId=r1&leaseId=l1&from=2026-01-01&to=2026-06-30";
        general.mockResolvedValue([{ accountId: "a", accountCode: "1200", accountName: "Rent Receivable", accountType: "ASSET", openingBalance: 4500,
            rows: [{ entryId: "e", entryNumber: "CIL-1", entryDate: "2026-02-01", docType: "CIL", particular: "Rent", narration: "", debit: 1000, credit: 0, balance: 5500,
                propertyId: null, unitId: null, leaseId: "l1", renterId: "r1", chequeId: null }],
            totalDebit: 1000, totalCredit: 0, closingBalance: 5500, truncated: false }]);
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalledWith({ renterId: "r1", leaseId: "l1", from: "2026-01-01", to: "2026-06-30" }));
        expect(renter).not.toHaveBeenCalled();
        expect(await screen.findByTestId("ledger-bf-a")).toHaveTextContent("4,500.00 Dr");
        expect(screen.getByTestId("ledger-subtotal-a")).toHaveTextContent("5,500.00 Dr");
    });

    it("drops the contract narrowing when another tenant is picked", async () => {
        query.current = "renterId=r1&leaseId=l1";
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalledTimes(1));
        fireEvent.change(screen.getByLabelText("Tenant"), { target: { value: "r2" } });
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(renter).toHaveBeenLastCalledWith("r2", expect.anything()));
        expect(new URL(window.location.href).searchParams.get("leaseId")).toBeNull();
    });
});
