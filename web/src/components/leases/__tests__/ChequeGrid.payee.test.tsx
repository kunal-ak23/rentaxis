import { cleanup, render, screen } from "@testing-library/react";
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

// Owner ruling 2026-09-29: the payee flag, and who confirmed it, stay visible
// on the cheque after the scan was attached.
describe("ChequeGrid payee flag", () => {
    it("shows a confirmed payee mismatch with the name read and who confirmed it", () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <ChequeGrid editable={false} contractValueInclVat={13700} cheques={[
                    cheque({ id: "c1", seqNo: 1, payeeCheck: "MISMATCH", payeeName: "Other Landlord LLC",
                        payeeMismatchConfirmedByName: "Mona Clerk", payeeMismatchConfirmedAt: "2026-09-29T10:00:00Z" }),
                    cheque({ id: "c2", seqNo: 2, chequeNumber: "000102", payeeCheck: "MATCH", payeeName: "Palm Ridge" }),
                ]} />
            </NextIntlClientProvider>,
        );
        const flag = screen.getByTestId("cheque-payee-mismatch-0");
        expect(flag).toHaveTextContent("Payee name does not match: Other Landlord LLC");
        expect(flag).toHaveTextContent("Confirmed by Mona Clerk on 29/09/2026");
        expect(screen.queryByTestId("cheque-payee-mismatch-1")).toBeNull();
    });
});
