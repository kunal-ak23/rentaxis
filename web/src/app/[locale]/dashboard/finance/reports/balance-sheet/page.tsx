"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle2, Download, FileText, Filter, Info, Scale, ShieldCheck } from "lucide-react";
import BalanceSheetTable from "@/components/finance/reports/BalanceSheetTable";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { propertyReportsApi, TOTAL, type BalanceSheet, type BalanceSheetQuery } from "@/lib/api/propertyReports";
import { hasPermission, type UserRole } from "@/lib/rbac";

const input = "px-3 py-2 rounded-lg border border-border bg-background text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none";
const button = "flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none";

const today = () => {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
};

/**
 * Finance → Reports → Balance sheet (F14-10): assets, liabilities and equity as
 * at a date, per property and consolidated, with an optional comparative date,
 * the open years' result in equity and the A = L + E check on the report.
 */
export default function BalanceSheetPage() {
    const t = useTranslations("PropertyReports");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canViewPropertyReports");

    const [draft, setDraft] = useState<BalanceSheetQuery>({ asAt: today(), compareAt: null });
    const [applied, setApplied] = useState<BalanceSheetQuery>(draft);
    const [data, setData] = useState<BalanceSheet | null>(null);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    const load = useCallback(async (q: BalanceSheetQuery) => {
        setLoading(true);
        setLoadError(null);
        try {
            setData(await propertyReportsApi.balanceSheet(q));
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

    const totalCheck = data?.check[TOTAL]?.amount ?? 0;

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6 flex-wrap">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Scale size={20} className="text-primary" />
                        {t("balanceSheet")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("balanceSheetDesc")}</p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                    {data && (
                        <span
                            data-testid="bs-check-badge"
                            className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold border ${
                                data.ok ? "bg-success/10 text-success border-success/30" : "bg-error/10 text-error border-error/30"
                            }`}
                        >
                            {data.ok ? <CheckCircle2 size={13} /> : <AlertTriangle size={13} />}
                            {data.ok ? t("bsCheckOk") : t("checkDiff", { amount: fmtAmount(totalCheck) })}
                        </span>
                    )}
                    {data && (
                        <>
                            <a className={button} href={propertyReportsApi.balanceSheetPdfUrl(applied, "en")} target="_blank" rel="noopener noreferrer">
                                <FileText size={13} />{t("pdfEn")}
                            </a>
                            <a className={button} href={propertyReportsApi.balanceSheetPdfUrl(applied, "ar")} target="_blank" rel="noopener noreferrer">
                                <FileText size={13} />{t("pdfAr")}
                            </a>
                            <a className={button} href={propertyReportsApi.balanceSheetCsvUrl(applied, locale)}>
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
                    {t("bsAsAt")}
                    <input type="date" className={input} value={draft.asAt} required
                           onChange={e => setDraft({ ...draft, asAt: e.target.value })} />
                </label>
                <label className="flex flex-col gap-1 text-[11px] font-semibold text-muted">
                    {t("bsCompareAt")}
                    <input type="date" className={input} value={draft.compareAt ?? ""}
                           onChange={e => setDraft({ ...draft, compareAt: e.target.value || null })} />
                </label>
                <button type="submit" disabled={loading}
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold disabled:opacity-50">
                    <Filter size={13} />{t("apply")}
                </button>
            </form>

            {loadError && <LoadErrorBanner message={loadError} onRetry={() => load(applied)} />}

            {data?.scoped && (
                <div className="mb-4 flex items-start gap-2 bg-input/60 border border-border text-muted rounded-xl px-5 py-3">
                    <Info size={16} className="shrink-0 mt-0.5" />
                    <span className="text-xs">{t("scopedNote")}</span>
                </div>
            )}

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map(i => <div key={i} className="bg-input rounded-xl h-12" />)}
                </div>
            ) : data ? (
                <>
                    <BalanceSheetTable data={data} locale={locale} />
                    {data.columns.length > 1 && <p className="mt-3 text-xs text-muted">{t("bsPropertyNote")}</p>}
                </>
            ) : null}
        </div>
    );
}
