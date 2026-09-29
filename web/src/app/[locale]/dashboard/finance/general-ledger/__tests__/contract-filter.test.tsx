// Demo feedback 2026-09-29: the contract grid's Ledger link opens the General
// Ledger at ?accountIds=<account>&leaseId=<contract>. The contract filter is sent
// to the API, said on the page, kept across Apply, and can be cleared.
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const query = { current: "" };
const general = vi.hoisted(() => vi.fn());
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(query.current) }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "ACCOUNTANT" } } }) }));
vi.mock("@/i18n/routing", () => ({ Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a> }));
vi.mock("@/components/finance/useNameLookup", () => ({ useNameLookup: () => ({ options: [{ id: "11111111-1111-4111-8111-111111111111", label: "Belle Vue" }], name: () => "", loading: false }) }));
const accounts = [{ id: "a0", code: "1100", name: "Acct 0", alias: null, group: false, active: true, accountType: "ASSET", accountSubType: null, propertyId: null }];
vi.mock("@/components/finance/AccountPicker", async orig => {
    const m = await orig<typeof import("@/components/finance/AccountPicker")>();
    return { ...m, loadAccounts: () => Promise.resolve(accounts) };
});
vi.mock("@/lib/api/ledger", async orig => {
    const m = await orig<typeof import("@/lib/api/ledger")>();
    return { ...m, ledgerApi: { ...m.ledgerApi, accounts: { ...m.ledgerApi.accounts, list: () => Promise.resolve(accounts) }, ledger: { ...m.ledgerApi.ledger, general } } };
});
const lookup = vi.hoisted(() => ({
    searchUnits: vi.fn(async () => [
        { id: "22222222-2222-4222-8222-222222222222", unitNumber: "101", propertyId: "11111111-1111-4111-8111-111111111111", propertyName: "Belle Vue", propertyType: "RESIDENTIAL", buildingId: null, buildingName: null, status: "VACANT" },
    ]),
    searchRenters: vi.fn(async () => []),
    unitNames: vi.fn(async (ids: string[]) => ({ rows: ids.map(id => ({ id, unitNumber: `Unit-${id}`, propertyId: null, propertyName: null, propertyType: null, buildingId: null, buildingName: null, status: null })), failedIds: [] })),
    renterNames: vi.fn(async () => ({ rows: [], failedIds: [] })),
}));
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));
vi.mock("@/lib/csv", async orig => {
    const m = await orig<typeof import("@/lib/csv")>();
    return { ...m, downloadCsv: vi.fn(), toCsv: m.toCsv };
});
import Page from "../page";

const LEASE_ID = "33333333-3333-4333-8333-333333333333";

const ledger = { accountId: "a0", accountCode: "1100", accountName: "Acct 0", accountType: "ASSET", openingBalance: 0,
    rows: [], totalDebit: 0, totalCredit: 0, closingBalance: 0, truncated: false };

beforeEach(() => { query.current = ""; general.mockResolvedValue([ledger]); window.history.replaceState(null, "", "/en/dashboard/finance/general-ledger"); });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("General Ledger — contract filter", () => {
    it("loads the account narrowed to the contract named in the URL, and says so", async () => {
        query.current = `accountIds=a0&leaseId=${LEASE_ID}&from=2026-01-05&to=2027-01-31`;
        window.history.replaceState(null, "", `/en/dashboard/finance/general-ledger?${query.current}`);
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalledWith(expect.objectContaining({
            accountIds: ["a0"], leaseId: LEASE_ID, from: "2026-01-05", to: "2027-01-31",
        })));
        expect(screen.getByTestId("ledger-contract-filter")).toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(new URL(window.location.href).searchParams.get("leaseId")).toBe(LEASE_ID));
    });

    it("clears the contract filter", async () => {
        query.current = `accountIds=a0&leaseId=${LEASE_ID}`;
        window.history.replaceState(null, "", `/en/dashboard/finance/general-ledger?${query.current}`);
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalled());
        fireEvent.click(screen.getByTestId("ledger-contract-filter-clear"));
        await waitFor(() => expect(general).toHaveBeenLastCalledWith(expect.objectContaining({ leaseId: undefined })));
        expect(screen.queryByTestId("ledger-contract-filter")).not.toBeInTheDocument();
        expect(new URL(window.location.href).searchParams.has("leaseId")).toBe(false);
    });

    it("drops a malformed leaseId", async () => {
        query.current = "accountIds=a0&leaseId=not-a-uuid";
        window.history.replaceState(null, "", `/en/dashboard/finance/general-ledger?${query.current}`);
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalled());
        expect(general.mock.calls[0][0].leaseId).toBeUndefined();
        expect(screen.queryByTestId("ledger-contract-filter")).not.toBeInTheDocument();
    });
});
