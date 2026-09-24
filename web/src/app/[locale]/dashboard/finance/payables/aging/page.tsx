"use client";

import { Fragment, useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, ChevronDown, ChevronRight, Download, Filter, PieChart, ShieldCheck } from "lucide-react";
import { Link } from "@/i18n/routing";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { payablesApi, type AgingFigures, type PayablesAging } from "@/lib/api/payables";
import { hasPermission, type UserRole } from "@/lib/rbac";

const th = "px-3 py-2.5 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-3 py-2 text-xs";
const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const button = "flex items-center gap-1.5 px-3 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all cursor-pointer";

const pad = (n: number) => String(n).padStart(2, "0");
function todayIso(): string {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

const BUCKETS: (keyof AgingFigures)[] = ["current", "d1to30", "d31to60", "d61to90", "d90plus"];

/** An amount cell: Latin digits, LTR inside an RTL page, blank for zero. */
function Amount({ v, strong }: { v: number | null | undefined; strong?: boolean }) {
    if (v === null || v === undefined || Math.abs(v) < 0.005) return <span className="text-muted">—</span>;
    return <bdi dir="ltr" className={`tabular-nums ${strong ? "font-bold" : ""}`}>{fmtAmount(v)}</bdi>;
}

/**
 * Finance → Payables → Aging (finance-ops spec §2): open supplier invoices by
 * days past due, as of any date, with unallocated advances and the tie-out to
 * each vendor's payable leaf. A non-zero Δ is a warning with a drill to the
 * vendor ledger, never a block. With a property chosen the figures are that
 * property's gross share and the vendor-level columns go (a property manager
 * always sees it this way, and must choose one of their properties).
 */
export default function PayablesAgingPage() {
    const t = useTranslations("Payables");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canViewPayablesAging");
    const manager = userRole === "PROPERTY_MANAGER";
    const canManage = hasPermission(userRole, "canManagePayables");
    const properties = useNameLookup("properties", allowed);

    const [asOf, setAsOf] = useState(todayIso);
    const [propertyId, setPropertyId] = useState("");
    const [applied, setApplied] = useState<{ asOf: string; propertyId: string }>({ asOf: todayIso(), propertyId: "" });
    const [data, setData] = useState<PayablesAging | null>(null);
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [open, setOpen] = useState<Record<string, boolean>>({});

    const load = useCallback(async (q: { asOf: string; propertyId: string }) => {
        setLoading(true);
        setLoadError(null);
        try {
            setData(await payablesApi.aging({ asOf: q.asOf, propertyId: q.propertyId || undefined }));
        } catch (err) {
            setData(null);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [tCommon]);

    useEffect(() => {
        if (!allowed) return;
        if (manager && !applied.propertyId) return;
        load(applied);
    }, [allowed, manager, applied, load]);

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <p className="text-sm text-muted">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    const vendorLevel = data?.vendorLevel ?? !applied.propertyId;
    const name = (r: { vendorName: string | null; vendorNameAr: string | null }) =>
        locale === "ar" && r.vendorNameAr ? r.vendorNameAr : r.vendorName ?? "";
    const columns = 2 + BUCKETS.length + (vendorLevel ? 4 : 1);

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <PieChart size={20} className="text-primary" />
                        {t("aging")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("agingDesc")}</p>
                </div>
                {data && (
                    <a className={button} data-testid="aging-csv"
                       href={payablesApi.agingCsvUrl({ asOf: applied.asOf, propertyId: applied.propertyId || undefined }, locale)}>
                        <Download size={13} />{t("csv")}
                    </a>
                )}
            </div>

            <form
                className="flex flex-wrap items-end gap-3 mb-5"
                onSubmit={e => {
                    e.preventDefault();
                    setApplied({ asOf, propertyId });
                }}
            >
                <label className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                    <span className="block mb-1">{t("asOf")}</span>
                    <input type="date" data-testid="aging-as-of" className={field} value={asOf} onChange={e => setAsOf(e.target.value)} />
                </label>
                <label className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                    <span className="block mb-1">{t("property")}</span>
                    <select data-testid="aging-property" className={field} value={propertyId} onChange={e => setPropertyId(e.target.value)}>
                        <option value="">{manager ? t("chooseProperty") : t("allProperties")}</option>
                        {properties.options.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}
                    </select>
                </label>
                <button type="submit" className={button} disabled={loading || (manager && !propertyId)} data-testid="aging-apply">
                    <Filter size={13} />{t("apply")}
                </button>
            </form>

            {loadError && <LoadErrorBanner message={loadError} onRetry={() => load(applied)} />}

            {manager && !applied.propertyId ? (
                <div className="text-center py-20 bg-background border border-dashed border-border rounded-xl text-sm text-muted">
                    {t("chooseProperty")}
                </div>
            ) : loading ? (
                <div className="space-y-3 animate-pulse">{[1, 2, 3].map(i => <div key={i} className="bg-input rounded-xl h-12" />)}</div>
            ) : data && data.rows.length === 0 ? (
                <div className="text-center py-20 bg-background border border-dashed border-border rounded-xl text-sm text-muted" data-testid="aging-empty">
                    {t("nothingOwed")}
                </div>
            ) : data ? (
                <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                    <div className="overflow-x-auto">
                        <table className="w-full min-w-[900px]" data-testid="aging-table">
                            <thead className="bg-input/60">
                                <tr>
                                    <th className={`${th} w-6`} />
                                    <th className={`${th} text-start`}>{t("vendor")}</th>
                                    {BUCKETS.map(b => <th key={b} className={`${th} text-end`}>{t(`bucket.${b}`)}</th>)}
                                    {vendorLevel && <th className={`${th} text-end`}>{t("advances")}</th>}
                                    <th className={`${th} text-end`}>{t("openTotal")}</th>
                                    {vendorLevel && <th className={`${th} text-end`}>{t("ledgerBalance")}</th>}
                                    {vendorLevel && <th className={`${th} text-end`}>{t("delta")}</th>}
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {data.rows.map(r => {
                                    const f = r.figures;
                                    const off = vendorLevel && f.delta !== null && Math.abs(f.delta) >= 0.005;
                                    return (
                                        <Fragment key={r.vendorId}>
                                            <tr data-testid={`aging-row-${r.vendorId}`} className="hover:bg-input/30">
                                                <td className={td}>
                                                    {r.items.length > 0 && (
                                                        <button
                                                            type="button"
                                                            aria-expanded={!!open[r.vendorId]}
                                                            aria-label={t("showItems")}
                                                            onClick={() => setOpen(o => ({ ...o, [r.vendorId]: !o[r.vendorId] }))}
                                                            className="text-muted cursor-pointer"
                                                        >
                                                            {open[r.vendorId] ? <ChevronDown size={13} /> : <ChevronRight size={13} className="rtl:rotate-180" />}
                                                        </button>
                                                    )}
                                                </td>
                                                <td className={`${td} font-medium`}>
                                                    {canManage ? (
                                                        <Link href={`/dashboard/finance/vendors/${r.vendorId}`} className="text-primary hover:underline">{name(r)}</Link>
                                                    ) : name(r)}
                                                    {!r.active && <span className="ms-2 text-[10px] text-muted">({t("inactive")})</span>}
                                                </td>
                                                {BUCKETS.map(b => <td key={b} className={`${td} text-end`}><Amount v={f[b] as number} /></td>)}
                                                {vendorLevel && <td className={`${td} text-end`}><Amount v={f.advances === null ? null : -f.advances} /></td>}
                                                <td className={`${td} text-end`}><Amount v={f.openTotal} strong /></td>
                                                {vendorLevel && <td className={`${td} text-end`}><Amount v={f.ledgerBalance} /></td>}
                                                {vendorLevel && (
                                                    <td className={`${td} text-end`} data-testid={`aging-delta-${r.vendorId}`}>
                                                        {off ? (
                                                            <Link
                                                                href={`/dashboard/finance/general-ledger?vendorId=${r.vendorId}`}
                                                                className="inline-flex items-center gap-1 text-warning font-semibold hover:underline"
                                                                title={t("deltaHint")}
                                                            >
                                                                <AlertTriangle size={12} />
                                                                <bdi dir="ltr" className="tabular-nums">{fmtAmount(f.delta ?? 0)}</bdi>
                                                            </Link>
                                                        ) : <span className="text-success">0.00</span>}
                                                    </td>
                                                )}
                                            </tr>
                                            {open[r.vendorId] && r.items.map(i => (
                                                <tr key={i.kind + i.id} className="bg-input/20 text-muted">
                                                    <td />
                                                    <td className={`${td} ps-6`}>
                                                        {i.invoiceNumber} <span className="font-mono text-[10px]">{i.docNumber ?? t("openingItem")}</span>
                                                        <span className="ms-2 text-[10px]">{t("due")} <bdi dir="ltr">{i.dueDate}</bdi></span>
                                                    </td>
                                                    {BUCKETS.map((b, k) => (
                                                        <td key={b} className={`${td} text-end`}>
                                                            {["CURRENT", "D1_30", "D31_60", "D61_90", "D90_PLUS"][k] === i.bucket ? <Amount v={i.open} /> : null}
                                                        </td>
                                                    ))}
                                                    <td colSpan={columns - 2 - BUCKETS.length} />
                                                </tr>
                                            ))}
                                        </Fragment>
                                    );
                                })}
                            </tbody>
                            <tfoot className="bg-input/60 border-t border-border font-bold" data-testid="aging-totals">
                                <tr>
                                    <td />
                                    <td className={td}>{t("total")}</td>
                                    {BUCKETS.map(b => <td key={b} className={`${td} text-end`}><Amount v={data.totals[b] as number} strong /></td>)}
                                    {vendorLevel && <td className={`${td} text-end`}><Amount v={data.totals.advances === null ? null : -data.totals.advances} strong /></td>}
                                    <td className={`${td} text-end`}><Amount v={data.totals.openTotal} strong /></td>
                                    {vendorLevel && <td className={`${td} text-end`}><Amount v={data.totals.ledgerBalance} strong /></td>}
                                    {vendorLevel && <td className={`${td} text-end`}><Amount v={data.totals.delta} strong /></td>}
                                </tr>
                            </tfoot>
                        </table>
                    </div>
                    <p className="px-4 py-3 text-[11px] text-muted border-t border-border">
                        {vendorLevel ? t("tieOutNote") : t("propertyNote")}
                    </p>
                </div>
            ) : null}
        </div>
    );
}
