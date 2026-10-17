import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../../../messages/en.json";

const api = vi.hoisted(() => ({
    fetchParkingSpots: vi.fn(),
    updateParkingSpot: vi.fn(),
    setParkingSpotAttachment: vi.fn(),
    createParkingSpot: vi.fn(),
    bulkCreateParkingSpots: vi.fn(),
    fetchPropertyUnits: vi.fn(),
}));
vi.mock("@/lib/api/facilities", async (orig) => {
    const real = await orig<typeof import("@/lib/api/facilities")>();
    return { ...real, ...api };
});

import { ApiError } from "@/lib/api/facilities";
import { ParkingTab } from "../ParkingTab";

const BUILDINGS = [
    { id: "bA", nameEn: "Tower A", nameAr: null },
    { id: "bB", nameEn: "Tower B", nameAr: null },
];
const UNITS = [
    { id: "u1", unitNumber: "101", building: { id: "bA" } },
    { id: "u2", unitNumber: "201", building: { id: "bB" } },
    { id: "u3", unitNumber: "102", building: { id: "bA" } },
];

const base = {
    propertyId: "p1", level: null, photoUrls: [], covered: true, active: true, held: false, pendingCount: 0,
    createdAt: "", updatedAt: "", feeType: "FREE", feeAmount: 0,
    attachedUnitId: null, attachedUnitNumber: null, attachedBuildingName: null,
};
/** Bookable bay scoped to Tower A only. */
const scoped = { ...base, id: "s1", spotNumber: "P-1", buildingIds: ["bA"] };
/** Bay attached to unit 101 in Tower A, not scoped to towers. */
const attached = { ...base, id: "s2", spotNumber: "P-2", buildingIds: [],
    attachedUnitId: "u1", attachedUnitNumber: "101", attachedBuildingName: "Tower A" };

function renderTab(rows: unknown[]) {
    api.fetchParkingSpots.mockResolvedValue({ content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 10 });
    api.fetchPropertyUnits.mockResolvedValue(UNITS);
    api.updateParkingSpot.mockResolvedValue(rows[0]);
    api.setParkingSpotAttachment.mockResolvedValue(rows[0]);
    api.createParkingSpot.mockResolvedValue(rows[0]);
    api.bulkCreateParkingSpots.mockResolvedValue(rows);
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ParkingTab propertyId="p1" buildings={BUILDINGS} canManage />
        </NextIntlClientProvider>,
    );
}

async function openEdit(index = 0) {
    fireEvent.click((await screen.findAllByLabelText(en.Facilities.editSpot))[index]);
    // The units arrive after the dialog opens; wait for the first real option.
    await waitFor(() => expect(within(screen.getByTestId("spot-attached-unit")).getAllByRole("option").length).toBeGreaterThan(1));
    return screen.getByTestId("spot-attached-unit") as HTMLSelectElement;
}

const save = () => fireEvent.click(screen.getByText(en.Facilities.save));

describe("ParkingTab attached unit", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("shows 'Attached to <unit> · <building>' for an attached bay and nothing for a bookable one", async () => {
        renderTab([scoped, attached]);
        const cell = await screen.findByTestId("spot-attached-s2");
        expect(cell).toHaveTextContent(`${en.Facilities.attachedTo} 101 · Tower A`);
        expect(screen.getByTestId("spot-attached-s1")).not.toHaveTextContent(en.Facilities.attachedTo);
        // An attached bay is never bookable, so the Held/Available badge gives way to "Not bookable".
        expect(within(screen.getByTestId("spot-attached-s2").closest("tr")!).getByText(en.Facilities.notBookable)).toBeInTheDocument();
    });

    it("lists only the units in the bay's towers, after 'Not attached (bookable)'", async () => {
        renderTab([scoped]);
        const select = await openEdit();
        const labels = within(select).getAllByRole("option").map(o => o.textContent);
        expect(labels).toEqual([en.Facilities.notAttachedOption, "101 · Tower A", "102 · Tower A"]);
        expect(select.value).toBe("");
        expect(screen.getByTestId("spot-current-attachment")).toHaveTextContent(en.Facilities.notAttachedNote);
    });

    it("lists every unit of the property for a bay that is not scoped to towers, and pre-selects the attachment", async () => {
        renderTab([attached]);
        const select = await openEdit();
        expect(within(select).getAllByRole("option")).toHaveLength(4);
        expect(select.value).toBe("u1");
        expect(screen.getByTestId("spot-current-attachment")).toHaveTextContent(`${en.Facilities.attachedTo} 101 · Tower A`);
    });

    const lastUpdateBody = () => api.updateParkingSpot.mock.calls.at(-1)![1];

    it("attaches: a changed pick rides on the one update request (setAttachment + attachedUnitId)", async () => {
        renderTab([scoped]);
        const select = await openEdit();
        fireEvent.change(select, { target: { value: "u3" } });
        save();
        await waitFor(() => expect(api.updateParkingSpot).toHaveBeenCalledTimes(1));
        expect(api.updateParkingSpot.mock.calls[0][0]).toBe("s1");
        expect(lastUpdateBody()).toMatchObject({ spotNumber: "P-1", setAttachment: true, attachedUnitId: "u3" });
        // The server does detach/update/attach atomically: no separate attachment call.
        expect(api.setParkingSpotAttachment).not.toHaveBeenCalled();
    });

    it("detaches: choosing 'Not attached' sends setAttachment with a null unit in the same request", async () => {
        renderTab([attached]);
        const select = await openEdit();
        fireEvent.change(select, { target: { value: "" } });
        save();
        await waitFor(() => expect(api.updateParkingSpot).toHaveBeenCalledTimes(1));
        expect(lastUpdateBody()).toMatchObject({ setAttachment: true, attachedUnitId: null });
        expect(api.setParkingSpotAttachment).not.toHaveBeenCalled();
    });

    it("moves an attached bay to another unit in ONE request", async () => {
        renderTab([attached]);
        const select = await openEdit();
        fireEvent.change(select, { target: { value: "u3" } });
        save();
        await waitFor(() => expect(api.updateParkingSpot).toHaveBeenCalledTimes(1));
        expect(lastUpdateBody()).toMatchObject({ setAttachment: true, attachedUnitId: "u3" });
        expect(api.setParkingSpotAttachment).not.toHaveBeenCalled();
    });

    it("an unchanged selection sends no setAttachment / attachedUnitId keys", async () => {
        for (const bay of [attached, scoped]) {
            renderTab([bay]);
            await openEdit();
            save();
            await waitFor(() => expect(api.updateParkingSpot).toHaveBeenCalledTimes(1));
            expect(lastUpdateBody()).not.toHaveProperty("setAttachment");
            expect(lastUpdateBody()).not.toHaveProperty("attachedUnitId");
            expect(api.setParkingSpotAttachment).not.toHaveBeenCalled();
            cleanup();
            vi.clearAllMocks();
        }
    });

    it("shows a refused save in the user's language and keeps the dialog open", async () => {
        renderTab([attached]);
        api.updateParkingSpot.mockRejectedValue(
            new ApiError(400, "English fallback", JSON.stringify({ message: "English fallback", code: "parking.attachHasBookings", args: {} })),
        );
        const select = await openEdit();
        fireEvent.change(select, { target: { value: "u3" } });
        save();
        expect(await screen.findByText(en.Common.errors.parking.attachHasBookings)).toBeInTheDocument();
        expect(screen.getByTestId("spot-attached-unit")).toBeInTheDocument();
        expect(api.setParkingSpotAttachment).not.toHaveBeenCalled();
    });

    it("keeps the bay's unit selected when the unit list fails to load, and 'Not attached' still detaches", async () => {
        renderTab([attached]);
        api.fetchPropertyUnits.mockRejectedValue(new Error("boom"));
        fireEvent.click((await screen.findAllByLabelText(en.Facilities.editSpot))[0]);
        expect(await screen.findByText(en.Facilities.unitsLoadError)).toBeInTheDocument();
        const select = screen.getByTestId("spot-attached-unit") as HTMLSelectElement;
        expect(select.value).toBe("u1");
        expect(within(select).getAllByRole("option").map(o => o.textContent))
            .toEqual([en.Facilities.notAttachedOption, "101 · Tower A"]);
        fireEvent.change(select, { target: { value: "" } });
        save();
        await waitFor(() => expect(api.updateParkingSpot).toHaveBeenCalledTimes(1));
        expect(lastUpdateBody()).toMatchObject({ setAttachment: true, attachedUnitId: null });
    });

    it("offers units of the towers currently ticked in the dialog, not only the saved ones", async () => {
        renderTab([scoped]);
        const select = await openEdit();
        expect(within(select).queryByRole("option", { name: "201 · Tower B" })).toBeNull();
        fireEvent.click(screen.getByRole("button", { name: "Tower B" }));
        expect(within(select).getByRole("option", { name: "201 · Tower B" })).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: "Tower A" }));
        expect(within(select).queryByRole("option", { name: "101 · Tower A" })).toBeNull();
    });

    describe("adding a bay", () => {
        async function openAdd(bulk = false) {
            fireEvent.click(await screen.findByRole("button", { name: bulk ? en.Facilities.bulkAddSpots : en.Facilities.addSpot }));
            await waitFor(() => expect(within(screen.getByTestId("spot-attached-unit")).getAllByRole("option").length).toBeGreaterThan(1));
            return screen.getByTestId("spot-attached-unit") as HTMLSelectElement;
        }
        const fillSpot = () => fireEvent.change(
            screen.getByText(`${en.Facilities.spotNumber} *`).parentElement!.querySelector("input")!, { target: { value: "P-9" } });
        const fillBulk = () => fireEvent.change(screen.getByPlaceholderText("B1-01, B1-02, P10-P20"), { target: { value: "P-1, P-2" } });

        it("offers 'Not attached' by default and only the units of the ticked towers", async () => {
            renderTab([scoped]);
            const select = await openAdd();
            expect(select.value).toBe("");
            // No towers ticked: every unit of the property.
            expect(within(select).getAllByRole("option").map(o => o.textContent))
                .toEqual([en.Facilities.notAttachedOption, "101 · Tower A", "201 · Tower B", "102 · Tower A"]);
            fireEvent.click(screen.getByRole("button", { name: "Tower B" }));
            expect(within(select).getAllByRole("option").map(o => o.textContent))
                .toEqual([en.Facilities.notAttachedOption, "201 · Tower B"]);
        });

        it("add with a unit sends attachedUnitId", async () => {
            renderTab([scoped]);
            const select = await openAdd();
            fillSpot();
            fireEvent.change(select, { target: { value: "u2" } });
            save();
            await waitFor(() => expect(api.createParkingSpot).toHaveBeenCalledTimes(1));
            expect(api.createParkingSpot.mock.calls[0][0]).toMatchObject({ propertyId: "p1", spotNumber: "P-9", attachedUnitId: "u2" });
            expect(api.setParkingSpotAttachment).not.toHaveBeenCalled();
        });

        it("add without a unit omits attachedUnitId", async () => {
            renderTab([scoped]);
            await openAdd();
            fillSpot();
            save();
            await waitFor(() => expect(api.createParkingSpot).toHaveBeenCalledTimes(1));
            expect(api.createParkingSpot.mock.calls[0][0]).not.toHaveProperty("attachedUnitId");
        });

        it("bulk add with a unit sends attachedUnitId; without one omits it", async () => {
            renderTab([scoped]);
            const select = await openAdd(true);
            fillBulk();
            fireEvent.change(select, { target: { value: "u1" } });
            save();
            await waitFor(() => expect(api.bulkCreateParkingSpots).toHaveBeenCalledTimes(1));
            expect(api.bulkCreateParkingSpots.mock.calls[0][0]).toMatchObject({ spotNumbers: ["P-1", "P-2"], attachedUnitId: "u1" });

            cleanup();
            vi.clearAllMocks();
            renderTab([scoped]);
            await openAdd(true);
            fillBulk();
            save();
            await waitFor(() => expect(api.bulkCreateParkingSpots).toHaveBeenCalledTimes(1));
            expect(api.bulkCreateParkingSpots.mock.calls[0][0]).not.toHaveProperty("attachedUnitId");
        });

        it("shows a refused create in the user's language and keeps the dialog open", async () => {
            renderTab([scoped]);
            api.createParkingSpot.mockRejectedValue(
                new ApiError(400, "English fallback", JSON.stringify({ message: "English fallback", code: "parking.attachUnitOutsideBay", args: {} })),
            );
            const select = await openAdd();
            fillSpot();
            fireEvent.change(select, { target: { value: "u2" } });
            save();
            expect(await screen.findByText(en.Common.errors.parking.attachUnitOutsideBay)).toBeInTheDocument();
            expect(screen.getByTestId("spot-attached-unit")).toBeInTheDocument();
        });
    });
});
