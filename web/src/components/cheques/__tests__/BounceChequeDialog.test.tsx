import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import type { Cheque } from "@/lib/api/leasing";

/**
 * `ChequeService.bounce` (:310-370) never reads the request's
 * `debitAccountId`: the credit side of the CBR is the cheque's own debit
 * account for a late return (:329-332), or PDC receivable for a DEPOSITED row
 * (:334). The dialog used to render a picker and a "leave empty to use the
 * cheque's bank account" hint, so an accountant reversing a cleared cheque
 * believed they had chosen which bank is credited back while the journal
 * ignored them.
 */

vi.mock("@/components/finance/AccountPicker", () => ({ default: () => <div data-testid="account-picker" /> }));

const api = vi.hoisted(() => ({ bounce: vi.fn() }));

vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, bounce: api.bounce } };
});

import BounceChequeDialog from "../BounceChequeDialog";

const CHEQUE: Cheque = {
    id: "c1", leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
    propertyName: "L'Olivier", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
    seqNo: 2, postingDate: "2026-06-01", chequeNumber: "000102", chequeDate: "2026-06-01",
    payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "Bank",
    amount: 13700, narration: null, mode: "PDC", status: "DEPOSITED",
    failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
    depositedAt: "2026-06-02", clearedAt: null, bouncedAt: null, returnedAt: null,
    pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
    due: false, overdue: false, daysOverdue: 0, ledgerSettled: false,
};

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("BounceChequeDialog", () => {
    it("offers no debit-account override, because the server ignores one", () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <BounceChequeDialog cheque={CHEQUE} onClose={() => {}} onDone={() => {}} />
            </NextIntlClientProvider>,
        );
        expect(screen.queryByTestId("account-picker")).not.toBeInTheDocument();
        expect(screen.queryByText("Leave empty to use the cheque's bank account.")).not.toBeInTheDocument();
    });

    it("sends the date, the reason and the note — and no account", async () => {
        api.bounce.mockResolvedValueOnce(CHEQUE);
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <BounceChequeDialog cheque={CHEQUE} onClose={() => {}} onDone={() => {}} />
            </NextIntlClientProvider>,
        );
        fireEvent.change(screen.getByTestId("bounce-date"), { target: { value: "2026-06-10" } });
        fireEvent.change(screen.getByTestId("bounce-failure-reason"), { target: { value: "ACCOUNT_CLOSED" } });
        fireEvent.change(screen.getByTestId("bounce-notes"), { target: { value: "Returned by ENBD" } });
        fireEvent.click(screen.getByTestId("cheque-bounce-confirm"));

        await waitFor(() => expect(api.bounce).toHaveBeenCalled());
        expect(api.bounce).toHaveBeenCalledWith("c1", {
            date: "2026-06-10",
            notes: "Returned by ENBD",
            failureReason: "ACCOUNT_CLOSED",
        });
    });

    it("labels the two newer failure reasons, not their raw enum names (F14-22)", () => {
        render(
            <NextIntlClientProvider locale="en" messages={en}>
                <BounceChequeDialog cheque={CHEQUE} onClose={() => {}} onDone={() => {}} />
            </NextIntlClientProvider>,
        );
        const options = Array.from(
            (screen.getByTestId("bounce-failure-reason") as HTMLSelectElement).options,
        ).map(o => ({ value: o.value, text: o.textContent }));
        expect(options).toContainEqual({ value: "STOPPED_PAYMENT", text: "Payment stopped" });
        expect(options).toContainEqual({
            value: "TECHNICAL_RETURN",
            text: "Technical return (stale, post-dated, amount mismatch)",
        });
    });
});
