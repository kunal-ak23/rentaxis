"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Plus } from "lucide-react";
import { NumberInput } from "@/components/ui/NumberInput";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { ApiError, penaltyApi, type PenaltyReason } from "@/lib/api/leasing";
import PenaltyQueue from "@/components/penalties/PenaltyQueue";

/**
 * The fines raised against one contract.
 *
 * Proposing and deciding are deliberately different permissions, mirroring
 * PenaltyAssessmentController: spotting that a renter should be fined is part
 * of running a building, so a property manager may propose; turning that
 * proposal into a charge on the ledger writes a journal, so only finance may
 * approve, waive or reverse it. The decision table itself is `PenaltyQueue`,
 * scoped to this lease — this tab used to carry a second copy of that table
 * and its own approve/waive/reverse wiring, which is how it came to offer
 * Waive without the shared queue's date/note dialogs.
 */

const field = "w-full bg-input border border-border rounded-lg px-2 py-1.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";

const REASONS: PenaltyReason[] = ["CHEQUE_RETURN", "LATE_PAYMENT", "OTHER"];

type Props = { leaseId: string; userRole: UserRole | undefined };

export default function LeasePenaltiesTab({ leaseId, userRole }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");

    const canPropose = hasPermission(userRole, "canProposePenalties");

    const [error, setError] = useState<string | null>(null);
    const [busyId, setBusyId] = useState<string | null>(null);
    const [reloadKey, setReloadKey] = useState(0);

    const [proposeOpen, setProposeOpen] = useState(false);
    const [reason, setReason] = useState<PenaltyReason>("LATE_PAYMENT");
    const [amount, setAmount] = useState(0);
    const [description, setDescription] = useState("");

    const propose = async () => {
        setBusyId("new");
        setError(null);
        try {
            await penaltyApi.propose({ leaseId, reason, amount, description: description || null });
            setProposeOpen(false);
            setAmount(0);
            setDescription("");
            // PenaltyQueue owns its own fetch; bump its key so the freshly
            // proposed row shows up on the PROPOSED tab without a page reload.
            setReloadKey(k => k + 1);
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

            {error && <p className="px-4 py-2 text-[11px] text-error bg-error/10" data-testid="penalty-propose-error">{error}</p>}

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

            <div className="p-4">
                <PenaltyQueue key={reloadKey} userRole={userRole} leaseId={leaseId} />
            </div>
        </div>
    );
}
