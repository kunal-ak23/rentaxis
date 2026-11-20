"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Link } from "@/i18n/routing";
import SideDrawer from "@/components/ui/SideDrawer";
import { renterRatingsApi, type RenterRatingDetail } from "@/lib/api/renterRatings";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { formatDate } from "@/lib/format";
import AdjustmentForm from "./AdjustmentForm";
import { GRADE_CLASS, gradeText, signedPoints } from "./ratingFormat";

function Trend({ points, label }: { points: { score: number }[]; label: string }) {
    if (points.length < 2) return null;
    const W = 160, H = 40;
    const pts = points.map((p, i) => `${((i / (points.length - 1)) * W).toFixed(1)},${(H - (Math.min(100, Math.max(0, p.score)) / 100) * H).toFixed(1)}`).join(" ");
    return (
        <svg role="img" aria-label={label} viewBox={`0 0 ${W} ${H}`} className="h-10 w-40 text-primary">
            <polyline points={pts} fill="none" stroke="currentColor" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        </svg>
    );
}

export default function RatingDetailsPanel({ renterId, open, onClose, onLoaded }: {
    renterId: string;
    open: boolean;
    onClose: () => void;
    onLoaded?: (d: RenterRatingDetail) => void;
}) {
    const t = useTranslations("RenterRating");
    const { data: session } = useSession();
    const canAdjust = hasPermission(session?.user?.role as UserRole | undefined, "canAdjustRenterRatings");
    const [data, setData] = useState<RenterRatingDetail | null>(null);
    const [error, setError] = useState(false);
    const [removeError, setRemoveError] = useState<string | null>(null);

    const load = useCallback(() => {
        setError(false);
        return renterRatingsApi.get(renterId).then(d => { setData(d); onLoaded?.(d); }).catch(() => setError(true));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [renterId]);

    useEffect(() => { if (open) void load(); }, [open, load]);

    async function remove(adjustmentId: string) {
        if (!window.confirm(t("removeConfirm"))) return;
        setRemoveError(null);
        try {
            await renterRatingsApi.removeAdjustment(renterId, adjustmentId);
            await load();
        } catch (e) {
            setRemoveError(e instanceof Error && e.message ? e.message : t("saveError"));
        }
    }

    return (
        <SideDrawer open={open} onClose={onClose} title={t("title")} testId="rating-panel" closeLabel={t("close")}>
            {error && <p role="alert" className="text-sm text-red-600">{t("loadError")}</p>}
            {!data && !error && <p className="text-sm text-muted">{t("loading")}</p>}
            {data && (
                <div className="space-y-5">
                    <div className="flex items-center gap-4">
                        <span className={`inline-flex items-center gap-2 rounded-full border px-3 py-1 text-sm font-bold ${GRADE_CLASS[data.grade]}`}>
                            <span>{gradeText(data.grade, t("grade.NEW"))}</span><span>{data.score}</span>
                        </span>
                        {data.grade !== "NEW" && <span className="text-sm text-foreground">{t(`grade.${data.grade}`)}</span>}
                    </div>
                    <p className="text-xs text-muted">{t("baseline", { base: data.base })}</p>
                    <Trend points={data.trend} label={t("trendLabel")} />
                    <section>
                        <h3 className="mb-2 text-xs font-bold text-foreground">{t("events")}</h3>
                        {data.events.length === 0 && <p className="text-xs text-muted">{t("noEvents")}</p>}
                        {removeError && <p role="alert" className="mb-2 text-xs text-red-600">{removeError}</p>}
                        <ul className="divide-y divide-border">
                            {data.events.map((e, i) => (
                                <li key={`${e.type}-${e.sourceId ?? i}-${i}`} className="flex items-start justify-between gap-3 py-2 text-xs">
                                    <div className="min-w-0">
                                        <div className="flex flex-wrap items-center gap-2">
                                            <span className="text-muted">{formatDate(e.date)}</span>
                                            <span className="font-medium text-foreground">{t(`event.${e.type}`)}</span>
                                            {e.weight === 0.5 && (
                                                <span className="rounded bg-gray-100 px-1.5 py-0.5 text-[10px] text-gray-700">{t("halfWeight")}</span>
                                            )}
                                        </div>
                                        {e.note && <div className="mt-0.5 text-muted">{e.note}</div>}
                                        {e.adjustmentId && e.createdByName && (
                                            <div className="mt-0.5 text-muted" data-testid="rating-adjustment-by">{t("byUser", { name: e.createdByName })}</div>
                                        )}
                                        {e.leaseId && (
                                            <Link href={`/dashboard/leases/${e.leaseId}`} className="mt-0.5 inline-block text-primary hover:underline">{t("contract")}</Link>
                                        )}
                                    </div>
                                    <div className="flex shrink-0 flex-col items-end gap-1">
                                        <span dir="ltr" className={`font-semibold ${e.appliedPoints < 0 ? "text-red-700" : "text-green-700"}`}>{signedPoints(e.appliedPoints)}</span>
                                        {canAdjust && e.adjustmentId && (
                                            <button type="button" onClick={() => remove(e.adjustmentId!)}
                                                aria-label={t("removeLabel", { reason: e.note || `${signedPoints(e.appliedPoints)} ${formatDate(e.date)}` })}
                                                className="text-[11px] text-red-600 hover:underline cursor-pointer">{t("remove")}</button>
                                        )}
                                    </div>
                                </li>
                            ))}
                        </ul>
                    </section>
                    {canAdjust && data.hasStartedContract && <AdjustmentForm renterId={renterId} onSaved={() => void load()} />}
                </div>
            )}
        </SideDrawer>
    );
}
