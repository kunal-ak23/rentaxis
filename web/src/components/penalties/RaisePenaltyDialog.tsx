"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { serverText } from "@/components/finance/bankrec/serverText";
import LeaseDialog from "@/components/leases/LeaseDialog";
import { NumberInput } from "@/components/ui/NumberInput";
import { businessTodayIso } from "@/lib/businessDate";
import { ApiError, penaltyApi, type PenaltyAssessment, type PenaltyReason } from "@/lib/api/leasing";

/**
 * "Raise penalty" (#12): a person charges a fine — damage, noise, a late
 * payment outside the automatic rule — rather than waiting for the rule engine.
 *
 * It is the same `POST /penalties` → `PenaltyAssessmentService.propose` path
 * the worklist already reads, so the result is an ordinary PROPOSED row that
 * posts nothing until finance approves it; approval is the one place a PEN
 * journal is written, by PostingService. The date is when the thing happened
 * (Asia/Dubai business day, never in the future); the entry date is still
 * chosen at approval.
 */

const REASONS: PenaltyReason[] = ["OTHER", "LATE_PAYMENT", "CHEQUE_RETURN", "SERVICE_RECHARGE", "ADMIN_FEE", "DAMAGE"];
/** F14-30: the reasons that are consideration for a supply (the server's PenaltyReason.vatableByDefault). */
const VAT_BY_DEFAULT: PenaltyReason[] = ["SERVICE_RECHARGE", "ADMIN_FEE", "DAMAGE", "MAINTENANCE_RECHARGE", "BOOKING_FEE"];
const field = "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1";

type Props = {
    open: boolean;
    leaseId: string;
    onClose: () => void;
    onRaised: (p: PenaltyAssessment) => void;
    /** Earliest allowed incident date: the contract date (the server refuses earlier). */
    minDate?: string | null;
};

/** Reasons that are penalties; every other reason is a charge for a supply (a recharge, a fee). */
export const PENALTY_REASONS: PenaltyReason[] = ["CHEQUE_RETURN", "LATE_PAYMENT", "OTHER"];

export default function RaisePenaltyDialog({ open, leaseId, onClose, onRaised, minDate }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const [reason, setReason] = useState<PenaltyReason>("OTHER");
    const [amount, setAmount] = useState(0);
    const [incidentDate, setIncidentDate] = useState(businessTodayIso());
    const [description, setDescription] = useState("");
    // F14-30: "auto" leaves VAT to the reason and the lease.
    const [vat, setVat] = useState<"auto" | "yes" | "no">("auto");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (open) {
            setReason("OTHER");
            setAmount(0);
            setIncidentDate(businessTodayIso());
            setDescription("");
            setVat("auto");
            setError(null);
        }
    }, [open]);

    // F15-18: a recharge or a fee is a charge, not a penalty; the dialog says which.
    const isPenalty = PENALTY_REASONS.includes(reason);
    const typeLabel = t(`reason.${reason}`);
    const titleText = isPenalty ? tl("raisePenalty") : tl("raiseCharge", { type: typeLabel });
    const today = businessTodayIso();
    const invalid = amount <= 0 || !incidentDate || incidentDate > today || (!!minDate && incidentDate < minDate);

    const submit = async () => {
        setBusy(true);
        setError(null);
        try {
            const raised = await penaltyApi.propose({
                leaseId, reason, amount, incidentDate, description: description.trim() || null,
                vatable: vat === "auto" ? null : vat === "yes",
            });
            onRaised(raised);
        } catch (e) {
            setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={titleText}
            onClose={onClose}
            onConfirm={submit}
            confirmText={titleText}
            cancelText={tl("cancel")}
            confirmDisabled={invalid}
            busy={busy}
            confirmTestId="raise-penalty-confirm"
        >
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                <div>
                    <label className={label} htmlFor="raise-penalty-reason">{tl("penaltyCategory")}</label>
                    <select id="raise-penalty-reason" className={field} value={reason} onChange={e => setReason(e.target.value as PenaltyReason)}>
                        {REASONS.map(r => <option key={r} value={r}>{t(`reason.${r}`)}</option>)}
                    </select>
                </div>
                <div>
                    <label className={label} htmlFor="raise-penalty-amount">{tl("amount")}</label>
                    <NumberInput id="raise-penalty-amount" min={0} step={0.01} className={`${field} text-end tabular-nums`} value={amount} onChange={setAmount} />
                </div>
                <div>
                    <label className={label} htmlFor="raise-penalty-vat">{t("chargeVat")}</label>
                    <select id="raise-penalty-vat" className={field} value={vat} onChange={e => setVat(e.target.value as "auto" | "yes" | "no")}>
                        <option value="auto">{t(`chargeVatAuto${VAT_BY_DEFAULT.includes(reason) ? "Yes" : "No"}`)}</option>
                        <option value="yes">{t("chargeVatYes")}</option>
                        <option value="no">{t("chargeVatNo")}</option>
                    </select>
                </div>
                <div>
                    <label className={label} htmlFor="raise-penalty-date">{tl("penaltyIncidentDate")}</label>
                    <input id="raise-penalty-date" type="date" min={minDate || undefined} max={today} className={field} value={incidentDate} onChange={e => setIncidentDate(e.target.value)} />
                </div>
                <div className="sm:col-span-2">
                    <label className={label} htmlFor="raise-penalty-narration">{tl("narration")}</label>
                    <input id="raise-penalty-narration" className={field} value={description} onChange={e => setDescription(e.target.value)} />
                </div>
            </div>
            <p className="mt-3 text-[11px] text-muted">{isPenalty ? tl("raisePenaltyHint") : tl("raiseChargeHint", { type: typeLabel })}</p>
            {error && <p role="alert" data-testid="raise-penalty-error" className="mt-3 text-[11px] text-error bg-error/10 rounded-lg px-3 py-2">{error}</p>}
        </LeaseDialog>
    );
}
