"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle, Loader2, XCircle } from "lucide-react";
import { Link } from "@/i18n/routing";
import LeaseDialog from "@/components/leases/LeaseDialog";
import { Pagination } from "@/components/ui/Pagination";
import { serverText } from "@/components/finance/bankrec/serverText";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { formatCurrency } from "@/lib/format";
import { useLatestRequest } from "@/hooks/useLatestRequest";
import { isAbortError } from "@/lib/api/abort";
import {
    ApiError, leaseApi, leaseBatchApi,
    type BulkTerminatePreviewRow, type BulkTerminateRow, type Cheque, type LeaseDetail, type LeaseStatus,
} from "@/lib/api/leasing";
import { chunks, isTerminable } from "@/lib/leases/renewalSeason";
import { runPool } from "@/lib/pool";

/** Everything but the two statuses `LeaseTerminationService.TERMINABLE` admits. */
const NOT_TERMINABLE: LeaseStatus[] = ["DRAFT", "PENDING_SIGNATURE", "RENEWED", "TERMINATED", "EXPIRED", "CLOSED"];
/** Previews are heavier than drafts (recognition and VAT priced per contract): smaller chunks. */
const PREVIEW_CHUNK = 20;
/** Preview chunks in flight at once: a preview writes nothing, so it can run beside another. */
const PREVIEW_CONCURRENCY = 3;
/** The most contracts one bulk terminate takes from "Select all". */
const SELECT_ALL_CAP = 500;

const sum = (rows: Cheque[] | undefined) => (rows ?? []).reduce((a, c) => a + Number(c.amount ?? 0), 0);

export interface TerminateFilters { date: string; propertyId: string; buildingId: string; floor: string; search: string }

/**
 * Bulk terminate (scale #17, PACT "Bulk Terminate"): the contracts ending on a date → a
 * preview of each, from the single termination preview (the settlement figures, the default
 * cheque split, its problems and notices) → confirm. Each contract is then ended by the single
 * terminate with that default split, in a transaction of its own: one refusal is listed on its
 * row and the others go through. A manager previews; only the finance roles confirm.
 */
export default function BulkTerminate({ filters, onDateChange, canTerminate }: {
    filters: TerminateFilters;
    onDateChange: (date: string) => void;
    canTerminate: boolean;
}) {
    const t = useTranslations("RenewalSeason");
    const tl = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const [rows, setRows] = useState<LeaseDetail[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(25);
    const [loading, setLoading] = useState(true);
    const [selected, setSelected] = useState<Map<string, LeaseDetail>>(new Map());
    const [previews, setPreviews] = useState<BulkTerminatePreviewRow[] | null>(null);
    const [include, setInclude] = useState<Set<string>>(new Set());
    const [progress, setProgress] = useState<{ done: number; total: number; what: "preview" | "terminate" } | null>(null);
    const [confirming, setConfirming] = useState(false);
    const [notes, setNotes] = useState("");
    const [results, setResults] = useState<BulkTerminateRow[] | null>(null);
    /** Each result's unit and tenant, captured when the run starts (the selection is cleared after it). */
    const [resultLabels, setResultLabels] = useState<Map<string, string>>(new Map());
    const [error, setError] = useState<string | null>(null);
    const begin = useLatestRequest();

    const query = (p: number, s: number) => leaseApi.paged({
        endDateFrom: filters.date, endDateTo: filters.date, excludeStatus: NOT_TERMINABLE,
        propertyId: filters.propertyId || undefined, buildingId: filters.buildingId || undefined,
        floor: filters.floor || undefined, search: filters.search || undefined,
        sort: "unit.unitNumber,asc", page: p, size: s,
    });

    const load = async () => {
        const { isCurrent } = begin();
        setLoading(true);
        try {
            const data = await query(page - 1, size);
            if (!isCurrent()) return;
            setRows(data.content ?? []);
            setTotal(data.totalElements ?? 0);
        } catch (e) {
            if (isAbortError(e) || !isCurrent()) return;
            setError(e instanceof ApiError ? e.message : t("loadFailed"));
        } finally {
            if (isCurrent()) setLoading(false);
        }
    };

    useEffect(() => {
        load();
        // eslint-disable-next-line react-hooks/exhaustive-deps -- reload on the filters and the page only
    }, [filters.date, filters.propertyId, filters.buildingId, filters.floor, filters.search, page, size]);

    useEffect(() => {
        setPage(1);
        setSelected(new Map());
        setPreviews(null);
        setResults(null);
    }, [filters.date, filters.propertyId, filters.buildingId, filters.floor, filters.search]);

    const toggle = (l: LeaseDetail) => setSelected(prev => {
        const next = new Map(prev);
        if (next.has(l.id)) next.delete(l.id); else next.set(l.id, l);
        return next;
    });
    const pageAllSelected = rows.length > 0 && rows.every(r => selected.has(r.id));
    const togglePage = () => setSelected(prev => {
        const next = new Map(prev);
        rows.forEach(r => (pageAllSelected ? next.delete(r.id) : next.set(r.id, r)));
        return next;
    });
    const selectAll = async () => {
        const next = new Map<string, LeaseDetail>();
        for (let p = 0; next.size < Math.min(total, SELECT_ALL_CAP); p++) {
            const data = await query(p, 100);
            (data.content ?? []).forEach(l => { if (next.size < SELECT_ALL_CAP) next.set(l.id, l); });
            if ((data.content ?? []).length < 100) break;
        }
        setSelected(next);
    };

    const runPreview = async () => {
        const ids = [...selected.keys()];
        setError(null);
        setResults(null);
        setProgress({ done: 0, total: ids.length, what: "preview" });
        const out: BulkTerminatePreviewRow[] = [];
        try {
            // Read-only, so three chunks at a time; the answers keep the order selected.
            let done = 0;
            const parts = await runPool(chunks(ids, PREVIEW_CHUNK), PREVIEW_CONCURRENCY, async part => {
                const rows = await leaseBatchApi.previewTerminate({ leaseIds: part, terminationDate: filters.date });
                done += rows.length;
                setProgress({ done, total: ids.length, what: "preview" });
                return rows;
            });
            out.push(...parts.flat());
        } catch (e) {
            setError(e instanceof ApiError ? (serverText(tCommon, e) || e.message) : t("batchFailed"));
        }
        setPreviews(out);
        // A contract the preview refuses, or whose termination would be refused, is left out by default.
        setInclude(new Set(out.filter(p => p.ok && (p.preview?.problems ?? []).length === 0).map(p => p.leaseId)));
        setProgress(null);
    };

    const runTerminate = async () => {
        const ids = (previews ?? []).filter(p => include.has(p.leaseId)).map(p => p.leaseId);
        setResultLabels(new Map(ids.map(id => [id, label(id)])));
        setConfirming(false);
        setError(null);
        setProgress({ done: 0, total: ids.length, what: "terminate" });
        const out: BulkTerminateRow[] = [];
        try {
            for (const part of chunks(ids, PREVIEW_CHUNK)) {
                out.push(...await leaseBatchApi.terminate({ leaseIds: part, terminationDate: filters.date, notes: notes.trim() || null }));
                setProgress({ done: out.length, total: ids.length, what: "terminate" });
            }
        } catch (e) {
            setError(e instanceof ApiError ? (serverText(tCommon, e) || e.message) : t("batchFailed"));
        }
        setResults(out);
        setProgress(null);
        setSelected(new Map());
        setPreviews(null);
        load();
    };

    const label = (id: string) => {
        const l = selected.get(id) ?? rows.find(r => r.id === id);
        return l ? `${l.unitIdentifier ?? "—"} · ${l.renterName ?? "—"}` : id;
    };
    const included = (previews ?? []).filter(p => include.has(p.leaseId));
    const failedResults = (results ?? []).filter(r => !r.ok);

    return (
        <div className="space-y-4" data-testid="bulk-terminate">
            <div className="flex flex-wrap items-end gap-3">
                <label className="block text-[11px] font-semibold text-muted">
                    {t("endingOn")}
                    <input type="date" value={filters.date} onChange={e => e.target.value && onDateChange(e.target.value)}
                        className="mt-1 block bg-surface border border-border rounded-lg px-3 py-2 text-xs text-foreground" data-testid="bulk-terminate-date" />
                </label>
                <p className="text-sm font-semibold" data-testid="bulk-terminate-count">
                    {loading ? <Loader2 size={14} className="animate-spin inline" /> : t("endingCount", { count: total, date: fmtIsoDate(filters.date, locale) })}
                </p>
                <div className="flex flex-wrap items-center gap-2 ms-auto">
                    {total > 0 && selected.size < Math.min(total, SELECT_ALL_CAP) && (
                        <button type="button" onClick={selectAll} className="text-xs font-semibold text-primary hover:underline" data-testid="bulk-terminate-select-all">
                            {t("selectAll", { n: Math.min(total, SELECT_ALL_CAP) })}
                        </button>
                    )}
                    {selected.size > 0 && (
                        <button type="button" onClick={runPreview} disabled={!!progress}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold disabled:opacity-50"
                            data-testid="bulk-terminate-preview">
                            {progress?.what === "preview" ? <Loader2 size={14} className="animate-spin" /> : null}
                            {progress?.what === "preview" ? t("previewRunning", progress) : t("previewN", { n: selected.size })}
                        </button>
                    )}
                </div>
            </div>
            {error && <p className="text-xs text-error" role="alert">{error}</p>}

            {results && (
                <section className="bg-surface border border-border rounded-[var(--radius-lg)] p-4" data-testid="bulk-terminate-results">
                    <p className="text-xs font-semibold mb-2">{t("terminateSummary", { ok: results.length - failedResults.length, failed: failedResults.length })}</p>
                    <ul className="space-y-1.5 text-xs">
                        {results.map(r => (
                            <li key={r.leaseId} className="flex items-start gap-2" data-testid={`bulk-terminate-result-${r.leaseId}`} data-ok={r.ok}>
                                {r.ok ? <CheckCircle size={14} className="text-success shrink-0 mt-0.5" /> : <XCircle size={14} className="text-error shrink-0 mt-0.5" />}
                                <Link href={`/dashboard/leases/${r.leaseId}`} className="font-medium hover:underline">{resultLabels.get(r.leaseId) ?? label(r.leaseId)}</Link>
                                <span className={r.ok ? "text-[var(--ink-600)]" : "text-error"}>
                                    {r.ok ? t("terminatedOn", { date: fmtIsoDate(r.terminationDate, locale) }) : (serverText(tCommon, r) || t("batchFailed"))}
                                </span>
                            </li>
                        ))}
                    </ul>
                    {results.length - failedResults.length > 0 && (
                        <Link href="/dashboard/leases?view=settlement" className="mt-3 inline-block text-xs font-semibold text-primary hover:underline">
                            {t("toSettlements")}
                        </Link>
                    )}
                </section>
            )}

            {previews ? (
                <section className="bg-surface border border-border rounded-[var(--radius-lg)] overflow-hidden" data-testid="bulk-terminate-previews">
                    <div className="overflow-x-auto">
                        <table className="w-full text-xs min-w-[760px]">
                            <thead className="bg-input text-muted text-[10px] uppercase tracking-wider">
                                <tr>
                                    <th className="p-2 w-8"><span className="sr-only">{t("include")}</span></th>
                                    <th className="p-2 text-start">{t("colContract")}</th>
                                    <th className="p-2 text-end">{t("colEarned")}</th>
                                    <th className="p-2 text-end">{t("colRefund")}</th>
                                    <th className="p-2 text-end">{t("colReturned")}</th>
                                    <th className="p-2 text-end">{t("colKept")}</th>
                                    <th className="p-2 text-end">{t("colReceivable")}</th>
                                    <th className="p-2 text-start">{t("colIssues")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {previews.map(p => {
                                    const v = p.preview;
                                    const issues = [...(v?.problems ?? [])];
                                    return (
                                        <tr key={p.leaseId} data-testid={`bulk-terminate-preview-${p.leaseId}`} data-ok={p.ok}>
                                            <td className="p-2">
                                                <input type="checkbox" disabled={!p.ok} checked={include.has(p.leaseId)} aria-label={t("include")}
                                                    onChange={() => setInclude(prev => { const n = new Set(prev); if (n.has(p.leaseId)) n.delete(p.leaseId); else n.add(p.leaseId); return n; })} />
                                            </td>
                                            <td className="p-2">
                                                <Link href={`/dashboard/leases/${p.leaseId}/terminate?date=${filters.date}`} className="font-medium hover:underline">{label(p.leaseId)}</Link>
                                            </td>
                                            {p.ok && v ? (
                                                <>
                                                    <td className="p-2 text-end tabular-nums"><bdi dir="ltr">{formatCurrency(v.earnedRentThroughDate)}</bdi></td>
                                                    <td className="p-2 text-end tabular-nums"><bdi dir="ltr">{formatCurrency(Number(v.unearnedRent) + Number(v.unearnedVat ?? 0))}</bdi></td>
                                                    <td className="p-2 text-end tabular-nums">{v.chequesToReturn.length} · <bdi dir="ltr">{formatCurrency(sum(v.chequesToReturn))}</bdi></td>
                                                    <td className="p-2 text-end tabular-nums">{v.chequesToKeep.length} · <bdi dir="ltr">{formatCurrency(sum(v.chequesToKeep))}</bdi></td>
                                                    <td className="p-2 text-end tabular-nums"><bdi dir="ltr">{formatCurrency(v.receivableAfter)}</bdi></td>
                                                    <td className="p-2">
                                                        {issues.map((m, i) => <p key={`p${i}`} className="text-error flex gap-1"><AlertTriangle size={12} className="shrink-0 mt-0.5" />{m}</p>)}
                                                        {(v.notices ?? []).map((m, i) => <p key={`n${i}`} className="text-warning">{m}</p>)}
                                                        {issues.length === 0 && (v.notices ?? []).length === 0 && <span className="text-muted">—</span>}
                                                    </td>
                                                </>
                                            ) : (
                                                <td colSpan={6} className="p-2 text-error">{serverText(tCommon, p) || t("batchFailed")}</td>
                                            )}
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>
                    <div className="flex flex-wrap items-center gap-3 p-3 border-t border-border">
                        <span className="text-xs text-muted">{t("includedCount", { n: included.length, of: previews.length })}</span>
                        <button type="button" onClick={() => setPreviews(null)} className="text-xs text-muted hover:underline">{t("backToList")}</button>
                        {canTerminate ? (
                            <button type="button" disabled={included.length === 0 || !!progress} onClick={() => setConfirming(true)}
                                className="ms-auto flex items-center gap-2 bg-error text-white px-4 py-2 rounded-lg text-xs font-semibold disabled:opacity-50"
                                data-testid="bulk-terminate-confirm-open">
                                {progress?.what === "terminate" ? <Loader2 size={14} className="animate-spin" /> : null}
                                {progress?.what === "terminate" ? t("terminateRunning", progress) : t("terminateN", { n: included.length })}
                            </button>
                        ) : (
                            <span className="ms-auto text-xs text-muted" data-testid="bulk-terminate-finance-only">{t("financeOnly")}</span>
                        )}
                    </div>
                </section>
            ) : (
                <section className="bg-surface border border-border rounded-[var(--radius-lg)] overflow-hidden">
                    <div className="overflow-x-auto">
                        <table className="w-full text-xs min-w-[560px]">
                            <thead className="bg-input text-muted text-[10px] uppercase tracking-wider">
                                <tr>
                                    <th className="p-2 w-8">
                                        <input type="checkbox" checked={pageAllSelected} onChange={togglePage} aria-label={t("selectPage")} data-testid="bulk-terminate-select-page" />
                                    </th>
                                    <th className="p-2 text-start">{tl("unit")}</th>
                                    <th className="p-2 text-start">{t("colTenant")}</th>
                                    <th className="p-2 text-start">{t("colProperty")}</th>
                                    <th className="p-2 text-start">{t("colStatus")}</th>
                                    <th className="p-2 text-end">{t("colRent")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {!loading && rows.length === 0 && (
                                    <tr><td colSpan={6} className="p-6 text-center text-muted" data-testid="bulk-terminate-empty">{t("noneEnding")}</td></tr>
                                )}
                                {rows.map(l => (
                                    <tr key={l.id} data-testid={`bulk-terminate-row-${l.id}`}>
                                        <td className="p-2">
                                            <input type="checkbox" checked={selected.has(l.id)} disabled={!isTerminable(l)} onChange={() => toggle(l)}
                                                aria-label={t("selectRow", { unit: l.unitIdentifier ?? "" })} />
                                        </td>
                                        <td className="p-2 font-medium"><Link href={`/dashboard/leases/${l.id}`} className="hover:underline">{l.unitIdentifier ?? "—"}</Link></td>
                                        <td className="p-2">{l.renterName ?? "—"}</td>
                                        <td className="p-2">{l.propertyName ?? "—"}</td>
                                        <td className="p-2">{tl(`leaseStatus.${l.status}`)}</td>
                                        <td className="p-2 text-end tabular-nums"><bdi dir="ltr">{formatCurrency(l.currentRentAmount ?? l.rentAmount)}</bdi></td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    {total > size && (
                        <div className="p-3 border-t border-border">
                            <Pagination currentPage={page} totalItems={total} itemsPerPage={size} onPageChange={setPage}
                                onItemsPerPageChange={n => { setSize(n); setPage(1); }} />
                        </div>
                    )}
                </section>
            )}

            <LeaseDialog
                open={confirming}
                title={t("confirmTerminateTitle", { n: included.length })}
                onClose={() => setConfirming(false)}
                onConfirm={runTerminate}
                confirmText={t("terminateN", { n: included.length })}
                cancelText={t("cancel")}
                destructive
                confirmTestId="bulk-terminate-confirm"
            >
                <div className="space-y-3">
                    <p className="text-xs">{t("confirmTerminateBody", { date: fmtIsoDate(filters.date, locale) })}</p>
                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider">
                        {t("notes")}
                        <textarea value={notes} onChange={e => setNotes(e.target.value)} rows={2}
                            className="mt-1 w-full bg-input border border-border rounded-lg px-3 py-2 text-xs normal-case" data-testid="bulk-terminate-notes" />
                    </label>
                </div>
            </LeaseDialog>
        </div>
    );
}
