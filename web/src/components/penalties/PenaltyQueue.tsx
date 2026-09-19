"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { fmtAmount } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { fmtIsoDate, todayIso } from "@/components/leases/leaseMath";
import LeaseDialog from "@/components/leases/LeaseDialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import {
    ApiError,
    penaltyApi,
    type PenaltyAssessment,
    type PenaltyAssessmentStatus,
} from "@/lib/api/leasing";

/**
 * The penalty worklist's own row set and gating, shared by the finance-wide
 * queue (`finance/penalties/page.tsx`) and one contract's own tab
 * (`LeasePenaltiesTab`) — one table over `PenaltyAssessmentController`'s
 * state machine rather than two screens quietly disagreeing about what a row
 * can do.
 *
 * Mirrors `PenaltyAssessmentController`: Approve and Waive are offered on
 * PROPOSED, Reverse on APPROVED, nothing on WAIVED or REVERSED — and all
 * three are `canApprovePenalties` (SA/TA/ACCOUNTANT), narrower than who may
 * merely see the queue.
 */

const TAB_ORDER: PenaltyAssessmentStatus[] = ["PROPOSED", "APPROVED", "WAIVED", "REVERSED"];

const th = "text-start px-3 py-2 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-3 py-2 text-xs";
const dialogField =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const dialogLabel = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type DecisionAction = "approve" | "waive" | "reverse";

type Props = {
    userRole: UserRole | undefined;
    /** Scope to one contract's own penalties — the lease tab's use. */
    leaseId?: string;
    propertyId?: string;
    /** Initial tab; defaults to the worklist's own PROPOSED. */
    status?: PenaltyAssessmentStatus;
};

export default function PenaltyQueue({ userRole, leaseId, propertyId, status }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const tLedger = useTranslations("Ledger");
    const locale = useLocale();

    const canDecide = hasPermission(userRole, "canApprovePenalties");

    const [tab, setTab] = useState<PenaltyAssessmentStatus>(status ?? "PROPOSED");
    const [rows, setRows] = useState<PenaltyAssessment[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);
    const [busyId, setBusyId] = useState<string | null>(null);

    const [decision, setDecision] = useState<{ action: DecisionAction; row: PenaltyAssessment } | null>(null);
    const [decisionDate, setDecisionDate] = useState(todayIso());
    const [decisionNote, setDecisionNote] = useState("");

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            const page = await penaltyApi.list({ leaseId, propertyId, status: tab, size: 200 });
            setRows(page.content ?? []);
        } catch (e) {
            setRows([]);
            setLoadError(e instanceof ApiError ? e.message : tl("journalsFailed"));
        } finally {
            setLoading(false);
        }
    }, [leaseId, propertyId, tab, tl]);

    useEffect(() => {
        load();
    }, [load]);

    const openDecision = (action: DecisionAction, row: PenaltyAssessment) => {
        setDecisionDate(todayIso());
        setDecisionNote("");
        setActionError(null);
        setDecision({ action, row });
    };

    const confirmDecision = async () => {
        if (!decision) return;
        const { action, row } = decision;
        setBusyId(row.id);
        setActionError(null);
        try {
            if (action === "approve") await penaltyApi.approve(row.id, decisionDate);
            else if (action === "waive") await penaltyApi.waive(row.id, decisionNote || undefined);
            else await penaltyApi.reverse(row.id, { date: decisionDate, note: decisionNote || undefined });
            setDecision(null);
            await load();
        } catch (e) {
            setActionError(e instanceof ApiError ? e.message : t("actionFailed"));
        } finally {
            setBusyId(null);
        }
    };

    const dialogTitle = decision
        ? `${t(decision.action)} — ${decision.row.renterName || decision.row.chequeNumber || ""} · ${fmtAmount(decision.row.amount)}`
        : "";

    return (
        <div data-testid="penalty-queue">
            <div className="flex items-center gap-1 p-1 bg-input/60 rounded-xl border border-border mb-4 w-fit">
                {TAB_ORDER.map(s => (
                    <button
                        key={s}
                        type="button"
                        data-testid={`penalty-tab-${s}`}
                        onClick={() => setTab(s)}
                        className={cn(
                            "px-3 py-1.5 rounded-lg text-[11px] font-semibold transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30",
                            tab === s
                                ? "bg-surface text-foreground shadow-sm border border-border"
                                : "text-muted hover:text-foreground",
                        )}
                    >
                        {t(`penaltyStatus.${s}`)}
                    </button>
                ))}
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}
            {actionError && (
                <p className="mb-3 text-[11px] text-error" data-testid="penalty-error">
                    {actionError}
                </p>
            )}

            {loading ? (
                <div className="flex justify-center py-8">
                    <Loader2 size={16} className="animate-spin text-muted" />
                </div>
            ) : (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    <div className="overflow-x-auto">
                        <table className="w-full min-w-[880px]">
                            <thead>
                                <tr className="bg-input/50">
                                    <th className={th}>{t("tenant")}</th>
                                    <th className={th}>{tLedger("propertyFilter")}</th>
                                    <th className={th}>{tl("chequeNo")}</th>
                                    <th className={th}>{t("failureReason")}</th>
                                    <th className={`${th} text-end`}>{tl("amount")}</th>
                                    <th className={th}>{t("proposedAt")}</th>
                                    <th className={th}>{tl("actions")}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {rows.map((p, i) => (
                                    <tr key={p.id} data-testid={`penalty-row-${i}`} className="border-t border-border hover:bg-input/20">
                                        <td className={td}>{p.renterName || "—"}</td>
                                        <td className={`${td} text-muted`}>{p.propertyName || "—"}</td>
                                        <td className={td}>{p.chequeNumber || "—"}</td>
                                        <td className={td}>{t(`reason.${p.reason}`)}</td>
                                        <td className={`${td} text-end tabular-nums font-semibold`}>{fmtAmount(p.amount)}</td>
                                        <td className={`${td} text-muted`}>
                                            {p.proposedAt ? fmtIsoDate(p.proposedAt, locale) : "—"}
                                        </td>
                                        <td className={td}>
                                            <div className="flex items-center gap-1.5">
                                                {canDecide && p.status === "PROPOSED" && (
                                                    <>
                                                        <button
                                                            type="button"
                                                            data-testid={`penalty-approve-${i}`}
                                                            disabled={busyId === p.id}
                                                            onClick={() => openDecision("approve", p)}
                                                            className="px-2 py-1 rounded-md text-[10px] font-bold bg-success/10 text-success hover:bg-success/20 cursor-pointer disabled:opacity-50"
                                                        >
                                                            {t("approve")}
                                                        </button>
                                                        <button
                                                            type="button"
                                                            data-testid={`penalty-waive-${i}`}
                                                            disabled={busyId === p.id}
                                                            onClick={() => openDecision("waive", p)}
                                                            className="px-2 py-1 rounded-md text-[10px] font-bold bg-input text-foreground hover:bg-border cursor-pointer disabled:opacity-50"
                                                        >
                                                            {t("waive")}
                                                        </button>
                                                    </>
                                                )}
                                                {canDecide && p.status === "APPROVED" && (
                                                    <button
                                                        type="button"
                                                        data-testid={`penalty-reverse-${i}`}
                                                        disabled={busyId === p.id}
                                                        onClick={() => openDecision("reverse", p)}
                                                        className="px-2 py-1 rounded-md text-[10px] font-bold bg-error/10 text-error hover:bg-error/20 cursor-pointer disabled:opacity-50"
                                                    >
                                                        {t("reverse")}
                                                    </button>
                                                )}
                                            </div>
                                        </td>
                                    </tr>
                                ))}
                                {rows.length === 0 && (
                                    <tr>
                                        <td className={`${td} text-muted text-center py-6`} colSpan={7}>
                                            {t("noPenaltiesQueue")}
                                        </td>
                                    </tr>
                                )}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}

            <LeaseDialog
                open={!!decision}
                title={dialogTitle}
                onClose={() => setDecision(null)}
                onConfirm={confirmDecision}
                confirmText={decision ? t(decision.action) : ""}
                cancelText={tl("cancel")}
                confirmDisabled={decision?.action === "waive" && !decisionNote.trim()}
                busy={busyId === decision?.row.id}
                destructive={decision?.action === "reverse"}
                confirmTestId={decision ? `penalty-${decision.action}-confirm` : "penalty-decision-confirm"}
            >
                <div className="space-y-3">
                    {(decision?.action === "approve" || decision?.action === "reverse") && (
                        <div>
                            <label className={dialogLabel} htmlFor="penalty-decision-date">
                                {t("decisionDate")}
                            </label>
                            <input
                                id="penalty-decision-date"
                                data-testid="penalty-decision-date"
                                type="date"
                                className={dialogField}
                                value={decisionDate}
                                onChange={e => setDecisionDate(e.target.value)}
                            />
                        </div>
                    )}
                    {(decision?.action === "waive" || decision?.action === "reverse") && (
                        <div>
                            <label className={dialogLabel} htmlFor="penalty-decision-note">
                                {t("decisionNote")}
                            </label>
                            <input
                                id="penalty-decision-note"
                                data-testid="penalty-decision-note"
                                className={dialogField}
                                value={decisionNote}
                                onChange={e => setDecisionNote(e.target.value)}
                            />
                        </div>
                    )}
                </div>
            </LeaseDialog>
        </div>
    );
}
