"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle2, FileUp } from "lucide-react";
import {
    bankRecApi,
    dmy,
    STATEMENT_FIELDS,
    type AmountMode,
    type ImportResult,
    type Profile,
    type StatementField,
} from "@/lib/api/bankRec";
import { Modal } from "./Modal";
import { Money } from "./Money";
import { serverText } from "./serverText";
import { button, field, label, primary, td, th } from "./styles";

/** "A", "B", … for column index 0, 1, … (as StatementMapper.letter). */
export function columnLetter(i: number): string {
    let n = i + 1;
    let s = "";
    while (n > 0) {
        const rem = (n - 1) % 26;
        s = String.fromCharCode(65 + rem) + s;
        n = Math.floor((n - 1) / 26);
    }
    return s;
}

export const DATE_FORMATS = ["dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "dd-MMM-yyyy", "dd/MM/yy", "yyyy-MM-dd", "MM/dd/yyyy"];

/** The format the first dates look like, or null when the grid does not say. */
export function guessDateFormat(sample: string[]): string | null {
    const s = sample.map(x => x.trim()).filter(Boolean);
    if (s.length === 0) return null;
    if (s.every(x => /^\d{4}-\d{2}-\d{2}/.test(x))) return "yyyy-MM-dd";
    if (s.every(x => /^\d{2}-[A-Za-z]{3}-\d{4}/.test(x))) return "dd-MMM-yyyy";
    if (s.every(x => /^\d{2}\/\d{2}\/\d{4}/.test(x))) return "dd/MM/yyyy";
    if (s.every(x => /^\d{2}\/\d{2}\/\d{2}(\D|$)/.test(x))) return "dd/MM/yy";
    return null;
}

const REQUIRED: Record<AmountMode, StatementField[]> = {
    SPLIT: ["txnDate", "description", "debit", "credit"],
    SIGNED: ["txnDate", "description", "amount"],
    DRCR_FLAG: ["txnDate", "description", "amount"],
};

/** The column fields an amount mode shows (and the server reads). */
export function fieldsFor(mode: AmountMode): StatementField[] {
    return STATEMENT_FIELDS.filter(f => (mode === "SPLIT" ? f !== "amount" && f !== "amountSign" : f !== "debit" && f !== "credit"))
        .filter(f => mode === "DRCR_FLAG" || f !== "amountSign");
}

/** F14-04: the delimiters a CSV may use; a tab is the tab character. */
export const DELIMITERS: { value: string; key: string }[] = [
    { value: ",", key: "delim_comma" },
    { value: ";", key: "delim_semicolon" },
    { value: "\t", key: "delim_tab" },
    { value: "|", key: "delim_pipe" },
];

/** Whether a column spec names a column of this header: its text (as the server matches it) or a letter within it. */
function inHeader(spec: string, header: string[]): boolean {
    const s = spec.trim();
    if (header.some(h => (h ?? "").trim().toLowerCase() === s.toLowerCase())) return true;
    return header.some((_, i) => columnLetter(i) === s.toUpperCase() && /^[A-Za-z]{1,2}$/.test(s));
}

/**
 * F14-03: only the columns the amount mode uses and the header has. A mapping kept
 * from another layout (debit/credit under a signed amount, a "Value Date" the new
 * file lacks) would otherwise be saved and refused as missing, for ever.
 */
export function relevantColumns(columns: Profile["columns"], mode: AmountMode, header: string[]): Profile["columns"] {
    const out: Profile["columns"] = {};
    for (const f of fieldsFor(mode)) {
        const v = columns[f];
        if (v && inHeader(v, header)) out[f] = v;
    }
    return out;
}

/** A first guess from the header text, so the wizard opens mostly filled in. */
export function guessColumns(header: string[]): Partial<Record<StatementField, string>> {
    const out: Partial<Record<StatementField, string>> = {};
    const find = (re: RegExp) => header.find(h => re.test(h.trim()));
    const set = (f: StatementField, re: RegExp) => {
        const h = find(re);
        if (h && !Object.values(out).includes(h)) out[f] = h;
    };
    set("valueDate", /value/i);
    set("txnDate", /^(txn|transaction|posting|book)?\s*date$/i);
    set("description", /narrat|descr|details|particular/i);
    set("reference", /ref/i);
    set("chequeNo", /cheque|chq/i);
    set("debit", /debit|withdraw|^dr$/i);
    set("credit", /credit|deposit|^cr$/i);
    set("balance", /balance/i);
    if (!out.debit && !out.credit) set("amount", /amount/i);
    return out;
}

/**
 * Import a statement (finance-ops spec §3): pick a file; the first time (or when
 * the header changed) map the columns with a live preview of the parsed rows;
 * then check the preview (new, already imported, warnings) and commit.
 */
export function StatementImportDialog({ bankAccountId, bankName, onClose, onImported }: {
    bankAccountId: string; bankName: string; onClose: () => void; onImported: () => void;
}) {
    const t = useTranslations("BankRec");
    const [file, setFile] = useState<File | null>(null);
    const [result, setResult] = useState<ImportResult | null>(null);
    const [grid, setGrid] = useState<string[][]>([]);
    const [mapping, setMapping] = useState<Profile | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [done, setDone] = useState<ImportResult | null>(null);
    const previewSeq = useRef(0);

    const run = async (f: File, profile: Profile | null, dryRun: boolean) => {
        setBusy(true);
        setError(null);
        try {
            const r = await bankRecApi.importFile(bankAccountId, f, { profile, dryRun });
            return r;
        } catch (err) {
            setError(serverText(t, err));
            return null;
        } finally {
            setBusy(false);
        }
    };

    const choose = async (f: File) => {
        setFile(f);
        setDone(null);
        const r = await run(f, null, true);
        if (!r) return;
        setResult(r);
        if (r.status === "PROFILE_REQUIRED") {
            setGrid(r.grid);
            const savedRaw = await bankRecApi.profile(bankAccountId).catch(() => null);
            // F14-59: a saved profile is a mapping for one file SHAPE (CSV row/column
            // layout differs completely from XLSX). Seeding headerRow/firstDataRow — or
            // any column mapping — from a profile saved against the other kind puts the
            // header pointer on a DATA row, and guessColumns then "pre-selects" columns by
            // matching against data text instead of header text. Only trust the saved
            // profile when this upload is the same kind it was saved against.
            const saved = savedRaw?.fileKind === r.fileKind ? savedRaw : null;
            const headerRow = saved?.headerRow ?? Math.max(1, r.grid.findIndex(row => row.filter(c => c).length >= 3) + 1);
            const hdr = r.grid[headerRow - 1] ?? [];
            const amountMode = saved?.amountMode ?? "SPLIT";
            // F14-03: a saved mapping seeds only what this file's header still has; the
            // header's own guesses fill the gaps.
            const columns = saved?.columns
                ? relevantColumns({ ...guessColumns(hdr), ...relevantColumns(saved.columns, amountMode, hdr) }, amountMode, hdr)
                : guessColumns(hdr);
            setMapping({
                fileKind: r.fileKind,
                sheetName: r.sheetName,
                headerRow,
                firstDataRow: saved?.firstDataRow ?? headerRow + 1,
                // F14-04: the delimiter this file uses, as the server read it.
                csvDelimiter: r.fileKind === "CSV" ? (r.csvDelimiter ?? saved?.csvDelimiter ?? ",") : null,
                columns,
                amountMode,
                dateFormats: saved?.dateFormats ?? null,
                decimalSeparator: saved?.decimalSeparator ?? ".",
                chequeNoPattern: saved?.chequeNoPattern ?? null,
                matchWindowDays: saved?.matchWindowDays ?? null,
            });
            if (!saved?.dateFormats?.length) {
                const cols = guessColumns(r.grid[headerRow - 1] ?? []);
                const idx = (r.grid[headerRow - 1] ?? []).findIndex(h => h?.trim() === cols.txnDate);
                const guess = idx < 0 ? null : guessDateFormat(r.grid.slice(headerRow).map(row => row[idx] ?? ""));
                if (guess) setMapping(m => (m ? { ...m, dateFormats: [guess] } : m));
            }
        } else {
            setMapping(null);
        }
    };

    const header = mapping ? (grid[mapping.headerRow - 1] ?? []) : [];
    // What is previewed and saved: the mode's columns that the header has (F14-03).
    const effective = (m: Profile): Profile => ({ ...m, columns: relevantColumns(m.columns, m.amountMode, grid[m.headerRow - 1] ?? []) });

    // The wizard's live preview: a dry run with the unsaved mapping.
    useEffect(() => {
        if (!file || !mapping) return;
        const seq = ++previewSeq.current;
        const h = setTimeout(async () => {
            const r = await bankRecApi.importFile(bankAccountId, file, { profile: mapping, dryRun: true }).catch(() => null);
            if (r && seq === previewSeq.current) {
                setResult(r);
                // A changed delimiter or sheet re-splits the file (F14-04).
                if (r.grid?.length) setGrid(r.grid);
            }
        }, 250);
        return () => clearTimeout(h);
    }, [file, mapping, bankAccountId]);

    const options = header.map((h, i) => ({ value: h?.trim() ? h.trim() : columnLetter(i), text: `${columnLetter(i)} · ${h ?? ""}` }));
    const effectiveColumns = mapping ? effective(mapping).columns : {};
    const missing = mapping ? REQUIRED[mapping.amountMode].filter(f => !effectiveColumns[f]) : [];
    // The date format is the accountant's statement, never a guess (PR #353 review).
    const noDateFormat = !!mapping && !(mapping.dateFormats && mapping.dateFormats.length > 0);

    const saveAndImport = async () => {
        if (!file || !mapping) return;
        setBusy(true);
        try {
            await bankRecApi.saveProfile(bankAccountId, effective(mapping));
        } catch (err) {
            setError(serverText(t, err));
            setBusy(false);
            return;
        }
        setBusy(false);
        const r = await run(file, null, true);
        if (r) {
            setResult(r);
            setMapping(r.status === "PROFILE_REQUIRED" ? mapping : null);
        }
    };

    const commit = async () => {
        if (!file) return;
        const r = await run(file, null, false);
        if (r) {
            setResult(r);
            if (r.status === "IMPORTED") {
                setDone(r);
                onImported();
            }
        }
    };

    const setCol = (f: StatementField, v: string) =>
        setMapping(m => (m ? { ...m, columns: { ...m.columns, [f]: v || undefined } } : m));

    const reasonText = result?.reason ? serverText(t, { code: result.reasonCode, args: result.reasonArgs, message: result.reason }) : null;

    return (
        <Modal title={`${t("import")} — ${bankName}`} onClose={onClose} wide testId="statement-import">
            <div className="space-y-4">
                <label className="flex items-center gap-3 text-xs text-muted">
                    <FileUp size={16} />
                    <span>{t("dropFile")}</span>
                    <input type="file" data-testid="import-file" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                           onChange={e => e.target.files?.[0] && choose(e.target.files[0])} />
                </label>
                {error && <div role="alert" className="text-xs text-error bg-error/10 border border-error/20 rounded-lg px-3 py-2">{error}</div>}

                {mapping && (
                    <div className="space-y-3" data-testid="mapping-wizard">
                        <div>
                            <h3 className="text-sm font-bold">{t("mappingTitle")}</h3>
                            <p className="text-xs text-muted">{t("mappingHint")}</p>
                            {reasonText && <p className="text-xs text-warning mt-1" data-testid="mapping-reason">{reasonText}</p>}
                        </div>
                        <div className="flex flex-wrap gap-3">
                            {mapping.fileKind === "XLSX" && result?.sheetNames && result.sheetNames.length > 1 && (
                                <label className="text-xs"><span className={`${label} block mb-1`}>{t("sheet")}</span>
                                    <select className={field} value={mapping.sheetName ?? ""}
                                            onChange={e => setMapping({ ...mapping, sheetName: e.target.value })}>
                                        {result.sheetNames.map(s => <option key={s} value={s}>{s}</option>)}
                                    </select>
                                </label>
                            )}
                            {mapping.fileKind === "CSV" && (
                                <label className="text-xs"><span className={`${label} block mb-1`}>{t("delimiter")}</span>
                                    <select className={field} data-testid="map-delimiter" value={mapping.csvDelimiter === "\\t" ? "\t" : (mapping.csvDelimiter || ",")}
                                            onChange={e => setMapping({ ...mapping, csvDelimiter: e.target.value })}>
                                        {DELIMITERS.map(d => <option key={d.key} value={d.value}>{t(d.key)}</option>)}
                                    </select>
                                </label>
                            )}
                            <label className="text-xs"><span className={`${label} block mb-1`}>{t("headerRow")}</span>
                                <input type="number" min={1} data-testid="map-header-row" className={`${field} w-20`} value={mapping.headerRow}
                                       onChange={e => {
                                           const h = Math.max(1, Number(e.target.value) || 1);
                                           setMapping({ ...mapping, headerRow: h, firstDataRow: Math.max(mapping.firstDataRow, h + 1) });
                                       }} />
                            </label>
                            <label className="text-xs"><span className={`${label} block mb-1`}>{t("firstDataRow")}</span>
                                <input type="number" min={2} className={`${field} w-20`} value={mapping.firstDataRow}
                                       onChange={e => setMapping({ ...mapping, firstDataRow: Math.max(mapping.headerRow + 1, Number(e.target.value) || 2) })} />
                            </label>
                            <label className="text-xs"><span className={`${label} block mb-1`}>{t("dateFormat")} *</span>
                                <select className={field} data-testid="map-date-format" value={mapping.dateFormats?.[0] ?? ""}
                                        onChange={e => setMapping({ ...mapping, dateFormats: e.target.value ? [e.target.value] : null })}>
                                    <option value="">{t("notMapped")}</option>
                                    {DATE_FORMATS.map(f => <option key={f} value={f}>{f}</option>)}
                                </select>
                            </label>
                            <label className="text-xs"><span className={`${label} block mb-1`}>{t("decimalSeparator")}</span>
                                <select className={field} data-testid="map-decimal" value={mapping.decimalSeparator ?? "."}
                                        onChange={e => setMapping({ ...mapping, decimalSeparator: e.target.value as "." | "," })}>
                                    <option value=".">1,234.50</option>
                                    <option value=",">1.234,50</option>
                                </select>
                            </label>
                            <label className="text-xs"><span className={`${label} block mb-1`}>{t("amountMode")}</span>
                                <select className={field} data-testid="map-amount-mode" value={mapping.amountMode}
                                        onChange={e => {
                                            // F14-03: the fields the new mode hides are cleared, not kept for the save.
                                            const amountMode = e.target.value as AmountMode;
                                            const keep = fieldsFor(amountMode);
                                            const columns: Profile["columns"] = {};
                                            for (const f of keep) if (mapping.columns[f]) columns[f] = mapping.columns[f];
                                            setMapping({ ...mapping, amountMode, columns });
                                        }}>
                                    {(["SPLIT", "SIGNED", "DRCR_FLAG"] as AmountMode[]).map(m => <option key={m} value={m}>{t(`mode_${m}`)}</option>)}
                                </select>
                            </label>
                        </div>
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                            {fieldsFor(mapping.amountMode).map(f => (
                                <label key={f} className="text-xs">
                                    <span className={`${label} block mb-1`}>{t(`field_${f}`)}{REQUIRED[mapping.amountMode].includes(f) ? " *" : ""}</span>
                                    <select className={`${field} w-full`} data-testid={`map-${f}`} value={mapping.columns[f] ?? ""}
                                            onChange={e => setCol(f, e.target.value)}>
                                        <option value="">{t("notMapped")}</option>
                                        {options.map(o => <option key={o.value} value={o.value}>{o.text}</option>)}
                                    </select>
                                </label>
                            ))}
                        </div>
                        <details className="text-xs">
                            <summary className="cursor-pointer text-muted">{t("rawRows")}</summary>
                            <div className="overflow-x-auto mt-2">
                                <table className="text-[11px]"><tbody>
                                    {grid.map((row, i) => (
                                        <tr key={i} className={i + 1 === mapping.headerRow ? "font-bold" : ""}>
                                            <td className="px-2 text-muted">{i + 1}</td>
                                            {row.map((c, j) => <td key={j} className="px-2 whitespace-nowrap">{c}</td>)}
                                        </tr>
                                    ))}
                                </tbody></table>
                            </div>
                        </details>
                        <button type="button" className={primary} data-testid="map-save" disabled={busy || missing.length > 0 || noDateFormat}
                                onClick={saveAndImport}>{t("saveAndImport")}</button>
                    </div>
                )}

                {result?.status === "ALREADY_IMPORTED" && (
                    <div role="alert" data-testid="already-imported" className="text-xs text-warning bg-warning/10 border border-warning/20 rounded-lg px-3 py-2">
                        {reasonText}
                    </div>
                )}
                {result && result.status !== "PROFILE_REQUIRED" && result.status !== "ALREADY_IMPORTED" && !done && (
                    <ResultCard result={result} />
                )}
                {result && result.status === "PREVIEW" && !mapping && !done && (
                    <div className="flex items-center gap-3">
                        <button type="button" className={primary} data-testid="import-commit" disabled={busy || result.linesNew === 0}
                                onClick={commit}>
                            {result.linesNew === 0 ? t("nothingNew") : t("commit", { count: result.linesNew })}
                        </button>
                        <button type="button" className={button} onClick={onClose}>{t("cancel")}</button>
                    </div>
                )}
                {done && (
                    <div data-testid="import-done" className="text-xs text-success bg-success/10 border border-success/20 rounded-lg px-3 py-2 flex items-center gap-2">
                        <CheckCircle2 size={14} />
                        {t("imported", { fresh: done.linesNew, dup: done.linesDuplicate })}
                        {done.warnings.length > 0 && <span className="text-warning">· {done.warnings.join(" · ")}</span>}
                    </div>
                )}
            </div>
        </Modal>
    );
}

/** Counts, warnings, row errors and the parsed rows of a dry run. */
function ResultCard({ result }: { result: ImportResult }) {
    const t = useTranslations("BankRec");
    return (
        <div className="space-y-2" data-testid="import-result">
            {result.status === "INVALID" ? (
                <div role="alert" className="text-xs text-error bg-error/10 border border-error/20 rounded-lg px-3 py-2">
                    <div className="font-bold flex items-center gap-1"><AlertTriangle size={13} />{t("invalid")}</div>
                    <ul className="list-disc ps-5 mt-1" data-testid="import-errors">{result.errors.map(e => <li key={e}>{e}</li>)}</ul>
                </div>
            ) : (
                <div className="text-xs flex flex-wrap gap-4" data-testid="import-counts">
                    <span className="font-bold">{t("counts", { fresh: result.linesNew, dup: result.linesDuplicate })}</span>
                    {result.openingBalance !== null && <span>{t("opening")}: <Money v={result.openingBalance} /></span>}
                    {result.closingBalance !== null && <span>{t("closing")}: <Money v={result.closingBalance} /></span>}
                    {result.order === "REVERSED" && <span className="text-muted">{t("reverseOrder")}</span>}
                </div>
            )}
            {result.warnings.length > 0 && (
                <div className="text-xs text-warning bg-warning/10 border border-warning/20 rounded-lg px-3 py-2" data-testid="import-warnings">
                    <span className="font-bold">{t("warnings")}: </span>{result.warnings.join(" · ")}
                </div>
            )}
            {result.rows.length > 0 && (
                <div className="overflow-x-auto max-h-80 border border-border rounded-lg">
                    <table className="w-full" data-testid="import-preview">
                        <thead className="bg-input sticky top-0"><tr>
                            <th className={th}>{t("date")}</th><th className={th}>{t("valueDate")}</th>
                            <th className={th}>{t("description")}</th><th className={th}>{t("reference")}</th>
                            <th className={th}>{t("chequeNo")}</th><th className={`${th} text-end`}>{t("amount")}</th>
                            <th className={`${th} text-end`}>{t("field_balance")}</th><th className={th} />
                        </tr></thead>
                        <tbody className="divide-y divide-border">
                            {result.rows.slice(0, 20).map(r => (
                                <tr key={r.fileRow} className={r.duplicate ? "opacity-50" : ""}>
                                    <td className={td}><bdi dir="ltr">{dmy(r.txnDate)}</bdi></td>
                                    <td className={td}><bdi dir="ltr">{dmy(r.valueDate)}</bdi></td>
                                    <td className={td}>{r.description}</td>
                                    <td className={td}>{r.reference ?? ""}</td>
                                    <td className={td}>{r.chequeNo ?? ""}</td>
                                    <td className={`${td} text-end`}><Money v={r.amount} /></td>
                                    <td className={`${td} text-end`}><Money v={r.balance} /></td>
                                    <td className={`${td} text-muted`}>{r.duplicate ? t("duplicate") : ""}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}
