import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import ar from "../../../../messages/ar.json";
import ChequeGrid, { toChequeRows } from "../ChequeGrid";
import { useState } from "react";
import { focusFirstInvalidMoney } from "@/components/ui/NumberInput";
import type { Cheque } from "@/lib/api/leasing";

/**
 * VAT per instalment on the cheque grid (spec 2026-09-24 §1): a VAT column,
 * editable on DRAFT rows, and a footer that checks Σ row VAT against the
 * contract's VAT — the same equality the post enforces.
 */

vi.mock("@/components/finance/AccountPicker", () => ({
    default: () => <div data-testid="account-picker" />,
}));
// The account cell's picker, reduced to "pick an account" (it fires no input event of its own).
vi.mock("@/components/finance/SettlementAccountPicker", () => ({
    default: ({ onChange }: { onChange: (id: string) => void }) => (
        <button type="button" data-testid="settlement-picker" onClick={() => onChange("acc-9")}>pick</button>
    ),
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

describe("ChequeGrid keyboard entry (scale #19)", () => {
    it("pastes cheque numbers and amounts from Excel down the rows, clearing each pasted row's VAT", () => {
        const onChange = vi.fn();
        renderGrid({ cheques: four, editable: true, onChange, contractValueInclVat: 126000, contractVat: 6000 });
        const no1 = screen.getByLabelText(`${en.Leasing.chequeNo} 1`);
        fireEvent.paste(no1, { clipboardData: { getData: () => "200001\t01/06/2026\tDIB\t\t32,000\n200002\t01/09/2026\tDIB\t\t30,000" } });
        const next: Cheque[] = onChange.mock.calls[0][0];
        expect(next.map(c => [c.chequeNumber, c.chequeDate, c.payeeBank, c.amount, c.vatAmount])).toEqual([
            ["200001", "2026-06-01", "DIB", 32000, null],
            ["200002", "2026-09-01", "DIB", 30000, null],
            ["000101", "2026-05-01", "ENBD", 31500, 1500],
            ["000101", "2026-05-01", "ENBD", 31500, 1500],
        ]);
    });

    it("reports rows past the end and adds them only when asked; added rows go to the server without an id", () => {
        let rows = four.slice(0, 1);
        const onChange = vi.fn((next: Cheque[]) => { rows = next; });
        const { rerender } = renderGrid({ cheques: rows, editable: true, onChange, contractValueInclVat: 126000 });
        fireEvent.paste(screen.getByLabelText(`${en.Leasing.chequeNo} 1`), { clipboardData: { getData: () => "A1\t\t\t\t100\nA2\t\t\t\t200\nA3\t\t\t\t300" } });
        expect(rows).toHaveLength(1);
        expect(rows[0].chequeNumber).toBe("A1");
        rerender(<NextIntlClientProvider locale="en" messages={en}><ChequeGrid cheques={rows} editable onChange={onChange} contractValueInclVat={126000} /></NextIntlClientProvider>);
        expect(screen.getByTestId("cheque-grid-paste-report-rows")).toHaveTextContent("2 pasted rows are past the end of the grid.");
        fireEvent.click(screen.getByTestId("cheque-grid-paste-report-add-rows"));
        expect(rows.map(r => [r.chequeNumber, r.amount, r.status])).toEqual([["A1", 100, "DRAFT"], ["A2", 200, "DRAFT"], ["A3", 300, "DRAFT"]]);
        rerender(<NextIntlClientProvider locale="en" messages={en}><ChequeGrid cheques={rows} editable onChange={onChange} contractValueInclVat={126000} /></NextIntlClientProvider>);
        expect(screen.getByTestId("cheque-grid-paste-report-added")).toHaveTextContent("2 rows added for the paste.");
        expect(toChequeRows(rows).map(r => r.id)).toEqual(["c1", null, null]);
    });

    it("adds only the rows past the end, blank of kind, and keeps edits made since the paste (#104)", () => {
        let rows = [cheque({ id: "c1", seqNo: 1, rowKind: "DEPOSIT" })];
        const onChange = vi.fn((next: Cheque[]) => { rows = next; });
        const view = (r: Cheque[]) => <NextIntlClientProvider locale="en" messages={en}><ChequeGrid cheques={r} editable onChange={onChange} contractValueInclVat={126000} /></NextIntlClientProvider>;
        const { rerender } = render(view(rows));
        fireEvent.paste(screen.getByLabelText(`${en.Leasing.chequeNo} 1`), { clipboardData: { getData: () => "A1\t\t\t\t100\nA2\t\t\t\t200" } });
        rerender(view(rows));
        // The accountant corrects row 1 after pasting, then agrees to add the rest.
        fireEvent.change(screen.getByLabelText(`${en.Leasing.chequeNo} 1`), { target: { value: "A1-fixed" } });
        rerender(view(rows));
        fireEvent.click(screen.getByTestId("cheque-grid-paste-report-add-rows"));
        expect(rows.map(r => r.chequeNumber)).toEqual(["A1-fixed", "A2"]);
        expect(rows.map(r => r.rowKind ?? null)).toEqual(["DEPOSIT", null]);
    });

    it("clears an account cell's flag when an account is picked (#104)", () => {
        let rows = four.slice(0, 2);
        const onChange = vi.fn((next: Cheque[]) => { rows = next; });
        const view = (r: Cheque[]) => <NextIntlClientProvider locale="en" messages={en}><ChequeGrid cheques={r} editable onChange={onChange} contractValueInclVat={126000} /></NextIntlClientProvider>;
        const { rerender } = render(view(rows));
        // Pasting from Payee Bank across into the account column: the account is reported, not applied.
        fireEvent.paste(screen.getByLabelText(`${en.Leasing.payeeBank} 1`), { clipboardData: { getData: () => "DIB\tBank 2" } });
        rerender(view(rows));
        const cell = screen.getAllByTestId("settlement-picker")[0].closest("td")!;
        expect(cell).toHaveAttribute("data-paste-invalid", "true");
        fireEvent.click(screen.getAllByTestId("settlement-picker")[0]);
        rerender(view(rows));
        expect(screen.getAllByTestId("settlement-picker")[0].closest("td")).not.toHaveAttribute("data-paste-invalid");
    });

    it("flags a pasted cell it could not read instead of keeping the old value silently", () => {
        renderGrid({ cheques: four, editable: true, onChange: vi.fn(), contractValueInclVat: 126000 });
        fireEvent.paste(screen.getByLabelText(`${en.Leasing.chequeDate} 1`), { clipboardData: { getData: () => "09/25/2026\tDIB\n01/10/2026\tDIB" } });
        expect(screen.getByTestId("cheque-grid-paste-report-cells")).toHaveTextContent("row 1 Date “09/25/2026”");
        expect(screen.getByLabelText(`${en.Leasing.chequeDate} 1`).closest("td")).toHaveAttribute("data-paste-invalid", "true");
        expect(screen.getByLabelText(`${en.Leasing.chequeDate} 2`).closest("td")).not.toHaveAttribute("data-paste-invalid");
    });

    it("moves to the next row's same cell on Enter", () => {
        renderGrid({ cheques: four, editable: true, onChange: vi.fn(), contractValueInclVat: 126000 });
        const bank1 = screen.getByLabelText(`${en.Leasing.payeeBank} 1`);
        bank1.focus();
        fireEvent.keyDown(bank1, { key: "Enter" });
        expect(document.activeElement).toBe(screen.getByLabelText(`${en.Leasing.payeeBank} 2`));
    });
});

/**
 * Batch 4 review #1: a refused entry typed into an "auto" VAT cell used to report
 * 0, flip the cell's key from auto to set, remount the input and lose the refusal
 * — the grid then saved an explicit VAT of 0 nobody typed.
 */
describe("ChequeGrid VAT cell keeps a refused entry", () => {
    function Harness() {
        const [rows, setRows] = useState<Cheque[]>([cheque({ id: "a", seqNo: 1, vatAmount: null })]);
        return (
            <NextIntlClientProvider locale="en" messages={en}>
                <ChequeGrid cheques={rows} editable contractValueInclVat={31500} contractVat={1500} onChange={setRows} />
                <output data-testid="vat-state">{String(rows[0].vatAmount)}</output>
            </NextIntlClientProvider>
        );
    }

    it.each(["1000.555", "1,5", "."])("stays invalid on %s, reports no explicit VAT, and blocks a save", text => {
        render(<Harness />);
        const input = screen.getByLabelText(`${en.Leasing.vatColumn} 1`) as HTMLInputElement;
        fireEvent.change(input, { target: { value: text } });
        const after = screen.getByLabelText(`${en.Leasing.vatColumn} 1`) as HTMLInputElement;
        expect(after.value).toBe(text);
        expect(after.dataset.moneyInvalid).toBe("true");
        expect(screen.getByTestId("vat-state")).toHaveTextContent("null");
        expect(focusFirstInvalidMoney(document)).toBe(true);
    });
});
