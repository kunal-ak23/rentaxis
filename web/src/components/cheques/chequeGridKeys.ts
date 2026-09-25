"use client";

import type { ClipboardEvent, KeyboardEvent } from "react";
import type { ChequeMode } from "@/lib/api/leasing";
import { TYPEABLE_MODES } from "./chequeRowRules";

/**
 * Keyboard-first cheque grids (scale spec #19): Enter / Shift+Enter and the
 * arrow keys move between cells, Ctrl/⌘+D fills a cell down from the row
 * above, and rows pasted from Excel (tab-separated) fill the grid from the
 * focused cell. Shared by the lease's ChequeGrid and ChequeRowsEditor.
 *
 * A paste never keeps an old value silently (PR #365 R1): every cell it could
 * not read, every column past the grid's last and every row past its end is
 * reported, and the unread cells are highlighted.
 *
 * The grid marks its rows `data-grid-row={index}` and its editable cells
 * `data-grid-field={field}`; the handlers below work from those.
 */

export type GridField =
    | "postingDate" | "chequeNumber" | "chequeDate" | "payeeBank" | "debitAccountId"
    | "amount" | "vatAmount" | "narration" | "mode";

type Row = { [K in GridField]?: unknown };

const pad = (n: number) => String(n).padStart(2, "0");

/** Arabic-Indic and Extended Arabic-Indic digits to ASCII; Arabic thousands/decimal/comma marks to "," "." ",". */
export function normaliseDigits(raw: string): string {
    return raw
        .replace(/[٠-٩]/g, d => String(d.charCodeAt(0) - 0x0660))
        .replace(/[۰-۹]/g, d => String(d.charCodeAt(0) - 0x06f0))
        .replace(/٬/g, ",")
        .replace(/٫/g, ".")
        .replace(/،/g, ",");
}

/**
 * A spreadsheet date: yyyy-mm-dd or dd/mm/yyyy (also with "-" or "."), day
 * first as in the UAE. A date whose second part cannot be a month (09/25/2026)
 * is a US-style date and is refused rather than guessed.
 */
export function parseDateCell(raw: string): string | null {
    const s = normaliseDigits(raw).trim();
    let m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
    if (m) return valid(+m[1], +m[2], +m[3]);
    m = s.match(/^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$/);
    if (m) return valid(+m[3], +m[2], +m[1]);
    return null;
}
function valid(y: number, mo: number, d: number): string | null {
    const dt = new Date(y, mo - 1, d);
    return dt.getFullYear() === y && dt.getMonth() === mo - 1 && dt.getDate() === d ? `${y}-${pad(mo)}-${pad(d)}` : null;
}

/**
 * An amount: "12,750.00", "12750", "AED 12,750" → 12750; the European
 * "12.750,00" → 12750 only in that unambiguous shape (dot thousands AND a
 * comma decimal). "12,75", "12.750", negatives and anything else → null.
 */
export function parseAmountCell(raw: string): number | null {
    const s = normaliseDigits(raw).replace(/aed|dhs?|د\.إ/gi, "").replace(/\s/g, "");
    let n: number | null = null;
    if (/^\d+(\.\d{1,2})?$/.test(s)) n = Number(s);
    else if (/^\d{1,3}(,\d{3})+(\.\d{1,2})?$/.test(s)) n = Number(s.replace(/,/g, ""));
    else if (/^\d{1,3}(\.\d{3})+,\d{1,2}$/.test(s)) n = Number(s.replace(/\./g, "").replace(",", "."));
    return n === null || !Number.isFinite(n) ? null : Math.round(n * 100) / 100;
}

/** Tab-separated rows as Excel and Sheets put them on the clipboard. */
export function parseClipboardGrid(text: string): string[][] {
    const lines = text.replace(/\r\n?/g, "\n").replace(/\n+$/, "").split("\n");
    return lines.map(l => l.split("\t"));
}

type CellRead = { ok: true; value: unknown } | { ok: false } | { ok: "blank" };

/** A pasted cell's value for `field`; a blank cell writes nothing and is not an error. */
export function cellValue(field: GridField, raw: string, modeLabel: (m: ChequeMode) => string): CellRead {
    const s = raw.trim();
    if (!s) return field === "chequeNumber" || field === "payeeBank" || field === "narration" ? { ok: true, value: "" } : { ok: "blank" };
    switch (field) {
        case "postingDate":
        case "chequeDate": {
            const d = parseDateCell(s);
            return d ? { ok: true, value: d } : { ok: false };
        }
        case "amount":
        case "vatAmount": {
            const n = parseAmountCell(s);
            return n === null ? { ok: false } : { ok: true, value: n };
        }
        case "mode": {
            const hit = TYPEABLE_MODES.find(m => m.toLowerCase() === s.toLowerCase() || modeLabel(m).toLowerCase() === s.toLowerCase());
            return hit ? { ok: true, value: hit } : { ok: false };
        }
        case "debitAccountId":
            // An account is picked from the chart, not typed: a pasted value here is reported, not applied.
            return { ok: false };
        case "chequeNumber":
            return { ok: true, value: normaliseDigits(s) };
        default:
            return { ok: true, value: s };
    }
}

export type PasteIssue = { row: number; field: GridField; raw: string };
export interface PasteOutcome<R> {
    rows: R[];
    /** Cells not written (unreadable, or the account column); their old value stays and they are highlighted. */
    issues: PasteIssue[];
    /** Columns past the grid's last field, ignored. */
    extraColumns: number;
    /** Pasted rows past the grid's end that were not added (the grid could not, or was not asked to, grow). */
    extraRows: number;
    /** Rows added to hold the paste. */
    addedRows: number;
}

/**
 * Writes a pasted block into `rows` from (`startRow`, `startField`) along the
 * grid's column order. Rows past the end are added with `addRow` when given,
 * otherwise counted in `extraRows`. `withValue` lets a grid attach side effects
 * to a write (a new amount clears that row's VAT on the lease grid).
 */
export function pasteIntoRows<R extends Row>(
    rows: R[], grid: string[][], startRow: number, startField: GridField, fields: GridField[],
    modeLabel: (m: ChequeMode) => string,
    addRow?: (index: number) => R,
    withValue: (row: R, field: GridField, value: unknown) => R = (row, field, value) => ({ ...row, [field]: value }),
): PasteOutcome<R> {
    const out = [...rows];
    const col0 = fields.indexOf(startField);
    const outcome: PasteOutcome<R> = { rows: out, issues: [], extraColumns: 0, extraRows: 0, addedRows: 0 };
    if (col0 < 0) return { ...outcome, rows };
    grid.forEach((cells, dr) => {
        const r = startRow + dr;
        const extra = cells.slice(fields.length - col0).filter(c => c.trim()).length;
        outcome.extraColumns = Math.max(outcome.extraColumns, extra);
        if (r >= out.length) {
            if (!addRow) { outcome.extraRows++; return; }
            out.push(addRow(r));
            outcome.addedRows++;
        }
        let row = out[r];
        cells.forEach((raw, dc) => {
            const field = fields[col0 + dc];
            if (!field) return;
            const read = cellValue(field, raw, modeLabel);
            if (read.ok === true) row = withValue(row, field, read.value);
            else if (read.ok === false) outcome.issues.push({ row: r, field, raw: raw.trim() });
        });
        out[r] = row;
    });
    return outcome;
}

function cellInput(root: HTMLElement, row: number, field: string): HTMLInputElement | HTMLSelectElement | null {
    return root.querySelector(`[data-grid-row="${row}"] [data-grid-field="${field}"] input, [data-grid-row="${row}"] [data-grid-field="${field}"] select`);
}

function focusCell(root: HTMLElement, row: number, field: string): boolean {
    const el = cellInput(root, row, field);
    if (!el) return false;
    el.focus();
    if (el instanceof HTMLInputElement && el.type !== "date") el.select();
    return true;
}

/** Where a paste started, so a grid can re-apply it (e.g. after the user agrees to add rows). */
export type PasteRequest = { grid: string[][]; startRow: number; startField: GridField };

type Opts<R extends Row> = {
    rows: R[];
    fields: GridField[];
    onChange: (rows: R[]) => void;
    modeLabel: (m: ChequeMode) => string;
    addRow?: (index: number) => R;
    withValue?: (row: R, field: GridField, value: unknown) => R;
    /** Every paste's outcome, for the grid's status line and highlights. */
    onPasted?: (outcome: PasteOutcome<R>, request: PasteRequest) => void;
};

/** Handlers for the grid's `<table>`: `onKeyDown` and `onPaste`. */
export function chequeGridHandlers<R extends Row>({ rows, fields, onChange, modeLabel, addRow, withValue, onPasted }: Opts<R>) {
    const at = (target: EventTarget | null) => {
        const el = target as HTMLElement | null;
        const cell = el?.closest?.("[data-grid-field]") as HTMLElement | null;
        const tr = el?.closest?.("[data-grid-row]") as HTMLElement | null;
        if (!cell || !tr) return null;
        return { el: el!, field: cell.dataset.gridField as GridField, row: Number(tr.dataset.gridRow) };
    };
    const write = withValue ?? ((row: R, field: GridField, value: unknown) => ({ ...row, [field]: value }));

    const onKeyDown = (e: KeyboardEvent<HTMLElement>) => {
        if (e.defaultPrevented || e.altKey) return;
        const pos = at(e.target);
        if (!pos) return;
        const root = e.currentTarget;
        const input = pos.el instanceof HTMLInputElement ? pos.el : null;
        const texty = !!input && input.type !== "date";
        const col = fields.indexOf(pos.field);

        if ((e.ctrlKey || e.metaKey) && (e.key === "d" || e.key === "D")) {
            // Fill down: this cell takes the value of the same cell one row up.
            e.preventDefault();
            if (pos.row === 0) return;
            const next = [...rows];
            next[pos.row] = write(next[pos.row], pos.field, rows[pos.row - 1][pos.field]);
            onChange(next);
            return;
        }
        if (e.ctrlKey || e.metaKey) return;

        let target: { row: number; field: GridField } | null = null;
        if (e.key === "Enter" && !(pos.el instanceof HTMLButtonElement)) {
            target = { row: pos.row + (e.shiftKey ? -1 : 1), field: pos.field };
        } else if ((e.key === "ArrowDown" || e.key === "ArrowUp") && texty) {
            target = { row: pos.row + (e.key === "ArrowDown" ? 1 : -1), field: pos.field };
        } else if ((e.key === "ArrowLeft" || e.key === "ArrowRight") && (texty || !input)) {
            const rtl = getComputedStyle(root).direction === "rtl";
            const forward = (e.key === "ArrowRight") !== rtl;
            if (input && texty) {
                const len = input.value.length;
                const atEdge = forward ? input.selectionStart === len && input.selectionEnd === len
                    : input.selectionStart === 0 && input.selectionEnd === 0;
                if (!atEdge) return;
            }
            const f = fields[col + (forward ? 1 : -1)];
            if (f) target = { row: pos.row, field: f };
        }
        if (!target || target.row < 0 || target.row >= rows.length) return;
        if (focusCell(root, target.row, target.field)) e.preventDefault();
    };

    const onPaste = (e: ClipboardEvent<HTMLElement>) => {
        const pos = at(e.target);
        if (!pos) return;
        const text = e.clipboardData.getData("text/plain");
        // A single value pastes into the cell the ordinary way.
        if (!/[\t\n]/.test(text.replace(/\r?\n$/, ""))) return;
        e.preventDefault();
        const request: PasteRequest = { grid: parseClipboardGrid(text), startRow: pos.row, startField: pos.field };
        const outcome = pasteIntoRows(rows, request.grid, pos.row, pos.field, fields, modeLabel, addRow, write);
        onChange(outcome.rows);
        onPasted?.(outcome, request);
    };

    return { onKeyDown, onPaste };
}
