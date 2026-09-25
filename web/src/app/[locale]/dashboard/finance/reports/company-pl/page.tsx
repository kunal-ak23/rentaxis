"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, Building2, CheckCircle2, Download, FileText, Filter, ShieldCheck } from "lucide-react";
import DrillDrawer from "@/components/finance/reports/DrillDrawer";
import PnlTable, { type DrillTarget } from "@/components/finance/reports/PnlTable";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { formatDate } from "@/lib/format";
import { lastMonth, propertyReportsApi, TOTAL, type Compare, type PropertyPnl } from "@/lib/api/propertyReports";
import { hasPermission, type UserRole } from "@/lib/rbac";

const input = "px-3 py-2 rounded-lg border border-border bg-background text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none";
const button = "flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none";

type Query = { from: string; to: string; compare: Compare };

/**
 * Finance → Reports → Company P&L (F14-10): the property P&L's Total column on
 * its own — income, expenses and the net result for the company over a period,
 * against the prior period, with the ledger check and the same drill-down.
 */
export default function CompanyPlPage() {
    const t = useTranslations("PropertyReports");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canViewCompanyReports");
    const canOpenLedger = hasPermission(userRole, "canAccessFinance");

    const [draft, setDraft] = useState<Query>({ ...lastMonth(), compare: "PREVIOUS" });
    const [applied, setApplied] = useState<Query>(draft);
    const [data, setData] = useState<PropertyPnl | null>(null);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [drill, setDrill] = useState<DrillTarget | null>(null);

    const load = useCallback(async (q: Query) => {
        setLoading(true);
        setLoadError(null);
        try {
            setData(await propertyReportsApi.companyPnl(q));
        } catch (err) {
            setData(null);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [tCommon]);

    useEffect(() => {
        if (!allowed) { setLoading(false); return; }
        load(applied);
    }, [allowed, applied, load]);

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

    // The company P&L is the Total column alone.
    const company = data ? { ...data, columns: data.columns.filter(c => c.key === TOTAL) } : null;

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6 flex-wrap">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Building2 size={20} className="text-primary" />
                        {t("companyPl")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("companyPlDesc")}</p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                    {data?.check && (
                        <span
                            data-testid="check-badge"
                            className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold border ${
                                data.check.ok ? "bg-success/10 text-success border-success/30" : "bg-error/10 text-error border-error/30"
                            }`}
                        >
                            {data.check.ok ? <CheckCircle2 size={13} /> : <AlertTriangle size={13} />}
                            {data.check.ok ? t("checkOk") : t("checkDiff", { amount: fmtAmount(data.check.difference) })}
                        </span>
                    )}
                    {data && (
                        <>
                            <a className={button} href={propertyReportsApi.companyPnlPdfUrl({ ...applied, lang: "en" })} target="_blank" rel="noopener noreferrer">
                                <FileText size={13} />{t("pdfEn")}
                            </a>
                            <a className={button} href={propertyReportsApi.companyPnlPdfUrl({ ...applied, lang: "ar" })} target="_blank" rel="noopener noreferrer">
                                <FileText size={13} />{t("pdfAr")}
                            </a>
                            <a className={button} href={propertyReportsApi.companyPnlCsvUrl({ ...applied, lang: locale })}>
                                <Download size={13} />{t("csv")}
                            </a>
                        </>
                    )}
                </div>
            </div>

            <form
                className="flex flex-wrap items-end gap-3 mb-5 bg-surface border border-border rounded-xl p-4"
                onSubmit={e => { e.preventDefault(); setApplied({ ...draft }); }}
            >
                <label className="flex flex-col gap-1 text-[11px] font-semibold text-muted">
                    {t("from")}
                    <input type="date" className={input} value={draft.from} required onChange={e => setDraft({ ...draft, from: e.target.value })} />
                </label>
                <label className="flex flex-col gap-1 text-[11px] font-semibold text-muted">
                    {t("to")}
                    <input type="date" className={input} value={draft.to} required onChange={e => setDraft({ ...draft, to: e.target.value })} />
                </label>
                <label className="flex flex-col gap-1 text-[11px] font-semibold text-muted">
                    {t("compare")}
                    <select className={input} value={draft.compare} onChange={e => setDraft({ ...draft, compare: e.target.value as Compare })}>
                        {(["NONE", "PREVIOUS", "LAST_YEAR"] as Compare[]).map(c => <option key={c} value={c}>{t(`compare${c}`)}</option>)}
                    </select>
                </label>
                <button type="submit" disabled={loading}
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold disabled:opacity-50">
                    <Filter size={13} />{t("apply")}
                </button>
            </form>

            {loadError && <LoadErrorBanner message={loadError} onRetry={() => load(applied)} />}

            {data?.priorFrom && data.priorTo && (
                <p className="text-xs text-muted mb-2">{t("priorPeriod", { from: formatDate(data.priorFrom), to: formatDate(data.priorTo) })}</p>
            )}

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map(i => <div key={i} className="bg-input rounded-xl h-12" />)}
                </div>
            ) : company ? (
                <PnlTable data={company} locale={locale} onDrill={setDrill} />
            ) : null}

            {drill && (
                <DrillDrawer
                    key={`${drill.column.key}|${drill.rowKey ?? ""}|${drill.groupId ?? ""}`}
                    target={drill}
                    from={applied.from}
                    to={applied.to}
                    propertyIds={[]}
                    locale={locale}
                    canOpenLedger={canOpenLedger}
                    onClose={() => setDrill(null)}
                />
            )}
        </div>
    );
}
