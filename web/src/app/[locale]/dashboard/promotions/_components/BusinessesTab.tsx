"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Pagination } from "@/components/ui/Pagination";
import { Toast, useToast } from "@/components/ui/Toast";
import {
    ApiError, createBusiness, deleteBusiness, fetchBusinesses, restoreBusiness, updateBusiness,
} from "@/lib/api/promotions";
import type { PromoBusinessDTO, PromoBusinessRequest } from "@/types/promotion";
import { AdStatusPill } from "./AdStatusPill";
import { BusinessEditor } from "./BusinessEditor";

const PAGE_SIZE = 10;

export function BusinessesTab({ onChanged }: { onChanged?: () => void | Promise<void> }) {
    const t = useTranslations("Promotions");

    const [rows, setRows] = useState<PromoBusinessDTO[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [editing, setEditing] = useState<PromoBusinessDTO | null | undefined>(undefined);
    // Archived businesses (a delete that kept their ads' history) are hidden unless asked for.
    const [showArchived, setShowArchived] = useState(false);
    const [busy, setBusy] = useState(false);
    const { toast, showToast } = useToast();

    // Guards a slow response from clobbering a newer one (same pattern as
    // dashboard/bookings/page.tsx).
    const requestIdRef = useRef(0);

    const load = useCallback(async (p: number, archived: boolean) => {
        const id = ++requestIdRef.current;
        setLoading(true);
        try {
            const data = await fetchBusinesses(p, PAGE_SIZE, archived);
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

    useEffect(() => { void load(page, showArchived); }, [load, page, showArchived]);

    async function save(body: PromoBusinessRequest) {
        try {
            if (editing) {
                await updateBusiness(editing.id, body);
            } else {
                await createBusiness(body);
            }
            setEditing(undefined);
            await load(page, showArchived);
            // Awaited inside the try: onChanged reloads the shell's business
            // lookup, and letting it float left a rejection unhandled and the
            // ad editor's picker stale until a full reload.
            await onChanged?.();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        }
    }

    /** Reloads after a row leaves this list (a delete or a restore), clamping a page that no longer exists. */
    async function reloadAfterRowLeaves() {
        // Deleting the last row on the last page leaves `page` past the end.
        // Pagination does not self-correct, so the list would render the
        // empty state while earlier pages still hold data.
        const remaining = total - 1;
        const lastPage = Math.max(0, Math.ceil(remaining / PAGE_SIZE) - 1);
        const next = Math.min(page, lastPage);
        setPage(next);
        await load(next, showArchived);
        await onChanged?.();
    }

    async function remove(row: PromoBusinessDTO) {
        // The confirmation says what a delete will do: it always succeeds, and
        // a business with ads is archived (with them) rather than refused.
        if (!window.confirm(t("confirmDeleteBusiness"))) {
            return;
        }
        setBusy(true);
        try {
            const outcome = await deleteBusiness(row.id);
            setError(null);
            showToast(t(outcome === "ARCHIVED" ? "toastArchived" : "toastDeleted"));
            await reloadAfterRowLeaves();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setBusy(false);
        }
    }

    async function restore(row: PromoBusinessDTO) {
        setBusy(true);
        try {
            await restoreBusiness(row.id);
            setError(null);
            showToast(t("toastRestored"));
            await reloadAfterRowLeaves();
        } catch (e) {
            // A name now taken by a live business is a 400 whose message says so.
            setError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setBusy(false);
        }
    }

    if (editing !== undefined) {
        return (
            <div className="space-y-4">
                {/* The editor replaces the list, so a refusal (a taken name, a bad domain) has to be shown here. */}
                {error && (
                    <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
                )}
                <BusinessEditor business={editing} onSave={save} onCancel={() => { setError(null); setEditing(undefined); }} />
            </div>
        );
    }

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between gap-3">
                <label className="flex items-center gap-2 text-sm">
                    <input type="checkbox" checked={showArchived}
                        onChange={e => { setPage(0); setShowArchived(e.target.checked); }} />
                    {t("showArchived")}
                </label>
                {!showArchived && (
                    <button type="button" onClick={() => { setError(null); setEditing(null); }}
                        className="rounded-lg bg-gray-900 px-4 py-2 text-white">{t("addBusiness")}</button>
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
                    {showArchived ? t("emptyArchivedBusinesses") : t("emptyBusinesses")}
                </p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-start text-gray-500">
                            <th className="py-2">{t("name")}</th>
                            <th>{t("category")}</th>
                            <th>{t("phone")}</th>
                            <th>{t("adCount")}</th>
                            <th>{t("active")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(row => (
                            <tr key={row.id} className="border-b">
                                <td className="py-2">{row.nameEn}</td>
                                <td>{t(`category${row.category}`)}</td>
                                <td>{row.phoneE164 ?? "—"}</td>
                                <td>{row.adCount}</td>
                                <td>
                                    {showArchived ? <AdStatusPill status="ARCHIVED" /> : row.active ? "✓" : "—"}
                                </td>
                                <td className="text-end whitespace-nowrap">
                                    {showArchived ? (
                                        // Archived: restorable, and nothing else (no edit, no delete).
                                        <button type="button" disabled={busy}
                                            aria-label={`${t("restore")}: ${row.nameEn}`}
                                            className="font-medium underline disabled:opacity-40"
                                            onClick={() => void restore(row)}>{t("restore")}</button>
                                    ) : (
                                        <>
                                            <button type="button" className="me-3 underline"
                                                onClick={() => { setError(null); setEditing(row); }}>{t("edit")}</button>
                                            <button type="button" disabled={busy}
                                                className="text-red-600 underline disabled:opacity-40"
                                                onClick={() => void remove(row)}>{t("delete")}</button>
                                        </>
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}

            {/* Pagination is 1-indexed; the backend's page param is 0-indexed. */}
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
