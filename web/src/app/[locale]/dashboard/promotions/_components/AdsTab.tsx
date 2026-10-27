"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Pagination } from "@/components/ui/Pagination";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Toast, useToast } from "@/components/ui/Toast";
import {
    ApiError, approveAd, createAd, deleteAd, fetchAds, rejectAd, restoreAd, updateAd,
} from "@/lib/api/promotions";
import {
    adDisplayStatus,
    type AdDisplayStatus,
    type PromoAdDTO,
    type PromoAdRequest,
    type PromoApprovalStatus,
    type PromoBusinessDTO,
} from "@/types/promotion";
import { AdEditor, type PropertyOption } from "./AdEditor";
import { AdStatusPill } from "./AdStatusPill";
import { RedirectSummary } from "./RedirectSummary";

const PAGE_SIZE = 10;

/** The server caps a rejection reason at 500 characters (PromotionService). */
const REJECT_REASON_MAX = 500;

const APPROVAL_FILTERS: { value: "" | PromoApprovalStatus; key: string }[] = [
    { value: "", key: "approvalAll" },
    { value: "PENDING", key: "approvalPending" },
    { value: "REJECTED", key: "approvalRejected" },
];

interface AdsTabProps {
    businesses: PromoBusinessDTO[];
    properties: PropertyOption[];
    /** Called after an approve or reject, so the shell can refresh the pending-count badge. */
    onReviewed?: () => void | Promise<void>;
}

/**
 * No tap-rate column. `clicks / impressions` is NOT the tap rate: impressions
 * are deduped per renter-day and clicks are not, so the ratio is
 * taps-per-renter-day and can exceed 1 — three renters, one tapping ten times,
 * renders as "333%". The real figure is distinct clickers over distinct
 * viewers, which only `PromoAdStatsDTO` carries. Show it on the ad detail view,
 * never derive it here.
 */
export function AdsTab({ businesses, properties, onReviewed }: AdsTabProps) {
    const t = useTranslations("Promotions");

    const [rows, setRows] = useState<PromoAdDTO[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [businessId, setBusinessId] = useState("");
    const [approval, setApproval] = useState<"" | PromoApprovalStatus>("");
    // Archived ads (a delete that kept their history) are hidden unless asked for.
    const [showArchived, setShowArchived] = useState(false);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [editing, setEditing] = useState<PromoAdDTO | null | undefined>(undefined);
    const [rejecting, setRejecting] = useState<PromoAdDTO | null>(null);
    const [reason, setReason] = useState("");
    // One flag for every row action (approve, reject, delete, restore): none may overlap another.
    const [reviewBusy, setReviewBusy] = useState(false);
    const { toast, showToast } = useToast();

    const requestIdRef = useRef(0);

    const load = useCallback(async (
        p: number, filterBusinessId: string, filterApproval: "" | PromoApprovalStatus, archived: boolean,
    ) => {
        const id = ++requestIdRef.current;
        setLoading(true);
        try {
            const data = await fetchAds(p, PAGE_SIZE, filterBusinessId || undefined, filterApproval || undefined, archived);
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

    useEffect(() => { void load(page, businessId, approval, showArchived); }, [load, page, businessId, approval, showArchived]);

    async function save(body: PromoAdRequest) {
        try {
            if (editing) {
                await updateAd(editing.id, body);
            } else {
                await createAd(body);
            }
            setEditing(undefined);
            await load(page, businessId, approval, showArchived);
        } catch (e) {
            // The backend re-checks the URL allowlist; surface its message verbatim.
            setError(e instanceof ApiError ? e.message : t("saveError"));
        }
    }

    /**
     * Reloads after a row action, then tells the shell to refresh the pending
     * count (a reviewed, deleted or restored ad moves it). When the row leaves
     * this list (a delete, a restore, a review under a filter) the last page may
     * no longer exist, so clamp it.
     */
    async function reloadAfter(rowLeavesList: boolean) {
        const remaining = rowLeavesList ? total - 1 : total;
        const lastPage = Math.max(0, Math.ceil(remaining / PAGE_SIZE) - 1);
        const next = Math.min(page, lastPage);
        setPage(next);
        await load(next, businessId, approval, showArchived);
        await onReviewed?.();
    }

    async function remove(row: PromoAdDTO) {
        if (!window.confirm(t("confirmDeleteAd"))) return;
        setReviewBusy(true);
        try {
            // Always succeeds for the user: an ad nothing has seen is deleted,
            // one with view history is archived so the history survives.
            const outcome = await deleteAd(row.id);
            setError(null);
            showToast(t(outcome === "ARCHIVED" ? "toastArchived" : "toastDeleted"));
            await reloadAfter(true);
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setReviewBusy(false);
        }
    }

    async function restore(row: PromoAdDTO) {
        setReviewBusy(true);
        try {
            await restoreAd(row.id);
            setError(null);
            showToast(t("toastRestored"));
            await reloadAfter(true);
        } catch (e) {
            // "Restore the business first" arrives as a 400 whose message says so.
            setError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setReviewBusy(false);
        }
    }

    async function approve(row: PromoAdDTO) {
        setReviewBusy(true);
        try {
            await approveAd(row.id);
            setError(null);
            await reloadAfter(approval !== "");
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setReviewBusy(false);
        }
    }

    async function confirmReject() {
        // The dialog's button is disabled without a reason; this is the same
        // rule where the request is made, so no path can send an empty one.
        const trimmed = reason.trim();
        if (!rejecting || trimmed === "") return;
        setReviewBusy(true);
        try {
            await rejectAd(rejecting.id, trimmed);
            setError(null);
            setRejecting(null);
            setReason("");
            await reloadAfter(approval !== "");
        } catch (e) {
            setRejecting(null);
            setError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setReviewBusy(false);
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
                <div className="flex flex-wrap items-center gap-2">
                    <select aria-label={t("business")} className="rounded-lg border px-3 py-2 text-sm"
                        value={businessId}
                        onChange={e => { setPage(0); setBusinessId(e.target.value); }}>
                        <option value="">{t("allBusinesses")}</option>
                        {businesses.map(b => <option key={b.id} value={b.id}>{b.nameEn}</option>)}
                    </select>
                    {!showArchived && (
                        <select aria-label={t("approvalFilter")} className="rounded-lg border px-3 py-2 text-sm"
                            value={approval}
                            onChange={e => { setPage(0); setApproval(e.target.value as "" | PromoApprovalStatus); }}>
                            {APPROVAL_FILTERS.map(f => <option key={f.value} value={f.value}>{t(f.key)}</option>)}
                        </select>
                    )}
                    <label className="flex items-center gap-2 text-sm">
                        <input type="checkbox" checked={showArchived}
                            onChange={e => { setPage(0); setApproval(""); setShowArchived(e.target.checked); }} />
                        {t("showArchived")}
                    </label>
                </div>
                {!showArchived && (
                    <button type="button" disabled={businesses.length === 0}
                        onClick={() => setEditing(null)}
                        className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-40">
                        {t("addAd")}
                    </button>
                )}
            </div>

            {showArchived && <p className="text-sm text-gray-600">{t("archivedNote")}</p>}

            {error && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
                    {error}
                </p>
            )}

            {loading ? (
                <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : rows.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">
                    {showArchived ? t("emptyArchivedAds") : approval === "" ? t("emptyAds") : t("emptyFilteredAds")}
                </p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-start text-gray-500">
                            <th className="py-2">{t("adTitle")}</th>
                            <th>{t("business")}</th>
                            <th>{t("ctaType")}</th>
                            <th>{t("priority")}</th>
                            <th>{t("status")}</th>
                            <th className="text-end">{t("views")}</th>
                            <th className="text-end">{t("taps")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(row => {
                            // businessActive is part of the server's eligibility
                            // rule and is not on PromoAdDTO. Without it, an admin
                            // who deactivates a business still sees all its ads
                            // reading "Live" while the feed serves none of them.
                            // businesses arrives asynchronously from the shell.
                            // Until it does, every find() misses and every ad
                            // would fall back to "active", showing Live for ads
                            // whose business is deactivated. Show nothing rather
                            // than something wrong.
                            const business = businesses.find(b => b.id === row.businessId);
                            // Approval comes first (adDisplayStatus): a pending or
                            // rejected ad is not showing whatever its window says,
                            // and that needs no business lookup.
                            // An archived ad is hidden whatever its other state says.
                            const status: AdDisplayStatus | null = showArchived ? "ARCHIVED"
                                : businesses.length === 0 && row.approvalStatus === "APPROVED"
                                    ? null
                                    : adDisplayStatus(row, new Date(), business?.active ?? true);
                            return (
                                <tr key={row.id} className="border-b">
                                    <td className="py-2">
                                        {row.titleEn ?? row.titleAr}
                                        <RedirectSummary ad={row} />
                                        {row.submittedByName && (
                                            <span className="block text-xs text-gray-500">
                                                {t("submittedBy", { name: row.submittedByName })}
                                            </span>
                                        )}
                                    </td>
                                    <td>{row.businessNameEn}</td>
                                    <td>{t(`ctaType${row.ctaType}`)}</td>
                                    <td>{row.priority}</td>
                                    <td>
                                        <AdStatusPill status={status} />
                                        {row.approvalStatus === "REJECTED" && row.rejectionReason && (
                                            <span className="mt-1 block max-w-xs text-xs text-gray-500">
                                                {t("rejectedReason", { reason: row.rejectionReason })}
                                            </span>
                                        )}
                                    </td>
                                    <td className="text-end">{row.impressions.toLocaleString()}</td>
                                    <td className="text-end">{row.clicks.toLocaleString()}</td>
                                    <td className="text-end whitespace-nowrap">
                                        {showArchived ? (
                                            // Archived: restorable, and nothing else (no edit, review or delete).
                                            <button type="button" disabled={reviewBusy}
                                                aria-label={`${t("restore")}: ${row.titleEn ?? row.titleAr ?? ""}`}
                                                className="font-medium underline disabled:opacity-40"
                                                onClick={() => void restore(row)}>{t("restore")}</button>
                                        ) : (
                                            <>
                                                {row.approvalStatus === "PENDING" && (
                                                    <>
                                                        <button type="button" disabled={reviewBusy}
                                                            aria-label={`${t("approve")}: ${row.titleEn ?? row.titleAr ?? ""}`}
                                                            className="me-3 font-medium text-green-700 underline disabled:opacity-40"
                                                            onClick={() => void approve(row)}>{t("approve")}</button>
                                                        <button type="button" disabled={reviewBusy}
                                                            aria-label={`${t("reject")}: ${row.titleEn ?? row.titleAr ?? ""}`}
                                                            className="me-3 font-medium text-red-700 underline disabled:opacity-40"
                                                            onClick={() => { setReason(""); setRejecting(row); }}>{t("reject")}</button>
                                                    </>
                                                )}
                                                <button type="button" className="me-3 underline"
                                                    onClick={() => setEditing(row)}>{t("edit")}</button>
                                                <button type="button" disabled={reviewBusy}
                                                    className="text-red-600 underline disabled:opacity-40"
                                                    onClick={() => void remove(row)}>{t("delete")}</button>
                                            </>
                                        )}
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

            <ConfirmDialog
                isOpen={rejecting !== null}
                onClose={() => { setRejecting(null); setReason(""); }}
                onConfirm={() => void confirmReject()}
                title={t("rejectTitle", { name: rejecting?.titleEn ?? rejecting?.titleAr ?? "" })}
                description={t("rejectDescription")}
                confirmText={t("reject")}
                cancelText={t("cancel")}
                isDestructive
                isLoading={reviewBusy}
                confirmDisabled={reason.trim() === ""}
                confirmTestId="reject-ad-confirm"
            >
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("rejectReason")}</span>
                    <textarea aria-label={t("rejectReason")} required maxLength={REJECT_REASON_MAX} rows={3}
                        className="w-full rounded-lg border px-3 py-2 text-sm"
                        value={reason} onChange={e => setReason(e.target.value)} />
                    <span className="text-xs text-gray-500">{t("rejectReasonHint", { max: REJECT_REASON_MAX })}</span>
                </label>
            </ConfirmDialog>
        </div>
    );
}
