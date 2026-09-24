"use client";
import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { fmtAmount } from "@/lib/api/ledger";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { recognitionApi, type RecognitionStatusSummary } from "@/lib/api/leasing";

/**
 * F14-27: a small heads-up on the main dashboard when month-end recognition
 * is behind, or the last automated run failed on something — the same
 * `GET /finance/recognition/status` the recognition page's own banner reads,
 * shown as a tile here so it doesn't take opening that page to notice.
 */
export default function RecognitionBehindWidget() {
    const t = useTranslations("Dashboard");
    const locale = useLocale();
    const [status, setStatus] = useState<RecognitionStatusSummary | null>(null);

    useEffect(() => {
        recognitionApi.status().then(setStatus).catch(() => setStatus(null));
    }, []);

    if (!status || (status.behind === 0 && status.lastRunFailed === 0)) return null;

    return (
        <div
            className="bg-warning/10 border border-warning/30 rounded-[var(--radius-lg)] p-4"
            data-testid="dashboard-recognition-behind"
        >
            <div className="flex items-center justify-between mb-1">
                <h3 className="text-xs font-semibold text-warning uppercase tracking-wider">
                    {t("recognitionBehindTitle")}
                </h3>
                <Link href="/dashboard/finance/recognition" className="text-[11px] font-medium text-primary hover:underline">
                    {t("viewAll")}
                </Link>
            </div>
            {status.behind > 0 && (
                <p className="text-xs text-foreground">
                    {t("recognitionBehindBody", {
                        count: status.behind,
                        amount: fmtAmount(status.behindAmount),
                        date: status.oldestPeriodEnd ? fmtIsoDate(status.oldestPeriodEnd, locale) : "—",
                    })}
                </p>
            )}
            {status.lastRunFailed > 0 && (
                <p className="text-xs text-error mt-1">
                    {t("recognitionLastRunFailedBody", { count: status.lastRunFailed })}
                </p>
            )}
        </div>
    );
}
