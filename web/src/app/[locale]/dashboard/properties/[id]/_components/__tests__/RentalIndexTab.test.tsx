import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../../../messages/en.json";

/**
 * The property page's "DLD rental index" tab: lists the ranges per unit type and
 * year, adds one, and imports a CSV sheet — rows the server refuses are listed
 * by row, nothing is saved, and a clean sheet is posted as parsed.
 */

const api = vi.hoisted(() => ({
    fetchRentalIndex: vi.fn(),
    upsertRentalIndexRange: vi.fn(),
    importRentalIndex: vi.fn(),
    deleteRentalIndexRange: vi.fn(),
    setUnitDewaPremiseNumber: vi.fn(),
}));
vi.mock("@/lib/api/rentalIndex", async (orig) => {
    const real = await orig<typeof import("@/lib/api/rentalIndex")>();
    return { ...real, ...api };
});

import { RentalIndexTab } from "../RentalIndexTab";

const RANGE = {
    id: "r1", propertyId: "p1", unitType: "BHK2", indexYear: 2027, minValue: 80522, maxValue: 98416,
    sourceNote: "DLD 11/11/2026", enteredBy: "u1", enteredAt: "2026-11-11T09:30:00Z",
};
const UNITS = [
    { id: "u1", unitNumber: "101", type: "BHK2", dewaPremiseNumber: "123456789" },
    { id: "u2", unitNumber: "102", type: "BHK1", dewaPremiseNumber: null },
];

function renderTab(rows: unknown[] = [RANGE], canManage = true) {
    api.fetchRentalIndex.mockResolvedValue(rows);
    api.upsertRentalIndexRange.mockResolvedValue(RANGE);
    api.setUnitDewaPremiseNumber.mockResolvedValue({ id: "u2", dewaPremiseNumber: "987654321" });
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RentalIndexTab propertyId="p1" units={UNITS} canManage={canManage} />
        </NextIntlClientProvider>,
    );
}

afterEach(() => { cleanup(); vi.resetAllMocks(); });

describe("RentalIndexTab", () => {
    it("lists the ranges with their midpoint and the units' DEWA numbers", async () => {
        renderTab();
        const row = await screen.findByTestId("rental-index-row");
        expect(row).toHaveTextContent("2 BHK");
        expect(row).toHaveTextContent("2027");
        expect(row).toHaveTextContent("80,522.00");
        expect(row).toHaveTextContent("98,416.00");
        expect(row).toHaveTextContent("89,469.00");
        expect(row).toHaveTextContent("DLD 11/11/2026");
        const units = screen.getByTestId("rental-index-units");
        expect(units).toHaveTextContent("123456789");
        expect(units).toHaveTextContent("Not entered");
        expect(screen.getByRole("link", { name: /Open the DLD calculator/ }))
            .toHaveAttribute("href", "https://dubailand.gov.ae/en/eservices/rental-index/");
    });

    it("says so when nothing has been entered, and hides the edit controls from a read-only role", async () => {
        renderTab([], false);
        expect(await screen.findByText(en.RentalIndex.empty)).toBeInTheDocument();
        expect(screen.queryByTestId("rental-index-add")).toBeNull();
        expect(screen.queryByTestId("rental-index-import-toggle")).toBeNull();
    });

    it("adds a range through the form", async () => {
        renderTab([]);
        fireEvent.click(await screen.findByTestId("rental-index-add"));
        fireEvent.change(screen.getByLabelText(en.RentalIndex.unitType), { target: { value: "BHK1" } });
        fireEvent.change(screen.getByLabelText(en.RentalIndex.year), { target: { value: "2027" } });
        fireEvent.change(screen.getByLabelText(en.RentalIndex.minValue), { target: { value: "59054" } });
        fireEvent.change(screen.getByLabelText(en.RentalIndex.maxValue), { target: { value: "72176" } });
        fireEvent.change(screen.getByLabelText(en.RentalIndex.sourceNote), { target: { value: "DLD" } });
        fireEvent.click(screen.getByTestId("rental-index-save"));
        await waitFor(() => expect(api.upsertRentalIndexRange).toHaveBeenCalledWith("p1",
            { unitType: "BHK1", indexYear: 2027, minValue: 59054, maxValue: 72176, sourceNote: "DLD" }));
    });

    it("refuses an inverted range before asking the server", async () => {
        renderTab([]);
        fireEvent.click(await screen.findByTestId("rental-index-add"));
        fireEvent.change(screen.getByLabelText(en.RentalIndex.minValue), { target: { value: "90000" } });
        fireEvent.change(screen.getByLabelText(en.RentalIndex.maxValue), { target: { value: "80000" } });
        fireEvent.click(screen.getByTestId("rental-index-save"));
        expect(await screen.findByRole("alert")).toHaveTextContent(en.RentalIndex.minMaxInvalid);
        expect(api.upsertRentalIndexRange).not.toHaveBeenCalled();
    });

    async function chooseCsv(text: string) {
        fireEvent.click(await screen.findByTestId("rental-index-import-toggle"));
        const file = new File([text], "index.csv", { type: "text/csv" });
        Object.defineProperty(file, "text", { value: async () => text });
        fireEvent.change(screen.getByTestId("rental-index-csv"), { target: { files: [file] } });
    }

    it("imports a parsed sheet and reports what was saved", async () => {
        api.importRentalIndex.mockResolvedValue({ saved: 2, errors: [] });
        renderTab([]);
        await chooseCsv("building,unit type,year,min,max\nSample Vista,2 BR,2026,83736,102344\n,1 BR,2027,59054,72176\n");
        expect(await screen.findByTestId("rental-index-csv-preview")).toHaveTextContent("2 rows ready to import.");
        fireEvent.click(screen.getByTestId("rental-index-import-run"));
        await waitFor(() => expect(api.importRentalIndex).toHaveBeenCalledWith("p1", [
            { building: "Sample Vista", unitType: "2 BR", indexYear: 2026, minValue: 83736, maxValue: 102344, sourceNote: null },
            { building: null, unitType: "1 BR", indexYear: 2027, minValue: 59054, maxValue: 72176, sourceNote: null },
        ]));
        expect(await screen.findByTestId("rental-index-imported")).toHaveTextContent("2 ranges imported.");
        expect(api.fetchRentalIndex).toHaveBeenCalledTimes(2);
    });

    it("lists the rows the server refused and saves nothing", async () => {
        api.importRentalIndex.mockResolvedValue({ saved: 0, errors: ["Row 3: Unknown unit type \"Villa\""] });
        renderTab([]);
        await chooseCsv("building,unit type,year,min,max\nSample Vista,2 BR,2026,83736,102344\nSample Vista,Villa,2026,1,2\n");
        fireEvent.click(await screen.findByTestId("rental-index-import-run"));
        const errors = await screen.findByTestId("rental-index-import-errors");
        expect(errors).toHaveTextContent('Row 3: Unknown unit type "Villa"');
        expect(screen.queryByTestId("rental-index-imported")).toBeNull();
        expect(api.fetchRentalIndex).toHaveBeenCalledTimes(1);
    });

    it("shows parse errors by sheet row before anything is sent", async () => {
        renderTab([]);
        await chooseCsv("building,unit type,year,min,max\nSample Vista,2 BR,twenty,83736,102344\n");
        const errors = await screen.findByTestId("rental-index-import-errors");
        expect(errors).toHaveTextContent('Row 2: year "twenty" is not a four-digit year');
        expect(screen.getByTestId("rental-index-import-run")).toBeDisabled();
        expect(api.importRentalIndex).not.toHaveBeenCalled();
    });

    it("edits a unit's DEWA number inline and refuses anything but nine digits", async () => {
        renderTab();
        fireEvent.click(await screen.findByLabelText("Edit DEWA number for unit 102"));
        const input = screen.getByLabelText("Edit DEWA number for unit 102");
        fireEvent.change(input, { target: { value: "12345" } });
        fireEvent.click(screen.getByText(en.RentalIndex.save));
        expect(await screen.findByRole("alert")).toHaveTextContent(en.MasterData.dewaPremiseNumberInvalid);
        expect(api.setUnitDewaPremiseNumber).not.toHaveBeenCalled();
        fireEvent.change(input, { target: { value: "987654321" } });
        fireEvent.click(screen.getByText(en.RentalIndex.save));
        await waitFor(() => expect(api.setUnitDewaPremiseNumber).toHaveBeenCalledWith("u2", "987654321"));
    });
});
