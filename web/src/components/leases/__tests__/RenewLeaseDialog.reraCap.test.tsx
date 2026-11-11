import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { ChargeType, LeaseDetail, LeaseLine, RenewalPreview } from "@/lib/api/leasing";

/**
 * Decree 43 (RERA) in the renewal preview: the cap block renders from the DTO's
 * index fields — red badge when the proposed rent is above the maximum, a
 * within badge otherwise, nothing at all when no range matched — and "Check on
 * DLD" copies the DEWA premise number and opens the calculator in the operator's
 * language. Nothing is fetched from dubailand.gov.ae.
 */

vi.mock("@/components/leases/LeaseLinesGrid", () => ({ default: () => <div data-testid="lines-grid" /> }));

const renew = vi.fn();
const renewalPreview = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, renew: (...a: unknown[]) => renew(...a),
        renewalPreview: (...a: unknown[]) => renewalPreview(...a) } };
});

import RenewLeaseDialog from "../RenewLeaseDialog";

const rent: LeaseLine = {
    id: "line-1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
    behaviour: "RENT", creditAccountId: "acc-1", creditAccountCode: "2100", creditAccountName: "Advance rent",
    grossAmount: 80000, discountAmount: 0, netAmount: 80000, narration: null, vatApplicable: false,
    periodStart: "2026-10-02", periodEnd: "2027-10-01", addendumId: null,
};
const LEASE = { id: "lease-1", propertyId: "p1", startDate: "2026-10-02", endDate: "2027-10-01", lines: [rent] } as unknown as LeaseDetail;
const CHARGE_TYPES = [{ id: "ct-rent", behaviour: "RENT", active: true }] as unknown as ChargeType[];

const BASE: RenewalPreview = {
    baseRent: 80000, newRent: 86400, changePercent: 8, copiedLines: [], skippedOneOffLines: [], warnPercent: null, exceedsWarn: false,
};
/** The measured 2027 two-bed: 80,522–98,416 → 89,469; 80,000 is 10.584 % below → 5 % → 84,000. */
const CAP = {
    indexYear: 2028, indexMin: 80522, indexMax: 98416, indexAverage: 89469, belowAveragePercent: 10.584,
    capPercent: 5, maxRent: 84000, indexEnteredAt: "2026-11-11T09:30:00Z", dewaPremiseNumber: "123456789",
};

function renderDialog(locale: "en" | "ar" = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={locale === "ar" ? ar : en}>
            <RenewLeaseDialog open lease={LEASE} chargeTypes={CHARGE_TYPES} onClose={() => {}} onRenewed={() => {}} />
        </NextIntlClientProvider>,
    );
}

let writeText: ReturnType<typeof vi.fn>;
let open: ReturnType<typeof vi.fn>;

beforeEach(() => {
    writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    open = vi.fn();
    vi.stubGlobal("open", open);
});

afterEach(() => {
    cleanup();
    renew.mockReset();
    renewalPreview.mockReset();
    vi.unstubAllGlobals();
});

describe("RenewLeaseDialog RERA cap (Decree 43)", () => {
    it("shows the range, average, band, maximum and a red badge when the rent is above the cap", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, ...CAP, exceedsCap: true });
        renderDialog();
        const block = await screen.findByTestId("renew-rera-cap");
        expect(block).toHaveAttribute("data-above", "true");
        expect(screen.getByTestId("renew-rera-badge")).toHaveTextContent("Above the RERA cap");
        expect(block).toHaveTextContent("DLD index range 80,522.00 – 98,416.00 (contracts ending 2028)");
        expect(block).toHaveTextContent("Average 89,469.00; current rent 10.6% below it");
        expect(block).toHaveTextContent("Permitted increase 5% → maximum rent 84,000.00");
        expect(block).toHaveTextContent("Estimate from the DLD index range entered on 11/11/2026; verify on DLD.");
        expect(block).toHaveTextContent("123456789");
    });

    it("shows a within badge when the rent is under the cap", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, newRent: 83200, changePercent: 4, ...CAP, exceedsCap: false });
        renderDialog();
        const block = await screen.findByTestId("renew-rera-cap");
        expect(block).toHaveAttribute("data-above", "false");
        expect(screen.getByTestId("renew-rera-badge")).toHaveTextContent("Within the RERA cap");
    });

    it("renders nothing when no index range matched (all cap fields null)", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, indexYear: null, indexMin: null, indexMax: null, indexAverage: null,
            belowAveragePercent: null, capPercent: null, maxRent: null, exceedsCap: null, indexEnteredAt: null, dewaPremiseNumber: null });
        renderDialog();
        await screen.findByTestId("renew-rent-preview");
        expect(screen.queryByTestId("renew-rera-cap")).toBeNull();
    });

    it("renders nothing on an older server that does not send the fields", async () => {
        renewalPreview.mockResolvedValue(BASE);
        renderDialog();
        await screen.findByTestId("renew-rent-preview");
        expect(screen.queryByTestId("renew-rera-cap")).toBeNull();
    });

    it("Check on DLD copies the DEWA number and opens the English calculator", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, ...CAP, exceedsCap: true });
        renderDialog();
        fireEvent.click(await screen.findByTestId("renew-rera-check"));
        await waitFor(() => expect(writeText).toHaveBeenCalledWith("123456789"));
        expect(open).toHaveBeenCalledWith("https://dubailand.gov.ae/en/eservices/rental-index/", "_blank", "noopener,noreferrer");
        expect(await screen.findByText(/DEWA number 123456789 copied/)).toBeInTheDocument();
    });

    it("opens the Arabic calculator in Arabic, and says when the unit has no DEWA number", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, ...CAP, exceedsCap: true, dewaPremiseNumber: null });
        renderDialog("ar");
        const block = await screen.findByTestId("renew-rera-cap");
        expect(block).toHaveTextContent(ar.Renewal.reraNoDewa);
        fireEvent.click(screen.getByTestId("renew-rera-check"));
        await waitFor(() => expect(open).toHaveBeenCalledWith("https://dubailand.gov.ae/ar/eservices/rental-index/", "_blank", "noopener,noreferrer"));
        expect(writeText).not.toHaveBeenCalled();
    });

    it("the cap is advisory: confirming still sends the proposed rent", async () => {
        renewalPreview.mockResolvedValue({ ...BASE, ...CAP, exceedsCap: true });
        renew.mockResolvedValue({ id: "lease-2" });
        renderDialog();
        await screen.findByTestId("renew-rera-cap");
        fireEvent.click(screen.getByTestId("renew-mode-PERCENT"));
        fireEvent.change(screen.getByTestId("renew-percent"), { target: { value: "8" } });
        fireEvent.click(screen.getByTestId("renew-lease-confirm"));
        await waitFor(() => expect(renew).toHaveBeenCalled());
        expect(renew.mock.calls[0][1].rentChange).toEqual({ mode: "PERCENT", percent: 8, newRentAmount: null });
    });
});
