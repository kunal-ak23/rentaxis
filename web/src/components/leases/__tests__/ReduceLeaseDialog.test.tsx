import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import type { LeaseDetail, LeaseLine, ReductionPreview } from "@/lib/api/leasing";

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));
vi.mock("@/components/finance/SettlementAccountPicker", () => ({ default: () => <div /> }));
const reductionPreview = vi.fn();
const reduce = vi.fn();
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return {
        ...m,
        leaseApi: {
            ...m.leaseApi,
            reductionPreview: (...a: unknown[]) => reductionPreview(...a),
            reduce: (...a: unknown[]) => reduce(...a),
        },
    };
});

import ReduceLeaseDialog, { reducibleLines } from "../ReduceLeaseDialog";

const base: LeaseLine = {
    id: "rent", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
    chargeTypeNameAr: "الإيجار", behaviour: "RENT", creditAccountId: null, creditAccountCode: null,
    creditAccountName: null, grossAmount: 51000, discountAmount: 0, netAmount: 51000, narration: null,
    vatApplicable: false, periodStart: null, periodEnd: null, recognition: "RENT_LIKE", postedRecognition: "RENT_LIKE",
};
const LINES: LeaseLine[] = [
    base,
    { ...base, id: "admin", seqNo: 2, chargeTypeCode: "ADMIN_FEE", chargeTypeName: "Admin Fee", behaviour: "FEE",
      netAmount: 2000, grossAmount: 2000, recognition: "ONE_OFF", postedRecognition: "ONE_OFF" },
    { ...base, id: "parking", seqNo: 3, chargeTypeCode: "PARKING_FEE", chargeTypeName: "Parking", behaviour: "FEE",
      netAmount: 3650, grossAmount: 3650, recognition: "RENT_LIKE", postedRecognition: "ONE_OFF" },
    { ...base, id: "dep", seqNo: 4, chargeTypeCode: "SECURITY_DEPOSIT", chargeTypeName: "Deposit", behaviour: "DEPOSIT",
      netAmount: 5000, grossAmount: 5000, postedRecognition: "NONE" },
];
const LEASE = { id: "lease-1", propertyId: "p1", startDate: "2026-09-24", endDate: "2027-09-23", lines: LINES } as unknown as LeaseDetail;

const PREVIEW: ReductionPreview = {
    effectiveFrom: "2027-03-01",
    lines: [{ lineId: "rent", chargeTypeCode: "RENT", chargeTypeName: "Rent", chargeTypeNameAr: null, lineAmount: 51000,
        newLineAmount: 39000, from: "2027-03-01", to: "2027-09-23", remainingDays: 207, remainingBefore: 28923.29,
        remainingAfter: 22117.81, credit: 6805.48, vat: 0 }],
    creditNet: 6805.48, vatFromDeferred: 0, vatCreditNote: 0, creditTotal: 6805.48,
    returnable: [{ id: "c4", seqNo: 5, chequeNumber: "300044", chequeDate: "2027-07-02", amount: 12750, vatAmount: 0 }],
    returnedTotal: 0, newRowsTotal: 0, gap: 0, problems: [],
};

function renderDialog(locale: "en" | "ar" = "en", onReduced = vi.fn()) {
    render(
        <NextIntlClientProvider locale={locale} messages={locale === "en" ? en : ar}>
            <ReduceLeaseDialog open lease={LEASE} onClose={() => {}} onReduced={onReduced} />
        </NextIntlClientProvider>,
    );
    return onReduced;
}

afterEach(() => { cleanup(); reductionPreview.mockReset(); reduce.mockReset(); });

describe("ReduceLeaseDialog (F14-32)", () => {
    it("offers only what the books earn over the term: rent, not a one-off, an at-posting fee or a deposit", () => {
        expect(reducibleLines(LINES).map(l => l.id)).toEqual(["rent"]);
    });

    it("prices the cut per day and posts it as a credit left on the renter's account", async () => {
        reductionPreview.mockResolvedValue(PREVIEW);
        reduce.mockResolvedValue({ addendum: { addendumNumber: "ADD-27/1" }, posting: {} });
        const onReduced = renderDialog();
        expect(screen.getByTestId("reduce-confirm")).toBeDisabled();
        fireEvent.change(screen.getByTestId("reduce-effective-from"), { target: { value: "2027-03-01" } });
        fireEvent.click(screen.getByTestId("reduce-pick-rent"));
        fireEvent.change(screen.getByTestId("reduce-amount-rent"), { target: { value: "39000" } });
        await waitFor(() => expect(screen.getByTestId("reduce-total")).toHaveTextContent("6,805.48"));
        expect(reductionPreview).toHaveBeenLastCalledWith("lease-1", expect.objectContaining({
            effectiveFrom: "2027-03-01", excess: "CREDIT", lines: [{ lineId: "rent", newAmount: 39000 }] }));
        fireEvent.click(screen.getByTestId("reduce-confirm"));
        await waitFor(() => expect(onReduced).toHaveBeenCalled());
        expect(reduce).toHaveBeenCalledWith("lease-1", expect.objectContaining({ returnChequeIds: [], cheques: [] }));
    });

    it("handing cheques back must come to the credit before it can post", async () => {
        reductionPreview.mockImplementation(async (_id: string, body: { returnChequeIds: string[] }) => ({
            ...PREVIEW,
            returnedTotal: body.returnChequeIds.length ? 12750 : 0,
            gap: body.returnChequeIds.length ? -5944.52 : 6805.48,
        }));
        renderDialog();
        fireEvent.change(screen.getByTestId("reduce-effective-from"), { target: { value: "2027-03-01" } });
        fireEvent.click(screen.getByTestId("reduce-pick-rent"));
        fireEvent.change(screen.getByTestId("reduce-amount-rent"), { target: { value: "39000" } });
        fireEvent.click(screen.getByTestId("reduce-excess-cheques"));
        fireEvent.click(await screen.findByTestId("reduce-return-c4"));
        await waitFor(() => expect(screen.getByTestId("reduce-gap")).toHaveTextContent("-5,944.52"));
        expect(screen.getByTestId("reduce-confirm")).toBeDisabled();
    });

    it("a cheque handed back that is exactly the credit needs no replacement", async () => {
        reductionPreview.mockImplementation(async (_id: string, body: { returnChequeIds: string[] }) => ({
            ...PREVIEW, returnedTotal: body.returnChequeIds.length ? 6805.48 : 0,
            gap: body.returnChequeIds.length ? 0 : 6805.48,
        }));
        renderDialog();
        fireEvent.change(screen.getByTestId("reduce-effective-from"), { target: { value: "2027-03-01" } });
        fireEvent.click(screen.getByTestId("reduce-pick-rent"));
        fireEvent.click(screen.getByTestId("reduce-excess-cheques"));
        fireEvent.click(await screen.findByTestId("reduce-return-c4"));
        await waitFor(() => expect(screen.getByTestId("reduce-confirm")).not.toBeDisabled());
    });

    it("shows a refusal in Arabic from its code", async () => {
        reductionPreview.mockResolvedValue({ ...PREVIEW, problems: [{ code: "lease.reductionNotLower",
            message: "Line 1 (RENT): not lower", args: { line: 1, code: "RENT" } }] });
        renderDialog("ar");
        fireEvent.change(screen.getByTestId("reduce-effective-from"), { target: { value: "2027-03-01" } });
        fireEvent.click(screen.getByTestId("reduce-pick-rent"));
        expect(await screen.findByTestId("reduce-problems")).toHaveTextContent("المبلغ الجديد ليس أقل");
        expect(screen.getByTestId("reduce-confirm")).toBeDisabled();
    });
});
