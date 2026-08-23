"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Pagination } from "@/components/ui/Pagination";
import { ApiError, createAd, deleteAd, fetchAds, updateAd } from "@/lib/api/promotions";
import { adStatus, type PromoAdDTO, type PromoAdRequest, type PromoBusinessDTO } from "@/types/promotion";
import { AdEditor, type PropertyOption } from "./AdEditor";

const PAGE_SIZE = 10;

const STATUS_CLASSES: Record<string, string> = {
    LIVE: "bg-green-50 text-green-700",
    SCHEDULED: "bg-blue-50 text-blue-700",
    EXPIRED: "bg-gray-100 text-gray-600",
    PAUSED: "bg-amber-50 text-amber-700",
};

interface AdsTabProps {
    businesses: PromoBusinessDTO[];
    properties: PropertyOption[];
}

/**
 * No tap-rate column. `clicks / impressions` is NOT the tap rate: impressions
 * are deduped per renter-day and clicks are not, so the ratio is
 * taps-per-renter-day and can exceed 1 — three renters, one tapping ten times,
 * renders as "333%". The real figure is distinct clickers over distinct
 * viewers, which only `PromoAdStatsDTO` carries. Show it on the ad detail view,
 * never derive it here.
 */
export function AdsTab({ businesses, properties }: AdsTabProps) {
    const t = useTranslations("Promotions");

    const [rows, setRows] = useState<PromoAdDTO[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [businessId, setBusinessId] = useState("");
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [editing, setEditing] = useState<PromoAdDTO | null | undefined>(undefined);

    const requestIdRef = useRef(0);

    const load = useCallback(async (p: number, filterBusinessId: string) => {
        const id = ++requestIdRef.current;
        setLoading(true);
        try {
            const data = await fetchAds(p, PAGE_SIZE, filterBusinessId || undefined);
            if (id !== requestIdRef.current) return;
            setRows(data.content);
            setTotal(data.totalElements);
            setError(null);
        } catch (e) {
            if (id !== requestIdRef.current) return;
            setError(e instanceof ApiError ? e.message : t("loadError"));
        } finally {
            if (id === requestIdRef.current) setLoading(false);
        }
    }, [t]);

    useEffect(() => { void load(page, businessId); }, [load, page, businessId]);

    async function save(body: PromoAdRequest) {
        try {
            if (editing) {
                await updateAd(editing.id, body);
            } else {
                await createAd(body);
            }
            setEditing(undefined);
            await load(page, businessId);
        } catch (e) {
            // The backend re-checks the URL allowlist; surface its message verbatim.
            setError(e instanceof ApiError ? e.message : t("saveError"));
        }
    }

    if (editing !== undefined) {
        return (
            <AdEditor businesses={businesses} properties={properties} ad={editing}
                onSave={save} onCancel={() => setEditing(undefined)} />
        );
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between gap-3">
                <select aria-label={t("business")} className="rounded-lg border px-3 py-2 text-sm"
                    value={businessId}
                    onChange={e => { setPage(0); setBusinessId(e.target.value); }}>
                    <option value="">{t("business")}</option>
                    {businesses.map(b => <option key={b.id} value={b.id}>{b.nameEn}</option>)}
                </select>
                <button type="button" disabled={businesses.length === 0}
                    onClick={() => setEditing(null)}
                    className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-40">
                    {t("addAd")}
                </button>
            </div>

            {error && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
                    {error}
                </p>
            )}

            {loading ? (
                <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : rows.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">{t("emptyAds")}</p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-left text-gray-500">
                            <th className="py-2">{t("adTitle")}</th>
                            <th>{t("business")}</th>
                            <th>{t("ctaType")}</th>
                            <th>{t("priority")}</th>
                            <th>{t("status")}</th>
                            <th className="text-right">{t("views")}</th>
                            <th className="text-right">{t("taps")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(row => {
                            // businessActive is part of the server's eligibility
                            // rule and is not on PromoAdDTO. Without it, an admin
                            // who deactivates a business still sees all its ads
                            // reading "Live" while the feed serves none of them.
                            const business = businesses.find(b => b.id === row.businessId);
                            const status = adStatus(row, new Date(), business?.active ?? true);
                            return (
                                <tr key={row.id} className="border-b">
                                    <td className="py-2">{row.titleEn ?? row.titleAr}</td>
                                    <td>{row.businessNameEn}</td>
                                    <td>{t(`ctaType${row.ctaType}`)}</td>
                                    <td>{row.priority}</td>
                                    <td>
                                        <span className={`rounded-full px-2 py-1 text-xs ${STATUS_CLASSES[status]}`}>
                                            {t(`status${status}`)}
                                        </span>
                                    </td>
                                    <td className="text-right">{row.impressions.toLocaleString()}</td>
                                    <td className="text-right">{row.clicks.toLocaleString()}</td>
                                    <td className="text-right">
                                        <button type="button" className="mr-3 underline"
                                            onClick={() => setEditing(row)}>{t("edit")}</button>
                                        <button type="button" className="text-red-600 underline"
                                            onClick={async () => {
                                                if (!window.confirm(
                                                    t("confirmDeleteAd", {
                                                        name: row.titleEn ?? row.titleAr ?? "",
                                                    }))) {
                                                    return;
                                                }
                                                try {
                                                    await deleteAd(row.id);
                                                } catch (e) {
                                                    // Deleting an ad with view
                                                    // history is refused, and
                                                    // that 400 was previously
                                                    // an unhandled rejection.
                                                    setError(e instanceof ApiError
                                                        ? e.message : t("saveError"));
                                                    return;
                                                }
                                                const remaining = total - 1;
                                                const lastPage = Math.max(
                                                    0, Math.ceil(remaining / PAGE_SIZE) - 1);
                                                const next = Math.min(page, lastPage);
                                                setPage(next);
                                                await load(next, businessId);
                                            }}>{t("delete")}</button>
                                    </td>
                                </tr>
                            );
                        })}
                    </tbody>
                </table>
            )}

            <Pagination
                currentPage={page + 1}
                totalItems={total}
                itemsPerPage={PAGE_SIZE}
                onPageChange={p => setPage(p - 1)}
            />
        </div>
    );
}
