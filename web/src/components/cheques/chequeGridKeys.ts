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
 * The grid marks its rows `data-grid-row={index}` and its editable cells
 * `data-grid-field={field}`; the handlers below work from those.
 */

export type GridField =
    | "postingDate" | "chequeNumber" | "chequeDate" | "payeeBank" | "debitAccountId"
    | "amount" | "vatAmount" | "narration" | "mode";

type Row = { [K in GridField]?: unknown };

const pad = (n: number) => String(n).padStart(2, "0");

/** A spreadsheet date: 2026-09-25, 25/09/2026, 25-09-2026, 25.09.2026 (day first, as in the UAE). */
export function parseDateCell(raw: string): string | null {
    const s = raw.trim();
    let m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
    if (m) return valid(+m[1], +m[2], +m[3]);
    m = s.match(/^(\d{1,2})[/.-](\d{1,2})[/.-](\d{2}|\d{4})$/);
    if (m) return valid(m[3].length === 2 ? 2000 + +m[3] : +m[3], +m[2], +m[1]);
    return null;
}
function valid(y: number, mo: number, d: number): string | null {
    const dt = new Date(y, mo - 1, d);
    return dt.getFullYear() === y && dt.getMonth() === mo - 1 && dt.getDate() === d ? `${y}-${pad(mo)}-${pad(d)}` : null;
}

/** "12,750.00", "AED 12750", "12750" → 12750; anything else → null. */
export function parseAmountCell(raw: string): number | null {
    const s = raw.replace(/[^\d.,-]/g, "").replace(/,/g, "");
    if (!s || !/^-?\d*\.?\d+$/.test(s)) return null;
    const n = Math.round(Number(s) * 100) / 100;
    return Number.isFinite(n) && n >= 0 ? n : null;
}

/** Tab-separated rows as Excel and Sheets put them on the clipboard. */
export function parseClipboardGrid(text: string): string[][] {
    const lines = text.replace(/\r\n?/g, "\n").replace(/\n+$/, "").split("\n");
    return lines.map(l => l.split("\t"));
}

/** A pasted cell's value for `field`, or undefined to leave the cell as it is. */
export function cellValue(field: GridField, raw: string, modeLabel: (m: ChequeMode) => string): unknown {
    const s = raw.trim();
    switch (field) {
        case "postingDate":
        case "chequeDate":
            return parseDateCell(s) ?? undefined;
        case "amount":
        case "vatAmount":
            return parseAmountCell(s) ?? undefined;
        case "mode": {
            const hit = TYPEABLE_MODES.find(m => m.toLowerCase() === s.toLowerCase() || modeLabel(m).toLowerCase() === s.toLowerCase());
            return hit ?? undefined;
        }
        case "debitAccountId":
            // An account is picked from the chart, not typed: a pasted column here is skipped.
            return undefined;
        default:
            return s;
    }
}

/**
 * Writes a pasted block into `rows` from (`startRow`, `startField`) along the
 * grid's column order. Rows past the end are added with `addRow` when the grid
 * can grow, and dropped when it cannot. `withValue` lets a grid attach side
 * effects to a write (a new amount clears that row's VAT on the lease grid).
 */
export function pasteIntoRows<R extends Row>(
    rows: R[], grid: string[][], startRow: number, startField: GridField, fields: GridField[],
    modeLabel: (m: ChequeMode) => string,
    addRow?: (index: number) => R,
    withValue: (row: R, field: GridField, value: unknown) => R = (row, field, value) => ({ ...row, [field]: value }),
): R[] {
    const out = [...rows];
    const col0 = fields.indexOf(startField);
    if (col0 < 0) return rows;
    grid.forEach((cells, dr) => {
        const r = startRow + dr;
        if (r >= out.length) {
            if (!addRow) return;
            out.push(addRow(r));
        }
        let row = out[r];
        cells.forEach((raw, dc) => {
            const field = fields[col0 + dc];
            if (!field) return;
            const value = cellValue(field, raw, modeLabel);
            if (value !== undefined) row = withValue(row, field, value);
        });
        out[r] = row;
    });
    return out;
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

type Opts<R extends Row> = {
    rows: R[];
    fields: GridField[];
    onChange: (rows: R[]) => void;
    modeLabel: (m: ChequeMode) => string;
    addRow?: (index: number) => R;
    withValue?: (row: R, field: GridField, value: unknown) => R;
};

/** Handlers for the grid's `<table>`: `onKeyDown` and `onPaste`. */
export function chequeGridHandlers<R extends Row>({ rows, fields, onChange, modeLabel, addRow, withValue }: Opts<R>) {
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
        onChange(pasteIntoRows(rows, parseClipboardGrid(text), pos.row, pos.field, fields, modeLabel, addRow, write));
    };

    return { onKeyDown, onPaste };
}
