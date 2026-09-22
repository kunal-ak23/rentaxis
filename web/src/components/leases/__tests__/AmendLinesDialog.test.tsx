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

function line(seqNo: number, grossAmount: number) {
    return {
        id: `ln${seqNo}`, seqNo, chargeTypeId: "ct-rent", chargeTypeCode: "RENT", chargeTypeName: "Rent",
        behaviour: "RENT" as const, creditAccountId: "acc-1", creditAccountCode: "210100",
        creditAccountName: "Advance Rent", grossAmount, discountAmount: 0, netAmount: grossAmount,
        narration: null, vatApplicable: true, periodStart: null, periodEnd: null,
    };
}

/**
 * Two lines totalling 60,000.00 net — 63,000.00 incl. VAT, which is exactly
 * what the four registered cheques below add up to. An amendment may move
 * money between the two lines; it may not change what they come to.
 */
const LEASE = {
    id: "lease-1", propertyId: "p1", endDate: "2026-12-31", displayContractNumber: "TCO-26/15",
    lines: [line(1, 40000), line(2, 20000)],
} as unknown as LeaseDetail;

/** Σ = 63,000.00 incl. VAT. */
const MATCHING_CHEQUES = [cheque("REGISTERED", 1), cheque("REGISTERED", 2), cheque("REGISTERED", 3), cheque("REGISTERED", 4)];

function cheque(status: Cheque["status"], seqNo = 1): Cheque {
    return {
        id: `c${seqNo}`, leaseId: "lease-1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: null, unitIdentifier: null, renterName: null, seqNo,
        postingDate: "2026-01-01", chequeNumber: `00010${seqNo}`, chequeDate: "2026-01-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: null, debitAccountName: null,
        amount: 15750, narration: null, mode: "PDC", status,
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

    it("stays disabled without a reason, and posts the redistributed lines once given", async () => {
        renderDialog(MATCHING_CHEQUES);
        const confirm = screen.getByTestId("amend-lines-confirm");
        expect(confirm).toBeDisabled();

        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "Parking removed at renewal" } });
        expect(confirm).toBeEnabled();

        // Money moved between the two lines; the contract value is unchanged,
        // which is the only kind of amendment the server accepts.
        fireEvent.change(screen.getByTestId("lease-line-amount-0"), { target: { value: "45000" } });
        fireEvent.change(screen.getByTestId("lease-line-amount-1"), { target: { value: "15000" } });
        expect(screen.getByTestId("amend-match")).toHaveAttribute("data-match", "true");
        fireEvent.click(confirm);

        await waitFor(() => expect(amendLines).toHaveBeenCalled());
        expect(amendLines).toHaveBeenCalledWith("lease-1", {
            lines: [
                expect.objectContaining({ chargeTypeId: "ct-rent", grossAmount: 45000 }),
                expect.objectContaining({ chargeTypeId: "ct-rent", grossAmount: 15000 }),
            ],
            reason: "Parking removed at renewal",
        });
    });

    it("blocks an amendment that re-prices the contract, and says why (#267)", () => {
        renderDialog(MATCHING_CHEQUES);
        const confirm = screen.getByTestId("amend-lines-confirm");
        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "Rent increase" } });
        expect(confirm).toBeEnabled();

        fireEvent.change(screen.getByTestId("lease-line-amount-0"), { target: { value: "50000" } });

        // LeasePostingService.validate(..., FOR_AMEND) re-checks Σ cheques
        // against the new lines incl. VAT (:431-436) — the untouched grid is
        // now 10,500.00 short and the server refuses.
        const badge = screen.getByTestId("amend-match");
        expect(badge).toHaveAttribute("data-match", "false");
        expect(badge).toHaveTextContent("Cheques total 63,000.00 but contract value is 73,500.00");
        expect(badge).toHaveTextContent("An amendment redistributes the contract value between the lines");
        expect(confirm).toBeDisabled();
    });

    it("keeps confirm disabled once a discount exceeds its own line's amount", () => {
        // A reason alone used to be enough to enable Confirm — the dialog
        // posted the same bad line the wizard would have refused to advance
        // past, and let the server's 400 catch it instead.
        renderDialog(MATCHING_CHEQUES);
        const confirm = screen.getByTestId("amend-lines-confirm");
        fireEvent.change(screen.getByTestId("amend-reason"), { target: { value: "Parking removed at renewal" } });
        expect(confirm).toBeEnabled();

        fireEvent.change(screen.getByTestId("lease-line-discount-0"), { target: { value: "40000.01" } });
        expect(confirm).toBeDisabled();

        fireEvent.change(screen.getByTestId("lease-line-discount-0"), { target: { value: "0" } });
        expect(confirm).toBeEnabled();
    });
});
