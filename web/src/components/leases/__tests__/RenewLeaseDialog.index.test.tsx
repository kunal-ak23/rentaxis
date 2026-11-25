import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { ChargeType, LeaseDetail } from "@/lib/api/leasing";

vi.mock("@/components/leases/LeaseLinesGrid", () => ({ default: () => <div data-testid="lines-grid" /> }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
}));
const renew = vi.fn();
const renewalPreview = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, renew: (...a: unknown[]) => renew(...a), renewalPreview: (...a: unknown[]) => renewalPreview(...a) } };
});
import RenewLeaseDialog from "../RenewLeaseDialog";
import { ApiError } from "@/lib/api/leasing";
import { fetchRouter } from "@/test/fetchRouter";

const LEASE = {
    id: "lease-1", unitId: "u1", propertyId: "p1", unitIdentifier: "101", startDate: "2026-10-01", endDate: "2027-09-30", rentAmount: 120000, lines: [{ id: "line-1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent", behaviour: "RENT", grossAmount: 120000, discountAmount: 0, netAmount: 120000, vatApplicable: false, periodStart: "2026-10-01", periodEnd: "2027-09-30", addendumId: null }],
} as unknown as LeaseDetail;
const BASE = { baseRent: 120000, newRent: 120000, changePercent: null, copiedLines: [], skippedOneOffLines: [], warnPercent: null, exceedsWarn: false };
const INDEX = {
    indexStatus: "OK", indexMin: 90000, indexMax: 110000, indexAverage: 100000, indexGapPercent: 16, indexAllowedPercent: 5,
    indexCapRent: 126000, indexCheckedOn: "2026-11-01", noticeDeadline: "2027-07-02", noticePassed: false,
};

function renderDialog() {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <RenewLeaseDialog open lease={LEASE} chargeTypes={[] as ChargeType[]} onClose={() => {}} onRenewed={() => {}} />
        </NextIntlClientProvider>,
    );
}

beforeEach(() => {
    fetchRouter().on("GET", "/units/paged", { body: { content: [{ id: "u1", type: "BHK1", dewaPremiseNumber: "123456789" }] } });
});
afterEach(() => { cleanup(); renew.mockReset(); renewalPreview.mockReset(); });

describe("RenewLeaseDialog rental index", () => {
    it("has no panel and no reason field when the preview carries no index", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, indexStatus: null });
        renew.mockResolvedValue({ id: "l2" });
        renderDialog();
        await waitFor(() => expect(renewalPreview).toHaveBeenCalled());
        expect(screen.queryByTestId("rental-index-panel")).toBeNull();
        expect(screen.queryByTestId("renew-index-reason")).toBeNull();
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        expect(renew.mock.calls[0][1]).not.toHaveProperty("indexOverrideReason");
    });

    it("within the cap: panel shows, no reason field", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, ...INDEX, indexAboveCap: false });
        renderDialog();
        expect(await screen.findByTestId("rental-index-panel")).toBeInTheDocument();
        expect(screen.queryByTestId("renew-index-reason")).toBeNull();
        expect(screen.queryByTestId("renew-index-above")).toBeNull();
    });

    it("above the cap: reason required and sent", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, ...INDEX, newRent: 130000, indexAboveCap: true, exceedsWarn: true, warnPercent: 5 });
        renew.mockResolvedValue({ id: "l2" });
        renderDialog();
        expect(await screen.findByTestId("renew-index-above")).toBeInTheDocument();
        expect(screen.queryByTestId("renew-rent-notice")).toBeNull();
        expect(screen.getByTestId("renew-lease-confirm")).toBeDisabled();
        fireEvent.change(screen.getByTestId("renew-index-reason"), { target: { value: "Market moved" } });
        expect(screen.getByTestId("renew-lease-confirm")).toBeEnabled();
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        expect(renew.mock.calls[0][1].indexOverrideReason).toBe("Market moved");
    });

    it("edited-lines mode: reveals the reason when the server asks, keeps inputs, resubmits with it", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, ...INDEX, indexAboveCap: false });
        renew.mockRejectedValueOnce(new ApiError(400, "above cap", JSON.stringify({ code: "renewal.indexOverrideRequired", args: { cap: "126,000" } })));
        renew.mockResolvedValueOnce({ id: "l2" });
        renderDialog();
        fireEvent.click(screen.getByTestId("renew-copy-lines"));
        expect(screen.queryByTestId("renew-index-reason")).toBeNull();
        fireEvent.change(screen.getByTestId("renew-ejari"), { target: { value: "E-1" } });
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        const reason = await screen.findByTestId("renew-index-reason");
        expect(screen.getByTestId("renew-index-above")).toHaveTextContent("126,000");
        expect((screen.getByTestId("renew-ejari") as HTMLInputElement).value).toBe("E-1");
        fireEvent.change(reason, { target: { value: "Agreed with tenant" } });
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalledTimes(2));
        expect(renew.mock.calls[1][1].indexOverrideReason).toBe("Agreed with tenant");
        expect(renew.mock.calls[1][1].ejariNumber).toBe("E-1");
    });
});
