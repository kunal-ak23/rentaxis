import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../../../messages/en.json";
import type { ChargeType, LeaseDetail, LeaseLine } from "@/lib/api/leasing";

/**
 * Final review I1: the server refuses an edit of a renewal's successor draft that raises the rent above the rental
 * index cap until it carries a reason (`renewal.indexOverrideRequired`). The editor reveals the reason field, then
 * resubmits the same form with `indexOverrideReason`. Any other refusal shows as before, with no reason field.
 */

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));

const updateDraft = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, updateDraft: (...a: unknown[]) => updateDraft(...a) } };
});

import LeaseMetadataEditor from "../LeaseMetadataEditor";
import { ApiError } from "@/lib/api/leasing";

const CHARGE_TYPES: ChargeType[] = [{
    id: "ct-rent", code: "RENT", nameEn: "Rent", nameAr: null, role: "ADVANCE_RENT",
    behaviour: "RENT", vatApplicableDefault: false, active: true, displayOrder: 1,
}];

const rentLine: LeaseLine = {
    id: "line-1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
    behaviour: "RENT", creditAccountId: "acc-1", creditAccountCode: "2100", creditAccountName: "Advance rent",
    grossAmount: 130000, discountAmount: 0, netAmount: 130000, narration: null, vatApplicable: false,
    periodStart: "2027-03-01", periodEnd: "2028-02-29", addendumId: null,
};

function renderEditor() {
    const lease = {
        id: "succ-1", version: 2, unitId: "u1", renterId: "r1", propertyId: "p1", status: "DRAFT",
        renewedFromLeaseId: "pred-1", startDate: "2027-03-01", endDate: "2028-02-29", rentVatApplicable: false,
        lines: [rentLine],
    } as unknown as LeaseDetail;
    render(
        <NextIntlClientProvider locale="en" messages={en}>
            <LeaseMetadataEditor lease={lease} chargeTypes={CHARGE_TYPES} />
        </NextIntlClientProvider>,
    );
    fireEvent.click(screen.getByText("Edit draft"));
}

const aboveCap = (args?: Record<string, unknown>) => new ApiError(400, "above cap",
    JSON.stringify({ code: "renewal.indexOverrideRequired", ...(args ? { args } : {}) }));

afterEach(() => {
    cleanup();
    updateDraft.mockReset();
});

describe("draft editor: rental index reason (final review I1)", () => {
    it("reveals the reason on renewal.indexOverrideRequired and resubmits with it", async () => {
        updateDraft.mockRejectedValueOnce(aboveCap({ cap: 126000 })).mockResolvedValueOnce({});
        renderEditor();
        expect(screen.queryByTestId("lease-draft-index-reason")).toBeNull();

        fireEvent.click(screen.getByTestId("lease-draft-save"));
        await screen.findByTestId("lease-draft-index-reason");
        expect(screen.getByTestId("lease-draft-index-above").textContent).toContain("126,000");
        expect(updateDraft.mock.calls[0][1]).not.toHaveProperty("indexOverrideReason");
        // No generic error banner: the reason field is the answer.
        expect(screen.queryByTestId("edit-errors")).toBeNull();

        fireEvent.change(screen.getByTestId("lease-draft-index-reason"), { target: { value: "  Agreed in writing  " } });
        fireEvent.click(screen.getByTestId("lease-draft-save"));
        await waitFor(() => expect(updateDraft).toHaveBeenCalledTimes(2));
        const body = updateDraft.mock.calls[1][1];
        expect(body.indexOverrideReason).toBe("Agreed in writing");
        expect(body.lines[0].grossAmount).toBe(130000);
        expect(body.version).toBe(2);
        await waitFor(() => expect(screen.queryByTestId("lease-draft-index-reason")).toBeNull());
    });

    it("names no figure when the server sent no cap", async () => {
        updateDraft.mockRejectedValueOnce(aboveCap());
        renderEditor();
        fireEvent.click(screen.getByTestId("lease-draft-save"));
        const notice = await screen.findByTestId("lease-draft-index-above");
        expect(notice.textContent).toBe(en.RentalIndex.aboveCapNoAmount);
    });

    it("any other refusal shows as before, with no reason field", async () => {
        updateDraft.mockRejectedValueOnce(new ApiError(400, "Only DRAFT leases can be edited", JSON.stringify({ message: "x" })));
        renderEditor();
        fireEvent.click(screen.getByTestId("lease-draft-save"));
        await screen.findByTestId("edit-errors");
        expect(screen.queryByTestId("lease-draft-index-reason")).toBeNull();
        expect(updateDraft.mock.calls[0][1]).not.toHaveProperty("indexOverrideReason");
    });
});
