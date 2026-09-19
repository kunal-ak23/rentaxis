"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2, Plus } from "lucide-react";
import { cn } from "@/lib/utils";
import { NumberInput } from "@/components/ui/NumberInput";
import { fmtAmount } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";
import {
    ApiError, penaltyApi,
    type PenaltyAssessment, type PenaltyAssessmentStatus, type PenaltyReason,
} from "@/lib/api/leasing";
import { fmtIsoDate } from "./leaseMath";

/**
 * The fines raised against one contract.
 *
 * Proposing and deciding are deliberately different permissions, mirroring
 * PenaltyAssessmentController: spotting that a renter should be fined is part
 * of running a building, so a property manager may propose; turning that
 * proposal into a charge on the ledger writes a journal, so only finance may
 * approve, waive or reverse it.
 */

const th = "text-start px-3 py-2 text-[10px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-1.5 text-xs";
const field = "w-full bg-input border border-border rounded-lg px-2 py-1.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";

const REASONS: PenaltyReason[] = ["CHEQUE_RETURN", "LATE_PAYMENT", "OTHER"];

const STATUS_COLORS: Record<PenaltyAssessmentStatus, string> = {
    PROPOSED: "bg-warning/10 text-warning",
    APPROVED: "bg-success/10 text-success",
    WAIVED: "bg-input text-muted",
    REVERSED: "bg-error/10 text-error",
};

type Props = { leaseId: string; userRole: UserRole | undefined };

export default function LeasePenaltiesTab({ leaseId, userRole }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const locale = useLocale();

    const canPropose = hasPermission(userRole, "canProposePenalties");
    const canDecide = hasPermission(userRole, "canApprovePenalties");

    const [rows, setRows] = useState<PenaltyAssessment[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [busyId, setBusyId] = useState<string | null>(null);

    const [proposeOpen, setProposeOpen] = useState(false);
    const [reason, setReason] = useState<PenaltyReason>("LATE_PAYMENT");
    const [amount, setAmount] = useState(0);
    const [description, setDescription] = useState("");

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const page = await penaltyApi.list({ leaseId, size: 200 });
            setRows(page.content ?? []);
        } catch (e) {
            setError(e instanceof ApiError ? e.message : tl("journalsFailed"));
        } finally {
            setLoading(false);
        }
    }, [leaseId, tl]);

    useEffect(() => {
        load();
    }, [load]);

    const act = async (id: string, fn: () => Promise<unknown>) => {
        setBusyId(id);
        setError(null);
        try {
            await fn();
            await load();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("actionFailed"));
        } finally {
            setBusyId(null);
        }
    };

    const propose = async () => {
        setBusyId("new");
        setError(null);
        try {
            await penaltyApi.propose({ leaseId, reason, amount, description: description || null });
            setProposeOpen(false);
            setAmount(0);
            setDescription("");
            await load();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("actionFailed"));
        } finally {
            setBusyId(null);
        }
    };

    return (
        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm" data-testid="lease-penalties-tab">
            <div className="px-4 py-3 border-b border-border flex items-center justify-between gap-3">
                <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("penalties")}</h3>
                {canPropose && (
                    <button
                        type="button"
                        data-testid="penalty-propose-open"
                        onClick={() => setProposeOpen(o => !o)}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                    >
                        <Plus size={12} /> {t("propose")}
                    </button>
                )}
            </div>

            {error && <p className="px-4 py-2 text-[11px] text-error bg-error/10" data-testid="penalty-error">{error}</p>}

            {proposeOpen && canPropose && (
                <div className="px-4 py-3 border-b border-border bg-input/20 grid grid-cols-1 md:grid-cols-4 gap-3 items-end">
                    <div>
                        <label className="block text-[10px] font-semibold text-muted uppercase mb-1" htmlFor="penalty-reason">{t("failureReason")}</label>
                        <select id="penalty-reason" className={field} value={reason} onChange={e => setReason(e.target.value as PenaltyReason)}>
                            {REASONS.map(r => (
                                <option key={r} value={r}>{t(`reason.${r}`)}</option>
                            ))}
                        </select>
                    </div>
                    <div>
                        <label className="block text-[10px] font-semibold text-muted uppercase mb-1" htmlFor="penalty-amount">{tl("amount")}</label>
                        <NumberInput id="penalty-amount" min={0} step={0.01} className={`${field} text-end tabular-nums`} value={amount} onChange={setAmount} />
                    </div>
                    <div>
                        <label className="block text-[10px] font-semibold text-muted uppercase mb-1" htmlFor="penalty-description">{tl("narration")}</label>
                        <input id="penalty-description" className={field} value={description} onChange={e => setDescription(e.target.value)} />
                    </div>
                    <button
                        type="button"
                        data-testid="penalty-propose-confirm"
                        disabled={amount <= 0 || busyId === "new"}
                        onClick={propose}
                        className="px-3 py-1.5 rounded-lg text-[11px] font-semibold bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer disabled:opacity-50"
                    >
                        {t("propose")}
                    </button>
                </div>
            )}

            {loading ? (
                <div className="flex justify-center py-8">
                    <Loader2 size={16} className="animate-spin text-muted" />
                </div>
            ) : (
                <div className="overflow-x-auto">
                    <table className="w-full min-w-[720px]">
                        <thead>
                            <tr className="bg-input/50">
                                <th className={th}>{t("failureReason")}</th>
                                <th className={`${th} text-end`}>{tl("amount")}</th>
                                <th className={th}>{tl("narration")}</th>
                                <th className={th}>{t("proposedQueue")}</th>
                                <th className={th}>{tl("chequeNo")}</th>
                                <th className={th}>{tl("actions")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map((p, i) => (
                                <tr key={p.id} data-testid={`penalty-row-${i}`} className="border-t border-border hover:bg-input/20">
                                    <td className={td}>{t(`reason.${p.reason}`)}</td>
                                    <td className={`${td} text-end tabular-nums`}>{fmtAmount(p.amount)}</td>
                                    <td className={`${td} text-muted`}>{p.description || "—"}</td>
                                    <td className={td}>
                                        <span className={cn("px-2 py-0.5 rounded-md text-[9px] font-semibold", STATUS_COLORS[p.status])}>
                                            {t(`penaltyStatus.${p.status}`)}
                                        </span>
                                        {p.proposedAt && (
                                            <span className="ms-2 text-[10px] text-muted">{fmtIsoDate(p.proposedAt, locale)}</span>
                                        )}
                                    </td>
                                    <td className={td}>{p.chequeNumber || "—"}</td>
                                    <td className={td}>
                                        <div className="flex items-center gap-1.5">
                                            {canDecide && p.status === "PROPOSED" && (
                                                <>
                                                    <button
                                                        type="button"
                                                        data-testid={`penalty-approve-${i}`}
                                                        disabled={busyId === p.id}
                                                        onClick={() => act(p.id, () => penaltyApi.approve(p.id))}
                                                        className="px-2 py-1 rounded-md text-[10px] font-bold bg-success/10 text-success hover:bg-success/20 cursor-pointer disabled:opacity-50"
                                                    >
                                                        {t("approve")}
                                                    </button>
                                                    <button
                                                        type="button"
                                                        data-testid={`penalty-waive-${i}`}
                                                        disabled={busyId === p.id}
                                                        onClick={() => act(p.id, () => penaltyApi.waive(p.id))}
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
                                                    onClick={() => act(p.id, () => penaltyApi.reverse(p.id, {}))}
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
                                    <td className={`${td} text-muted text-center py-6`} colSpan={6}>
                                        {tl("noPenalties")}
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </div>
            )}
        </div>
    );
}
