"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Download, FileSpreadsheet, FileText, Filter, ShieldCheck } from "lucide-react";
import PnlControls, { type PnlControlsValue } from "@/components/finance/reports/PnlControls";
import StatementView from "@/components/finance/reports/StatementView";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { lastMonth, propertyReportsApi, type PropertyStatement } from "@/lib/api/propertyReports";
import { hasPermission, type UserRole } from "@/lib/rbac";

const button = "flex items-center gap-1.5 px-3 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none";

/**
 * Finance → Reports → Property statement (finance-ops spec §1): the nine-section
 * pack for one property and period, with PDF (English or Arabic) and CSV.
 */
export default function PropertyStatementPage() {
    const t = useTranslations("PropertyReports");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canViewPropertyReports");

    const initial: PnlControlsValue = { kind: "month", ...lastMonth(), propertyIds: [], compare: "PREVIOUS", allocate: "NONE" };
    const [applied, setApplied] = useState<PnlControlsValue>(initial);
    const [data, setData] = useState<PropertyStatement | null>(null);
    const [loading, setLoading] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);

    const propertyId = applied.propertyIds[0];

    const load = useCallback(
        async (q: PnlControlsValue) => {
            if (!q.propertyIds[0]) return;
            setLoading(true);
            setLoadError(null);
            try {
                setData(await propertyReportsApi.statement({ propertyId: q.propertyIds[0], from: q.from, to: q.to }));
            } catch (err) {
                setData(null);
                setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
            } finally {
                setLoading(false);
            }
        },
        [tCommon],
    );

    useEffect(() => {
        if (allowed) load(applied);
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

    const q = propertyId ? { propertyId, from: applied.from, to: applied.to } : null;

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <FileSpreadsheet size={20} className="text-primary" />
                        {t("propertyStatement")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("propertyStatementDesc")}</p>
                </div>
                {q && data && (
                    <div className="flex flex-wrap items-center gap-2">
                        <a className={button} href={propertyReportsApi.statementPdfUrl({ ...q, lang: "en" })} target="_blank" rel="noopener noreferrer">
                            <FileText size={13} />{t("pdfEn")}
                        </a>
                        <a className={button} href={propertyReportsApi.statementPdfUrl({ ...q, lang: "ar" })} target="_blank" rel="noopener noreferrer">
                            <FileText size={13} />{t("pdfAr")}
                        </a>
                        <a className={button} href={propertyReportsApi.statementCsvUrl({ ...q, lang: locale })}>
                            <Download size={13} />{t("csv")}
                        </a>
                    </div>
                )}
            </div>

            <PnlControls initial={applied} scoped busy={loading} singleProperty applyIcon={<Filter size={13} />} onApply={setApplied} />

            {loadError && <LoadErrorBanner message={loadError} onRetry={() => load(applied)} />}

            {!propertyId ? (
                <div className="text-center py-20 bg-background border border-dashed border-border rounded-xl text-sm text-muted">
                    {t("chooseProperty")}
                </div>
            ) : loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3].map(i => <div key={i} className="bg-input rounded-xl h-24" />)}
                </div>
            ) : data ? (
                <StatementView data={data} locale={locale} />
            ) : null}
        </div>
    );
}
