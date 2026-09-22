"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle, Info } from "lucide-react";
import { fmtAmount } from "@/lib/api/ledger";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError, penaltyApi, type PenaltyAssessment } from "@/lib/api/leasing";

/**
 * The renter's own penalties — read-only, and APPROVED only.
 *
 * `GET /penalties/mine` (`PenaltyAssessmentController#mine`) already scopes
 * to the caller's own renter id and to APPROVED: a PROPOSED fine is not yet
 * a decision, and a WAIVED or REVERSED one is no longer a charge, so neither
 * belongs on a screen whose whole point is "why was I charged". The charge
 * itself is collected as an ordinary cheque row on the Payments page
 * (`penaltyAssessmentId` marks it there) — this page exists only to explain
 * it, not to record a payment against it.
 */
export default function RenterPenaltiesPage() {
    const t = useTranslations("RenterPenalties");
    const tCheques = useTranslations("Cheques");
    const locale = useLocale();

    const [rows, setRows] = useState<PenaltyAssessment[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        try {
            setRows(await penaltyApi.mine());
            setLoadError(null);
        } catch (e) {
            setLoadError(e instanceof ApiError ? e.message : t("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [t]);

    useEffect(() => {
        load();
    }, [load]);

    const reload = () => {
        setLoadError(null);
        setLoading(true);
        load();
    };

    return (
        <div className="p-8 max-w-5xl mx-auto">
            {loadError && <LoadErrorBanner message={loadError} onRetry={reload} />}
            <div className="mb-8">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t("pageTitle")}</h1>
                <p className="text-xs text-muted font-medium">{t("pageSubtitle")}</p>
            </div>

            <div className="flex items-start gap-3 bg-info/5 border border-info/20 rounded-xl p-4 mb-6">
                <Info size={15} className="text-info mt-0.5 shrink-0" />
                <p className="text-xs text-foreground/80 font-medium leading-relaxed">{t("clearanceNotice")}</p>
            </div>

            {loading ? (
                <div className="space-y-4">
                    {[1, 2].map(i => (
                        <div key={i} className="bg-surface rounded-xl p-5 border border-border animate-pulse h-24" />
                    ))}
                </div>
            ) : rows.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-success shadow-sm mb-6 border border-border">
                        <CheckCircle size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-2 uppercase tracking-widest">{t("empty")}</p>
                    <p className="text-xs text-muted">{t("emptySub")}</p>
                </div>
            ) : (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    <div className="overflow-x-auto">
                        <table className="w-full min-w-[720px] text-xs">
                            <thead className="bg-input/50">
                                <tr className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                                    <th className="text-start px-4 py-3">{t("colReason")}</th>
                                    <th className="text-start px-4 py-3">{t("colDescription")}</th>
                                    <th className="text-end px-4 py-3">{t("colAmount")}</th>
                                    <th className="text-start px-4 py-3">{t("colCheque")}</th>
                                    <th className="text-start px-4 py-3">{t("colApprovedOn")}</th>
                                    <th className="text-start px-4 py-3">{t("colCollectionStatus")}</th>
                                    <th className="text-start px-4 py-3">{t("colNote")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {rows.map((p, i) => (
                                    <tr key={p.id} data-testid={`renter-penalty-row-${i}`}>
                                        <td className="px-4 py-3">
                                            <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[9px] font-bold uppercase tracking-widest bg-error/10 text-error border border-error/20">
                                                <AlertTriangle size={10} />
                                                {tCheques(`reason.${p.reason}`)}
                                            </span>
                                        </td>
                                        <td className="px-4 py-3 text-muted">{p.description || "—"}</td>
                                        <td className="px-4 py-3 text-end tabular-nums font-bold text-foreground">{fmtAmount(p.amount)}</td>
                                        <td className="px-4 py-3">{p.chequeNumber || "—"}</td>
                                        <td className="px-4 py-3">{p.approvedAt ? fmtIsoDate(p.approvedAt, locale) : "—"}</td>
                                        <td className="px-4 py-3">
                                            {p.collectionStatus ? tCheques(`status.${p.collectionStatus}`) : "—"}
                                        </td>
                                        <td className="px-4 py-3 text-muted">{p.resolutionNote || "—"}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>
            )}
        </div>
    );
}
