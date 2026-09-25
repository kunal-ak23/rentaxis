import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import { normaliseDigits, parseAmountCell, parseClipboardGrid, parseDateCell, pasteIntoRows, type GridField } from "../chequeGridKeys";

vi.mock("@/components/finance/SettlementAccountPicker", () => ({ default: () => <input aria-label="account" /> }));
import ChequeRowsEditor, { blankChequeRow, type ChequeDraft } from "../ChequeRowsEditor";

afterEach(cleanup);
const label = (m: string) => ({ PDC: "Post-dated cheque", CASH: "Cash", TRANSFER: "Bank transfer" } as Record<string, string>)[m] ?? m;

describe("cell parsing", () => {
    it("reads dd/mm/yyyy and yyyy-mm-dd, and refuses US-style and impossible dates", () => {
        expect(parseDateCell("25/09/2026")).toBe("2026-09-25");
        expect(parseDateCell("05-01-2026")).toBe("2026-01-05");
        expect(parseDateCell("2026-09-25")).toBe("2026-09-25");
        expect(parseDateCell("09/25/2026")).toBeNull();
        expect(parseDateCell("31/02/2026")).toBeNull();
        expect(parseDateCell("5/1/26")).toBeNull();
        expect(parseDateCell("next week")).toBeNull();
    });
    it("reads Arabic-Indic digits and Arabic separators", () => {
        expect(parseDateCell("٢٥/٠٩/٢٠٢٦")).toBe("2026-09-25");
        expect(parseAmountCell("١٢٬٧٥٠٫٥٠")).toBe(12750.5);
        expect(parseAmountCell("۱۲۷۵۰")).toBe(12750);
        expect(normaliseDigits("رقم ١٠٠٠٢٦")).toBe("رقم 100026");
    });
    it("reads thousands separators, the unambiguous European shape, and refuses the rest", () => {
        expect(parseAmountCell("12,750.00")).toBe(12750);
        expect(parseAmountCell("AED 4,250.5")).toBe(4250.5);
        expect(parseAmountCell("12.750,00")).toBe(12750);
        expect(parseAmountCell("1.234.567,8")).toBe(1234567.8);
        expect(parseAmountCell("12,75")).toBeNull();
        expect(parseAmountCell("12.750")).toBeNull();
        expect(parseAmountCell("12,75,000")).toBeNull();
        expect(parseAmountCell("(1,000)")).toBeNull();
        expect(parseAmountCell("-5")).toBeNull();
        expect(parseAmountCell("abc")).toBeNull();
    });
    it("splits Excel's clipboard into rows and cells", () => {
        expect(parseClipboardGrid("a\tb\r\nc\td\r\n")).toEqual([["a", "b"], ["c", "d"]]);
    });
});

describe("pasteIntoRows", () => {
    const fields: GridField[] = ["postingDate", "chequeNumber", "chequeDate", "payeeBank", "debitAccountId", "amount", "mode"];
    it("fills from the focused cell along the column order and adds rows when the grid can grow", () => {
        const rows = [{ chequeNumber: "", amount: 0 }] as Record<string, unknown>[];
        const o = pasteIntoRows(rows, [["100026", "24/09/2026", "DIB", "", "12,750.00", "Cash"], ["100027", "24/10/2026", "DIB", "", "12,750", "pdc"]],
            0, "chequeNumber", fields, label, i => ({ key: i }));
        expect(o.rows).toEqual([
            { chequeNumber: "100026", chequeDate: "2026-09-24", payeeBank: "DIB", amount: 12750, mode: "CASH" },
            { key: 1, chequeNumber: "100027", chequeDate: "2026-10-24", payeeBank: "DIB", amount: 12750, mode: "PDC" },
        ]);
        expect(o).toMatchObject({ issues: [], extraColumns: 0, extraRows: 0, addedRows: 1 });
    });
    it("reports every cell it could not write, keeps its old value, and never guesses", () => {
        const rows = [{ chequeDate: "2026-01-01", amount: 5, mode: "PDC" }] as Record<string, unknown>[];
        const o = pasteIntoRows(rows, [["09/25/2026", "DIB", "Bank A", "12.750", "cheque"]], 0, "chequeDate", fields, label);
        expect(o.rows[0]).toEqual({ chequeDate: "2026-01-01", payeeBank: "DIB", amount: 5, mode: "PDC" });
        expect(o.issues).toEqual([
            { row: 0, field: "chequeDate", raw: "09/25/2026" },
            { row: 0, field: "debitAccountId", raw: "Bank A" },
            { row: 0, field: "amount", raw: "12.750" },
            { row: 0, field: "mode", raw: "cheque" },
        ]);
    });
    it("reports columns past the last field and rows past the end when the grid cannot grow", () => {
        const o = pasteIntoRows([{ amount: 5 }] as Record<string, unknown>[], [["7", "PDC", "extra", "more"], ["8", "PDC"]], 0, "amount", fields, label);
        expect(o.rows).toEqual([{ amount: 7, mode: "PDC" }]);
        expect(o.extraColumns).toBe(2);
        expect(o.extraRows).toBe(1);
    });
});

function Editor({ initial }: { initial: ChequeDraft[] }) {
    const [rows, setRows] = useState(initial);
    return (
        <NextIntlClientProvider locale="en" messages={en}>
            <ChequeRowsEditor rows={rows} onChange={setRows} propertyId={null} expectedTotal={0} testIdPrefix="t" />
            <output data-testid="rows">{JSON.stringify(rows.map(r => [r.chequeNumber ?? "", r.amount, r.payeeBank ?? ""]))}</output>
        </NextIntlClientProvider>
    );
}
const rows3 = () => [0, 1, 2].map(k => ({ ...blankChequeRow(k), chequeNumber: `N${k}`, amount: (k + 1) * 100 }));

describe("ChequeRowsEditor keyboard", () => {
    it("moves down with Enter and ArrowDown, up with Shift+Enter", () => {
        render(<Editor initial={rows3()} />);
        const no1 = screen.getByLabelText(`${en.Leasing.chequeNo} 1`);
        no1.focus();
        fireEvent.keyDown(no1, { key: "Enter" });
        expect(document.activeElement).toBe(screen.getByLabelText(`${en.Leasing.chequeNo} 2`));
        fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
        expect(document.activeElement).toBe(screen.getByLabelText(`${en.Leasing.chequeNo} 3`));
        fireEvent.keyDown(document.activeElement!, { key: "Enter", shiftKey: true });
        expect(document.activeElement).toBe(screen.getByLabelText(`${en.Leasing.chequeNo} 2`));
    });

    it("moves across with the arrow keys only from the edge of the text", () => {
        render(<Editor initial={rows3()} />);
        const bank = screen.getByLabelText(`${en.Leasing.payeeBank} 1`) as HTMLInputElement;
        fireEvent.change(bank, { target: { value: "DIB" } });
        bank.focus();
        bank.setSelectionRange(1, 1);
        fireEvent.keyDown(bank, { key: "ArrowRight" });
        expect(document.activeElement).toBe(bank);
        bank.setSelectionRange(3, 3);
        fireEvent.keyDown(bank, { key: "ArrowRight" });
        expect(document.activeElement).toBe(screen.getAllByLabelText("account")[0]);
    });

    it("fills a cell down from the row above with Ctrl+D", () => {
        render(<Editor initial={rows3()} />);
        const bank1 = screen.getByLabelText(`${en.Leasing.payeeBank} 1`);
        fireEvent.change(bank1, { target: { value: "ENBD" } });
        fireEvent.keyDown(screen.getByLabelText(`${en.Leasing.payeeBank} 2`), { key: "d", ctrlKey: true });
        expect(JSON.parse(screen.getByTestId("rows").textContent!)[1][2]).toBe("ENBD");
    });

    it("pastes rows copied from Excel from the focused cell, adding rows as needed", () => {
        render(<Editor initial={rows3().slice(0, 1)} />);
        const no1 = screen.getByLabelText(`${en.Leasing.chequeNo} 1`);
        fireEvent.paste(no1, { clipboardData: { getData: () => "A1\t\t\t\t500\nA2\t\t\t\t600\n" } });
        expect(JSON.parse(screen.getByTestId("rows").textContent!)).toEqual([["A1", 500, ""], ["A2", 600, ""]]);
        expect(screen.getByLabelText(`${en.Leasing.chequeNo} 2`)).toHaveValue("A2");
    });

    it("says which pasted cells it could not read, highlights them until edited, and says how many rows it added", () => {
        render(<Editor initial={rows3().slice(0, 1)} />);
        const no1 = screen.getByLabelText(`${en.Leasing.chequeNo} 1`);
        fireEvent.paste(no1, { clipboardData: { getData: () => "A1\t09/25/2026\t\t\t500\nA2\t01/10/2026\t\t\tabc\n" } });
        const report = screen.getByTestId("t-paste-report");
        expect(report).toHaveAttribute("role", "status");
        expect(screen.getByTestId("t-paste-report-cells")).toHaveTextContent("2 pasted cells were not recognised and were left as they were: row 1 Date “09/25/2026”; row 2 Amount “abc”");
        expect(screen.getByTestId("t-paste-report-added")).toHaveTextContent("1 row added for the paste.");
        const cell = screen.getByLabelText(`${en.Leasing.chequeDate} 1`).closest("td")!;
        expect(cell).toHaveAttribute("data-paste-invalid", "true");
        fireEvent.change(screen.getByLabelText(`${en.Leasing.chequeDate} 1`), { target: { value: "2031-03-15" } });
        expect(screen.getByLabelText(`${en.Leasing.chequeDate} 1`).closest("td")).not.toHaveAttribute("data-paste-invalid");
    });

    it("leaves a single pasted value to the browser", () => {
        render(<Editor initial={rows3()} />);
        const no1 = screen.getByLabelText(`${en.Leasing.chequeNo} 1`);
        const ev = fireEvent.paste(no1, { clipboardData: { getData: () => "123" } });
        expect(ev).toBe(true); // not prevented
    });
});
