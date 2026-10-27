// src/lib/leases/chequeNumbering.ts
import type { Cheque } from "../api/leasing";

/** What the operator typed for one cheque row. */
export interface Edit { chequeNumber: string; payeeBank: string; chequeDate: string }

export type RowProblem = "numberRequired" | "dateRequired" | "dateInvalid" | "numberRepeated";

/** Arabic-Indic (٠-٩) and Extended Arabic-Indic (۰-۹) digits as ASCII, so ٥٠٠٠٠١ and 500001 are one number. */
export function westernDigits(s: string): string {
    return s.replace(/[٠-٩]/g, d => String(d.charCodeAt(0) - 0x0660))
        .replace(/[۰-۹]/g, d => String(d.charCodeAt(0) - 0x06F0));
}

/** A cheque number as compared and saved: trimmed, ASCII digits. */
export function normNumber(s: string | null | undefined): string {
    return westernDigits((s ?? "").trim());
}

/** Whether `iso` is `yyyy-mm-dd` naming a day that exists (no 31 February, no month 15). */
export function isRealIsoDate(iso: string): boolean {
    const m = iso.match(/^(\d{4})-(\d{2})-(\d{2})$/);
    if (!m) return false;
    const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
    const t = new Date(Date.UTC(y, mo - 1, d));
    return t.getUTCFullYear() === y && t.getUTCMonth() === mo - 1 && t.getUTCDate() === d;
}

/**
 * The two date formats the CSV accepts (re-review P2-2): ISO `yyyy-mm-dd`, and the UAE's
 * `dd/mm/yyyy` (day first, slashes). Arabic-Indic digits are read as ASCII. Anything else —
 * a US-order date, dashes or dots between day and month, a day that does not exist — is not a
 * date: null, never a guess.
 */
export function csvDate(raw: string): string | null {
    const s = westernDigits(raw.trim());
    let iso: string | null = null;
    if (/^\d{4}-\d{2}-\d{2}$/.test(s)) iso = s;
    else {
        const m = s.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
        if (m) iso = `${m[3]}-${m[2].padStart(2, "0")}-${m[1].padStart(2, "0")}`;
    }
    return iso && isRealIsoDate(iso) ? iso : null;
}

/**
 * The same checks the server makes before a draft's grid is saved
 * (`LeaseBatchService.numberOne`), plus a real-date check, so nothing the server would refuse
 * as a whole request is ever sent: a post-dated cheque needs a number and a real date, and no
 * number appears twice within the draft. Cash and transfer rows carry no number. Keyed by
 * cheque id; a row with no problem is absent.
 */
export function chequeRowProblems(rows: Cheque[], edits: Record<string, Edit>): Record<string, RowProblem> {
    const out: Record<string, RowProblem> = {};
    const seen = new Set<string>();
    for (const c of rows) {
        const e = edits[c.id];
        const date = e ? e.chequeDate : c.chequeDate ?? "";
        if (date && !isRealIsoDate(date)) {
            out[c.id] = "dateInvalid";
            continue;
        }
        if (c.mode !== "PDC") continue;
        const number = normNumber(e ? e.chequeNumber : c.chequeNumber);
        if (!number) out[c.id] = "numberRequired";
        else if (seen.has(number)) out[c.id] = "numberRepeated";
        else seen.add(number);
        if (!date && !out[c.id]) out[c.id] = "dateRequired";
    }
    return out;
}

/**
 * Re-review P3-2: the same cheque number on post-dated rows of two drafts in this batch. Two
 * renters' cheques can share a number, so it is a warning, not a refusal — but it is usually a
 * CSV line matched to the wrong contract. Keyed by cheque id: the other draft it collides with.
 */
export function crossDraftRepeats(drafts: { draftId: string; rows: Cheque[] }[], edits: Record<string, Edit>): Record<string, string> {
    const byNumber = new Map<string, { draftId: string; chequeId: string }[]>();
    for (const d of drafts) {
        for (const c of d.rows) {
            if (c.mode !== "PDC") continue;
            const n = normNumber(edits[c.id] ? edits[c.id].chequeNumber : c.chequeNumber);
            if (!n) continue;
            byNumber.set(n, [...(byNumber.get(n) ?? []), { draftId: d.draftId, chequeId: c.id }]);
        }
    }
    const out: Record<string, string> = {};
    for (const hits of byNumber.values()) {
        for (const h of hits) {
            const other = hits.find(x => x.draftId !== h.draftId);
            if (other) out[h.chequeId] = other.draftId;
        }
    }
    return out;
}

/** One draft as the CSV can name it: by any of its keys (contract numbers, unit, id). */
export interface CsvDraft { draftId: string; keys: (string | null | undefined)[]; rows: Cheque[] }

const norm = (s: string) => westernDigits(s).trim().toLowerCase().replace(/\s+/g, "");

/** A line's cells, quote-aware: `"Emirates NBD, Dubai"` is one cell; `""` inside quotes is a quote. */
export function csvCells(line: string, sep: string): string[] {
    const out: string[] = [];
    let cur = "";
    let quoted = false;
    for (let i = 0; i < line.length; i++) {
        const ch = line[i];
        if (quoted) {
            if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
            else if (ch === '"') quoted = false;
            else cur += ch;
        } else if (ch === '"') quoted = true;
        else if (ch === sep) { out.push(cur.trim()); cur = ""; }
        else cur += ch;
    }
    out.push(cur.trim());
    return out;
}

/** The file's separator, chosen once from its first line: tab, then semicolon when there is no comma, else comma. */
function separatorOf(first: string): string {
    if (first.includes("\t")) return "\t";
    return first.includes(";") && !first.includes(",") ? ";" : ",";
}

/** A cell that reads like a date: digits split by `/`, `-` or `.` (real or not). */
const DATE_LIKE = /\d{1,4}\s*[/.-]\s*\d{1,2}\s*[/.-]\s*\d{1,4}/;
/** A cell that is a number (a cheque number, an amount). */
const NUMERIC = /^\d[\d\s,.]*$/;

/** The words a header of this CSV is made of, English and Arabic (lower case, no diacritics). */
const HEADER_WORDS = new Set([
    "contract", "contracts", "lease", "unit", "id", "due", "date", "duedate", "cheque", "check", "chq", "number", "no",
    "num", "bank", "payee", "chequedate", "chequenumber", "dueday",
    "العقد", "عقد", "رقم", "الشيك", "شيك", "البنك", "بنك", "تاريخ", "الاستحقاق", "استحقاق", "الوحدة", "وحدة",
]);

/** Whether a cell is made of header words only: "due_date", "Cheque No.", "تاريخ الاستحقاق". */
function isHeaderWordCell(cell: string): boolean {
    const words = cell.toLowerCase().split(/[^a-z\u0600-\u06FF]+/).filter(Boolean);
    return words.length > 0 && !/[0-9\u0660-\u0669\u06F0-\u06F9]/.test(cell) && words.every(w => HEADER_WORDS.has(w));
}

export interface CsvResult {
    edits: Record<string, Edit>;
    applied: number;
    /** Lines naming no row (unknown contract, no row on that due date, two rows it could be, no number). */
    unmatched: number[];
    /** Lines whose due date or cheque date is not a real date in an accepted format. */
    invalidDates: number[];
}

/**
 * Apply a CSV of `contract, due_date, cheque_number[, bank[, cheque_date]]` to the grid. The
 * contract is any key of a draft (its own or the renewed contract's number, the unit, the id),
 * the due date the row's date as loaded; dates are `yyyy-mm-dd` or `dd/mm/yyyy`. A header line
 * and words-only title lines above it are skipped; any other line is data. A line that names no row, or that two rows could both take (a unit number shared
 * by two buildings, or two cheques of one draft on the same date), is reported by line number
 * and changes nothing; so is a line with a date that is not real — never a guess.
 */
export function applyChequeCsv(text: string, drafts: CsvDraft[], edits: Record<string, Edit>): CsvResult {
    const byKey = new Map<string, CsvDraft[]>();
    for (const d of drafts) {
        for (const k of new Set(d.keys.filter((x): x is string => !!x).map(norm))) {
            byKey.set(k, [...(byKey.get(k) ?? []), d]);
        }
    }
    const next = { ...edits };
    const unmatched: number[] = [];
    const invalidDates: number[] = [];
    let applied = 0;
    const lines = text.replace(/^﻿/, "").split(/\r?\n/);
    const sep = separatorOf(lines.find(l => l.trim()) ?? "");
    // Sim r22 S22-05 / r23 / #425 review P3-R4-4 / r24 S24-04: blank lines and a title may
    // come before the header. The header is, before the first valid data row (a real due date
    // on a contract the batch knows), the first line of known header words (English or
    // Arabic), else the first line of words with no digits. Every line after it is data,
    // reported if it is wrong. A line before it that looks like data (a date-like or numeric
    // cell) is read as data too — reported, never silently dropped; only words are preamble.
    const hasDigit = (l: string) => /[0-9\u0660-\u0669\u06F0-\u06F9]/.test(l);
    const cellsOf = (l: string) => csvCells(l, sep);
    const wordy = (l: string) => cellsOf(l).filter(c => /[a-z\u0600-\u06FF]/i.test(c)).length >= 2;
    const knownHeader = (l: string) => cellsOf(l).filter(isHeaderWordCell).length >= 2;
    const validData = (l: string) => {
        const [contract = "", due = ""] = cellsOf(l);
        return !!csvDate(due) && byKey.has(norm(contract));
    };
    const firstValid = lines.findIndex(l => l.trim() !== "" && validData(l));
    const beforeData = (i: number) => firstValid < 0 || i < firstValid;
    let headerAt = lines.findIndex((l, i) => beforeData(i) && l.trim() !== "" && knownHeader(l));
    if (headerAt < 0) headerAt = lines.findIndex((l, i) => beforeData(i) && l.trim() !== "" && wordy(l) && !hasDigit(l));
    const looksLikeData = (l: string) => cellsOf(l).some(c => DATE_LIKE.test(westernDigits(c)) || NUMERIC.test(westernDigits(c)));
    // Sim r25 S25-02: the r22 first-line fallback, kept under #435's rules. With no header, a
    // first line of words (letters, no digit, so no due date) before the data is a title
    // ("Tower A cheques"), however many cells it has — skipped, not reported as a bad date.
    // #438 review: unless its first cell names a draft ("VILLA-A,,"): that is a data line
    // missing its date, reported, never silently dropped.
    if (headerAt < 0) {
        const first = lines.findIndex(l => l.trim() !== "");
        if (first >= 0 && beforeData(first) && !hasDigit(lines[first]) && /[a-z\u0600-\u06FF]/i.test(lines[first])
            && !byKey.has(norm(cellsOf(lines[first])[0] ?? ""))) {
            headerAt = first;
        }
    }
    lines.forEach((line, i) => {
        if (!line.trim() || i === headerAt) return;
        if (i < headerAt && !looksLikeData(line)) return;
        const [contract = "", due = "", number = "", bank = "", chequeDate = ""] = csvCells(line, sep);
        const dueIso = csvDate(due);
        const chequeIso = chequeDate.trim() ? csvDate(chequeDate) : null;
        if (!dueIso || (chequeDate.trim() && !chequeIso)) {
            invalidDates.push(i + 1);
            return;
        }
        const candidates = (byKey.get(norm(contract)) ?? []).flatMap(d => d.rows.filter(c => c.chequeDate === dueIso));
        const row = candidates.length === 1 ? candidates[0] : undefined;
        if (!row || !normNumber(number)) {
            unmatched.push(i + 1);
            return;
        }
        const prev = next[row.id] ?? { chequeNumber: row.chequeNumber ?? "", payeeBank: row.payeeBank ?? "", chequeDate: row.chequeDate ?? "" };
        next[row.id] = { chequeNumber: normNumber(number), payeeBank: bank.trim() || prev.payeeBank, chequeDate: chequeIso ?? prev.chequeDate };
        applied++;
    });
    return { edits: next, applied, unmatched, invalidDates };
}
