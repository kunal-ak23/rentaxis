import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import ChequeGrid, { toChequeRows } from "../ChequeGrid";
import type { Cheque } from "@/lib/api/leasing";

/**
 * VAT per instalment on the cheque grid (spec 2026-09-24 §1): a VAT column,
 * editable on DRAFT rows, and a footer that checks Σ row VAT against the
 * contract's VAT — the same equality the post enforces.
 */

vi.mock("@/components/finance/AccountPicker", () => ({
    default: () => <div data-testid="account-picker" />,
}));

function cheque(over: Partial<Cheque> & { id: string; seqNo: number }): Cheque {
    return {
        leaseId: "l1", propertyId: "p1", unitId: "u1", renterId: "r1",
        propertyName: "Tower", unitIdentifier: "A-101", renterName: "Renter",
        postingDate: "2026-04-20", chequeNumber: "000101", chequeDate: "2026-05-01",
        payeeBank: "ENBD", payerName: null, debitAccountId: "acc-1", debitAccountName: "Bank",
        amount: 31500, narration: null, mode: "PDC", status: "DRAFT",
        failureReason: null, replacesId: null, replacedById: null, imageUrl: null,
        depositedAt: null, clearedAt: null, bouncedAt: null, returnedAt: null,
        pdrJournalId: null, crtJournalId: null, cbrJournalId: null, penaltyAssessmentId: null,
        due: false, overdue: false, daysOverdue: 0, ledgerSettled: false, vatAmount: 1500, vatTaxableAmount: 30000,
        ...over,
    };
}

function renderGrid(props: Partial<React.ComponentProps<typeof ChequeGrid>> = {}, messages = en, locale = "en") {
    return render(
        <NextIntlClientProvider locale={locale} messages={messages}>
            <ChequeGrid
                cheques={props.cheques ?? []}
                editable={props.editable ?? false}
                contractValueInclVat={props.contractValueInclVat ?? 0}
                {...props}
            />
        </NextIntlClientProvider>,
    );
}

afterEach(cleanup);

const four = [1, 2, 3, 4].map(n => cheque({ id: `c${n}`, seqNo: n }));

describe("ChequeGrid VAT column", () => {
    it("shows each row's VAT and a green footer when Σ VAT = contract VAT", () => {
        renderGrid({ cheques: four, contractValueInclVat: 126000, contractVat: 6000 });
        expect(screen.getByTestId("cheque-vat-0")).toHaveTextContent("1,500.00");
        expect(screen.getByTestId("cheque-grid-vat-total")).toHaveTextContent("6,000.00");
        const badge = screen.getByTestId("cheque-grid-vat-match");
        expect(badge).toHaveAttribute("data-match", "true");
        expect(badge).toHaveTextContent("Σ VAT 6,000.00 = contract VAT 6,000.00");
    });

    it("flags a grid whose VAT does not add up", () => {
        renderGrid({
            cheques: [cheque({ id: "a", seqNo: 1, vatAmount: 1400 }), ...four.slice(1)],
            contractValueInclVat: 126000,
            contractVat: 6000,
            editable: true,
        });
        const badge = screen.getByTestId("cheque-grid-vat-match");
        expect(badge).toHaveAttribute("data-match", "false");
        expect(badge).toHaveTextContent("Σ VAT 5,900.00 but the contract charges VAT of 6,000.00");
    });

    it("offers to re-spread a VAT column that no longer adds up, handing every row back to the default", () => {
        const onChange = vi.fn();
        renderGrid({
            cheques: [cheque({ id: "a", seqNo: 1, vatAmount: 1400 }), ...four.slice(1)],
            contractValueInclVat: 126000,
            contractVat: 6000,
            editable: true,
            onChange,
        });
        fireEvent.click(screen.getByTestId("cheque-grid-vat-respread"));
        expect(onChange).toHaveBeenCalledTimes(1);
        const rows = onChange.mock.calls[0][0] as Cheque[];
        expect(rows).toHaveLength(4);
        expect(rows.every(r => r.vatAmount === null)).toBe(true);
        expect(toChequeRows(rows).every(r => r.vatAmount === null)).toBe(true);
    });

    it("offers no re-spread when the VAT adds up, or on a read-only grid", () => {
        renderGrid({ cheques: four, contractValueInclVat: 126000, contractVat: 6000, editable: true, onChange: vi.fn() });
        expect(screen.queryByTestId("cheque-grid-vat-respread")).toBeNull();
        cleanup();
        renderGrid({
            cheques: [cheque({ id: "a", seqNo: 1, vatAmount: 1400 }), ...four.slice(1)],
            contractValueInclVat: 126000,
            contractVat: 6000,
        });
        expect(screen.queryByTestId("cheque-grid-vat-respread")).toBeNull();
    });

    it("labels the re-spread action in Arabic", () => {
        renderGrid({
            cheques: [cheque({ id: "a", seqNo: 1, vatAmount: 1400 }), ...four.slice(1)],
            contractValueInclVat: 126000,
            contractVat: 6000,
            editable: true,
            onChange: vi.fn(),
        }, ar, "ar");
        expect(screen.getByTestId("cheque-grid-vat-respread")).toHaveTextContent(ar.Leasing.vatRespread);
    });

    it("hides the column on a contract without VAT", () => {
        renderGrid({
            cheques: [cheque({ id: "a", seqNo: 1, vatAmount: 0, vatTaxableAmount: 0 })],
            contractValueInclVat: 31500,
            contractVat: 0,
        });
        expect(screen.queryByTestId("cheque-vat-0")).toBeNull();
        expect(screen.queryByTestId("cheque-grid-vat-match")).toBeNull();
    });

    it("edits a DRAFT row's VAT, and a changed amount hands the row back to the pro-rata default", () => {
        const onChange = vi.fn();
        renderGrid({ cheques: four, contractValueInclVat: 126000, contractVat: 6000, editable: true, onChange });

        fireEvent.change(screen.getByLabelText("VAT 1"), { target: { value: "1600" } });
        expect(onChange).toHaveBeenLastCalledWith(expect.arrayContaining([
            expect.objectContaining({ id: "c1", vatAmount: 1600 }),
        ]));

        fireEvent.change(screen.getByLabelText("Amount 2"), { target: { value: "30000" } });
        expect(onChange).toHaveBeenLastCalledWith(expect.arrayContaining([
            expect.objectContaining({ id: "c2", amount: 30000, vatAmount: null }),
        ]));
    });

    it("says the VAT will be spread when a row has no figure yet, and sends null for it", () => {
        const rows = [cheque({ id: "a", seqNo: 1, vatAmount: null }), cheque({ id: "b", seqNo: 2, vatAmount: 1500 })];
        renderGrid({ cheques: rows, contractValueInclVat: 63000, contractVat: 3000, editable: true });
        const badge = screen.getByTestId("cheque-grid-vat-match");
        expect(badge).toHaveAttribute("data-match", "pending");
        expect(badge).toHaveTextContent("rows marked auto get their share when the grid is saved");
        expect(toChequeRows(rows).map(r => r.vatAmount)).toEqual([null, 1500]);
    });

    it("does not argue with a legacy lease whose posted rows carry no VAT", () => {
        renderGrid({
            cheques: four.map(c => ({ ...c, status: "REGISTERED" as const, vatAmount: 0 })),
            contractValueInclVat: 126000,
            contractVat: 6000,
        });
        expect(screen.queryByTestId("cheque-grid-vat-match")).toBeNull();
    });

    it("renders in Arabic", () => {
        renderGrid({ cheques: four, contractValueInclVat: 126000, contractVat: 6000 }, ar, "ar");
        expect(screen.getByTestId("cheque-grid-vat-match")).toHaveTextContent("مجموع الضريبة");
    });
});
