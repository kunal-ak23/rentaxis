import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ReplaceChequeDialog from "../ReplaceChequeDialog";
import { ApiError, type Cheque } from "@/lib/api/leasing";

vi.mock("@/components/finance/AccountPicker", () => ({
    default: () => <div data-testid="account-picker" />,
}));

const replace = vi.fn(async () => [] as Cheque[]);
vi.mock("@/lib/api/leasing", async orig => {
    const m = await orig<typeof import("@/lib/api/leasing")>();
    return { ...m, chequeApi: { ...m.chequeApi, replace: (...a: unknown[]) => replace(...(a as [])) } };
});

function bouncedCheque(amount = 10000): Cheque {
    return {
        id: "c1", leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "L'Olivier", unitIdentifier: "A-101", renterName: "Prabhjot Singh",
        seqNo: 1, postingDate: "2026-06-01", chequeNumber: "000101", chequeDate: "2026-06-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "PDC Receivable",
        amount, narration: null, mode: "PDC", status: "BOUNCED",
        failureReason: "BOUNCE", replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: "2026-06-05", returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0,
    };
}

function renderDialog(cheque: Cheque | null) {
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <ReplaceChequeDialog cheque={cheque} propertyId="p1" onClose={() => {}} onDone={() => {}} />
        </NextIntlClientProvider>,
    );
}

afterEach(() => {
    cleanup();
    replace.mockClear();
});

describe("ReplaceChequeDialog", () => {
    it("labels its date field as the replacement date, not a deposit date (#84)", () => {
        renderDialog(bouncedCheque(10000));
        expect(screen.getByLabelText("Replacement date")).toBe(screen.getByTestId("replace-date"));
        expect(screen.queryByText("Deposit Date")).toBeNull();
    });

    it("defaults to one row at the bounced amount, with a residual of zero", () => {
        renderDialog(bouncedCheque(10000));
        expect((screen.getByTestId("replace-row-0-amount") as HTMLInputElement).value).toBe("10000");
        expect(screen.getByTestId("replace-residual")).toHaveTextContent("residual 0.00 stays in rent receivable");
        expect(screen.getByTestId("replace-residual")).toHaveAttribute("data-over", "false");
        expect(screen.getByTestId("replace-confirm")).toBeEnabled();
    });

    it("does not offer ONLINE as a replacement mode — the server refuses it", () => {
        renderDialog(bouncedCheque(10000));
        const options = Array.from((screen.getByTestId("replace-row-0-mode") as HTMLSelectElement).options).map(o => o.value);
        expect(options).toEqual(["PDC", "CASH", "TRANSFER"]);
    });

    it("computes the residual as replacements fall short of the bounced amount", () => {
        renderDialog(bouncedCheque(10000));
        fireEvent.change(screen.getByTestId("replace-row-0-amount"), { target: { value: "6000" } });
        expect(screen.getByTestId("replace-residual")).toHaveTextContent("Replacements total 6,000.00 of 10,000.00 bounced");
        expect(screen.getByTestId("replace-residual")).toHaveTextContent("residual 4,000.00 stays in rent receivable");
        expect(screen.getByTestId("replace-residual")).toHaveAttribute("data-over", "false");
    });

    it("blocks submit and flags the total once replacements exceed the bounced amount", () => {
        renderDialog(bouncedCheque(10000));
        fireEvent.click(screen.getByTestId("replace-add-row"));
        fireEvent.change(screen.getByTestId("replace-row-0-amount"), { target: { value: "8000" } });
        fireEvent.change(screen.getByTestId("replace-row-1-amount"), { target: { value: "5000" } });

        expect(screen.getByTestId("replace-residual")).toHaveAttribute("data-over", "true");
        expect(screen.getByTestId("replace-residual")).toHaveTextContent("exceeds the bounced amount");
        expect(screen.getByTestId("replace-confirm")).toBeDisabled();
    });

    it("submits every row's amount, mode and cheque number and reports the server's refusal", async () => {
        replace.mockRejectedValueOnce(new ApiError(400, "Total replacements exceed the bounced amount"));
        renderDialog(bouncedCheque(10000));

        fireEvent.click(screen.getByTestId("replace-add-row"));
        fireEvent.change(screen.getByTestId("replace-row-0-amount"), { target: { value: "6000" } });
        fireEvent.change(screen.getByTestId("replace-row-0-number"), { target: { value: "CHQ-900" } });
        fireEvent.change(screen.getByTestId("replace-row-1-mode"), { target: { value: "CASH" } });
        fireEvent.change(screen.getByTestId("replace-row-1-amount"), { target: { value: "4000" } });

        fireEvent.click(screen.getByTestId("replace-confirm"));

        await waitFor(() => expect(replace).toHaveBeenCalled());
        expect(replace).toHaveBeenCalledWith("c1", expect.objectContaining({
            replacements: [
                expect.objectContaining({ mode: "PDC", chequeNumber: "CHQ-900", amount: 6000 }),
                expect.objectContaining({ mode: "CASH", chequeNumber: null, amount: 4000 }),
            ],
        }));

        await waitFor(() =>
            expect(screen.getByTestId("replace-error")).toHaveTextContent("Total replacements exceed the bounced amount"),
        );
    });

    it("sends a Cash replacement's date instead of discarding it — every mode needs one", async () => {
        renderDialog(bouncedCheque(10000));
        fireEvent.change(screen.getByTestId("replace-row-0-mode"), { target: { value: "CASH" } });
        fireEvent.change(screen.getByTestId("replace-row-0-date"), { target: { value: "2026-07-15" } });

        expect(screen.getByTestId("replace-confirm")).toBeEnabled();
        fireEvent.click(screen.getByTestId("replace-confirm"));

        await waitFor(() => expect(replace).toHaveBeenCalled());
        // ChequeRowRules.validateRow (:140-144) refuses a null chequeDate for
        // CASH and TRANSFER too — nulling it made Cash and Bank Transfer
        // replacements a guaranteed 400 with no field the operator could fix.
        expect(replace).toHaveBeenCalledWith("c1", expect.objectContaining({
            replacements: [expect.objectContaining({ mode: "CASH", chequeDate: "2026-07-15", chequeNumber: null })],
        }));
    });

    it("blocks submit on a row the server's row rules would refuse", () => {
        renderDialog(bouncedCheque(10000));
        fireEvent.change(screen.getByTestId("replace-row-0-date"), { target: { value: "" } });
        expect(screen.getByTestId("replace-confirm")).toBeDisabled();

        fireEvent.change(screen.getByTestId("replace-row-0-date"), { target: { value: "2026-07-15" } });
        expect(screen.getByTestId("replace-confirm")).toBeEnabled();

        // Two rows may not claim the same cheque number — the server accumulates
        // `seenNumbers` across the payload (ChequeRowRules.java:152).
        fireEvent.click(screen.getByTestId("replace-add-row"));
        fireEvent.change(screen.getByTestId("replace-row-0-amount"), { target: { value: "5000" } });
        fireEvent.change(screen.getByTestId("replace-row-0-number"), { target: { value: "000900" } });
        fireEvent.change(screen.getByTestId("replace-row-1-amount"), { target: { value: "5000" } });
        fireEvent.change(screen.getByTestId("replace-row-1-number"), { target: { value: "000900" } });
        expect(screen.getByTestId("replace-confirm")).toBeDisabled();
    });

    it("removes a row but never below one", () => {
        renderDialog(bouncedCheque(10000));
        expect(screen.getByTestId("replace-row-0-remove")).toBeDisabled();
        fireEvent.click(screen.getByTestId("replace-add-row"));
        expect(screen.getByTestId("replace-row-0-remove")).toBeEnabled();
        fireEvent.click(screen.getByTestId("replace-row-1-remove"));
        expect(screen.queryByTestId("replace-row-1")).not.toBeInTheDocument();
        expect(screen.getByTestId("replace-row-0-remove")).toBeDisabled();
    });
});
