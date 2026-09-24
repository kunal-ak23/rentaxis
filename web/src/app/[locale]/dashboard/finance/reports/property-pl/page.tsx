"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle2, Download, Filter, Info, PieChart, ShieldCheck } from "lucide-react";
import DrillDrawer from "@/components/finance/reports/DrillDrawer";
import PnlControls, { type PnlControlsValue } from "@/components/finance/reports/PnlControls";
import PnlTable, { type DrillTarget } from "@/components/finance/reports/PnlTable";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { lastMonth, propertyReportsApi, type PropertyPnl } from "@/lib/api/propertyReports";
import { hasPermission, type UserRole } from "@/lib/rbac";

/**
 * Finance → Reports → Property P&L (finance-ops spec §1): income by type,
 * expenses by type and NOI per property for a period, with the prior-period
 * comparison, an honest Unassigned column, a check row against the ledger, and a
 * drill-down from every figure to its journal lines.
 */
export default function PropertyPlPage() {
    const t = useTranslations("PropertyReports");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canViewPropertyReports");
    const canOpenLedger = hasPermission(userRole, "canAccessFinance");

    const initial: PnlControlsValue = {
        kind: "month",
        ...lastMonth(),
        propertyIds: [],
        compare: "PREVIOUS",
        allocate: "NONE",
    };
    const [applied, setApplied] = useState<PnlControlsValue>(initial);
    const [data, setData] = useState<PropertyPnl | null>(null);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [drill, setDrill] = useState<DrillTarget | null>(null);

    const load = useCallback(
        async (q: PnlControlsValue) => {
            setLoading(true);
            setLoadError(null);
            try {
                setData(await propertyReportsApi.pnl({
                    from: q.from, to: q.to, propertyIds: q.propertyIds, compare: q.compare, allocate: q.allocate,
                }));
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
        if (!allowed) {
            setLoading(false);
            return;
        }
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

    const csvHref = propertyReportsApi.pnlCsvUrl({
        from: applied.from, to: applied.to, propertyIds: applied.propertyIds,
        compare: applied.compare, allocate: applied.allocate,
    }, locale);

    return (
        <div>
            <div className="flex items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <PieChart size={20} className="text-primary" />
                        {t("propertyPl")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("propertyPlDesc")}</p>
                </div>
                <div className="flex items-center gap-2">
                    {data?.check && (
                        <span
                            data-testid="check-badge"
                            className={`flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-semibold border ${
                                data.check.ok ? "bg-success/10 text-success border-success/30" : "bg-error/10 text-error border-error/30"
                            }`}
                        >
                            {data.check.ok ? <CheckCircle2 size={13} /> : <AlertTriangle size={13} />}
                            {data.check.ok
                                ? t("checkOk")
                                : t("checkDiff", { amount: fmtAmount(data.check.difference) })}
                        </span>
                    )}
                    <a
                        href={csvHref}
                        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        <Download size={13} />
                        {t("csv")}
                    </a>
                </div>
            </div>

            <PnlControls
                initial={applied}
                scoped={data?.scoped ?? userRole === "PROPERTY_MANAGER"}
                busy={loading}
                applyIcon={<Filter size={13} />}
                onApply={setApplied}
            />

            {loadError && <LoadErrorBanner message={loadError} onRetry={() => load(applied)} />}

            {data?.scoped && (
                <div className="mb-4 flex items-start gap-2 bg-input/60 border border-border text-muted rounded-xl px-5 py-3">
                    <Info size={16} className="shrink-0 mt-0.5" />
                    <span className="text-xs">{t("scopedNote")}</span>
                </div>
            )}

            {data?.priorFrom && data.priorTo && (
                <p className="text-xs text-muted mb-2">
                    {t("priorPeriod", { from: data.priorFrom, to: data.priorTo })}
                </p>
            )}

            {loading ? (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4].map(i => <div key={i} className="bg-input rounded-xl h-12" />)}
                </div>
            ) : data ? (
                <>
                    <PnlTable data={data} locale={locale} onDrill={setDrill} />
                    {data.allocation && data.allocation.allocatedToOthers !== 0 && (
                        <p className="mt-2 text-xs text-muted" data-testid="allocated-to-others">
                            {t("allocatedToOthers", { amount: fmtAmount(data.allocation.allocatedToOthers) })}
                        </p>
                    )}
                    {data.allocation && data.allocation.basisUsed !== data.allocation.basis && (
                        <p className="mt-2 text-xs text-warning">{t("allocationFallback")}</p>
                    )}
                    {data.dataQuality.lineAccountPropertyMismatches > 0 && (
                        <p className="mt-3 text-xs text-muted" data-testid="mismatch-footer">
                            {t("mismatchFooter", { count: data.dataQuality.lineAccountPropertyMismatches })}
                        </p>
                    )}
                </>
            ) : null}

            {drill && (
                <DrillDrawer
                    key={`${drill.column.key}|${drill.rowKey ?? ""}|${drill.groupId ?? ""}`}
                    target={drill}
                    from={applied.from}
                    to={applied.to}
                    propertyIds={applied.propertyIds}
                    locale={locale}
                    canOpenLedger={canOpenLedger}
                    onClose={() => setDrill(null)}
                />
            )}
        </div>
    );
}
