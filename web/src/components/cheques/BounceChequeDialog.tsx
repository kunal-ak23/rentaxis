"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "@/components/leases/LeaseDialog";
import AccountPicker from "@/components/finance/AccountPicker";
import { fmtAmount } from "@/lib/api/ledger";
import { todayIso } from "@/components/leases/leaseMath";
import { ApiError, chequeApi, type Cheque, type ChequeFailureReason } from "@/lib/api/leasing";

/**
 * A cheque bounced — from DEPOSITED (the ordinary case) or from CLEARED, PDC
 * only, as a late return (spec §7.4). The server enforces which; this dialog
 * is opened for either and always sends the same request shape.
 */

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

const FAILURE_REASONS: ChequeFailureReason[] = ["BOUNCE", "SIGNATURE_MISMATCH", "ACCOUNT_CLOSED"];

type Props = {
    cheque: Cheque | null;
    propertyId?: string | null;
    onClose: () => void;
    onDone: () => void;
};

export default function BounceChequeDialog({ cheque, propertyId, onClose, onDone }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");

    const [date, setDate] = useState(todayIso());
    const [failureReason, setFailureReason] = useState<ChequeFailureReason>("BOUNCE");
    const [notes, setNotes] = useState("");
    const [debitAccountId, setDebitAccountId] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!cheque) return;
        setDate(todayIso());
        setFailureReason("BOUNCE");
        setNotes("");
        setDebitAccountId(null);
        setError(null);
    }, [cheque]);

    if (!cheque) return null;

    const submit = async () => {
        setBusy(true);
        setError(null);
        try {
            await chequeApi.bounce(cheque.id, {
                date,
                notes: notes || null,
                failureReason,
                debitAccountId,
            });
            onDone();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    const title = `${t("bounce")} — ${cheque.chequeNumber || `#${cheque.seqNo}`} · ${fmtAmount(cheque.amount)}`;

    return (
        <LeaseDialog
            open
            title={title}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("bounce")}
            cancelText={tl("cancel")}
            busy={busy}
            destructive
            confirmTestId="cheque-bounce-confirm"
        >
            <div className="space-y-3">
                <div>
                    <label className={label} htmlFor="bounce-date">{t("bounceDate")}</label>
                    <input
                        id="bounce-date"
                        data-testid="bounce-date"
                        type="date"
                        className={field}
                        value={date}
                        onChange={e => setDate(e.target.value)}
                    />
                </div>
                <div>
                    <label className={label} htmlFor="bounce-failure-reason">{t("failureReason")}</label>
                    <select
                        id="bounce-failure-reason"
                        data-testid="bounce-failure-reason"
                        className={field}
                        value={failureReason}
                        onChange={e => setFailureReason(e.target.value as ChequeFailureReason)}
                    >
                        {FAILURE_REASONS.map(r => (
                            <option key={r} value={r}>{t(`failureReasons.${r}`)}</option>
                        ))}
                    </select>
                </div>
                <div>
                    <label className={label}>{tl("debitAccount")}</label>
                    <AccountPicker
                        value={debitAccountId}
                        onChange={setDebitAccountId}
                        leafOnly
                        propertyId={propertyId}
                        placeholder={tl("debitAccount")}
                    />
                    <p className="text-[10px] text-muted mt-1">{t("debitAccountOverrideHint")}</p>
                </div>
                <div>
                    <label className={label} htmlFor="bounce-notes">{tl("narration")}</label>
                    <input
                        id="bounce-notes"
                        data-testid="bounce-notes"
                        className={field}
                        value={notes}
                        onChange={e => setNotes(e.target.value)}
                    />
                </div>
                {error && (
                    <p className="text-[11px] text-error" data-testid="bounce-error">{error}</p>
                )}
            </div>
        </LeaseDialog>
    );
}
