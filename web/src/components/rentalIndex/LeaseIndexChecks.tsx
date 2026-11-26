"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { rentalIndexApi, type LeaseIndexCheck } from "@/lib/api/rentalIndex";
import { useRentalIndexEnabled } from "./useRentalIndexEnabled";
import { wholeAed } from "./format";

const ltr = (chunks: React.ReactNode) => <bdi dir="ltr">{chunks}</bdi>;

/**
 * The rental index checks stored on this lease (spec 2026-11-25 §9, §14.7; final review I2): each renewal, and each
 * later edit of its successor draft, as it was decided — rent, cap, allowed increase, who and when, and the reason
 * when it went above the cap. Staff only (the flag and a viewing role); renders nothing when there are none, when the
 * flag is off (and then asks the server nothing), or when they cannot be read.
 *
 * @param refreshKey re-read when it changes (the lease's version, so a saved draft edit shows its new check)
 */
export default function LeaseIndexChecks({ leaseId, refreshKey }: { leaseId: string; refreshKey?: unknown }) {
    const enabled = useRentalIndexEnabled();
    const t = useTranslations("RentalIndex");
    const locale = useLocale();
    // Keyed by the lease they were read for, so another lease's checks never show while its own load.
    const [loaded, setLoaded] = useState<{ leaseId: string; rows: LeaseIndexCheck[] } | null>(null);

    useEffect(() => {
        if (!enabled) return;
        let live = true;
        rentalIndexApi.indexChecks(leaseId)
            .then(rows => { if (live) setLoaded({ leaseId, rows: rows ?? [] }); })
            .catch(() => { if (live) setLoaded({ leaseId, rows: [] }); });
        return () => { live = false; };
    }, [enabled, leaseId, refreshKey]);

    const checks = enabled && loaded?.leaseId === leaseId ? loaded.rows : [];
    if (checks.length === 0) return null;

    return (
        <div className="mt-2 pt-2 border-t border-border space-y-1.5" data-testid="lease-index-checks">
            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">{t("checksTitle")}</p>
            <ul className="space-y-1.5">
                {checks.map(c => {
                    const figures = {
                        rent: wholeAed(c.newRent), cap: wholeAed(c.capRent), pct: c.allowedPercent, n: ltr,
                    };
                    return (
                        <li key={c.id} data-testid="lease-index-check"
                            className={`text-[11px] ${c.aboveCap ? "text-error" : "text-muted"}`}>
                            <p className="tabular-nums">
                                {c.aboveCap ? t.rich("checkAbove", figures) : t.rich("checkWithin", figures)}
                            </p>
                            <p className="text-muted">
                                {t.rich(c.decidedByName ? "checkBy" : "checkOn", {
                                    who: c.decidedByName ?? "", date: fmtIsoDate(c.decidedAt, locale), n: ltr,
                                })}
                                {" · "}
                                {c.hasUnitDocument ? t("checkUnitResult") : t.rich("checkRange", {
                                    source: t(`source${c.rateSource}`), date: fmtIsoDate(c.rateCheckedOn, locale), n: ltr,
                                })}
                            </p>
                            {c.aboveCap && c.overrideReason && (
                                <p className="text-foreground whitespace-pre-wrap break-words" data-testid="lease-index-check-reason">
                                    {t("checkReason", { reason: c.overrideReason })}
                                </p>
                            )}
                        </li>
                    );
                })}
            </ul>
        </div>
    );
}
