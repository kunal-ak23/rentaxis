"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Plus, Send, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { paymentRunsApi, type PaymentRun, type PaymentRunStatus } from "@/lib/api/payables";
import { Pagination } from "@/components/ui/Pagination";
import { RunStatusChip } from "@/components/finance/PaymentRunWizard";
import { hasPermission, type UserRole } from "@/lib/rbac";

const th = "px-3 py-2.5 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap text-start";
const td = "px-3 py-2 text-xs";
const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "text-[10px] font-semibold text-muted uppercase tracking-wider";
const STATUSES: PaymentRunStatus[] = ["DRAFT", "POSTED", "CANCELLED"];

/**
 * Finance → Payables → Payment runs (finance-ops spec §2): every run, newest
 * last, and the way into the new-run wizard. Finance roles only.
 *
 * Scale follow-up: a page at a time from `GET /payment-runs/paged`, filtered on the
 * server by status and payment date, instead of every run the organisation ever made.
 */
export default function PaymentRunsPage() {
    const t = useTranslations("PaymentRuns");
    const tCommon = useTranslations("Common");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManagePayables");
    const [runs, setRuns] = useState<PaymentRun[] | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [status, setStatus] = useState<PaymentRunStatus | "">("");
    const [from, setFrom] = useState("");
    const [to, setTo] = useState("");
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(25);
    const [total, setTotal] = useState(0);
    /** Bumped by Retry: the effect below re-reads with the same filters. */
    const [reloads, setReloads] = useState(0);
    const load = useCallback(() => setReloads(n => n + 1), []);

    useEffect(() => {
        if (!allowed) return;
        // A response for filters the user has since changed (or an unmounted page) is dropped.
        let alive = true;
        paymentRunsApi.paged({ status: status || undefined, from: from || undefined, to: to || undefined, page: page - 1, size })
            .then(p => {
                if (!alive) return;
                setLoadError(null);
                const lastPage = Math.max(1, Math.ceil((p.totalElements ?? 0) / size));
                if ((p.content?.length ?? 0) === 0 && p.totalElements > 0 && page > lastPage) {
                    setPage(lastPage);
                    return;
                }
                setRuns(p.content ?? []);
                setTotal(p.totalElements ?? 0);
            })
            .catch(err => {
                if (!alive) return;
                setRuns([]);
                setTotal(0);
                setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
            });
        return () => {
            alive = false;
        };
    }, [allowed, status, from, to, page, size, reloads, tCommon]);

    /** A filter change starts again at page 1. */
    const filter = (apply: () => void) => {
        apply();
        setPage(1);
    };

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

            <div className="flex flex-wrap items-end gap-3 mb-4" data-testid="runs-filters">
                <label className={label}>
                    <span className="block mb-1">{t("status")}</span>
                    <select data-testid="runs-status" className={field} value={status}
                            onChange={e => filter(() => setStatus(e.target.value as PaymentRunStatus | ""))}>
                        <option value="">{t("allStatuses")}</option>
                        {STATUSES.map(s => <option key={s} value={s}>{t(`statuses.${s}`)}</option>)}
                    </select>
                </label>
                <label className={label}>
                    <span className="block mb-1">{t("paymentDateFrom")}</span>
                    <input type="date" data-testid="runs-from" className={field} value={from}
                           onChange={e => filter(() => setFrom(e.target.value))} />
                </label>
                <label className={label}>
                    <span className="block mb-1">{t("paymentDateTo")}</span>
                    <input type="date" data-testid="runs-to" className={field} value={to} min={from || undefined}
                           onChange={e => filter(() => setTo(e.target.value))} />
                </label>
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
                            <tr><td colSpan={7} className="text-center text-xs text-muted py-10">
                                {status || from || to ? t("noRunsFiltered") : t("noRuns")}
                            </td></tr>
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
            <Pagination
                currentPage={page}
                totalItems={total}
                itemsPerPage={size}
                onPageChange={setPage}
                onItemsPerPageChange={n => { setSize(n); setPage(1); }}
            />
        </div>
    );
}
