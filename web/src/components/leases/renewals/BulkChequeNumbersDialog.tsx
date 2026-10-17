"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { CheckCircle, Loader2, Upload, XCircle } from "lucide-react";
import LeaseDialog from "@/components/leases/LeaseDialog";
import { Link } from "@/i18n/routing";
import { serverText } from "@/components/finance/bankrec/serverText";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { formatCurrency } from "@/lib/format";
import { ApiError, chequeBatchApi, type Cheque, type DraftGrid, type DraftNumbers } from "@/lib/api/leasing";
import { BATCH_CHUNK, chunks } from "@/lib/leases/renewalSeason";
import { applyChequeCsv, chequeRowProblems, type CsvDraft, type Edit } from "@/lib/leases/chequeNumbering";

/** A renewal draft to number, with the predecessor's facts the queue row already knows. */
export interface NumberingTarget { draftId: string; predecessorContract: string | null; unitIdentifier: string | null; renterName: string | null }

type DraftState = { saved?: boolean; error?: string | null; rowErrors?: Record<string, string> };

const input = "w-full bg-input border border-border rounded-lg px-2 py-1.5 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none";
const cell = "text-[10px] font-semibold text-muted uppercase tracking-wider md:hidden";

/**
 * Bulk cheque numbering (PR #421 ruling 2): the selected renewal drafts' cheque rows in one grid —
 * draft, unit, tenant, due date, amount, cheque number, bank, cheque date — filled by typing or
 * by a CSV keyed by contract (the draft's or the renewed contract's number, or the unit) and due
 * date. Each draft is saved by the single grid save, 25 drafts a call; a draft refused is listed
 * with its row errors and keeps what was typed, the others are saved, and a call that fails
 * marks its drafts as not processed. Then the drafts go to the existing bulk post.
 */
export default function BulkChequeNumbersDialog({ targets, onClose }: { targets: NumberingTarget[]; onClose: () => void }) {
    const t = useTranslations("RenewalSeason");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const [grids, setGrids] = useState<Record<string, DraftGrid>>({});
    const [edits, setEdits] = useState<Record<string, Edit>>({});
    const [state, setState] = useState<Record<string, DraftState>>({});
    const [progress, setProgress] = useState<{ done: number; total: number; what: "load" | "save" } | null>({ done: 0, total: targets.length, what: "load" });
    const [csv, setCsv] = useState("");
    const [csvReport, setCsvReport] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const fileRef = useRef<HTMLInputElement>(null);

    const textOf = (e: unknown) => (e instanceof ApiError ? (serverText(tCommon, e) || e.message) : t("batchFailed"));

    // Load every draft's grid, 25 drafts a call; a call that fails marks its drafts, the rest load.
    useEffect(() => {
        let alive = true;
        (async () => {
            const loaded: Record<string, DraftGrid> = {};
            const failures: Record<string, DraftState> = {};
            let done = 0;
            for (const part of chunks(targets.map(x => x.draftId), BATCH_CHUNK)) {
                try {
                    const rows = await chequeBatchApi.load(part);
                    const answered = new Map(rows.map(r => [r.leaseId, r]));
                    for (const id of part) {
                        const g = answered.get(id);
                        if (g?.ok) loaded[id] = g;
                        else failures[id] = { error: g ? (serverText(tCommon, g) || t("batchFailed")) : t("notProcessed") };
                    }
                } catch (e) {
                    for (const id of part) failures[id] = { error: t("chunkFailed", { error: textOf(e) }) };
                }
                done += part.length;
                if (alive) setProgress({ done, total: targets.length, what: "load" });
            }
            if (!alive) return;
            const initial: Record<string, Edit> = {};
            for (const g of Object.values(loaded)) {
                for (const c of draftRows(g)) initial[c.id] = { chequeNumber: c.chequeNumber ?? "", payeeBank: c.payeeBank ?? "", chequeDate: c.chequeDate ?? "" };
            }
            setGrids(loaded);
            setEdits(initial);
            setState(failures);
            setProgress(null);
        })();
        return () => { alive = false; };
        // eslint-disable-next-line react-hooks/exhaustive-deps -- loads once for the targets the dialog opened with
    }, []);

    const order = targets.filter(x => grids[x.draftId]);
    const problems = useMemo(() => {
        const out: Record<string, Record<string, string>> = {};
        for (const x of order) {
            const p = chequeRowProblems(draftRows(grids[x.draftId]), edits);
            out[x.draftId] = Object.fromEntries(Object.entries(p).map(([id, code]) => [id, t(`numbering.${code}`)]));
        }
        return out;
    }, [order, grids, edits, t]);

    const setEdit = (chequeId: string, field: keyof Edit, value: string) =>
        setEdits(prev => ({ ...prev, [chequeId]: { ...prev[chequeId], [field]: value } }));

    const applyCsv = () => {
        const drafts: CsvDraft[] = order.map(x => ({
            draftId: x.draftId,
            keys: [grids[x.draftId].contractNumber, x.predecessorContract, x.unitIdentifier, x.draftId],
            rows: draftRows(grids[x.draftId]),
        }));
        const r = applyChequeCsv(csv, drafts, edits);
        setEdits(r.edits);
        setCsvReport(t("csvApplied", { applied: r.applied, unmatched: r.unmatched.length })
            + (r.unmatched.length ? ` ${t("csvUnmatchedLines", { lines: r.unmatched.slice(0, 10).join(", ") })}` : ""));
    };

    const save = async () => {
        setError(null);
        const pending = order.filter(x => !state[x.draftId]?.saved);
        const ready = pending.filter(x => Object.keys(problems[x.draftId] ?? {}).length === 0);
        const next: Record<string, DraftState> = { ...state };
        for (const x of pending) if (!ready.includes(x)) next[x.draftId] = { error: t("fixRowsFirst"), rowErrors: problems[x.draftId] };
        setProgress({ done: 0, total: ready.length, what: "save" });
        let done = 0;
        let failedCall = false;
        const nextGrids = { ...grids };
        for (const part of chunks(ready, BATCH_CHUNK)) {
            const body: DraftNumbers[] = part.map(x => ({
                leaseId: x.draftId,
                version: grids[x.draftId].version,
                rows: draftRows(grids[x.draftId]).map(c => ({
                    chequeId: c.id,
                    chequeNumber: edits[c.id]?.chequeNumber.trim() || null,
                    payeeBank: edits[c.id]?.payeeBank.trim() || null,
                    chequeDate: edits[c.id]?.chequeDate || null,
                    amount: c.amount,
                })),
            }));
            try {
                const rows = await chequeBatchApi.save(body);
                const answered = new Map(rows.map(r => [r.leaseId, r]));
                for (const x of part) {
                    const r = answered.get(x.draftId);
                    if (r?.ok) {
                        next[x.draftId] = { saved: true };
                        nextGrids[x.draftId] = { ...grids[x.draftId], version: r.version, cheques: r.cheques ?? grids[x.draftId].cheques };
                    } else {
                        next[x.draftId] = {
                            error: r ? (serverText(tCommon, r) || t("batchFailed")) : t("notProcessed"),
                            rowErrors: Object.fromEntries((r?.rowErrors ?? []).filter(e => e.chequeId).map(e => [e.chequeId!, e.message])),
                        };
                    }
                }
            } catch (e) {
                failedCall = true;
                for (const x of part) next[x.draftId] = { error: t("chunkFailed", { error: textOf(e) }) };
            }
            done += part.length;
            setProgress({ done, total: ready.length, what: "save" });
        }
        if (failedCall) setError(t("someChunksFailed"));
        setGrids(nextGrids);
        setState(next);
        setProgress(null);
    };

    const saved = targets.filter(x => state[x.draftId]?.saved).length;
    const attention = targets.filter(x => state[x.draftId]?.error).length;
    const busy = !!progress;
    const label = (x: NumberingTarget) => `${x.unitIdentifier ?? "—"} · ${x.renterName ?? "—"}`;

    return (
        <LeaseDialog open title={t("numberingTitle", { n: targets.length })} onClose={onClose} onConfirm={save}
            confirmText={busy && progress?.what === "save" ? t("savingProgress", progress) : t("saveNumbers")}
            cancelText={t("close")} busy={busy} confirmDisabled={busy || order.every(x => state[x.draftId]?.saved)}
            confirmTestId="numbering-save" width="xl">
            <div className="space-y-4" data-testid="numbering-dialog">
                <p className="text-xs text-muted">{t("numberingIntro")}</p>
                {progress?.what === "load" && (
                    <p className="text-xs text-muted flex items-center gap-2"><Loader2 size={12} className="animate-spin" />{t("loadingProgress", progress)}</p>
                )}
                {error && <p className="text-xs text-error" role="alert">{error}</p>}
                {(saved > 0 || attention > 0) && (
                    <div className="flex flex-wrap items-center gap-3 text-xs" data-testid="numbering-summary">
                        <span>{t("numberingSummary", { saved, attention, total: targets.length })}</span>
                        {saved > 0 && (
                            <Link href="/dashboard/leases?view=draft" className="font-semibold text-primary hover:underline" data-testid="numbering-to-post">
                                {t("postTheDrafts")}
                            </Link>
                        )}
                    </div>
                )}

                <details className="border border-border rounded-lg p-3" data-testid="numbering-csv">
                    <summary className="text-xs font-semibold cursor-pointer flex items-center gap-2"><Upload size={12} />{t("csvTitle")}</summary>
                    <p className="mt-2 text-[11px] text-muted">{t("csvHelp")}</p>
                    <textarea value={csv} onChange={e => setCsv(e.target.value)} rows={4} dir="ltr"
                        className={`${input} mt-2 font-mono`} placeholder="contract,due_date,cheque_number,bank,cheque_date" data-testid="numbering-csv-text" />
                    <div className="mt-2 flex flex-wrap items-center gap-3">
                        <input ref={fileRef} type="file" accept=".csv,text/csv,text/plain" className="text-xs" data-testid="numbering-csv-file"
                            onChange={async e => { const f = e.target.files?.[0]; if (f) setCsv(await f.text()); }} />
                        <button type="button" onClick={applyCsv} disabled={!csv.trim() || busy}
                            className="text-xs font-semibold text-primary hover:underline disabled:opacity-50" data-testid="numbering-csv-apply">
                            {t("csvApply")}
                        </button>
                        {csvReport && <span className="text-xs text-muted" data-testid="numbering-csv-report">{csvReport}</span>}
                    </div>
                </details>

                {targets.filter(x => !grids[x.draftId] && state[x.draftId]?.error).map(x => (
                    <p key={x.draftId} className="text-xs text-error flex gap-2" data-testid={`numbering-draft-${x.draftId}`} data-ok="false">
                        <XCircle size={14} className="shrink-0" />{label(x)}: {state[x.draftId]?.error}
                    </p>
                ))}

                <div className="space-y-4">
                    {order.map(x => {
                        const g = grids[x.draftId];
                        const st = state[x.draftId] ?? {};
                        const local = problems[x.draftId] ?? {};
                        return (
                            <section key={x.draftId} className="border border-border rounded-lg overflow-hidden" data-testid={`numbering-draft-${x.draftId}`}
                                data-ok={st.saved ? "true" : st.error ? "false" : undefined}>
                                <header className="bg-input px-3 py-2 text-xs flex flex-wrap items-center gap-2">
                                    {st.saved ? <CheckCircle size={14} className="text-success" /> : st.error ? <XCircle size={14} className="text-error" /> : null}
                                    <span className="font-semibold">{t("draftFor", { contract: g.contractNumber ?? x.predecessorContract ?? "—" })}</span>
                                    <span>{label(x)}</span>
                                    {st.saved && <span className="text-success">{t("numbersSaved")}</span>}
                                    {st.error && <span className="text-error">{st.error}</span>}
                                </header>
                                <div className="hidden md:grid md:grid-cols-[1fr_1fr_1.4fr_1.2fr_1fr] gap-2 px-3 pt-2 text-[10px] font-semibold text-muted uppercase tracking-wider">
                                    <span>{t("colDue")}</span><span className="text-end">{t("colAmount")}</span><span>{t("colChequeNumber")}</span>
                                    <span>{t("colBank")}</span><span>{t("colChequeDate")}</span>
                                </div>
                                <ul className="divide-y divide-border">
                                    {draftRows(g).map(c => {
                                        const e = edits[c.id] ?? { chequeNumber: "", payeeBank: "", chequeDate: "" };
                                        const problem = st.rowErrors?.[c.id] ?? local[c.id];
                                        return (
                                            <li key={c.id} className="px-3 py-2 grid grid-cols-2 md:grid-cols-[1fr_1fr_1.4fr_1.2fr_1fr] gap-2 items-center"
                                                data-testid={`numbering-row-${c.id}`}>
                                                <div><span className={cell}>{t("colDue")} </span><span className="text-xs">{fmtIsoDate(c.chequeDate, locale)}</span></div>
                                                <div className="text-end"><span className={cell}>{t("colAmount")} </span>
                                                    <bdi dir="ltr" className="text-xs tabular-nums">{formatCurrency(c.amount)}</bdi></div>
                                                <label className="block"><span className={cell}>{t("colChequeNumber")}</span>
                                                    <input className={input} value={e.chequeNumber} disabled={st.saved || busy} dir="ltr" inputMode="numeric"
                                                        aria-label={t("colChequeNumber")} aria-invalid={!!problem || undefined}
                                                        onChange={ev => setEdit(c.id, "chequeNumber", ev.target.value)} data-testid={`numbering-number-${c.id}`} /></label>
                                                <label className="block"><span className={cell}>{t("colBank")}</span>
                                                    <input className={input} value={e.payeeBank} disabled={st.saved || busy} aria-label={t("colBank")}
                                                        onChange={ev => setEdit(c.id, "payeeBank", ev.target.value)} data-testid={`numbering-bank-${c.id}`} /></label>
                                                <label className="block"><span className={cell}>{t("colChequeDate")}</span>
                                                    <input type="date" className={input} value={e.chequeDate} disabled={st.saved || busy} aria-label={t("colChequeDate")}
                                                        onChange={ev => setEdit(c.id, "chequeDate", ev.target.value)} data-testid={`numbering-date-${c.id}`} /></label>
                                                {problem && <p className="col-span-2 md:col-span-5 text-[11px] text-error" data-testid={`numbering-error-${c.id}`}>{problem}</p>}
                                            </li>
                                        );
                                    })}
                                </ul>
                            </section>
                        );
                    })}
                </div>
            </div>
        </LeaseDialog>
    );
}

/** A draft's own grid rows (DRAFT status), in schedule order. */
function draftRows(g: DraftGrid | undefined): Cheque[] {
    return (g?.cheques ?? []).filter(c => c.status === "DRAFT").sort((a, b) => a.seqNo - b.seqNo);
}
