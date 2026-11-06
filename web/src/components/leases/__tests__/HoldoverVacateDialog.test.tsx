import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";

const leaseApi = vi.hoisted(() => ({ holdoverVacatePreview: vi.fn(), holdoverVacate: vi.fn() }));
vi.mock("@/lib/api/leasing", async (orig) => {
    const real = await orig<typeof import("@/lib/api/leasing")>();
    return { ...real, leaseApi: { ...real.leaseApi, ...leaseApi } };
});

import HoldoverVacateDialog from "../HoldoverVacateDialog";

const PREVIEW = {
    holdFrom: "2025-10-01", movedOutOn: "2025-11-15", days: 46, dayRate: 164.383562,
    months: [{ month: "10/2025", days: 31, amount: 5095.89 }, { month: "11/2025", days: 15, amount: 2465.75 }],
    amount: 7561.64,
};

/** A lease on hold whose renter left: priced by the server, charged with the figure shown. */
describe("HoldoverVacateDialog", () => {
    afterEach(() => { cleanup(); vi.clearAllMocks(); });

    it("prices the move-out month by month and confirms with that total", async () => {
        leaseApi.holdoverVacatePreview.mockResolvedValue(PREVIEW);
        leaseApi.holdoverVacate.mockResolvedValue({ leaseId: "L", amount: 7561.64, chargeId: "C" });
        const onDone = vi.fn();
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <HoldoverVacateDialog open lease={{ id: "L", endDate: "2025-09-30" }} today="2026-11-13"
                                      onClose={() => {}} onDone={onDone} />
            </NextIntlClientProvider>,
        );
        fireEvent.change(screen.getByTestId("holdover-moved-out-on"), { target: { value: "2025-11-15" } });
        await waitFor(() => expect(leaseApi.holdoverVacatePreview).toHaveBeenLastCalledWith("L", "2025-11-15"));
        expect(await screen.findByTestId("holdover-total")).toHaveTextContent("7,561.64");
        expect(screen.getByTestId("holdover-months").querySelectorAll("li")).toHaveLength(2);

        fireEvent.click(screen.getByTestId("holdover-vacate-confirm"));
        await waitFor(() => expect(leaseApi.holdoverVacate).toHaveBeenCalledWith("L", "2025-11-15", 7561.64));
        expect(onDone).toHaveBeenCalled();
    });
});
