import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { ChargeType, LeaseDetail } from "@/lib/api/leasing";

/**
 * #54 review I-2: extending a commercial lease (header "Rent carries VAT"
 * ticked) with a RENT line must tax that rent. The grid used to take RENT's
 * catalogue default (off) and send it explicitly, so the server's
 * header-following default never ran: the cheques were asked to cover the net
 * only and the extension's rent posted without output VAT.
 *
 * The real grid is rendered here (only the pickers are stubbed), so the flag
 * travels the whole way: lease → grid → row → request body.
 */

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/finance/SettlementAccountPicker", () => ({ default: () => <div data-testid="settlement-picker" /> }));

const extend = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, extend: (...a: unknown[]) => extend(...a) } };
});

import ExtendLeaseDialog from "../ExtendLeaseDialog";

const CHARGE_TYPES: ChargeType[] = [
    {
        id: "ct-rent", code: "RENT", nameEn: "Rent", nameAr: null, role: "ADVANCE_RENT",
        behaviour: "RENT", vatApplicableDefault: false, active: true, displayOrder: 1,
    },
];

function renderDialog(rentVatApplicable: boolean) {
    const lease = {
        id: "lease-1", propertyId: "p1", startDate: "2026-01-01", endDate: "2026-12-31", rentVatApplicable,
    } as unknown as LeaseDetail;
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ExtendLeaseDialog open lease={lease} chargeTypes={CHARGE_TYPES} onClose={() => {}} onExtended={() => {}} />
        </NextIntlClientProvider>,
    );
}

function fillRentLine() {
    fireEvent.change(screen.getByTestId("extend-new-end-date"), { target: { value: "2027-03-31" } });
    fireEvent.change(screen.getByTestId("lease-line-type-0"), { target: { value: "ct-rent" } });
    fireEvent.change(screen.getByTestId("lease-line-amount-0"), { target: { value: "10000" } });
}

afterEach(() => {
    cleanup();
    extend.mockReset();
});

describe("ExtendLeaseDialog: RENT line follows the lease's rent-VAT flag (#54)", () => {
    it("taxes the extension's rent on a commercial lease and asks the cheques to cover it", async () => {
        extend.mockResolvedValue({});
        renderDialog(true);
        fillRentLine();

        expect((screen.getByTestId("lease-line-vat-0") as HTMLInputElement).checked).toBe(true);
        expect(screen.getByTestId("lease-lines-contract-value")).toHaveTextContent("10,500.00");

        // Cheques for the net alone do not match.
        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "10000" } });
        expect(screen.getByTestId("extend-lease-confirm")).toBeDisabled();

        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "10500" } });
        expect(screen.getByTestId("extend-lease-confirm")).toBeEnabled();
        fireEvent.click(screen.getByTestId("extend-lease-confirm"));

        await waitFor(() => expect(extend).toHaveBeenCalled());
        const body = extend.mock.calls[0][1];
        expect(body.lines).toHaveLength(1);
        expect(body.lines[0]).toMatchObject({ chargeTypeId: "ct-rent", grossAmount: 10000, vatApplicable: true });
    });

    it("leaves a residential lease's extension rent untaxed", async () => {
        extend.mockResolvedValue({});
        renderDialog(false);
        fillRentLine();

        expect(screen.getByTestId("lease-lines-contract-value")).toHaveTextContent("10,000.00");
        fireEvent.change(screen.getByLabelText("Amount 1"), { target: { value: "10000" } });
        fireEvent.click(screen.getByTestId("extend-lease-confirm"));

        await waitFor(() => expect(extend).toHaveBeenCalled());
        expect(extend.mock.calls[0][1].lines[0].vatApplicable).toBe(false);
    });
});
