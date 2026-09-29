// The General Ledger's Unit filter: scoped to the chosen property, reflected
// in the URL as ?unitId=, sent to the API, and dropped like a malformed
// propertyId when it isn't a real UUID (see on-demand.test.tsx).
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

const UNIT_ID = "22222222-2222-4222-8222-222222222222";
const PROPERTY_ID = "11111111-1111-4111-8111-111111111111";

const ledger = { accountId: "a0", accountCode: "1100", accountName: "Acct 0", accountType: "ASSET", openingBalance: 0,
    rows: [], totalDebit: 0, totalCredit: 0, closingBalance: 0, truncated: false };

beforeEach(() => { query.current = ""; general.mockResolvedValue([ledger]); window.history.replaceState(null, "", "/en/dashboard/finance/general-ledger"); });
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("General Ledger — Unit filter", () => {
    it("sends unitId to the API once a unit is chosen and applied", async () => {
        query.current = `accountIds=a0&propertyId=${PROPERTY_ID}`;
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalled());
        fireEvent.click(screen.getByTestId("ledger-unit-filter"));
        fireEvent.click(await screen.findByText("101"));
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(general).toHaveBeenLastCalledWith(expect.objectContaining({ unitId: UNIT_ID })));
        expect(new URL(window.location.href).searchParams.get("unitId")).toBe(UNIT_ID);
    });

    it("drops a malformed unitId from the query it sends and from the URL", async () => {
        query.current = `accountIds=a0&propertyId=${PROPERTY_ID}&unitId=not-a-uuid`;
        window.history.replaceState(null, "", `/en/dashboard/finance/general-ledger?${query.current}`);
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalled());
        expect(general.mock.calls[0][0].unitId).toBeUndefined();
        await waitFor(() => expect(new URLSearchParams(window.location.search).has("unitId")).toBe(false));
    });

    it("loads straight away with a bookmarked propertyId + unitId (unit page's link)", async () => {
        query.current = `accountIds=a0&propertyId=${PROPERTY_ID}&unitId=${UNIT_ID}`;
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalledWith(expect.objectContaining({ propertyId: PROPERTY_ID, unitId: UNIT_ID })));
    });

    it("disables the unit picker until a property is picked", async () => {
        query.current = "accountIds=a0";
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalled());
        expect(screen.getByTestId("ledger-unit-filter")).toBeDisabled();
    });

    it("clears the unit filter when the property is cleared", async () => {
        query.current = `accountIds=a0&propertyId=${PROPERTY_ID}&unitId=${UNIT_ID}`;
        render(<Page />);
        await waitFor(() => expect(general).toHaveBeenCalled());
        const propertySelect = screen.getByLabelText("Property");
        fireEvent.change(propertySelect, { target: { value: "" } });
        expect(screen.getByTestId("ledger-unit-filter")).toBeDisabled();
        fireEvent.click(screen.getByRole("button", { name: "Apply" }));
        await waitFor(() => expect(general).toHaveBeenLastCalledWith(expect.objectContaining({ unitId: undefined, propertyId: undefined })));
    });
});
