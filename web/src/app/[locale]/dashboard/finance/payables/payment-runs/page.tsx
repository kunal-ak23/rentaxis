"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Plus, Send, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { paymentRunsApi, type PaymentRun } from "@/lib/api/payables";
import { RunStatusChip } from "@/components/finance/PaymentRunWizard";
import { hasPermission, type UserRole } from "@/lib/rbac";

const th = "px-3 py-2.5 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap text-start";
const td = "px-3 py-2 text-xs";

/**
 * Finance → Payables → Payment runs (finance-ops spec §2): every run, newest
 * last, and the way into the new-run wizard. Finance roles only.
 */
export default function PaymentRunsPage() {
    const t = useTranslations("PaymentRuns");
    const tCommon = useTranslations("Common");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManagePayables");
    const [runs, setRuns] = useState<PaymentRun[] | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoadError(null);
        try {
            setRuns(await paymentRunsApi.list());
        } catch (err) {
            setRuns([]);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        }
    }, [tCommon]);

    useEffect(() => {
        if (!allowed) return;
        let alive = true;
        paymentRunsApi.list()
            .then(r => alive && setRuns(r))
            .catch(err => {
                if (!alive) return;
                setRuns([]);
                setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
            });
        return () => {
            alive = false;
        };
    }, [allowed, tCommon]);

    if (userRole && !allowed) {
        return (
            <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center max-w-4xl">
                <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                <p className="text-sm text-muted">{t("accessDenied")}</p>
            </div>
        );
    }

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Send size={20} className="text-primary rtl:-scale-x-100" />{t("title")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("desc")}</p>
                </div>
                <Link href="/dashboard/finance/payables/payment-runs/new" data-testid="new-run"
                      className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-white text-xs font-bold hover:bg-primary/90">
                    <Plus size={13} />{t("newRun")}
                </Link>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                <table className="w-full" data-testid="runs-table">
                    <thead className="bg-background border-b border-border">
                        <tr>
                            <th className={th}>{t("runNumber")}</th>
                            <th className={th}>{t("paymentDate")}</th>
                            <th className={th}>{t("method")}</th>
                            <th className={th}>{t("account")}</th>
                            <th className={`${th} text-end`}>{t("vendors")}</th>
                            <th className={`${th} text-end`}>{t("total")}</th>
                            <th className={th}>{t("status")}</th>
                        </tr>
                    </thead>
                    <tbody>
                        {runs === null ? (
                            <tr><td colSpan={7} className="text-center text-xs text-muted py-10">{t("loading")}</td></tr>
                        ) : runs.length === 0 ? (
                            <tr><td colSpan={7} className="text-center text-xs text-muted py-10">{t("noRuns")}</td></tr>
                        ) : runs.map(r => (
                            <tr key={r.id} className="border-b border-border last:border-0 hover:bg-background/50">
                                <td className={td}>
                                    <Link href={`/dashboard/finance/payables/payment-runs/${r.id}`} className="font-bold text-primary hover:underline">
                                        <bdi dir="ltr">{r.runNumber}</bdi>
                                    </Link>
                                </td>
                                <td className={td}><bdi dir="ltr">{r.paymentDate}</bdi></td>
                                <td className={td}>{t(`methods.${r.method}`)}</td>
                                <td className={td}>{r.paymentAccountName}</td>
                                <td className={`${td} text-end tabular-nums`}>{r.vendorCount}</td>
                                <td className={`${td} text-end`}><bdi dir="ltr" className="tabular-nums">{fmtAmount(r.total)}</bdi></td>
                                <td className={td}><RunStatusChip status={r.status} /></td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
