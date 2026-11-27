import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import { BulkExpectedRentDialog } from "../BulkExpectedRentDialog";
import { ExpectedRentCell } from "../ExpectedRentCell";

/** Expected rent: edit one unit in place, or set it per unit type across projects. */

function wrap(ui: React.ReactNode) {
    return render(<NextIntlClientProvider locale="en" messages={en}>{ui}</NextIntlClientProvider>);
}

function okJson(body: unknown) {
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }));
}

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
});

describe("ExpectedRentCell", () => {
    it("saves a new rent for one unit", async () => {
        const fetchMock = vi.fn(() => okJson({ id: "u1", expectedRent: 31500 }));
        vi.stubGlobal("fetch", fetchMock);
        const onSaved = vi.fn();
        wrap(<ExpectedRentCell unitId="u1" value={25000} canEdit onSaved={onSaved} />);
        fireEvent.click(screen.getByTestId("expected-rent-edit-u1"));
        fireEvent.change(screen.getByTestId("expected-rent-input-u1"), { target: { value: "31500" } });
        fireEvent.click(screen.getByTestId("expected-rent-save-u1"));
        await waitFor(() => expect(onSaved).toHaveBeenCalledWith(31500));
        expect(fetchMock).toHaveBeenCalledWith("/api/proxy/v1/units/u1/expected-rent", expect.objectContaining({
            method: "PUT", body: JSON.stringify({ expectedRent: 31500 }),
        }));
    });

    it("offers no edit to a role that cannot price units", () => {
        wrap(<ExpectedRentCell unitId="u1" value={25000} canEdit={false} onSaved={() => {}} />);
        expect(screen.queryByTestId("expected-rent-edit-u1")).toBeNull();
    });
});

describe("BulkExpectedRentDialog", () => {
    const projects = [{ id: "p1", label: "Tower A" }, { id: "p2", label: "Tower B" }];

    it("sends the rent per type for the chosen projects and reports the count", async () => {
        const fetchMock = vi.fn(() => okJson({ updated: { BHK2: 12, BHK3: 8 }, total: 20 }));
        vi.stubGlobal("fetch", fetchMock);
        const onDone = vi.fn();
        wrap(<BulkExpectedRentDialog open onClose={() => {}} onDone={onDone} projects={projects} />);
        fireEvent.change(screen.getByTestId("bulk-rent-BHK2"), { target: { value: "30000" } });
        fireEvent.change(screen.getByTestId("bulk-rent-BHK3"), { target: { value: "40000" } });
        fireEvent.click(screen.getByLabelText("Tower B"));
        fireEvent.click(screen.getByTestId("bulk-rent-apply"));
        await waitFor(() => expect(screen.getByTestId("bulk-rent-done").textContent).toBe("20 units updated."));
        expect(onDone).toHaveBeenCalled();
        const body = JSON.parse((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body as string);
        expect(body).toEqual({ propertyIds: ["p1"], buildingId: null, rents: { BHK2: 30000, BHK3: 40000 } });
    });

    it("asks for a rent before sending anything", () => {
        const fetchMock = vi.fn();
        vi.stubGlobal("fetch", fetchMock);
        wrap(<BulkExpectedRentDialog open onClose={() => {}} onDone={() => {}} projects={projects} />);
        fireEvent.click(screen.getByTestId("bulk-rent-apply"));
        expect(screen.getByRole("alert").textContent).toBe("Enter a rent for at least one unit type.");
        expect(fetchMock).not.toHaveBeenCalled();
    });

    it("on one project shows only the types it has, with their unit counts", () => {
        wrap(<BulkExpectedRentDialog open onClose={() => {}} onDone={() => {}} projects={[projects[0]]} fixedProjectId="p1"
            units={[{ type: "BHK2" }, { type: "BHK2" }, { type: "BHK3" }]} />);
        expect(screen.getByTestId("bulk-rent-BHK2")).toBeTruthy();
        expect(screen.getByTestId("bulk-rent-BHK3")).toBeTruthy();
        expect(screen.queryByTestId("bulk-rent-STUDIO")).toBeNull();
        expect(screen.getByText("2 units")).toBeTruthy();
        expect(screen.getByText("1 unit")).toBeTruthy();
    });
});
