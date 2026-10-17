"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "@/components/leases/LeaseDialog";
import { serverText } from "@/components/finance/bankrec/serverText";
import { ApiError, renewalMarkApi, type LeaseDetail } from "@/lib/api/leasing";

/**
 * Mark a contract "not renewing" (PR #421 ruling 1): a soft, reversible note on its renewal —
 * who, when, and an optional reason. It gives no notice and changes nothing on the contract or
 * the unit; Give notice is a separate action. Undo, Renew or Extend clears it.
 */
export default function NotRenewingDialog({ lease, onClose, onMarked }: {
    lease: LeaseDetail;
    onClose: () => void;
    onMarked: () => void;
}) {
    const t = useTranslations("RenewalSeason");
    const tCommon = useTranslations("Common");
    const [note, setNote] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const save = async () => {
        setBusy(true);
        setError(null);
        try {
            await renewalMarkApi.set(lease.id, note.trim() || null);
            onMarked();
        } catch (e) {
            setError(e instanceof ApiError ? (serverText(tCommon, e) || e.message) : t("batchFailed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog open title={t("markTitle", { unit: lease.unitIdentifier ?? "—" })} onClose={onClose} onConfirm={save}
            confirmText={t("markConfirm")} cancelText={t("cancel")} busy={busy} confirmDisabled={note.length > 500}
            confirmTestId="renewal-mark-confirm">
            <div className="space-y-3" data-testid="renewal-mark-dialog">
                <p className="text-xs text-muted">{t("markIntro")}</p>
                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider">
                    {t("markNote")}
                    <textarea value={note} onChange={e => setNote(e.target.value)} rows={2} maxLength={500}
                        className="mt-1 w-full bg-input border border-border rounded-lg px-3 py-2 text-xs normal-case"
                        data-testid="renewal-mark-note" />
                </label>
                {error && <p className="text-xs text-error" role="alert">{error}</p>}
            </div>
        </LeaseDialog>
    );
}
