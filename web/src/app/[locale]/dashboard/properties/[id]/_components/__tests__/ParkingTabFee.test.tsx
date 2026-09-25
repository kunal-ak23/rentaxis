import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../../../messages/en.json";

const api = vi.hoisted(() => ({ fetchParkingSpots: vi.fn(), updateParkingSpot: vi.fn() }));
vi.mock("@/lib/api/facilities", async (orig) => {
    const real = await orig<typeof import("@/lib/api/facilities")>();
    return { ...real, ...api };
});

import { ParkingTab } from "../ParkingTab";

/** F14-50 (PR #361 R1): a parking spot's fee is set on its edit form. */
describe("ParkingTab fee", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("sends a fixed fee per booking", async () => {
        const spot = { id: "s1", propertyId: "p1", spotNumber: "P-1", level: null, photoUrls: [], covered: true, active: true,
            buildingIds: [], held: false, pendingCount: 0, createdAt: "", updatedAt: "", feeType: "FREE", feeAmount: 0 };
        api.fetchParkingSpots.mockResolvedValue({ content: [spot], totalElements: 1, totalPages: 1, number: 0, size: 10 });
        api.updateParkingSpot.mockResolvedValue(spot);
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <ParkingTab propertyId="p1" buildings={[]} canManage />
            </NextIntlClientProvider>,
        );
        fireEvent.click(await screen.findByLabelText(en.Facilities.editSpot));
        fireEvent.change(screen.getByTestId("spot-fee-type"), { target: { value: "PER_BOOKING" } });
        fireEvent.change(screen.getByTestId("spot-fee-amount"), { target: { value: "150" } });
        fireEvent.submit(screen.getByTestId("spot-fee-amount").closest("form")!);
        await waitFor(() => expect(api.updateParkingSpot).toHaveBeenCalled());
        expect(api.updateParkingSpot.mock.calls[0][1]).toMatchObject({ feeType: "PER_BOOKING", feeAmount: 150 });
    });
});
