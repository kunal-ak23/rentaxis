"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { CheckCircle2 } from "lucide-react";
import LeaseDialog from "@/components/leases/LeaseDialog";
import { NumberInput } from "@/components/ui/NumberInput";
import { fmtAmount } from "@/lib/api/ledger";
import { ApiError, penaltyApi, type Cheque, type PenaltyAssessment } from "@/lib/api/leasing";
import BounceChequeDialog from "./BounceChequeDialog";
import ReplaceChequeDialog from "./ReplaceChequeDialog";
import { chequeLabel } from "./chequeLabel";

type Step = "bounce" | "next" | "replace";

type Props = {
    cheque: Cheque | null;
    /** canProposePenalties — who may propose the fee (finance still approves it). */
    canProposeFee: boolean;
    onClose: () => void;
    /** Something was written (the bounce, a replacement, a fee): refresh the list. */
    onChanged: () => void;
};

/**
 * Bounce in one flow (scale spec #18): record the return, then — without going
 * back to the register — replace the cheque and charge the bounce fee, each
 * with the existing endpoint (`PUT /cheques/{id}/bounce`,
 * `POST /cheques/{id}/replace`, `POST /penalties`). One dialog is shown at a
 * time; the "what next?" step is where the operator lands after each.
 *
 * The fee: the server's rule engine may already have proposed a CHEQUE_RETURN
 * penalty for this cheque (bounce threshold reached). The step looks for it
 * first and only offers a manual fee when there is none, so the renter is not
 * charged twice.
 */
export default function BounceFlow({ cheque, canProposeFee, onClose, onChanged }: Props) {
    const t = useTranslations("Cheques");
    const [step, setStep] = useState<Step>("bounce");
    const [replaced, setReplaced] = useState(false);
    const [auto, setAuto] = useState<PenaltyAssessment | null | undefined>(undefined);
    const [fee, setFee] = useState(0);
    const [proposed, setProposed] = useState<number | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        setStep("bounce");
        setReplaced(false);
        setAuto(undefined);
        setFee(0);
        setProposed(null);
        setError(null);
    }, [cheque]);

    // After the bounce: is there already an automatic cheque-return penalty for this cheque?
    useEffect(() => {
        if (step !== "next" || !cheque || auto !== undefined) return;
        let alive = true;
        penaltyApi.list({ leaseId: cheque.leaseId, status: "PROPOSED", page: 0, size: 50 })
            .then(p => { if (alive) setAuto((p.content ?? []).find(a => a.chequeId === cheque.id && a.reason === "CHEQUE_RETURN") ?? null); })
            .catch(() => { if (alive) setAuto(null); });
        return () => { alive = false; };
    }, [step, cheque, auto]);

    if (!cheque) return null;

    if (step === "bounce") {
        return <BounceChequeDialog cheque={cheque} onClose={onClose} onDone={() => { onChanged(); setStep("next"); }} />;
    }
    if (step === "replace") {
        return (
            <ReplaceChequeDialog
                cheque={cheque}
                propertyId={cheque.propertyId}
                onClose={() => setStep("next")}
                onDone={() => { onChanged(); setReplaced(true); setStep("next"); }}
            />
        );
    }

    const proposeFee = async () => {
        setBusy(true);
        setError(null);
        try {
            await penaltyApi.propose({ leaseId: cheque.leaseId, chequeId: cheque.id, reason: "CHEQUE_RETURN", amount: fee });
            setProposed(fee);
            onChanged();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    const done = <CheckCircle2 size={14} className="text-success shrink-0" />;
    return (
        <LeaseDialog
            open
            title={t("bounceFlowTitle")}
            onClose={onClose}
            onConfirm={onClose}
            confirmText={t("bounceFlowDone")}
            cancelText={t("bounceFlowLater")}
            confirmTestId="bounce-flow-done"
        >
            <div className="space-y-4 text-xs" data-testid="bounce-flow">
                <p className="text-foreground">{t("bounceFlowReturned", { cheque: `${chequeLabel(cheque)} · ${fmtAmount(cheque.amount)}` })}</p>

                <section className="space-y-1.5">
                    <h4 className="text-[10px] font-semibold text-muted uppercase tracking-wider">{t("bounceFlowReplaceHeading")}</h4>
                    {replaced ? (
                        <p className="flex items-center gap-1.5 text-success" data-testid="bounce-flow-replaced">{done}{t("bounceFlowReplaced")}</p>
                    ) : (
                        <div className="flex flex-wrap items-center justify-between gap-2">
                            <span className="text-muted">{t("bounceFlowReplaceHint")}</span>
                            <button type="button" data-testid="bounce-flow-replace" onClick={() => setStep("replace")}
                                className="px-3 py-1.5 rounded-lg bg-primary text-primary-foreground font-bold cursor-pointer">
                                {t("bounceFlowReplaceNow")}
                            </button>
                        </div>
                    )}
                </section>

                {(canProposeFee || auto) && (
                    <section className="space-y-1.5 border-t border-border pt-3">
                        <h4 className="text-[10px] font-semibold text-muted uppercase tracking-wider">{t("bounceFlowFeeHeading")}</h4>
                        {auto === undefined ? (
                            <p className="text-muted">{t("bounceFlowChecking")}</p>
                        ) : auto ? (
                            <p className="flex items-center gap-1.5" data-testid="bounce-flow-auto-penalty">{done}{t("bounceFlowAutoProposed", { amount: fmtAmount(auto.amount) })}</p>
                        ) : proposed !== null ? (
                            <p className="flex items-center gap-1.5 text-success" data-testid="bounce-flow-fee-proposed">{done}{t("bounceFlowFeeProposed", { amount: fmtAmount(proposed) })}</p>
                        ) : (
                            <>
                                <p className="text-muted">{t("bounceFlowFeeHint")}</p>
                                <div className="flex items-end gap-2">
                                    <label className="flex-1">
                                        <span className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">{t("bounceFlowFeeAmount")}</span>
                                        <NumberInput value={fee} onChange={setFee} data-testid="bounce-flow-fee-amount" dir="ltr"
                                            className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-end tabular-nums" />
                                    </label>
                                    <button type="button" data-testid="bounce-flow-propose-fee" onClick={proposeFee} disabled={busy || !(fee > 0)}
                                        className="px-3 py-2 rounded-lg border border-border font-bold cursor-pointer disabled:opacity-50">
                                        {t("bounceFlowProposeFee")}
                                    </button>
                                </div>
                            </>
                        )}
                        {error && <p className="text-error" data-testid="bounce-flow-error">{error}</p>}
                    </section>
                )}
            </div>
        </LeaseDialog>
    );
}
