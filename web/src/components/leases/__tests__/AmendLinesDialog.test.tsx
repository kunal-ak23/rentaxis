import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import AmendLinesDialog, { amendBlockedBy } from "../AmendLinesDialog";
import type { ChargeType, Cheque, LeaseDetail } from "@/lib/api/leasing";

vi.mock("@/components/finance/AccountPicker", () => ({
    default: () => <div data-testid="account-picker" />,
}));

const amendLines = vi.fn(async () => ({
    lease: {} as LeaseDetail, tcoJournalId: "j2", tcoEntryNumber: "TCO-26/16", cheques: [],
}));
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, leaseApi: { ...m.leaseApi, amendLines: (...a: unknown[]) => amendLines(...(a as [])) } };
});

const CHARGE_TYPES: ChargeType[] = [{
    id: "ct-rent", code: "RENT", nameEn: "Rent", nameAr: null, role: "ADVANCE_RENT",
    behaviour: "RENT", vatApplicableDefault: true, active: true, displayOrder: 1,
}];

const LEASE = {
    id: "lease-1", propertyId: "p1", endDate: "2026-12-31", displayContractNumber: "TCO-26/15",
    lines: [{
        id: "ln1", seqNo: 1, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
        behaviour: "RENT" as const, creditAccountId: "acc-1", creditAccountCode: "210100",
        creditAccountName: "Advance Rent", grossAmount: 60000, discountAmount: 0, netAmount: 60000,
        narration: null, vatApplicable: true, periodStart: null, periodEnd: null,
    }],
} as unknown as LeaseDetail;

function cheque(status: Cheque["status"], seqNo = 1): Cheque {
    return {
        id: `c${seqNo}`, leaseId: "lease-1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: null, unitIdentifier: null, renterName: null, seqNo,
        postingDate: "2026-01-01", chequeNumber: `00010${seqNo}`, chequeDate: "2026-01-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: null, debitAccountName: null,
        amount: 15000, narration: null, mode: "PDC", status,
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0,
    };
}

function renderDialog(cheques: Cheque[]) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <AmendLinesDialog
                open
                lease={LEASE}
                cheques={cheques}
                chargeTypes={CHARGE_TYPES}
                onClose={() => {}}
                onAmended={() => {}}
            />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    amendLines.mockClear();
});

describe("AmendLinesDialog", () => {
    it("names the cheque that closed the door once one has been banked", () => {
        // The money has started moving against a contract value the amendment
        // would change underneath it — the backend refuses, so this does too,
        // with the reason visible instead of as a 400 after retyping the lines.
        expect(amendBlockedBy([cheque("REGISTERED"), cheque("DEPOSITED", 2)])?.id).toBe("c2");
        expect(amendBlockedBy([cheque("REGISTERED"), cheque("REGISTERED", 2)])).toBeNull();

        renderDialog([cheque("REGISTERED"), cheque("DEPOSITED", 2)]);
        expect(screen.getByTestId("amend-blocked")).toHaveTextContent(
            "Cheque 000102 is Deposited. Amend is only possible while every cheque is still Registered.",
        );
        expect(screen.getByTestId("amend-lines-confirm")).toBeDisabled();
        expect(screen.getByTestId("amend-reason")).toBeDisabled();
    });

    it("stays disabled without a reason, and posts the lines with it once given", async () => {
        renderDialog([cheque("REGISTERED")]);
        const confirm = screen.getByTestId("amend-lines-confirm");
        expect(confirm).toBeDisabled();

        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "Parking removed at renewal" } });
        expect(confirm).toBeEnabled();

        fireEvent.change(screen.getByTestId("lease-line-amount-0"), { target: { value: "55000" } });
        fireEvent.click(confirm);

        await waitFor(() => expect(amendLines).toHaveBeenCalled());
        expect(amendLines).toHaveBeenCalledWith("lease-1", {
            lines: [expect.objectContaining({ chargeTypeId: "ct-rent", grossAmount: 55000 })],
            reason: "Parking removed at renewal",
        });
    });

    it("keeps confirm disabled once a discount exceeds its own line's amount", () => {
        // A reason alone used to be enough to enable Confirm — the dialog
        // posted the same bad line the wizard would have refused to advance
        // past, and let the server's 400 catch it instead.
        renderDialog([cheque("REGISTERED")]);
        const confirm = screen.getByTestId("amend-lines-confirm");
        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "Parking removed at renewal" } });
        expect(confirm).toBeEnabled();

        fireEvent.change(screen.getByTestId("lease-line-amount-0"), { target: { value: "55000" } });
        fireEvent.change(screen.getByTestId("lease-line-discount-0"), { target: { value: "55000.01" } });
        expect(confirm).toBeDisabled();

        fireEvent.change(screen.getByTestId("lease-line-discount-0"), { target: { value: "0" } });
        expect(confirm).toBeEnabled();
    });
});
