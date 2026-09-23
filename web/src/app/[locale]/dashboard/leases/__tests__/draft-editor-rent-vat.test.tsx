import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { ChargeType, LeaseDetail, LeaseLine } from "@/lib/api/leasing";

/**
 * #54 review I-1: the draft editor's "Rent carries VAT" checkbox saved the
 * header flag while every RENT line kept its own explicit `vatApplicable:
 * false` — and the server honours the line. The lease said rent was taxed; the
 * contract value, cheques and posting charged 0 VAT. The real grid is rendered
 * so the PUT body is what the operator's clicks produce.
 */

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));

const updateDraft = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, updateDraft: (...a: unknown[]) => updateDraft(...a) } };
});

import LeaseMetadataEditor from "../LeaseMetadataEditor";

const CHARGE_TYPES: ChargeType[] = [
    {
        id: "ct-rent", code: "RENT", nameEn: "Rent", nameAr: null, role: "ADVANCE_RENT",
        behaviour: "RENT", vatApplicableDefault: false, active: true, displayOrder: 1,
    },
    {
        id: "ct-fee", code: "ADMIN_FEE", nameEn: "Admin Fee", nameAr: null, role: "ADMIN_FEE",
        behaviour: "FEE", vatApplicableDefault: true, active: true, displayOrder: 2,
    },
];

const line = (over: Partial<LeaseLine>): LeaseLine => ({
    id: "line-1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
    behaviour: "RENT", creditAccountId: "acc-1", creditAccountCode: "2100", creditAccountName: "Advance rent",
    grossAmount: 48000, discountAmount: 0, netAmount: 48000, narration: null, vatApplicable: false,
    periodStart: "2026-10-01", periodEnd: "2027-09-30", addendumId: null,
    ...over,
});

function renderEditor(lines: LeaseLine[]) {
    const lease = {
        id: "lease-1", unitId: "u1", renterId: "r1", propertyId: "p1", status: "DRAFT",
        startDate: "2026-10-01", endDate: "2027-09-30", rentVatApplicable: false, lines,
    } as unknown as LeaseDetail;
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseMetadataEditor lease={lease} chargeTypes={CHARGE_TYPES} />
        </NextIntlClientProvider>,
    );
    fireEvent.click(screen.getByText("Edit draft"));
}

async function savedLines() {
    fireEvent.click(screen.getByTestId("lease-draft-save"));
    await waitFor(() => expect(updateDraft).toHaveBeenCalled());
    const body = updateDraft.mock.calls[0][1];
    return { header: body.rentVatApplicable, lines: body.lines.map((l: { vatApplicable: boolean }) => l.vatApplicable) };
}

const vatBox = (i: number) => screen.getByTestId(`lease-line-vat-${i}`) as HTMLInputElement;

afterEach(() => {
    cleanup();
    updateDraft.mockReset();
});

describe("draft editor: header rent-VAT flag drives RENT lines (#54)", () => {
    it("ticking the header flag taxes the RENT lines it saves, and leaves other lines alone", async () => {
        updateDraft.mockResolvedValue({});
        renderEditor([line({}), line({ id: "line-2", seqNo: 2, chargeTypeId: "ct-fee", behaviour: "FEE", grossAmount: 500, vatApplicable: false })]);

        fireEvent.click(screen.getByTestId("edit-rent-vat"));
        expect(vatBox(0).checked).toBe(true);
        expect(vatBox(1).checked).toBe(false);

        expect(await savedLines()).toEqual({ header: true, lines: [true, false] });
    });

    it("a RENT charge picked in the grid takes the header flag", async () => {
        updateDraft.mockResolvedValue({});
        renderEditor([line({ chargeTypeId: "ct-fee", behaviour: "FEE" })]);
        fireEvent.click(screen.getByTestId("edit-rent-vat"));

        fireEvent.change(screen.getByTestId("lease-line-type-0"), { target: { value: "ct-rent" } });
        expect(vatBox(0).checked).toBe(true);
        expect((await savedLines()).lines).toEqual([true]);
    });

    it("a RENT line the operator set by hand keeps its choice when the header is toggled again", async () => {
        updateDraft.mockResolvedValue({});
        renderEditor([line({}), line({ id: "line-2", seqNo: 2, grossAmount: 1000 })]);

        fireEvent.click(screen.getByTestId("edit-rent-vat"));     // header on: both taxed
        fireEvent.click(vatBox(1));                                // operator zero-rates line 2
        fireEvent.click(screen.getByTestId("edit-rent-vat"));     // header off
        fireEvent.click(screen.getByTestId("edit-rent-vat"));     // header on again

        expect(await savedLines()).toEqual({ header: true, lines: [true, false] });
    });
});
