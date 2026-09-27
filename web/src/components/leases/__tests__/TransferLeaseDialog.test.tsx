import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { LeaseDetail, TransferPreview } from "@/lib/api/leasing";

const api = vi.hoisted(() => ({ transferPreview: vi.fn(), transfer: vi.fn() }));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, ...api } };
});
const lookup = vi.hoisted(() => ({ searchUnits: vi.fn(), searchRenters: vi.fn(), unitNames: vi.fn(), renterNames: vi.fn() }));
vi.mock("@/lib/api/lookup", () => ({ lookupApi: lookup }));

import TransferLeaseDialog, { gapFor } from "../TransferLeaseDialog";

const LEASE = { id: "lease-a", unitId: "u-102", startDate: "2026-01-01", endDate: "2026-12-31",
    rentVatApplicable: false } as unknown as LeaseDetail;

/** The spec's worked example. */
const PREVIEW: TransferPreview = {
    moveDate: "2026-05-15", targetUnitId: "u-201", newStart: "2026-05-16", newEnd: "2026-12-31", newDays: 230,
    earnedThrough: 22191.78, unearned: 37808.22, unearnedVat: 0, balanceCarried: -7808.22, depositCarried: 3000,
    suggestedRent: 37808.22,
    cheques: [
        { chequeId: "jul", seqNo: 4, chequeNumber: "610043", chequeDate: "2026-07-01", amount: 15000, status: "REGISTERED", disposition: "CARRY" },
        { chequeId: "oct", seqNo: 5, chequeNumber: "610044", chequeDate: "2026-10-01", amount: 15000, status: "REGISTERED", disposition: "CARRY" },
    ],
    carriedTotal: 30000, gapToCollect: 0, problems: [],
};

function renderDialog(locale: "en" | "ar" = "en", onDrafted = vi.fn()) {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <TransferLeaseDialog open lease={LEASE} onClose={() => {}} onDrafted={onDrafted} />
        </NextIntlClientProvider>,
    );
    return onDrafted;
}

const unit = (id: string, unitNumber: string) => ({
    id, unitNumber, propertyId: "p1", propertyName: "Palm Tower", propertyType: "RESIDENTIAL",
    buildingId: null, buildingName: null, status: "VACANT",
});

afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("gapFor (spec §2)", () => {
    it("adds returned paper and the rent's change to the preview's gap", () => {
        expect(gapFor(PREVIEW, {}, 41589.04, false)).toBe(3780.82);
        expect(gapFor(PREVIEW, { jul: "RETURN" }, 41589.04, false)).toBe(18780.82);
        expect(gapFor(PREVIEW, { jul: "KEEP" }, 41589.04, false)).toBe(3780.82);
        expect(gapFor(PREVIEW, {}, 37808.22 + 100, true)).toBe(105);
    });
});

describe("TransferLeaseDialog", () => {
    it("searches vacant units other than its own, pre-fills the rent, and drafts with the cheque plan", async () => {
        lookup.searchUnits.mockResolvedValue([unit("u-102", "A-102"), unit("u-201", "A-201")]);
        api.transferPreview.mockResolvedValue(PREVIEW);
        api.transfer.mockResolvedValue({ id: "lease-b" });
        const onDrafted = renderDialog();
        fireEvent.change(screen.getByTestId("transfer-move-date"), { target: { value: "2026-05-15" } });
        fireEvent.click(screen.getByTestId("transfer-unit"));
        const target = await screen.findByText("A-201");
        expect(screen.queryByText("A-102")).toBeNull(); // the lease's own unit is not offered
        expect(lookup.searchUnits).toHaveBeenCalledWith(expect.objectContaining({ q: "", status: "VACANT" }));
        fireEvent.click(target);
        expect(screen.getByTestId("transfer-unit")).toHaveTextContent("A-201");
        await waitFor(() => expect((screen.getByTestId("transfer-rent") as HTMLInputElement).value).toBe("37808.22"));
        expect(api.transferPreview).toHaveBeenCalledWith("lease-a", "2026-05-15", "u-201", "2026-12-31");
        fireEvent.change(screen.getByTestId("transfer-rent"), { target: { value: "41589.04" } });
        expect(screen.getByTestId("transfer-gap")).toHaveTextContent("3,780.82");
        expect(screen.getByTestId("transfer-balance")).toHaveTextContent("7,808.22");
        fireEvent.click(screen.getByTestId("transfer-return-jul"));
        expect(screen.getByTestId("transfer-carried")).toHaveTextContent("15,000.00");
        fireEvent.click(screen.getByTestId("transfer-confirm"));
        await waitFor(() => expect(onDrafted).toHaveBeenCalledWith({ id: "lease-b" }));
        expect(api.transfer).toHaveBeenCalledWith("lease-a", expect.objectContaining({
            moveDate: "2026-05-15", targetUnitId: "u-201", rent: 41589.04,
            chequeDispositions: [{ chequeId: "jul", disposition: "RETURN" }, { chequeId: "oct", disposition: "CARRY" }] }));
    });

    it("reads in Arabic, with amounts kept left-to-right", async () => {
        lookup.searchUnits.mockResolvedValue([unit("u-201", "A-201")]);
        api.transferPreview.mockResolvedValue(PREVIEW);
        renderDialog("ar");
        fireEvent.change(screen.getByTestId("transfer-move-date"), { target: { value: "2026-05-15" } });
        expect(screen.getByTestId("transfer-unit")).toHaveTextContent(ar.Leasing.transfer.chooseUnit);
        fireEvent.click(screen.getByTestId("transfer-unit"));
        fireEvent.click(await screen.findByText("A-201"));
        const summary = await screen.findByTestId("transfer-summary");
        expect(summary).toHaveTextContent("مدفوع مقدمًا");
        expect(screen.getByTestId("transfer-balance").querySelector("bdi[dir='ltr']")?.textContent).toBe("7,808.22");
        expect(screen.getByTestId("transfer-carry-jul")).toHaveTextContent("نقل");
    });
});

/** Break-it round 1 (money) F2: a rent of 0.001 drafted a successor with RENT 0. */
describe("TransferLeaseDialog rent guard (F1/F2)", () => {
    async function openToRent() {
        lookup.searchUnits.mockResolvedValue([unit("u-201", "A-201")]);
        api.transferPreview.mockResolvedValue(PREVIEW);
        renderDialog();
        fireEvent.change(screen.getByTestId("transfer-move-date"), { target: { value: "2026-05-15" } });
        fireEvent.click(screen.getByTestId("transfer-unit"));
        fireEvent.click(await screen.findByText("A-201"));
        await waitFor(() => expect((screen.getByTestId("transfer-rent") as HTMLInputElement).value).toBe("37808.22"));
    }

    it("refuses a rent below one fil, with three decimals or in a mis-grouped form", async () => {
        await openToRent();
        for (const [text, key] of [["0.001", "min"], ["0", "min"], ["41589.045", "decimals"], ["41,58", "format"]] as const) {
            fireEvent.change(screen.getByTestId("transfer-rent"), { target: { value: text } });
            expect(screen.getByTestId("transfer-confirm"), text).toBeDisabled();
            expect(screen.getByRole("alert")).toHaveTextContent(en.MoneyInput[key]);
        }
    });

    it("sends a grouped rent exactly", async () => {
        api.transfer.mockResolvedValue({ id: "lease-b" });
        await openToRent();
        fireEvent.change(screen.getByTestId("transfer-rent"), { target: { value: "41,589.04" } });
        fireEvent.click(screen.getByTestId("transfer-confirm"));
        await waitFor(() => expect(api.transfer).toHaveBeenCalled());
        expect(api.transfer.mock.calls[0][1].rent).toBe(41589.04);
    });
});
