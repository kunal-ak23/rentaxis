// src/lib/leases/chequeNumbering.ts
import type { Cheque } from "../api/leasing";

/** What the operator typed for one cheque row. */
export interface Edit { chequeNumber: string; payeeBank: string; chequeDate: string }

export type RowProblem = "numberRequired" | "dateRequired" | "numberRepeated";

/**
 * The same checks the server makes before a draft's grid is saved
 * (`LeaseBatchService.numberOne`): a post-dated cheque needs a number and a date, and no
 * number appears twice within the draft. Cash and transfer rows carry no number. Keyed by
 * cheque id; a row with no problem is absent.
 */
export function chequeRowProblems(rows: Cheque[], edits: Record<string, Edit>): Record<string, RowProblem> {
    const out: Record<string, RowProblem> = {};
    const seen = new Set<string>();
    for (const c of rows) {
        if (c.mode !== "PDC") continue;
        const e = edits[c.id];
        const number = (e ? e.chequeNumber : c.chequeNumber ?? "").trim();
        const date = e ? e.chequeDate : c.chequeDate ?? "";
        if (!number) out[c.id] = "numberRequired";
        else if (seen.has(number)) out[c.id] = "numberRepeated";
        else seen.add(number);
        if (!date && !out[c.id]) out[c.id] = "dateRequired";
    }
    return out;
}

/** One draft as the CSV can name it: by any of its keys (contract numbers, unit, id). */
export interface CsvDraft { draftId: string; keys: (string | null | undefined)[]; rows: Cheque[] }

const norm = (s: string) => s.trim().toLowerCase().replace(/\s+/g, "");

/** `2027-01-15`, `15/01/2027` or `15-01-2027` → ISO; anything else → null. */
export function csvDate(raw: string): string | null {
    const s = raw.trim();
    if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s;
    const m = s.match(/^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$/);
    if (!m) return null;
    return `${m[3]}-${m[2].padStart(2, "0")}-${m[1].padStart(2, "0")}`;
}

function cells(line: string): string[] {
    const sep = line.includes("\t") ? "\t" : line.includes(";") && !line.includes(",") ? ";" : ",";
    return line.split(sep).map(c => c.trim().replace(/^"(.*)"$/, "$1"));
}

/**
 * Apply a CSV of `contract, due_date, cheque_number[, bank[, cheque_date]]` to the grid. The
 * contract is any key of a draft (its own or the renewed contract's number, the unit, the id),
 * the due date the row's date as loaded. A header line is skipped. Lines that match no row, or
 * have no number, are reported by line number and change nothing.
 */
export function applyChequeCsv(text: string, drafts: CsvDraft[], edits: Record<string, Edit>):
    { edits: Record<string, Edit>; applied: number; unmatched: number[] } {
    const byKey = new Map<string, CsvDraft>();
    for (const d of drafts) for (const k of d.keys) if (k) byKey.set(norm(k), d);
    const next = { ...edits };
    const unmatched: number[] = [];
    let applied = 0;
    text.split(/\r?\n/).forEach((line, i) => {
        if (!line.trim()) return;
        const [contract = "", due = "", number = "", bank = "", chequeDate = ""] = cells(line);
        if (i === 0 && !csvDate(due)) return; // a header
        const draft = byKey.get(norm(contract));
        const dueIso = csvDate(due);
        const row = draft && dueIso ? draft.rows.find(c => c.chequeDate === dueIso) : undefined;
        if (!row || !number.trim()) {
            unmatched.push(i + 1);
            return;
        }
        const prev = next[row.id] ?? { chequeNumber: row.chequeNumber ?? "", payeeBank: row.payeeBank ?? "", chequeDate: row.chequeDate ?? "" };
        next[row.id] = {
            chequeNumber: number.trim(),
            payeeBank: bank.trim() || prev.payeeBank,
            chequeDate: csvDate(chequeDate) ?? prev.chequeDate,
        };
        applied++;
    });
    return { edits: next, applied, unmatched };
}
