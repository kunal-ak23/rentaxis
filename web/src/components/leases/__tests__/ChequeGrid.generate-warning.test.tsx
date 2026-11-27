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
        propertyName: "Sample Heights", unitIdentifier: "A-101", renterName: "Sample Renter One",
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

// QA-38 (27 Nov prod retest): Generate Cheques replaces the saved grid at once.
// When the grid already holds typed cheque numbers, the panel says so first.
describe("ChequeGrid generate warning", () => {
    function renderGrid(cheques: Cheque[]) {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <ChequeGrid editable contractValueInclVat={27400} cheques={cheques} onChange={() => {}} onGenerate={() => {}} />
            </NextIntlClientProvider>,
        );
        fireEvent.click(screen.getByTestId("cheque-grid-generate"));
    }

    it("warns that numbered cheques will be replaced", () => {
        renderGrid([
            cheque({ id: "c1", seqNo: 1, status: "DRAFT" }),
            cheque({ id: "c2", seqNo: 2, chequeNumber: "000102", status: "DRAFT" }),
        ]);
        expect(screen.getByTestId("cheque-generate-replaces")).toHaveTextContent(
            "This replaces the 2 cheques now in the grid, including the cheque numbers and banks typed on them.");
    });

    it("says nothing when no cheque has a number yet", () => {
        renderGrid([
            cheque({ id: "c1", seqNo: 1, chequeNumber: null, status: "DRAFT" }),
            cheque({ id: "c2", seqNo: 2, chequeNumber: " ", status: "DRAFT" }),
        ]);
        expect(screen.getByTestId("cheque-generate-form")).toBeInTheDocument();
        expect(screen.queryByTestId("cheque-generate-replaces")).toBeNull();
    });
});
