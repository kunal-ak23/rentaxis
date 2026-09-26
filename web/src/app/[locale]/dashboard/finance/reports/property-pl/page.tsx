"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, Building2, CheckCircle2, Download, FileText, Filter, Info, PieChart, ShieldCheck } from "lucide-react";
import DrillDrawer from "@/components/finance/reports/DrillDrawer";
import PnlControls, { type PnlControlsValue } from "@/components/finance/reports/PnlControls";
import PnlTable, { type DrillTarget } from "@/components/finance/reports/PnlTable";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import { formatDate } from "@/lib/format";
import { lastMonth, propertyReportsApi, type PropertyPnl } from "@/lib/api/propertyReports";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { useBuildings } from "@/hooks/useBuildings";
import { cn } from "@/lib/utils";

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

    // S16-02: "By tower" — only possible with exactly one property picked, and
    // only shown once that property is known to have towers (Buildings).
    const singlePropertyId = applied.propertyIds.length === 1 ? applied.propertyIds[0] : null;
    const { hasBuildings } = useBuildings(singlePropertyId);
    const [byTower, setByTower] = useState(false);
    const [buildingData, setBuildingData] = useState<PropertyPnl | null>(null);
    const [buildingLoading, setBuildingLoading] = useState(false);
    const [buildingError, setBuildingError] = useState<string | null>(null);

    // R1 P3-1: a request counter — switching the property/period quickly must
    // not let an older, slower read land after a newer one and overwrite it.
    const loadSeq = useRef(0);
    const load = useCallback(
        async (q: PnlControlsValue) => {
            const seq = ++loadSeq.current;
            setLoading(true);
            setLoadError(null);
            try {
                const result = await propertyReportsApi.pnl({
                    from: q.from, to: q.to, propertyIds: q.propertyIds, compare: q.compare, allocate: q.allocate,
                });
                if (seq !== loadSeq.current) return;
                setData(result);
            } catch (err) {
                if (seq !== loadSeq.current) return;
                setData(null);
                setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
            } finally {
                if (seq === loadSeq.current) setLoading(false);
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

    // The toggle only makes sense for one property with towers; anything else
    // (no property, several properties, or one with no towers) falls back to
    // the by-property view rather than showing a stale by-tower table.
    useEffect(() => {
        if (!singlePropertyId || !hasBuildings) setByTower(false);
    }, [singlePropertyId, hasBuildings]);

    // R1 P3-1: same guard as `load`, for the by-tower read (a property or
    // period change while it is in flight).
    const loadBuildingSeq = useRef(0);
    const loadBuilding = useCallback(async () => {
        if (!singlePropertyId) return;
        const seq = ++loadBuildingSeq.current;
        setBuildingLoading(true);
        setBuildingError(null);
        try {
            const result = await propertyReportsApi.pnlByBuilding({
                propertyId: singlePropertyId, from: applied.from, to: applied.to, compare: applied.compare,
            });
            if (seq !== loadBuildingSeq.current) return;
            setBuildingData(result);
        } catch (err) {
            if (seq !== loadBuildingSeq.current) return;
            setBuildingData(null);
            setBuildingError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            if (seq === loadBuildingSeq.current) setBuildingLoading(false);
        }
    }, [singlePropertyId, applied.from, applied.to, applied.compare, tCommon]);

    useEffect(() => {
        if (!byTower || !singlePropertyId) return;
        loadBuilding();
        // eslint-disable-next-line react-hooks/exhaustive-deps -- loadBuilding already depends on everything relevant
    }, [byTower, singlePropertyId, applied.from, applied.to, applied.compare]);

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
                    {!byTower && data?.check && (
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
                    {!byTower && (["en", "ar"] as const).map(lang => (
                        <a
                            key={lang}
                            data-testid={`pl-pdf-${lang}`}
                            href={propertyReportsApi.pnlPdfUrl({
                                from: applied.from, to: applied.to, propertyIds: applied.propertyIds, compare: applied.compare,
                            }, lang)}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        >
                            <FileText size={13} />
                            {t(lang === "en" ? "pdfEn" : "pdfAr")}
                        </a>
                    ))}
                    {!byTower && (
                        <a
                            href={csvHref}
                            className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        >
                            <Download size={13} />
                            {t("csv")}
                        </a>
                    )}
                </div>
            </div>

            <PnlControls
                initial={applied}
                scoped={data?.scoped ?? userRole === "PROPERTY_MANAGER"}
                busy={loading}
                applyIcon={<Filter size={13} />}
                onApply={setApplied}
            />

            {/* S16-02: "By tower" — a column per Building of the one property picked, for a
                property that has any; the standard by-property view otherwise. */}
            {singlePropertyId && hasBuildings && (
                <div className="flex items-center bg-input rounded-lg p-0.5 border border-border w-fit mb-4" role="group" aria-label={t("byTowerToggleLabel")}>
                    <button
                        type="button"
                        data-testid="pl-view-property"
                        onClick={() => setByTower(false)}
                        className={cn(
                            "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer",
                            !byTower ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground",
                        )}
                    >
                        {t("byProperty")}
                    </button>
                    <button
                        type="button"
                        data-testid="pl-view-tower"
                        onClick={() => setByTower(true)}
                        className={cn(
                            "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                            byTower ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground",
                        )}
                    >
                        <Building2 size={13} />
                        {t("byTower")}
                    </button>
                </div>
            )}

            {byTower && <p className="text-xs text-muted mb-3">{t("byTowerNote")}</p>}

            {loadError && !byTower && <LoadErrorBanner message={loadError} onRetry={() => load(applied)} />}
            {byTower && buildingError && <LoadErrorBanner message={buildingError} onRetry={loadBuilding} />}

            {data?.scoped && (
                <div className="mb-4 flex items-start gap-2 bg-input/60 border border-border text-muted rounded-xl px-5 py-3">
                    <Info size={16} className="shrink-0 mt-0.5" />
                    <span className="text-xs">{t("scopedNote")}</span>
                </div>
            )}

            {data?.priorFrom && data.priorTo && (
                <p className="text-xs text-muted mb-2">
                    {t("priorPeriod", { from: formatDate(data.priorFrom), to: formatDate(data.priorTo) })}
                </p>
            )}

            {byTower ? (
                buildingLoading ? (
                    <div className="space-y-3 animate-pulse">
                        {[1, 2, 3, 4].map(i => <div key={i} className="bg-input rounded-xl h-12" />)}
                    </div>
                ) : buildingData ? (
                    <>
                        {buildingData.check && (
                            <span
                                data-testid="tower-check-badge"
                                className={`inline-flex items-center gap-1 mb-3 px-3 py-1.5 rounded-lg text-xs font-semibold border ${
                                    buildingData.check.ok ? "bg-success/10 text-success border-success/30" : "bg-error/10 text-error border-error/30"
                                }`}
                            >
                                {buildingData.check.ok ? <CheckCircle2 size={13} /> : <AlertTriangle size={13} />}
                                {buildingData.check.ok
                                    ? t("checkOk")
                                    : t("checkDiff", { amount: fmtAmount(buildingData.check.difference) })}
                            </span>
                        )}
                        <div data-testid="pl-by-tower-table">
                            <PnlTable data={buildingData} locale={locale} />
                        </div>
                    </>
                ) : null
            ) : loading ? (
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

            {!byTower && drill && (
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
