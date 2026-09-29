import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import { useState, type ReactNode } from "react";
import ar from "../../../../messages/ar.json";
import en from "../../../../messages/en.json";

vi.mock("@/lib/api/lookup", () => ({
    lookupApi: {
        searchUnits: vi.fn(),
        searchRenters: vi.fn(),
        unitNames: vi.fn(),
        renterNames: vi.fn(),
    },
}));

import { lookupApi, type RenterOption, type UnitOption } from "@/lib/api/lookup";
import { RenterPicker } from "@/components/pickers/RenterPicker";
import { UnitPicker } from "@/components/pickers/UnitPicker";

const api = vi.mocked(lookupApi);

const unit = (over: Partial<UnitOption>): UnitOption => ({
    id: "u1", unitNumber: "101", propertyId: "p1", propertyName: "Palm Tower", propertyType: "RESIDENTIAL",
    buildingId: null, buildingName: null, status: "VACANT", ...over,
});
const renter = (over: Partial<RenterOption>): RenterOption => ({
    id: "r1", nameEn: "Ali Hassan", nameAr: "علي حسن", phone: "+971500000000", email: "ali@example.com", ...over,
});

function withIntl(locale: "en" | "ar", node: ReactNode) {
    return render(<NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>{node}</NextIntlClientProvider>);
}

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("UnitPicker", () => {
    it("passes propertyId and status through and marks commercial units", async () => {
        api.searchUnits.mockResolvedValue([
            unit({ id: "u1", unitNumber: "101", buildingName: "Block A" }),
            unit({ id: "u2", unitNumber: "G-02", propertyName: "Souk Plaza", propertyType: "COMMERCIAL" }),
        ]);
        withIntl("en", <UnitPicker value="" onChange={vi.fn()} propertyId="p1" status="VACANT" testId="unit" />);

        fireEvent.click(screen.getByTestId("unit"));
        expect(await screen.findByText("G-02")).toBeInTheDocument();
        expect(api.searchUnits).toHaveBeenCalledWith(expect.objectContaining({ q: "", propertyId: "p1", status: "VACANT" }));
        expect(screen.getByText("Palm Tower · Block A")).toBeInTheDocument();
        expect(screen.getByText("Souk Plaza [Commercial]")).toBeInTheDocument();
    });

    it("narrows the search to a building when given one (demo feedback 2026-09-29)", async () => {
        api.searchUnits.mockResolvedValue([unit({ id: "u1", unitNumber: "101", buildingId: "b1", buildingName: "Block A" })]);
        withIntl("en", <UnitPicker value="" onChange={vi.fn()} propertyId="p1" buildingId="b1" status="VACANT" testId="unit" />);

        fireEvent.click(screen.getByTestId("unit"));
        expect(await screen.findByText("101")).toBeInTheDocument();
        expect(api.searchUnits).toHaveBeenCalledWith(expect.objectContaining({ propertyId: "p1", buildingId: "b1", status: "VACANT" }));
    });

    it("hides excludeIds rows and calls onChange with the unit", async () => {
        const u1 = unit({ id: "u1", unitNumber: "101" });
        api.searchUnits.mockResolvedValue([u1, unit({ id: "u2", unitNumber: "102" })]);
        const onChange = vi.fn();
        withIntl("en", <UnitPicker value="" onChange={onChange} excludeIds={["u2"]} testId="unit" />);

        fireEvent.click(screen.getByTestId("unit"));
        const row = await screen.findByText("101");
        expect(screen.queryByText("102")).not.toBeInTheDocument();
        fireEvent.click(row);
        expect(onChange).toHaveBeenCalledWith("u1", u1);
    });

    it("needs no names call for a unit it already saw in a search", async () => {
        api.searchUnits.mockResolvedValue([unit({ id: "u-seen", unitNumber: "303" })]);
        function Harness() {
            const [id, setId] = useState("");
            return <UnitPicker value={id} onChange={(next) => setId(next)} testId="unit" />;
        }
        withIntl("en", <Harness />);

        fireEvent.click(screen.getByTestId("unit"));
        fireEvent.click(await screen.findByText("303"));
        expect(screen.getByTestId("unit")).toHaveTextContent("303");
        await act(async () => {
            await Promise.resolve();
        });
        expect(api.unitNames).not.toHaveBeenCalled();
    });

    it("resolves the label of a value it has not seen with one names call", async () => {
        api.unitNames.mockResolvedValue({ rows: [unit({ id: "u-far", unitNumber: "909" })], failedIds: [] });
        withIntl("en", <UnitPicker value="u-far" onChange={vi.fn()} testId="unit" />);

        expect(await screen.findByText("909")).toBeInTheDocument();
        expect(api.unitNames).toHaveBeenCalledTimes(1);
        expect(api.unitNames).toHaveBeenCalledWith(["u-far"]);
    });
});

describe("RenterPicker", () => {
    it("labels renters in Arabic under the ar locale", async () => {
        api.searchRenters.mockResolvedValue([renter({})]);
        withIntl("ar", <RenterPicker value="" onChange={vi.fn()} testId="renter" />);

        fireEvent.click(screen.getByTestId("renter"));
        expect(await screen.findByText("علي حسن")).toBeInTheDocument();
        expect(screen.getByText("ali@example.com")).toBeInTheDocument();
        expect(screen.getByPlaceholderText(ar.Pickers.typeToSearch)).toBeInTheDocument();
    });

    it("labels renters as 'English (Arabic)' under en, falling back to phone for the sublabel", async () => {
        api.searchRenters.mockResolvedValue([renter({ email: null })]);
        withIntl("en", <RenterPicker value="" onChange={vi.fn()} testId="renter" />);

        fireEvent.click(screen.getByTestId("renter"));
        expect(await screen.findByText("Ali Hassan (علي حسن)")).toBeInTheDocument();
        expect(screen.getByText("+971500000000")).toBeInTheDocument();
    });

    it("hides excludeIds rows", async () => {
        api.searchRenters.mockResolvedValue([renter({ id: "r1" }), renter({ id: "r2", nameEn: "Omar", nameAr: null })]);
        withIntl("en", <RenterPicker value="" onChange={vi.fn()} excludeIds={["r1"]} testId="renter" />);

        fireEvent.click(screen.getByTestId("renter"));
        expect(await screen.findByText("Omar")).toBeInTheDocument();
        expect(screen.queryByText("Ali Hassan (علي حسن)")).not.toBeInTheDocument();
    });
});

describe("picker placeholders", () => {
    it("default to translated text when the caller passes none", () => {
        withIntl("ar", <UnitPicker value="" onChange={vi.fn()} testId="unit" />);
        expect(screen.getByTestId("unit")).toHaveTextContent(ar.Pickers.selectUnit);
        cleanup();
        withIntl("ar", <RenterPicker value="" onChange={vi.fn()} testId="renter" />);
        expect(screen.getByTestId("renter")).toHaveTextContent(ar.Pickers.selectRenter);
        expect(ar.Pickers.selectRenter).toBe("اختر مستأجرًا");
        expect(ar.Pickers.selectUnit).toBe("اختر وحدة");
    });
});
