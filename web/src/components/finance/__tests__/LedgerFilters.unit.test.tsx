import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("@/components/finance/useNameLookup", () => ({
    useNameLookup: () => ({ options: [{ id: "p1", label: "Belle Vue" }, { id: "p2", label: "Marina" }], name: () => "", loading: false }),
}));
const accounts = [
    { id: "a", code: "1101", name: "Belle Vue bank", alias: null, group: false, active: true, accountType: "ASSET", accountSubType: null, propertyId: "p1" },
];
vi.mock("@/components/finance/AccountPicker", async orig => {
    const m = await orig<typeof import("@/components/finance/AccountPicker")>();
    return { ...m, default: () => <div data-testid="account-picker" />, loadAccounts: () => Promise.resolve(accounts) };
});
vi.mock("@/lib/api/lookup", () => ({
    lookupApi: {
        searchUnits: vi.fn(async () => []),
        searchRenters: vi.fn(async () => []),
        unitNames: vi.fn(async (ids: string[]) => ({ rows: ids.map(id => ({ id, unitNumber: `Unit-${id}`, propertyId: null, propertyName: null, propertyType: null, buildingId: null, buildingName: null, status: null })), failedIds: [] })),
        renterNames: vi.fn(async () => ({ rows: [], failedIds: [] })),
    },
}));
import { lookupApi } from "@/lib/api/lookup";
import LedgerFilters from "../LedgerFilters";

const api = vi.mocked(lookupApi);

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("General Ledger filters — unit scope", () => {
    it("disables the unit picker until a property is chosen", () => {
        render(<LedgerFilters value={{ from: "2026-01-01", to: "2026-06-30" }} onChange={vi.fn()}
            onApply={() => {}} showProperty showUnit />);
        expect(screen.getByTestId("ledger-unit-filter")).toBeDisabled();
    });

    it("scopes the unit search to the selected property once one is picked", async () => {
        render(<LedgerFilters value={{ from: "2026-01-01", to: "2026-06-30", propertyId: "p1" }} onChange={vi.fn()}
            onApply={() => {}} showProperty showUnit />);
        const trigger = screen.getByTestId("ledger-unit-filter");
        expect(trigger).not.toBeDisabled();
        fireEvent.click(trigger);
        expect(api.searchUnits).toHaveBeenCalledWith(expect.objectContaining({ propertyId: "p1" }));
    });

    it("clears an incompatible unit when the property changes", () => {
        const onChange = vi.fn();
        render(<LedgerFilters value={{ from: "2026-01-01", to: "2026-06-30", propertyId: "p1", unitId: "u1" }} onChange={onChange}
            onApply={() => {}} showProperty showUnit />);
        const select = screen.getByLabelText("Property");
        fireEvent.change(select, { target: { value: "p2" } });
        expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ propertyId: "p2", unitId: undefined }));
    });

    it("clears the unit when the property is cleared entirely", () => {
        const onChange = vi.fn();
        render(<LedgerFilters value={{ from: "2026-01-01", to: "2026-06-30", propertyId: "p1", unitId: "u1" }} onChange={onChange}
            onApply={() => {}} showProperty showUnit />);
        const select = screen.getByLabelText("Property");
        fireEvent.change(select, { target: { value: "" } });
        expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ propertyId: undefined, unitId: undefined }));
    });

    it("sends the picked unit id up through onChange", async () => {
        api.searchUnits.mockResolvedValue([
            { id: "u1", unitNumber: "101", propertyId: "p1", propertyName: "Belle Vue", propertyType: "RESIDENTIAL", buildingId: null, buildingName: null, status: "VACANT" },
        ]);
        const onChange = vi.fn();
        render(<LedgerFilters value={{ from: "2026-01-01", to: "2026-06-30", propertyId: "p1" }} onChange={onChange}
            onApply={() => {}} showProperty showUnit />);
        fireEvent.click(screen.getByTestId("ledger-unit-filter"));
        fireEvent.click(await screen.findByText("101"));
        expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ unitId: "u1" }));
    });
});
