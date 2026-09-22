"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import Link from "next/link";
import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { fmtAmount } from "@/lib/api/ledger";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError, recognitionApi, type RecognitionEntry, type RecognitionStatus } from "@/lib/api/leasing";
import { fmtIsoDate } from "./leaseMath";

/**
 * One contract's rent recognition schedule (spec §8.2, §11).
 *
 * Every calendar-month slice of every rent segment, whatever its status — the
 * planned rows as well as the posted ones, because the question the tab
 * answers is "does this contract's rent add back up", and a table that showed
 * only what the ledger has seen so far could never answer it. The footer does
 * the addition, and says whether the answer is the contract's rent.
 *
 * **What the footer adds is the LIVE plan.** `RecognitionService.scheduleFor`
 * (backend/src/main/java/com/datagami/rentaxis/core/service/recognition/RecognitionService.java:169-171)
 * returns every entry the lease has ever had, in every status.
 * `rebuildAfterAmend` (:238-259) marks the superseded POSTED rows REVERSED and
 * the superseded PLANNED ones CANCELLED, **keeps their amounts**, and then lays
 * a complete new schedule down beside them; `truncateForTermination` reverses
 * the entry containing T and inserts a new, shorter row next to it. So Σ over
 * every returned row is the sum of every version of the schedule that has ever
 * existed — 111,000 on a 51,000 contract amended to 60,000 — which is not a
 * figure about this contract at all. The retired rows stay on screen, struck and
 * subtotalled separately, because an accountant reading a rebuilt schedule needs
 * to see what it replaced.
 *
 * **After a termination there is no equality to check.** The plan stops at T, so
 * it is deliberately shorter than the contract's rent, and "differs by" would be
 * a false alarm on every terminated lease. The footer reports what has been
 * recognised against what is planned instead.
 *
 * `GET /leases/{id}/recognition` is one role wider than the month-end close
 * (`RecognitionController#schedule`, :110-111, admits PROPERTY_MANAGER): a
 * lease's schedule is part of the contract they manage. There is no action on
 * this tab, so there is no role gate in it either.
 *
 * Links use `next/link` with an explicit locale prefix rather than the
 * `@/i18n/routing` wrapper, which vitest cannot resolve outside a Next runtime
 * — the same workaround {@link LeaseJournalsTab} carries.
 */

const th = "text-start px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-1.5 text-xs";

/** Grey planned, green posted, muted reversed, struck-out cancelled (§11). */
const STATUS_CLASS: Record<RecognitionStatus, string> = {
    PLANNED: "bg-input text-muted border-border",
    POSTED: "bg-success/10 text-success border-success/20",
    REVERSED: "bg-input text-muted/70 border-border",
    CANCELLED: "bg-input text-muted/70 border-border line-through",
};

/**
 * The rows that are still the plan. PLANNED is what the close will post;
 * POSTED is what it already has. REVERSED and CANCELLED are the two ways
 * `rebuildAfterAmend` and `truncateForTermination` retire a row they replaced.
 */
const LIVE: RecognitionStatus[] = ["PLANNED", "POSTED"];

function isLive(r: RecognitionEntry): boolean {
    return LIVE.includes(r.status);
}

function sum(rows: RecognitionEntry[]): number {
    return Math.round(rows.reduce((s, r) => s + (r.amount ?? 0), 0) * 100) / 100;
}

type Props = {
    leaseId: string;
    /**
     * The contract's rent, for the footer's check. Optional: the tab is useful
     * without it, and the caller is the only thing that knows the figure.
     */
    contractRent?: number | null;
    /**
     * Whether this contract was terminated (`LeaseDTO.terminatedOn != null`).
     * A truncated schedule is meant to be short, so the footer reports progress
     * rather than claiming a match it can never have.
     */
    terminated?: boolean;
};

export default function RecognitionScheduleTab({ leaseId, contractRent, terminated }: Props) {
    const t = useTranslations("Recognition");
    const locale = useLocale();

    const [rows, setRows] = useState<RecognitionEntry[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            setRows(await recognitionApi.leaseSchedule(leaseId));
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("scheduleFailed"));
        } finally {
            setLoading(false);
        }
    }, [leaseId, t]);

    useEffect(() => {
        load();
    }, [load]);

    /**
     * Σ over the live plan; the retired rows get their own subtotal. What the
     * footer compares is the schedule against the rent, not the ledger against
     * the rent — that is the Journals tab's question.
     */
    const total = useMemo(() => sum(rows.filter(isLive)), [rows]);
    const supersededTotal = useMemo(() => sum(rows.filter(r => !isLive(r))), [rows]);
    const supersededCount = useMemo(() => rows.filter(r => !isLive(r)).length, [rows]);
    const recognised = useMemo(() => sum(rows.filter(r => r.status === "POSTED")), [rows]);
    const difference = contractRent == null ? null : Math.round((total - contractRent) * 100) / 100;

    if (loading) {
        return (
            <div className="flex justify-center py-10">
                <Loader2 size={18} className="animate-spin text-muted" />
            </div>
        );
    }

    if (error) {
        return <LoadErrorBanner message={error} onRetry={load} />;
    }

    return (
        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm" data-testid="recognition-schedule">
            <div className="px-4 py-3 border-b border-border">
                <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("schedule")}</h3>
            </div>
            <div className="overflow-x-auto">
                <table className="w-full min-w-[640px]">
                    <thead>
                        <tr className="bg-input/50">
                            <th className={th}>{t("period")}</th>
                            <th className={`${th} text-end`}>{t("days")}</th>
                            <th className={`${th} text-end`}>{t("amount")}</th>
                            <th className={th}>{t("status")}</th>
                            <th className={th}>{t("journal")}</th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map((r, i) => (
                            <tr
                                key={r.id}
                                data-testid={`recognition-row-${i}`}
                                className="border-t border-border hover:bg-input/20"
                            >
                                <td className={td}>
                                    {fmtIsoDate(r.periodStart, locale)} – {fmtIsoDate(r.periodEnd, locale)}
                                </td>
                                <td className={`${td} text-end tabular-nums`}>{r.days}</td>
                                <td
                                    data-testid={`recognition-amount-${i}`}
                                    className={cn(
                                        `${td} text-end tabular-nums`,
                                        // Both ways a row is retired read the
                                        // same: struck and muted, so the eye
                                        // never adds them into the plan.
                                        !isLive(r) && "line-through text-muted",
                                    )}
                                >
                                    {fmtAmount(r.amount)}
                                </td>
                                <td className={td}>
                                    <span
                                        className={cn(
                                            "px-2 py-0.5 rounded-lg text-[10px] font-semibold border",
                                            STATUS_CLASS[r.status],
                                        )}
                                    >
                                        {t(`status${r.status}`)}
                                    </span>
                                </td>
                                <td className={td}>
                                    {r.journalId && r.journalNumber ? (
                                        <Link
                                            href={`/${locale}/dashboard/finance/journals/${r.journalId}`}
                                            className="text-primary hover:underline"
                                        >
                                            {r.journalNumber}
                                        </Link>
                                    ) : (
                                        <span className="text-muted">—</span>
                                    )}
                                </td>
                            </tr>
                        ))}
                        {rows.length === 0 && (
                            <tr>
                                <td className={`${td} text-muted text-center py-6`} colSpan={5}>
                                    {t("scheduleEmpty")}
                                </td>
                            </tr>
                        )}
                    </tbody>
                    {rows.length > 0 && (
                        <tfoot>
                            {supersededCount > 0 && (
                                <tr className="border-t border-border">
                                    <td className={`${td} text-muted`} colSpan={2}>
                                        {t("scheduleSuperseded", { count: supersededCount })}
                                    </td>
                                    <td
                                        className={`${td} text-end tabular-nums text-muted line-through`}
                                        data-testid="recognition-schedule-superseded"
                                    >
                                        {fmtAmount(supersededTotal)}
                                    </td>
                                    <td className={`${td} text-[11px] text-muted`} colSpan={2}>
                                        {t("scheduleSupersededHint")}
                                    </td>
                                </tr>
                            )}
                            <tr className="border-t-2 border-border bg-input/30">
                                <td className={`${td} font-semibold`} colSpan={2}>
                                    {t("scheduleTotal")}
                                </td>
                                <td
                                    className={`${td} text-end tabular-nums font-semibold`}
                                    data-testid="recognition-schedule-total"
                                >
                                    {fmtAmount(total)}
                                </td>
                                <td className={td} colSpan={2}>
                                    {terminated ? (
                                        <span data-testid="recognition-schedule-check" className="text-[11px] text-muted">
                                            {t("scheduleRecognisedOfPlanned", {
                                                recognised: fmtAmount(recognised),
                                                planned: fmtAmount(total),
                                            })}
                                        </span>
                                    ) : (
                                        difference !== null && (
                                            <span
                                                data-testid="recognition-schedule-check"
                                                className={cn(
                                                    "text-[11px]",
                                                    difference === 0 ? "text-success" : "text-warning",
                                                )}
                                            >
                                                {difference === 0
                                                    ? t("scheduleMatches")
                                                    : t("scheduleDiffers", { amount: fmtAmount(Math.abs(difference)) })}
                                            </span>
                                        )
                                    )}
                                </td>
                            </tr>
                        </tfoot>
                    )}
                </table>
            </div>
        </div>
    );
}
