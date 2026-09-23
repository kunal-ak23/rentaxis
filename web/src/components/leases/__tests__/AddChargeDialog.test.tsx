import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { ChargeType, LeaseDetail } from "@/lib/api/leasing";

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/finance/SettlementAccountPicker", () => ({ default: () => <div /> }));
vi.mock("@/components/leases/LeaseLinesGrid", () => ({ default: () => <div data-testid="lines-grid" /> }));
vi.mock("@/components/leases/leaseMath", async orig => {
    const m = await orig<typeof import("@/components/leases/leaseMath")>();
    return { ...m, linesAreValid: () => true, totalsOf: () => ({ gross: 6000, discount: 0, net: 6000, vat: 0, inclVat: 6000 }) };
});
const addCharge = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, addCharge: (...a: unknown[]) => addCharge(...a) } };
});

import AddChargeDialog from "../AddChargeDialog";

const LEASE = { id: "lease-1", propertyId: "p1", startDate: "2026-10-02", endDate: "2027-10-01" } as unknown as LeaseDetail;
const CHARGE_TYPES: ChargeType[] = [];

function renderDialog(onAdded = vi.fn()) {
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <AddChargeDialog open lease={LEASE} chargeTypes={CHARGE_TYPES} onClose={() => {}} onAdded={onAdded} />
        </NextIntlClientProvider>,
    );
    return onAdded;
}

afterEach(() => { cleanup(); addCharge.mockReset(); });

describe("AddChargeDialog", () => {
    it("stays disabled until an effective date inside the tenancy is chosen and the cheques cover the charge", () => {
        renderDialog();
        const confirm = screen.getByTestId("add-charge-confirm");
        expect(confirm).toBeDisabled();

        fireEvent.change(screen.getByTestId("add-charge-effective-from"), { target: { value: "2027-02-15" } });
        expect(confirm).toBeDisabled(); // default cheque row is 0, lines charge 6,000

        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "6000" } });
        expect(screen.getByTestId("add-charge-match")).toHaveAttribute("data-match", "true");
        expect(confirm).not.toBeDisabled();

        fireEvent.change(screen.getByTestId("add-charge-effective-from"), { target: { value: "2027-10-02" } });
        expect(confirm).toBeDisabled(); // after the lease end
    });

    it("posts the addendum with a blank Ejari sent as null", async () => {
        addCharge.mockResolvedValue({ addendum: { addendumNumber: "ADD-27/1" }, posting: {} });
        const onAdded = renderDialog();
        fireEvent.change(screen.getByTestId("add-charge-effective-from"), { target: { value: "2027-02-15" } });
        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "6000" } });
        fireEvent.click(screen.getByTestId("add-charge-confirm"));

        await waitFor(() => expect(onAdded).toHaveBeenCalled());
        expect(addCharge).toHaveBeenCalledWith("lease-1", expect.objectContaining({
            effectiveFrom: "2027-02-15", ejariNumber: null,
        }));
    });
});
