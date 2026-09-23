import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { ChargeType, LeaseDetail, LeaseLine } from "@/lib/api/leasing";

/**
 * I2 (#49 follow-on): "carry deposit forward" is ticked by default. If the
 * operator unticks "copy lines" to edit the rent, the grid still holds last
 * year's DEPOSIT line — and sending it as well would charge a second deposit
 * while the old one is JV-moved across.
 */

vi.mock("@/components/leases/LeaseLinesGrid", () => ({ default: () => <div data-testid="lines-grid" /> }));

const renew = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, renew: (...a: unknown[]) => renew(...a) } };
});

import RenewLeaseDialog from "../RenewLeaseDialog";

const rent: LeaseLine = {
    id: "line-1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
    behaviour: "RENT", creditAccountId: "acc-1", creditAccountCode: "2100", creditAccountName: "Advance rent",
    grossAmount: 48000, discountAmount: 0, netAmount: 48000, narration: null, vatApplicable: false,
    periodStart: "2024-10-01", periodEnd: "2025-09-30", addendumId: null,
};
const deposit: LeaseLine = {
    ...rent, id: "line-2", seqNo: 2, chargeTypeId: "ct-dep", chargeTypeCode: "DEPOSIT",
    chargeTypeName: "Security deposit", behaviour: "DEPOSIT", grossAmount: 5000, netAmount: 5000,
    periodStart: null, periodEnd: null,
};

const LEASE = {
    id: "lease-1", propertyId: "p1", startDate: "2024-10-01", endDate: "2025-09-30",
    lines: [rent, deposit],
} as unknown as LeaseDetail;

const CHARGE_TYPES = [
    { id: "ct-rent", behaviour: "RENT", active: true },
    { id: "ct-dep", behaviour: "DEPOSIT", active: true },
] as unknown as ChargeType[];

function renderDialog() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RenewLeaseDialog open lease={LEASE} chargeTypes={CHARGE_TYPES} onClose={() => {}} onRenewed={() => {}} />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    renew.mockReset();
});

describe("RenewLeaseDialog deposit carry-forward (I2)", () => {
    it("does not send last year's deposit line while the deposit is carried forward", async () => {
        renew.mockResolvedValue({ id: "lease-2" });
        renderDialog();
        fireEvent.click(screen.getByTestId("renew-copy-lines"));
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        const body = renew.mock.calls[0][1];
        expect(body.carryDepositForward).toBe(true);
        expect(body.lines.map((l: { chargeTypeId: string }) => l.chargeTypeId)).toEqual(["ct-rent"]);
    });

    it("sends it when the deposit is not carried forward", async () => {
        renew.mockResolvedValue({ id: "lease-2" });
        renderDialog();
        fireEvent.click(screen.getByTestId("renew-copy-lines"));
        fireEvent.click(screen.getByTestId("renew-carry-deposit"));
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        const body = renew.mock.calls[0][1];
        expect(body.carryDepositForward).toBe(false);
        expect(body.lines.map((l: { chargeTypeId: string }) => l.chargeTypeId)).toEqual(["ct-rent", "ct-dep"]);
    });
});
