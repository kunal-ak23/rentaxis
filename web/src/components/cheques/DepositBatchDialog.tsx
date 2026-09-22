"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "@/components/leases/LeaseDialog";
import SettlementAccountPicker from "@/components/finance/SettlementAccountPicker";
import { fmtAmount } from "@/lib/api/ledger";
import { todayIso } from "@/components/leases/leaseMath";
import { ApiError, chequeApi, type Cheque } from "@/lib/api/leasing";

/**
 * The day's deposit run (PACT "Cheque / Cash Collection"): a batch of
 * matured, unbanked rows, deposited into one bank account in one act.
 *
 * `POST /cheques/deposit-batch` is all-or-nothing — a single bad row 400s the
 * whole request, naming the offending cheque numbers in its message. That
 * message is shown verbatim here; the selection itself is the collection
 * page's to keep or clear, not this dialog's.
 */

type Props = {
    open: boolean;
    chequeIds: string[];
    total: number;
    propertyId?: string | null;
    onClose: () => void;
    onDone: (cheques: Cheque[]) => void;
};

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

export default function DepositBatchDialog({ open, chequeIds, total, propertyId, onClose, onDone }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");

    const [date, setDate] = useState(todayIso());
    const [debitAccountId, setDebitAccountId] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setDate(todayIso());
        setDebitAccountId(null);
        setError(null);
    }, [open]);

    const submit = async () => {
        setBusy(true);
        setError(null);
        try {
            const cheques = await chequeApi.depositBatch({ chequeIds, date, debitAccountId });
            onDone(cheques);
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={`${t("depositBatch")} (${chequeIds.length})`}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("depositBatch")}
            cancelText={tl("cancel")}
            busy={busy}
            confirmDisabled={chequeIds.length === 0}
            confirmTestId="deposit-batch-confirm"
        >
            <div className="space-y-3">
                <p className="text-[12px] text-muted" data-testid="deposit-batch-total">
                    {t("depositBatchSummary", { n: chequeIds.length, total: fmtAmount(total) })}
                </p>
                <div>
                    <label className={label} htmlFor="deposit-batch-date">{t("depositDate")}</label>
                    <input
                        id="deposit-batch-date"
                        data-testid="deposit-batch-date"
                        type="date"
                        className={field}
                        value={date}
                        onChange={e => setDate(e.target.value)}
                    />
                </div>
                <div>
                    <label className={label}>{tl("debitAccount")}</label>
                    <SettlementAccountPicker
                        value={debitAccountId}
                        onChange={setDebitAccountId}
                        propertyId={propertyId}
                        placeholder={tl("debitAccount")}
                    />
                    <p className="text-[10px] text-muted mt-1">{t("debitAccountOverrideHint")}</p>
                </div>
                {error && (
                    <p className="text-[11px] text-error" data-testid="deposit-batch-error">{error}</p>
                )}
            </div>
        </LeaseDialog>
    );
}
