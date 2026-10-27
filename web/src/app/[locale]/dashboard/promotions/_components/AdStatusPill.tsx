"use client";

import { useTranslations } from "next-intl";
import type { AdDisplayStatus } from "@/types/promotion";

const STATUS_CLASSES: Record<AdDisplayStatus, string> = {
    PENDING: "bg-amber-50 text-amber-800",
    REJECTED: "bg-red-50 text-red-700",
    LIVE: "bg-green-50 text-green-700",
    SCHEDULED: "bg-blue-50 text-blue-700",
    EXPIRED: "bg-gray-100 text-gray-600",
    PAUSED: "bg-amber-50 text-amber-700",
};

/**
 * An ad's status pill, shared by the admin list and the promoter portal.
 * `status` is null while it cannot be known yet (the business lookup that
 * decides Live vs Paused has not arrived): a dash, never a guess.
 */
export function AdStatusPill({ status }: { status: AdDisplayStatus | null }) {
    const t = useTranslations("Promotions");
    if (status === null) return <span className="text-xs text-gray-400">—</span>;
    return (
        <span data-testid="ad-status" data-status={status}
            className={`rounded-full px-2 py-1 text-xs ${STATUS_CLASSES[status]}`}>
            {t(`status${status}`)}
        </span>
    );
}
