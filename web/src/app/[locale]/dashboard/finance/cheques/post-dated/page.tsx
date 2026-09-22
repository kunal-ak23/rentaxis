"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { CalendarClock, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { useNameLookup } from "@/components/finance/useNameLookup";
import ChequeStatusBadge from "@/components/cheques/ChequeStatusBadge";
import { fmtIsoDate, round2 } from "@/components/leases/leaseMath";
import { fmtAmount } from "@/lib/api/ledger";
import { ApiError, chequeApi, type Cheque } from "@/lib/api/leasing";
import { hasPermission, type UserRole } from "@/lib/rbac";

const field =
    "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";
const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-4 py-3 text-xs text-foreground";

function currentMonth(): string {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}

/** Maturity date grouping, in chequeDate order, each day carrying its own
 * subtotal and a running total across the whole month. */
function groupByMaturity(rows: Cheque[]): { date: string; rows: Cheque[]; subtotal: number }[] {
    const byDate = new Map<string, Cheque[]>();
    for (const c of rows) {
        const key = (c.chequeDate ?? c.postingDate).slice(0, 10);
        const list = byDate.get(key) ?? [];
        list.push(c);
        byDate.set(key, list);
    }
    return [...byDate.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([date, dateRows]) => ({
            date,
            rows: dateRows,
            subtotal: dateRows.reduce((s, c) => round2(s + c.amount), 0),
        }));
}

export default function PostDatedChequesPage() {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageCheques");

    const properties = useNameLookup("properties", allowed);

    const [month, setMonth] = useState(currentMonth());
    const [propertyId, setPropertyId] = useState("");
    const [rows, setRows] = useState<Cheque[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            setRows(await chequeApi.postDated({ month, propertyId: propertyId || undefined }));
        } catch (err) {
            setRows([]);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [month, propertyId, tCommon]);

    useEffect(() => {
        if (!allowed) {
            setLoading(false);
            return;
        }
        load();
    }, [allowed, load]);

    const groups = useMemo(() => groupByMaturity(rows), [rows]);
    const total = useMemo(() => rows.reduce((s, c) => round2(s + c.amount), 0), [rows]);

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    let running = 0;

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <CalendarClock size={20} className="text-primary" />
                        {t("postDated")}
                    </h1>
                </div>
                <Link
                    href="/dashboard/finance/cheques"
                    className="px-3 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40"
                >
                    {t("register")}
                </Link>
            </div>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6 flex flex-wrap items-end gap-4">
                <div>
                    <label className={label} htmlFor="pd-month">{t("month")}</label>
                    <input
                        id="pd-month"
                        data-testid="post-dated-month"
                        type="month"
                        className={field}
                        value={month}
                        onChange={ev => setMonth(ev.target.value)}
                    />
                </div>
                <div>
                    <label className={label} htmlFor="pd-property">{tLedger("propertyFilter")}</label>
                    <select
                        id="pd-property"
                        data-testid="post-dated-property-filter"
                        className={`${field} min-w-[12rem]`}
                        value={propertyId}
                        onChange={ev => setPropertyId(ev.target.value)}
                    >
                        <option value="">{tLedger("selectProperty")}</option>
                        {properties.options.map(p => (
                            <option key={p.id} value={p.id}>{p.label}</option>
                        ))}
                    </select>
                </div>
                <div className="ms-auto text-xs text-muted" data-testid="post-dated-running-total">
                    {t("runningTotal")}: <strong className="text-foreground">{fmtAmount(total)}</strong>
                </div>
            </div>

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3].map(i => <div key={i} className="bg-input rounded-xl h-14" />)}
                </div>
            ) : rows.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <CalendarClock size={28} />
                    </div>
                    <h3 className="text-sm font-bold text-foreground mb-1">{t("noPostDated")}</h3>
                </div>
            ) : (
                <div className="space-y-4">
                    {groups.map(group => (
                        <div key={group.date} data-testid={`post-dated-group-${group.date}`} className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                            <div className="px-4 py-2.5 bg-input/40 border-b border-border flex items-center justify-between">
                                <span className="text-xs font-semibold">{fmtIsoDate(group.date, locale)}</span>
                                <span className="text-xs font-semibold tabular-nums" data-testid={`post-dated-subtotal-${group.date}`}>
                                    {t("subtotal")}: {fmtAmount(group.subtotal)}
                                </span>
                            </div>
                            <table className="w-full">
                                <thead>
                                    <tr>
                                        <th className={th}>{tl("chequeNo")}</th>
                                        <th className={th}>{t("tenant")}</th>
                                        <th className={th}>{tl("unit")}</th>
                                        <th className={th}>{tLedger("propertyFilter")}</th>
                                        <th className={`${th} text-end`}>{tl("amount")}</th>
                                        <th className={th}>{tLedger("status")}</th>
                                        <th className={`${th} text-end`}>{t("runningTotal")}</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {group.rows.map(c => {
                                        running = round2(running + c.amount);
                                        return (
                                            <tr key={c.id} data-testid={`cheque-row-${c.id}`}>
                                                <td className={`${td} font-semibold`}>{c.chequeNumber || `#${c.seqNo}`}</td>
                                                <td className={td}>{c.renterName || "—"}</td>
                                                <td className={td}>{c.unitIdentifier || "—"}</td>
                                                <td className={`${td} text-muted`}>{c.propertyName || "—"}</td>
                                                <td className={`${td} text-end tabular-nums`}>{fmtAmount(c.amount)}</td>
                                                <td className={td}>
                                                    <ChequeStatusBadge status={c.status} testId={`cheque-status-${c.id}`} />
                                                </td>
                                                <td className={`${td} text-end tabular-nums font-semibold`} data-testid={`post-dated-running-${c.id}`}>
                                                    {fmtAmount(running)}
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                            </table>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
