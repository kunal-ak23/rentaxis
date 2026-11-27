"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Pagination } from "@/components/ui/Pagination";
import { Toast, useToast } from "@/components/ui/Toast";
import {
    ApiError, archiveCategory, createCategory, listCategories, restoreCategory, updateCategory,
} from "@/lib/api/promotions";
import { promoImageSrc } from "@/lib/assetUrl";
import type { DirectoryCategory, DirectoryCategoryRequest, PromoBusinessDTO } from "@/types/promotion";
import { AdStatusPill } from "./AdStatusPill";
import { CategoryEditor } from "./CategoryEditor";

const PAGE_SIZE = 10;

/** The renter app's business directory: categories ("Car wash") and which businesses sit in each. */
export function DirectoryTab({ businesses }: { businesses: PromoBusinessDTO[] }) {
    const t = useTranslations("Promotions");

    // A paused (inactive) or removed business is linked but never shown to renters,
    // so the admin sees how many of a category's businesses are actually live.
    const liveIds = new Set(businesses.filter(b => b.active !== false).map(b => b.id));
    const liveCount = (row: DirectoryCategory) => row.businessIds.filter(id => liveIds.has(id)).length;

    const [rows, setRows] = useState<DirectoryCategory[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [editing, setEditing] = useState<DirectoryCategory | null | undefined>(undefined);
    const [showArchived, setShowArchived] = useState(false);
    const [busy, setBusy] = useState(false);
    const { toast, showToast } = useToast();
    const requestIdRef = useRef(0);

    const load = useCallback(async (p: number, archived: boolean) => {
        const id = ++requestIdRef.current;
        setLoading(true);
        try {
            const data = await listCategories(archived, p, PAGE_SIZE);
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

    // Throws on failure so the editor can show the refusal (an unknown business) inline.
    async function save(body: DirectoryCategoryRequest) {
        if (editing) await updateCategory(editing.id, body);
        else await createCategory(body);
        setEditing(undefined);
        await load(page, showArchived);
    }

    async function reloadAfterRowLeaves() {
        const lastPage = Math.max(0, Math.ceil((total - 1) / PAGE_SIZE) - 1);
        const next = Math.min(page, lastPage);
        setPage(next);
        await load(next, showArchived);
    }

    async function archive(row: DirectoryCategory) {
        if (!window.confirm(t("confirmArchiveCategory"))) return;
        setBusy(true);
        try {
            await archiveCategory(row.id);
            setError(null);
            showToast(t("toastArchived"));
            await reloadAfterRowLeaves();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setBusy(false);
        }
    }

    async function restore(row: DirectoryCategory) {
        setBusy(true);
        try {
            await restoreCategory(row.id);
            setError(null);
            showToast(t("toastRestored"));
            await reloadAfterRowLeaves();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            setBusy(false);
        }
    }

    if (editing !== undefined) {
        return (
            <CategoryEditor category={editing} businesses={businesses} onSave={save}
                onCancel={() => { setError(null); setEditing(undefined); }} />
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
                        className="rounded-lg bg-gray-900 px-4 py-2 text-white">{t("addCategory")}</button>
                )}
            </div>

            {showArchived && <p className="text-sm text-gray-600">{t("archivedNote")}</p>}

            {error && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
            )}

            {loading ? (
                <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : rows.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">
                    {showArchived ? t("emptyArchivedDirectory") : t("emptyDirectory")}
                </p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-start text-gray-500">
                            <th className="py-2 text-start">{t("categoryIcon")}</th>
                            <th className="text-start">{t("nameEn")}</th>
                            <th className="text-start">{t("categoryBusinessCount")}</th>
                            <th className="text-start">{t("active")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(row => (
                            <tr key={row.id} className="border-b">
                                <td className="py-2">
                                    {row.iconUrl ? (
                                        // eslint-disable-next-line @next/next/no-img-element
                                        <img src={promoImageSrc(row.iconUrl, "admin")} alt=""
                                            className="size-8 rounded object-cover" />
                                    ) : "—"}
                                </td>
                                <td>{row.nameEn}</td>
                                <td>{t("categoryBusinessLive", { count: row.businessCount, live: liveCount(row) })}</td>
                                <td>
                                    {showArchived ? <AdStatusPill status="ARCHIVED" /> : row.active ? "✓" : "—"}
                                </td>
                                <td className="text-end whitespace-nowrap">
                                    {showArchived ? (
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
                                                onClick={() => void archive(row)}>{t("delete")}</button>
                                        </>
                                    )}
                                </td>
                            </tr>
                        ))}
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
