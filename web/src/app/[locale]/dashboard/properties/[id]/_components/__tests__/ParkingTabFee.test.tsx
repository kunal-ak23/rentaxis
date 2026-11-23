import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../../../../../messages/en.json";

const api = vi.hoisted(() => ({ fetchParkingSpots: vi.fn(), updateParkingSpot: vi.fn() }));
vi.mock("@/lib/api/facilities", async (orig) => {
    const real = await orig<typeof import("@/lib/api/facilities")>();
    return { ...real, ...api };
});

// The real picker loads the chart over fetch; a stub that reports one pick is enough here.
vi.mock("@/components/finance/AccountPicker", () => ({
    default: ({ value, onChange, accountType }: { value: string | null; onChange: (id: string) => void; accountType?: string }) => (
        <input data-testid="income-account-picker" data-account-type={accountType} value={value ?? ""}
               onChange={e => onChange(e.target.value)} />
    ),
}));

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

    it("offers per-day and per-month but never per-hour, and sends the income account", async () => {
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
        const select = screen.getByTestId("spot-fee-type") as HTMLSelectElement;
        expect(Array.from(select.options).map(o => o.value)).toEqual(["FREE", "PER_BOOKING", "PER_DAY", "PER_MONTH"]);
        fireEvent.change(select, { target: { value: "PER_MONTH" } });
        fireEvent.change(screen.getByTestId("spot-fee-amount"), { target: { value: "300" } });
        const picker = screen.getByTestId("income-account-picker");
        expect(picker.getAttribute("data-account-type")).toBe("INCOME");
        fireEvent.change(picker, { target: { value: "acc-1" } });
        fireEvent.submit(screen.getByTestId("spot-fee-amount").closest("form")!);
        await waitFor(() => expect(api.updateParkingSpot).toHaveBeenCalled());
        expect(api.updateParkingSpot.mock.calls[0][1]).toMatchObject({ feeType: "PER_MONTH", feeAmount: 300, incomeAccountId: "acc-1" });
    });

    /**
     * Final round: the fee field used to be a plain `<input type="number">` fed
     * straight through `Number(...)` — a third decimal silently became NaN/rounded
     * and was posted anyway. It is now the shared money input, so the browser's
     * own form validation refuses the submit before the request is sent.
     */
    it("refuses a fee with a third decimal in the form, without calling the API", async () => {
        const spot = { id: "s1", propertyId: "p1", spotNumber: "P-1", level: null, photoUrls: [], covered: true, active: true,
            buildingIds: [], held: false, pendingCount: 0, createdAt: "", updatedAt: "", feeType: "FREE", feeAmount: 0 };
        api.fetchParkingSpots.mockResolvedValue({ content: [spot], totalElements: 1, totalPages: 1, number: 0, size: 10 });
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <ParkingTab propertyId="p1" buildings={[]} canManage />
            </NextIntlClientProvider>,
        );
        fireEvent.click(await screen.findByLabelText(en.Facilities.editSpot));
        fireEvent.change(screen.getByTestId("spot-fee-type"), { target: { value: "PER_BOOKING" } });
        const feeInput = screen.getByTestId("spot-fee-amount") as HTMLInputElement;
        fireEvent.change(feeInput, { target: { value: "150.555" } });
        expect(feeInput.dataset.moneyInvalid).toBe("true");
        expect(feeInput.validationMessage).not.toBe("");
        // A native submit-button click, not fireEvent.submit(form) — jsdom only runs
        // constraint validation via the implicit-submission path a real click takes.
        fireEvent.click(screen.getByText(en.Facilities.save));
        expect(api.updateParkingSpot).not.toHaveBeenCalled();
    });
});
