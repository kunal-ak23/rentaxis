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
 * is skipped. A line that names no row, or that two rows could both take (a unit number shared
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
    // Sim r22 S22-05 / r23: blank lines, a title row and the header may come before the
    // data. Every leading row with words, no due date and no known contract (English or
    // Arabic) is preamble; the first other row ends it.
    let preamble = true;
    lines.forEach((line, i) => {
        if (!line.trim()) return;
        const [contract = "", due = "", number = "", bank = "", chequeDate = ""] = csvCells(line, sep);
        const dueIso = csvDate(due);
        // A row naming a known contract is data, even with a bad date (reported below).
        if (preamble && !dueIso && !byKey.has(norm(contract)) && /[a-z\u0600-\u06FF]/i.test(line)) return; // a title or the header
        preamble = false;
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
