"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { renterRatingsApi, type RatingGrade } from "@/lib/api/renterRatings";
import RatingDetailsPanel from "./RatingDetailsPanel";
import { useRenterRatingEnabled } from "./useRenterRatingEnabled";
import { GRADE_CLASS, gradeText } from "./ratingFormat";

/**
 * Grade + score chip; click opens the details panel. With `prefetched` (the list
 * endpoint's stored values) it never fetches on render. The live fetch happens
 * only when the panel opens. Renders nothing unless the feature is on and the
 * caller is staff.
 */
export default function RatingBadge({ renterId, compact, prefetched }: {
    renterId: string;
    compact?: boolean;
    prefetched?: { score: number; grade: RatingGrade };
}) {
    const t = useTranslations("RenterRating");
    const enabled = useRenterRatingEnabled();
    const [live, setLive] = useState<{ score: number; grade: RatingGrade } | null>(null);
    const [open, setOpen] = useState(false);

    useEffect(() => {
        if (!enabled || prefetched) return;
        let cancelled = false;
        renterRatingsApi.get(renterId).then(d => { if (!cancelled) setLive({ score: d.score, grade: d.grade }); }).catch(() => {});
        return () => { cancelled = true; };
    }, [enabled, prefetched, renterId]);

    if (!enabled) return null;
    const r = live ?? prefetched;
    if (!r) return null;
    const label = t(`grade.${r.grade}`);
    const shown = gradeText(r.grade, t("grade.NEW"));
    return (
        <>
            <button type="button" onClick={() => setOpen(true)} data-testid={`rating-badge-${renterId}`}
                aria-label={t("openDetails", { grade: shown, score: r.score })}
                className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-semibold cursor-pointer hover:opacity-80 ${GRADE_CLASS[r.grade]}`}>
                <span>{shown}</span>
                <span>{r.score}</span>
                {!compact && r.grade !== "NEW" && <span className="font-normal">{label}</span>}
            </button>
            {open && (
                <RatingDetailsPanel renterId={renterId} open onClose={() => setOpen(false)}
                    onLoaded={d => setLive({ score: d.score, grade: d.grade })} />
            )}
        </>
    );
}
