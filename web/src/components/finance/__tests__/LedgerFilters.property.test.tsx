import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ options: [{ id: "p1", label: "Belle Vue" }, { id: "p2", label: "Marina" }], name: () => "", loading: false }),
}));
const accounts = [
    { id: "a", code: "1101", name: "Belle Vue bank", alias: null, group: false, active: true, accountType: "ASSET", accountSubType: null, propertyId: "p1" },
    { id: "b", code: "1102", name: "Marina bank", alias: null, group: false, active: true, accountType: "ASSET", accountSubType: null, propertyId: "p2" },
    { id: "c", code: "4000", name: "Org income", alias: null, group: false, active: true, accountType: "INCOME", accountSubType: null, propertyId: null },
];
vi.mock("@/components/finance/AccountPicker", async orig => {
    const m = await orig<typeof import("@/components/finance/AccountPicker")>();
    return { ...m, default: () => <div data-testid="account-picker" />, loadAccounts: () => Promise.resolve(accounts) };
});
import LedgerFilters from "../LedgerFilters";

afterEach(cleanup);

describe("General Ledger filters — property scope (#104)", () => {
    it("drops picks that belong to another property's accounts when the property changes", async () => {
        const onChange = vi.fn();
        render(<LedgerFilters value={{ accountIds: ["a", "b", "c"], from: "2026-01-01", to: "2026-06-30" }} onChange={onChange}
            onApply={() => {}} showAccounts showProperty maxAccounts={20} />);
        await waitFor(() => expect(screen.getByText(/1102/)).toBeInTheDocument());
        const select = screen.getAllByRole("combobox").find(s => within(s).includes("Marina"))!;
        fireEvent.change(select, { target: { value: "p2" } });
        expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ propertyId: "p2", accountIds: ["b", "c"] }));
        fireEvent.change(select, { target: { value: "" } });
        expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ propertyId: undefined, accountIds: ["a", "b", "c"] }));
    });
});

function within(select: HTMLElement) {
    return Array.from(select.querySelectorAll("option")).map(o => o.textContent ?? "");
}
