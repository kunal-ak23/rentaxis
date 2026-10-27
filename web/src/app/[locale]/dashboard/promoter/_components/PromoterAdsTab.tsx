"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Pagination } from "@/components/ui/Pagination";
import { Toast, useToast } from "@/components/ui/Toast";
import {
    ApiError, createPromoterAd, deletePromoterAd, fetchPromoterAds, updatePromoterAd,
} from "@/lib/api/promoter";
import { adDisplayStatus, type PromoAdDTO, type PromoAdRequest, type PromoBusinessDTO } from "@/types/promotion";
import { AdEditor, type PropertyOption } from "../../promotions/_components/AdEditor";
import { AdStatusPill } from "../../promotions/_components/AdStatusPill";
import { RedirectSummary } from "../../promotions/_components/RedirectSummary";

const PAGE_SIZE = 10;

interface PromoterAdsTabProps {
    businesses: PromoBusinessDTO[];
    /** Only the properties this promoter was given. */
    properties: PropertyOption[];
}

/**
 * The promoter's ads: every call goes to /promoter/ads, which returns only ads
 * of the businesses they were granted. No priority column and no tap-rate (see
 * the admin AdsTab for why a rate is never derived from the counters).
 */
export function PromoterAdsTab({ businesses, properties }: PromoterAdsTabProps) {
    const t = useTranslations("Promotions");
    const tp = useTranslations("Promoter");

    const [rows, setRows] = useState<PromoAdDTO[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [businessId, setBusinessId] = useState("");
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const [editing, setEditing] = useState<PromoAdDTO | null | undefined>(undefined);
    const [busy, setBusy] = useState(false);
    const { toast, showToast } = useToast();

    const requestIdRef = useRef(0);

    const load = useCallback(async (p: number, filterBusinessId: string) => {
        const id = ++requestIdRef.current;
        setLoading(true);
        try {
            const data = await fetchPromoterAds(p, PAGE_SIZE, filterBusinessId || undefined);
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
                await updatePromoterAd(editing.id, body);
            } else {
                await createPromoterAd(body);
            }
            setEditing(undefined);
            setNotice(tp("sentForApproval"));
            await load(page, businessId);
        } catch (e) {
            // The backend re-checks the link allowlist and the property set; its message says which.
            setError(e instanceof ApiError ? e.message : t("saveError"));
        }
    }

    async function remove(row: PromoAdDTO) {
        if (!window.confirm(t("confirmDeleteAd"))) return;
        setBusy(true);
        try {
            // Always succeeds: an ad nothing has seen is deleted; one with view
            // history is archived, which hides it from this list too.
            const outcome = await deletePromoterAd(row.id);
            setNotice(null);
            setError(null);
            showToast(t(outcome === "ARCHIVED" ? "toastArchived" : "toastDeleted"));
            const lastPage = Math.max(0, Math.ceil((total - 1) / PAGE_SIZE) - 1);
            const next = Math.min(page, lastPage);
            setPage(next);
            await load(next, businessId);
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setBusy(false);
        }
    }

    if (editing !== undefined) {
        return (
            <div className="space-y-4">
                {/* The editor replaces the list, so a refusal (a link off the allowlist, a
                    property outside the promoter's access) has to be shown here. */}
                {error && (
                    <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
                )}
                <AdEditor mode="promoter" businesses={businesses} properties={properties} ad={editing}
                    onSave={save} onCancel={() => { setError(null); setEditing(undefined); }} />
            </div>
        );
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between gap-3">
                <select aria-label={t("business")} className="rounded-lg border px-3 py-2 text-sm"
                    value={businessId}
                    onChange={e => { setPage(0); setBusinessId(e.target.value); }}>
                    <option value="">{t("allBusinesses")}</option>
                    {businesses.map(b => <option key={b.id} value={b.id}>{b.nameEn}</option>)}
                </select>
                <button type="button" disabled={businesses.length === 0}
                    onClick={() => { setNotice(null); setError(null); setEditing(null); }}
                    className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-40">
                    {t("addAd")}
                </button>
            </div>

            {notice && (
                <p role="status" className="rounded-lg bg-green-50 px-3 py-2 text-sm text-green-800">{notice}</p>
            )}
            {error && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
            )}

            {loading ? (
                <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : rows.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">{tp("emptyAds")}</p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-gray-500">
                            <th className="py-2 text-start">{t("adTitle")}</th>
                            <th className="text-start">{t("business")}</th>
                            <th className="text-start">{t("ctaType")}</th>
                            <th className="text-start">{t("status")}</th>
                            <th className="text-end">{t("views")}</th>
                            <th className="text-end">{t("taps")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(row => {
                            const business = businesses.find(b => b.id === row.businessId);
                            // Live vs Paused needs the business's own switch; until the
                            // lookup arrives an approved ad shows a dash, not a guess.
                            const status = businesses.length === 0 && row.approvalStatus === "APPROVED"
                                ? null
                                : adDisplayStatus(row, new Date(), business?.active ?? true);
                            return (
                                <tr key={row.id} className="border-b">
                                    <td className="py-2">
                                        {row.titleEn ?? row.titleAr}
                                        <RedirectSummary ad={row} />
                                    </td>
                                    <td>{row.businessNameEn}</td>
                                    <td>{t(`ctaType${row.ctaType}`)}</td>
                                    <td>
                                        <AdStatusPill status={status} />
                                        {row.approvalStatus === "REJECTED" && row.rejectionReason && (
                                            <span data-testid="rejection-reason"
                                                className="mt-1 block max-w-xs text-xs text-red-700">
                                                {t("rejectedReason", { reason: row.rejectionReason })}
                                            </span>
                                        )}
                                    </td>
                                    <td className="text-end">{row.impressions.toLocaleString()}</td>
                                    <td className="text-end">{row.clicks.toLocaleString()}</td>
                                    <td className="text-end whitespace-nowrap">
                                        <button type="button" className="me-3 underline"
                                            onClick={() => { setNotice(null); setError(null); setEditing(row); }}>{t("edit")}</button>
                                        <button type="button" disabled={busy}
                                            className="text-red-600 underline disabled:opacity-40"
                                            onClick={() => void remove(row)}>{t("delete")}</button>
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

            <Toast toast={toast} />
        </div>
    );
}
