import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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

function renderGrid(props: Partial<React.ComponentProps<typeof ChequeGrid>>) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ChequeGrid cheques={props.cheques ?? []} editable={false} contractValueInclVat={0} {...props} />
        </NextIntlClientProvider>,
    );
}

afterEach(cleanup);

describe("ChequeGrid combined receipt", () => {
    const rows = [
        cheque({ id: "c1", seqNo: 1, status: "CLEARED" }),
        cheque({ id: "c2", seqNo: 2, status: "DEPOSITED" }),
        cheque({ id: "c3", seqNo: 3, status: "CLEARED", mode: "CASH" }),
    ];

    it("offers a checkbox only on rows that have a receipt, and hands the picked ones over", async () => {
        const onCombinedReceipt = vi.fn().mockResolvedValue(undefined);
        renderGrid({ cheques: rows, onRowAction: vi.fn(), onCombinedReceipt });

        expect(screen.getByTestId("cheque-receipt-select-0")).toBeInTheDocument();
        expect(screen.queryByTestId("cheque-receipt-select-1")).not.toBeInTheDocument();
        expect(screen.getByTestId("cheque-receipt-select-2")).toBeInTheDocument();

        const button = screen.getByTestId("combined-receipt-download");
        expect(button).toBeDisabled();
        expect(button).toHaveTextContent("Combined receipt (0)");

        fireEvent.click(screen.getByTestId("cheque-receipt-select-0"));
        fireEvent.click(screen.getByTestId("cheque-receipt-select-2"));
        expect(button).toHaveTextContent("Combined receipt (2)");
        fireEvent.click(button);
        await waitFor(() => expect(onCombinedReceipt).toHaveBeenCalledWith(["c1", "c3"]));
    });

    it("shows no selection without the handler, or while the grid is being edited", () => {
        renderGrid({ cheques: rows, onRowAction: vi.fn() });
        expect(screen.queryByTestId("combined-receipt-bar")).not.toBeInTheDocument();
        expect(screen.queryByTestId("cheque-receipt-select-0")).not.toBeInTheDocument();
        cleanup();
        renderGrid({ cheques: rows, editable: true, onCombinedReceipt: vi.fn() });
        expect(screen.queryByTestId("combined-receipt-bar")).not.toBeInTheDocument();
    });
});
