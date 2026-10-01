import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ChequeGrid from "../ChequeGrid";
import type { Cheque } from "@/lib/api/leasing";

vi.mock("@/components/finance/AccountPicker", () => ({
    default: () => <div data-testid="account-picker" />,
}));

function cheque(over: Partial<Cheque> & { id: string; seqNo: number }): Cheque {
    return {
        leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
        postingDate: "2026-01-01", chequeNumber: "000101", chequeDate: "2026-01-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "PDC Receivable",
        amount: 13700, narration: "Rent - 1st Installment", mode: "PDC", status: "REGISTERED",
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
        ...over,
    };
}

afterEach(cleanup);

const wrap = (ui: React.ReactNode) => <NextIntlClientProvider locale="en" messages={en}>{ui}</NextIntlClientProvider>;

// Restored (lost in the v2 rebuild): each cheque of a posted contract can take
// its own scan.
describe("ChequeGrid scan entry points", () => {
    it("offers Attach scan on a registered PDC row of a running contract, not on cash or banked rows", () => {
        const onScanRow = vi.fn();
        render(wrap(
            <ChequeGrid editable={false} contractValueInclVat={41100} leaseStatus="ACTIVE" onRowAction={() => {}} onScanRow={onScanRow} cheques={[
                cheque({ id: "c1", seqNo: 1 }),
                cheque({ id: "c2", seqNo: 2, chequeNumber: "000102", status: "DEPOSITED" }),
                cheque({ id: "c3", seqNo: 3, chequeNumber: null, mode: "CASH" }),
            ]} />,
        ));
        fireEvent.click(screen.getByTestId("cheque-action-scan-0"));
        expect(onScanRow).toHaveBeenCalledWith(expect.objectContaining({ id: "c1" }));
        expect(screen.getByTestId("cheque-action-scan-0")).toHaveTextContent("Attach scan");
        expect(screen.queryByTestId("cheque-action-scan-1")).toBeNull();
        expect(screen.queryByTestId("cheque-action-scan-2")).toBeNull();
    });

    it("offers no scan on a closed contract", () => {
        render(wrap(
            <ChequeGrid editable={false} contractValueInclVat={13700} leaseStatus="CLOSED" onRowAction={() => {}} onScanRow={() => {}}
                cheques={[cheque({ id: "c1", seqNo: 1 })]} />,
        ));
        expect(screen.queryByTestId("cheque-action-scan-0")).toBeNull();
    });
});
