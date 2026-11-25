"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle } from "lucide-react";
import { wholeAed } from "@/components/rentalIndex/format";
import { CheckOnDld } from "@/components/rentalIndex/CheckOnDld";
import { RentalIndexAddDialog } from "@/app/[locale]/dashboard/properties/[id]/_components/RentalIndexAddDialog";
import { fmtIsoDate } from "./leaseMath";
import type { LeaseDetail, RenewalPreview } from "@/lib/api/leasing";


/** ISO yyyy-mm-dd to dd/mm/yyyy, the way DLD's date box takes it. */
const ddmmyyyy = (iso: string) => {
    const [y, m, d] = iso.slice(0, 10).split("-");
    return `${d}/${m}/${y}`;
};

type UnitInfo = { type: string | null; dewa: string | null };

/**
 * The rental index in the renew dialog (spec §8): the range in force, the allowed increase and the highest
 * new rent, the 90-day notice deadline, and the way to check or attach DLD's result for this unit.
 * Rendered only when the preview carries an index status, i.e. the feature is on.
 */
export default function RentalIndexPanel({ lease, preview, onAttached }: {
    lease: LeaseDetail;
    preview: RenewalPreview;
    /** After a DLD result is attached, so the dialog re-reads the preview. */
    onAttached: () => void;
}) {
    const t = useTranslations("RentalIndex");
    const locale = useLocale();
    const [unit, setUnit] = useState<UnitInfo | null>(null);
    const [attaching, setAttaching] = useState(false);
    const status = preview.indexStatus;
    const checkable = status === "OK" || status === "NO_INDEX";

    // The lease payload carries no DEWA number or unit type, so read the unit from its own page of /units/paged.
    useEffect(() => {
        if (!checkable || !lease.unitId || !lease.unitIdentifier) return;
        let alive = true;
        const q = new URLSearchParams({ q: lease.unitIdentifier, size: "50" });
        if (lease.propertyId) q.set("propertyId", lease.propertyId);
        fetch(`/api/proxy/v1/units/paged?${q.toString()}`)
            .then(r => (r.ok ? r.json() : null))
            .then((page: { content?: { id: string; type?: string | null; dewaPremiseNumber?: string | null }[] } | null) => {
                const u = page?.content?.find(x => x.id === lease.unitId);
                if (alive) setUnit({ type: u?.type ?? null, dewa: u?.dewaPremiseNumber ?? null });
            })
            .catch(() => undefined);
        return () => { alive = false; };
    }, [checkable, lease.unitId, lease.unitIdentifier, lease.propertyId]);

    if (!status) return null;
    const rent = Number(preview.baseRent ?? lease.currentRentAmount ?? lease.rentAmount ?? 0);
    const deadline = preview.noticeDeadline ? fmtIsoDate(preview.noticeDeadline, locale) : null;

    return (
        <div className="border border-border rounded-lg p-3 space-y-3" data-testid="rental-index-panel">
            <div className="text-[10px] font-semibold text-muted uppercase tracking-wider">{t("panelTitle")}</div>
            {status === "OK" ? (
                <dl className="grid grid-cols-2 md:grid-cols-4 gap-3 text-xs">
                    <div>
                        <dt className="text-muted">{t("pRange")}</dt>
                        <dd className="font-semibold tabular-nums" dir="ltr">{wholeAed(preview.indexMin ?? 0)} – {wholeAed(preview.indexMax ?? 0)}</dd>
                        {preview.indexCheckedOn && (
                            <dd className="text-[11px] text-muted">{t("pChecked", { date: fmtIsoDate(preview.indexCheckedOn, locale) })}</dd>
                        )}
                    </div>
                    <div>
                        <dt className="text-muted">{t("pAverage")}</dt>
                        <dd className="font-semibold tabular-nums" dir="ltr">{wholeAed(preview.indexAverage ?? 0)}</dd>
                        <dd className="text-[11px] text-muted"><bdi dir="ltr">{t("pGap")} {preview.indexGapPercent ?? 0}%</bdi></dd>
                    </div>
                    <div>
                        <dt className="text-muted">{t("pAllowed")}</dt>
                        <dd className="font-semibold tabular-nums" dir="ltr">{preview.indexAllowedPercent ?? 0}%</dd>
                    </div>
                    <div>
                        <dt className="text-muted">{t("pCap")}</dt>
                        <dd className="font-semibold tabular-nums" dir="ltr">AED {wholeAed(preview.indexCapRent ?? 0)}</dd>
                    </div>
                </dl>
            ) : (
                <p className="text-xs text-muted" data-testid="rental-index-status">{t(`status${status}`)}</p>
            )}

            {deadline && (
                <p className="text-xs text-foreground">{t("noticeDeadline", { date: deadline })}</p>
            )}
            {preview.noticePassed && (
                <p role="alert" data-testid="rental-index-notice-passed"
                    className="flex items-center gap-1.5 text-[11px] text-warning bg-warning/10 border border-warning/20 rounded-lg px-3 py-2">
                    <AlertTriangle size={12} /> {t("noticePassed", { date: deadline ?? "" })}
                </p>
            )}

            {checkable && (
                <>
                    <CheckOnDld end={ddmmyyyy(lease.endDate)} typeLabel="Apartment" dewa={unit?.dewa ?? null} rent={rent}
                        unitsHref={`/dashboard/properties/${lease.propertyId}/units`} />
                    {lease.propertyId && (
                        <button type="button" data-testid="renew-index-attach" onClick={() => setAttaching(true)}
                            className="px-3 py-1.5 rounded-lg text-xs font-bold border border-border text-foreground hover:bg-background cursor-pointer">
                            {t("attach")}
                        </button>
                    )}
                </>
            )}
            {attaching && lease.propertyId && (
                <RentalIndexAddDialog mode="pdf" propertyId={lease.propertyId} unitType={unit?.type ?? null} unitId={lease.unitId}
                    onClose={() => setAttaching(false)} onSaved={() => { setAttaching(false); onAttached(); }} />
            )}
        </div>
    );
}
