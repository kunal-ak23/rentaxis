"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import Link from "next/link";
import { CalendarClock, Eye, Loader2, Play, ShieldCheck } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { fmtAmount, ledgerApi } from "@/lib/api/ledger";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { Pagination } from "@/components/ui/Pagination";
import { fmtIsoDate, todayIso } from "@/components/leases/leaseMath";
import {
    ApiError, recognitionApi,
    type RecognitionEntry, type RecognitionRunResult,
} from "@/lib/api/leasing";

/**
 * Month-end close for per-day rent recognition (spec §8.4).
 *
 * Two acts on one screen, and the difference between them is the point:
 * **Preview** writes nothing and answers with `wouldPost`, **Run** posts one
 * `CIL` per planned period that ended. The result card never shows a "posted"
 * figure for a preview, because `RecognitionRunResultDTO` deliberately reports
 * `posted: 0` there — a preview that reported itself as posted is how a close
 * gets signed off twice.
 *
 * The date is bounded at today, mirroring
 * `RecognitionController.notInTheFuture` (:145-157): income is recognised for
 * periods that have **ended**, and both `pending` and `run` 400 on a later
 * date. So a future value disables both buttons and stops the fetch rather
 * than rendering the server's refusal.
 *
 * Grouped by property (spec §11): an accountant closing a month works one
 * building at a time, and a flat list of every lease's rows is not a worklist.
 * `RecognitionEntryDTO` carries `propertyId`/`propertyName`/`unitName` for
 * exactly this.
 */

const th = "text-start px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-1.5 text-xs";

/** The close is nearly always run for the month that has just ended. */
function lastDayOfPreviousMonth(): string {
    const now = new Date();
    const d = new Date(now.getFullYear(), now.getMonth(), 0);
    const pad = (n: number) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** The bucket a row with no property falls into — last, and still counted. */
export const UNASSIGNED = "unassigned";

export type PropertyGroup = {
    /** `propertyId`, or {@link UNASSIGNED}. Also the group's data-testid suffix. */
    key: string;
    propertyName: string | null;
    rows: RecognitionEntry[];
    subtotal: number;
};

/**
 * By property, groups in property-name order with the unplaceable ones last;
 * inside a group, by unit then by period.
 *
 * A row whose `propertyId` is null is bucketed rather than dropped. The schema
 * does not allow a lease without a unit, so this should be unreachable — but the
 * page it feeds is a close worklist, and a row that quietly vanished from the
 * list is a row the accountant does not know the run is about to post.
 */
export function groupByProperty(rows: RecognitionEntry[]): PropertyGroup[] {
    const byProperty = new Map<string, RecognitionEntry[]>();
    for (const r of rows) {
        const key = r.propertyId ?? UNASSIGNED;
        const list = byProperty.get(key) ?? [];
        list.push(r);
        byProperty.set(key, list);
    }
    return [...byProperty.entries()]
        .map(([key, list]) => ({
            key,
            propertyName: list[0].propertyName,
            rows: [...list].sort(
                (a, b) =>
                    (a.unitName ?? "").localeCompare(b.unitName ?? "")
                    || a.periodStart.localeCompare(b.periodStart),
            ),
            subtotal: list.reduce((s, r) => s + (r.amount ?? 0), 0),
        }))
        .sort((a, b) => {
            if (a.key === UNASSIGNED) return 1;
            if (b.key === UNASSIGNED) return -1;
            return (a.propertyName ?? "").localeCompare(b.propertyName ?? "");
        });
}

export default function RecognitionPage() {
    const t = useTranslations("Recognition");
    const tLedger = useTranslations("Ledger");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canRunRecognition");

    const [to, setTo] = useState(lastDayOfPreviousMonth);
    const [pending, setPending] = useState<RecognitionEntry[]>([]);
    const [lockedThrough, setLockedThrough] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [result, setResult] = useState<RecognitionRunResult | null>(null);
    const [busy, setBusy] = useState<"preview" | "run" | null>(null);
    const [runError, setRunError] = useState<string | null>(null);
    const [confirmOpen, setConfirmOpen] = useState(false);
    const [page, setPage] = useState(1);
    const [pageSize, setPageSize] = useState(25);

    const today = todayIso();
    const inTheFuture = to > today;

    const loadPending = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            setPending(await recognitionApi.pending(to));
        } catch (e) {
            setPending([]);
            setLoadError(e instanceof ApiError ? e.message : t("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [to, t]);

    useEffect(() => {
        if (!allowed) {
            setLoading(false);
            return;
        }
        // The same rule the server has: never ask for a period that has not
        // ended. Without this the screen 400s on every keystroke of the year.
        if (inTheFuture) {
            setLoading(false);
            return;
        }
        loadPending();
    }, [allowed, inTheFuture, loadPending]);

    useEffect(() => {
        if (!allowed) return;
        ledgerApi.fiscal
            .get()
            .then(f => setLockedThrough(f.booksLockedThrough))
            .catch(() => setLockedThrough(null));
    }, [allowed]);

    useEffect(() => {
        setPage(1);
    }, [to, pending.length]);

    const groups = useMemo(() => groupByProperty(pending), [pending]);
    // Σ over the groups, not over `pending`: the grand total and the subtotals
    // are read together and have to be the same addition.
    const pendingTotal = useMemo(() => groups.reduce((s, g) => s + g.subtotal, 0), [groups]);
    const pagedGroups = groups.slice((page - 1) * pageSize, page * pageSize);

    const execute = async (preview: boolean) => {
        setBusy(preview ? "preview" : "run");
        setRunError(null);
        try {
            const r = await recognitionApi.run(to, preview);
            setResult(r);
            setConfirmOpen(false);
            // A real run has changed what is pending; a preview has not.
            if (!preview) await loadPending();
        } catch (e) {
            setRunError(e instanceof ApiError ? e.message : t("runFailed"));
        } finally {
            setBusy(null);
        }
    };

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted" data-testid="recognition-access-denied">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    return (
        <div className="max-w-5xl space-y-6">
            <div>
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <CalendarClock size={20} className="text-primary" />
                    {t("title")}
                </h1>
                <p className="text-xs text-muted">{t("desc")}</p>
            </div>

            <div className="bg-surface border border-border rounded-xl px-5 py-4 flex flex-wrap items-end gap-4">
                <label className="flex flex-col gap-1">
                    <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">{t("toDate")}</span>
                    <input
                        type="date"
                        data-testid="recognition-to-date"
                        value={to}
                        max={today}
                        onChange={e => setTo(e.target.value)}
                        className="border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                    />
                    <span className="text-[10px] text-muted">{t("toDateHint")}</span>
                </label>

                {lockedThrough && (
                    <p className="text-[10px] text-muted mb-2" data-testid="recognition-locked-through">
                        {t("booksLockedThrough")}: {fmtIsoDate(lockedThrough, locale)}
                    </p>
                )}

                <div className="ms-auto flex items-center gap-2 mb-1">
                    <button
                        type="button"
                        data-testid="recognition-preview"
                        disabled={inTheFuture || busy !== null}
                        onClick={() => execute(true)}
                        className="flex items-center gap-2 border border-border text-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-input transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {busy === "preview" ? <Loader2 size={14} className="animate-spin" /> : <Eye size={14} />}
                        {t("preview")}
                    </button>
                    <button
                        type="button"
                        data-testid="recognition-run"
                        disabled={inTheFuture || busy !== null}
                        onClick={() => setConfirmOpen(true)}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {busy === "run" ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}
                        {t("run")}
                    </button>
                </div>
            </div>

            {inTheFuture && (
                <div
                    role="alert"
                    data-testid="recognition-future-warning"
                    className="bg-warning/10 border border-warning/30 text-warning rounded-xl px-5 py-3 text-sm"
                >
                    {t("futureDate")}
                </div>
            )}

            {runError && (
                <div
                    role="alert"
                    data-testid="recognition-run-error"
                    className="bg-error/10 border border-error/30 text-error rounded-xl px-5 py-3 text-sm"
                >
                    {runError}
                </div>
            )}

            {result && (
                <div
                    data-testid="recognition-result"
                    className={cn(
                        "rounded-xl border px-5 py-4 space-y-3",
                        result.preview ? "bg-info/5 border-info/30" : "bg-success/5 border-success/30",
                    )}
                >
                    <p
                        data-testid="recognition-result-title"
                        className={cn("text-xs font-semibold", result.preview ? "text-info" : "text-success")}
                    >
                        {result.preview ? t("previewResult") : t("runResult")}
                    </p>
                    <div className="flex flex-wrap gap-6">
                        {result.preview ? (
                            <Stat label={t("wouldPost")} value={String(result.wouldPost)} testId="recognition-would-post" />
                        ) : (
                            <Stat label={t("posted")} value={String(result.posted)} testId="recognition-posted" />
                        )}
                        <Stat
                            label={t("postedAmount")}
                            value={fmtAmount(result.amount)}
                            testId="recognition-result-amount"
                        />
                        {result.failed > 0 && (
                            <Stat label={t("failed")} value={String(result.failed)} testId="recognition-failed" />
                        )}
                    </div>
                    {result.wouldPost === 0 && result.posted === 0 && result.failed === 0 && (
                        <p className="text-xs text-muted" data-testid="recognition-nothing">{t("nothingToPost")}</p>
                    )}
                    {result.skippedLocked > 0 && (
                        <>
                            <p className="text-xs text-warning" data-testid="recognition-skipped">
                                {t("skippedLockedNote", {
                                    count: result.skippedLocked,
                                    date: fmtIsoDate(result.booksLockedThrough, locale),
                                })}
                            </p>
                            {/*
                              The count alone is not something an accountant can
                              act on — reopening a period means knowing WHICH
                              contracts and WHICH months are behind the lock, and
                              the backend sends the rows for exactly that.
                            */}
                            {result.skippedLockedEntries.length > 0 && (
                                <ul
                                    className="list-disc ps-5 space-y-0.5"
                                    aria-label={t("skippedLocked")}
                                    data-testid="recognition-skipped-entries"
                                >
                                    {result.skippedLockedEntries.map(e => (
                                        <li key={e.id} className="text-[11px] text-muted">
                                            {fmtIsoDate(e.periodStart, locale)} – {fmtIsoDate(e.periodEnd, locale)}
                                            {" · "}
                                            {t("unit")} {e.unitName ?? "—"}
                                            {e.propertyName ? ` · ${e.propertyName}` : ""}
                                            {" · "}
                                            {fmtAmount(e.amount)}
                                        </li>
                                    ))}
                                </ul>
                            )}
                        </>
                    )}
                    {result.errors.length > 0 && (
                        <ul className="list-disc ps-5 space-y-1" data-testid="recognition-errors">
                            {result.errors.map((e, i) => (
                                <li key={i} className="text-xs text-error">{e}</li>
                            ))}
                        </ul>
                    )}
                </div>
            )}

            {loadError && <LoadErrorBanner message={loadError} onRetry={loadPending} />}

            <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                <div className="px-4 py-3 border-b border-border flex items-center justify-between">
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("pending")}</h2>
                    <span className="text-[11px] text-muted" data-testid="recognition-pending-total">
                        {t("pendingCount", { count: pending.length })} · {t("grandTotal")}: {fmtAmount(pendingTotal)}
                    </span>
                </div>

                {loading ? (
                    <div className="flex justify-center py-10">
                        <Loader2 size={18} className="animate-spin text-muted" />
                    </div>
                ) : groups.length === 0 ? (
                    <p className="text-xs text-muted text-center py-8" data-testid="recognition-no-pending">
                        {t("noPending")}
                    </p>
                ) : (
                    <div className="divide-y divide-border">
                        {pagedGroups.map(g => (
                            <div key={g.key} data-testid={`recognition-group-${g.key}`}>
                                <div className="px-4 py-2 bg-input/30 flex items-center justify-between gap-3">
                                    <span className="text-[11px] font-semibold text-foreground">
                                        {g.propertyName ?? t("unassigned")}
                                    </span>
                                    <span
                                        className="text-[11px] text-muted tabular-nums"
                                        data-testid={`recognition-group-total-${g.key}`}
                                    >
                                        {t("groupTotal")}: {fmtAmount(g.subtotal)}
                                    </span>
                                </div>
                                <div className="overflow-x-auto">
                                    <table className="w-full min-w-[640px]">
                                        <thead>
                                            <tr>
                                                <th scope="col" className={th}>{t("unit")}</th>
                                                <th scope="col" className={th}>{t("lease")}</th>
                                                <th scope="col" className={th}>{t("period")}</th>
                                                <th scope="col" className={`${th} text-end`}>{t("days")}</th>
                                                <th scope="col" className={`${th} text-end`}>{t("amount")}</th>
                                                <th scope="col" className={th}>{t("status")}</th>
                                            </tr>
                                        </thead>
                                        <tbody>
                                            {g.rows.map(r => (
                                                <tr
                                                    key={r.id}
                                                    data-testid={`recognition-pending-row-${r.id}`}
                                                    className="border-t border-border"
                                                >
                                                    <td className={td} data-testid={`recognition-row-unit-${r.id}`}>
                                                        {r.unitName ?? "—"}
                                                    </td>
                                                    <td className={td}>
                                                        <Link
                                                            href={`/${locale}/dashboard/leases/${r.leaseId}`}
                                                            // Thirteen links reading "Open contract" are thirteen
                                                            // identical stops for a screen reader; the unit is the
                                                            // only thing that tells them apart.
                                                            aria-label={t("openLeaseFor", { unit: r.unitName ?? "—" })}
                                                            className="text-primary hover:underline"
                                                        >
                                                            {t("openLease")}
                                                        </Link>
                                                    </td>
                                                    <td className={td}>
                                                        {fmtIsoDate(r.periodStart, locale)} – {fmtIsoDate(r.periodEnd, locale)}
                                                    </td>
                                                    <td className={`${td} text-end tabular-nums`}>{r.days}</td>
                                                    <td className={`${td} text-end tabular-nums`}>{fmtAmount(r.amount)}</td>
                                                    <td className={`${td} text-muted`}>{t(`status${r.status}`)}</td>
                                                </tr>
                                            ))}
                                        </tbody>
                                    </table>
                                </div>
                            </div>
                        ))}
                        <div className="px-4">
                            <Pagination
                                currentPage={page}
                                totalItems={groups.length}
                                itemsPerPage={pageSize}
                                onPageChange={setPage}
                                onItemsPerPageChange={n => {
                                    setPageSize(n);
                                    setPage(1);
                                }}
                            />
                        </div>
                    </div>
                )}
            </div>

            <ConfirmDialog
                isOpen={confirmOpen}
                onClose={() => setConfirmOpen(false)}
                onConfirm={() => execute(false)}
                isLoading={busy === "run"}
                title={t("runConfirmTitle", { date: fmtIsoDate(to, locale) })}
                description={t("runConfirmBody", { date: fmtIsoDate(to, locale) })}
                confirmText={t("run")}
                cancelText={tLedger("cancel")}
                confirmTestId="recognition-run-confirm"
            />
        </div>
    );
}

function Stat({ label, value, testId }: { label: string; value: string; testId: string }) {
    return (
        <div>
            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">{label}</p>
            <p className="text-lg font-bold text-foreground tabular-nums" data-testid={testId}>{value}</p>
        </div>
    );
}
