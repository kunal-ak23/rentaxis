"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import { ApiError, leaseApi, type HoldoverVacatePreview, type LeaseDetail } from "@/lib/api/leasing";
import { fmtAmount } from "@/lib/api/ledger";
import { serverText } from "@/components/finance/bankrec/serverText";
import { formatDate } from "@/lib/format";

/**
 * A lease on hold whose renter turned out to have left (holdover, Nov 2026). The
 * user enters the move-out date; the server prices the held-over days at the old
 * day rate, month by month, and on confirm charges them as use and occupation —
 * dated today, so nothing lands in a closed month — then expires the lease and
 * releases the unit.
 */
type Props = {
    open: boolean;
    lease: Pick<LeaseDetail, "id" | "endDate">;
    today: string;
    onClose: () => void;
    onDone: () => void;
};

export default function HoldoverVacateDialog({ open, lease, today, onClose, onDone }: Props) {
    const t = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const [movedOutOn, setMovedOutOn] = useState(today);
    const [preview, setPreview] = useState<HoldoverVacatePreview | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!open) {
            setPreview(null);
            setError(null);
            setMovedOutOn(today);
            return;
        }
        if (!movedOutOn) return;
        let cancelled = false;
        setError(null);
        leaseApi
            .holdoverVacatePreview(lease.id, movedOutOn)
            .then(res => { if (!cancelled) setPreview(res); })
            .catch((e: unknown) => {
                if (cancelled) return;
                setPreview(null);
                setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("holdover.failed"));
            });
        return () => { cancelled = true; };
    }, [open, lease.id, movedOutOn, today, t, tCommon]);

    const confirm = async () => {
        if (!preview) return;
        setBusy(true);
        setError(null);
        try {
            await leaseApi.holdoverVacate(lease.id, movedOutOn, preview.amount);
            onDone();
        } catch (e) {
            setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("holdover.failed"));
        } finally {
            setBusy(false);
        }
    };

    return (
        <LeaseDialog
            open={open}
            title={t("holdover.vacateTitle")}
            onClose={onClose}
            onConfirm={confirm}
            confirmText={t("holdover.vacateConfirm")}
            cancelText={t("cancel")}
            confirmDisabled={!preview}
            busy={busy}
            confirmTestId="holdover-vacate-confirm"
        >
            <div data-testid="holdover-vacate" className="space-y-3 text-xs">
                <p className="text-muted">{t("holdover.vacateHelp", { date: formatDate(lease.endDate) })}</p>
                <label className="flex flex-col gap-1">
                    <span className="text-muted">{t("holdover.movedOutOn")}</span>
                    <input
                        type="date"
                        data-testid="holdover-moved-out-on"
                        className="rounded-lg border border-border bg-input px-2 py-1"
                        value={movedOutOn}
                        min={lease.endDate}
                        max={today}
                        onChange={e => setMovedOutOn(e.target.value)}
                    />
                </label>
                {preview && (
                    <div className="rounded-xl border border-border bg-input/30 px-3 py-2.5 space-y-1.5">
                        <p className="text-muted">
                            {t("holdover.days", { days: preview.days, rate: fmtAmount(preview.dayRate) })}
                        </p>
                        <ul className="space-y-0.5" data-testid="holdover-months">
                            {preview.months.map(m => (
                                <li key={m.month} className="flex gap-2">
                                    <bdi dir="ltr">{m.month}</bdi>
                                    <span className="text-muted">{t("holdover.monthDays", { days: m.days })}</span>
                                    <span className="ms-auto tabular-nums"><bdi dir="ltr">{fmtAmount(m.amount)}</bdi></span>
                                </li>
                            ))}
                        </ul>
                        <div className="flex justify-between font-semibold border-t border-border pt-1.5">
                            <span>{t("holdover.total")}</span>
                            <span className="tabular-nums" data-testid="holdover-total"><bdi dir="ltr">{fmtAmount(preview.amount)}</bdi></span>
                        </div>
                    </div>
                )}
                {error && <p className="text-error" role="alert" data-testid="holdover-error">{error}</p>}
            </div>
        </LeaseDialog>
    );
}
