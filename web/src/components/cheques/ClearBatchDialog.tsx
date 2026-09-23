"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "@/components/leases/LeaseDialog";
import { fmtAmount } from "@/lib/api/ledger";
import { todayIso } from "@/components/leases/leaseMath";
import { ApiError, chequeApi, type Cheque } from "@/lib/api/leasing";

/**
 * One bank credit, cleared in one act (gap #57) — the counterpart of
 * {@link DepositBatchDialog}. The bank clears a deposit slip as a single
 * statement line, so ticking the deposited rows and clearing them together
 * keeps the register matching the statement.
 *
 * `POST /cheques/clear-batch` is all-or-nothing: a row that is not DEPOSITED
 * 400s the whole request with its cheque number in the message, shown
 * verbatim here. Each row posts exactly what a single Clear posts.
 */

type Props = {
    open: boolean;
    chequeIds: string[];
    total: number;
    onClose: () => void;
    onDone: (cheques: Cheque[]) => void;
};

const field =
    "w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

export default function ClearBatchDialog({ open, chequeIds, total, onClose, onDone }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");

    const [date, setDate] = useState(todayIso());
    const [narration, setNarration] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) return;
        setDate(todayIso());
        setNarration("");
        setError(null);
    }, [open]);

    const submit = async () => {
        setBusy(true);
        setError(null);
        try {
            const cheques = await chequeApi.clearBatch({
                chequeIds,
                clearingDate: date,
                narration: narration.trim() || null,
            });
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
            title={`${t("clearBatch")} (${chequeIds.length})`}
            onClose={onClose}
            onConfirm={submit}
            confirmText={t("clearBatch")}
            cancelText={tl("cancel")}
            busy={busy}
            confirmDisabled={chequeIds.length === 0 || !date}
            confirmTestId="clear-batch-confirm"
        >
            <div className="space-y-3">
                <p className="text-[12px] text-muted" data-testid="clear-batch-total">
                    {t("clearBatchSummary", { n: chequeIds.length, total: fmtAmount(total) })}
                </p>
                <div>
                    <label className={label} htmlFor="clear-batch-date">{t("clearingDate")}</label>
                    <input
                        id="clear-batch-date"
                        data-testid="clear-batch-date"
                        type="date"
                        className={field}
                        value={date}
                        onChange={e => setDate(e.target.value)}
                    />
                </div>
                <div>
                    <label className={label} htmlFor="clear-batch-narration">{tl("narration")}</label>
                    <textarea
                        id="clear-batch-narration"
                        data-testid="clear-batch-narration"
                        rows={2}
                        className={field}
                        value={narration}
                        onChange={e => setNarration(e.target.value)}
                    />
                </div>
                <p className="text-[10px] text-muted">{t("clearBatchHint")}</p>
                {error && (
                    <p className="text-[11px] text-error" data-testid="clear-batch-error">{error}</p>
                )}
            </div>
        </LeaseDialog>
    );
}
