"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { Loader2 } from "lucide-react";
import { Pagination } from "@/components/ui/Pagination";
import {
    ApiError, createBusiness, deleteBusiness, fetchBusinesses, updateBusiness,
} from "@/lib/api/promotions";
import type { PromoBusinessDTO, PromoBusinessRequest } from "@/types/promotion";
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

    // Guards a slow response from clobbering a newer one (same pattern as
    // dashboard/bookings/page.tsx).
    const requestIdRef = useRef(0);

    const load = useCallback(async (p: number) => {
        const id = ++requestIdRef.current;
        setLoading(true);
        try {
            const data = await fetchBusinesses(p, PAGE_SIZE);
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

    useEffect(() => { void load(page); }, [load, page]);

    async function save(body: PromoBusinessRequest) {
        try {
            if (editing) {
                await updateBusiness(editing.id, body);
            } else {
                await createBusiness(body);
            }
            setEditing(undefined);
            await load(page);
            // Awaited inside the try: onChanged reloads the shell's business
            // lookup, and letting it float left a rejection unhandled and the
            // ad editor's picker stale until a full reload.
            await onChanged?.();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        }
    }

    async function remove(row: PromoBusinessDTO) {
        // Irreversible, and the row gives no other signal of what is about to
        // happen. The server refuses when ads reference it, but a business with
        // none is gone for good.
        if (!window.confirm(t("confirmDeleteBusiness", { name: row.nameEn }))) {
            return;
        }
        try {
            await deleteBusiness(row.id);
            // Deleting the last row on the last page leaves `page` past the end.
            // Pagination does not self-correct, so the list would render the
            // empty state while earlier pages still hold data.
            const remaining = total - 1;
            const lastPage = Math.max(0, Math.ceil(remaining / PAGE_SIZE) - 1);
            const next = Math.min(page, lastPage);
            setPage(next);
            await load(next);
            await onChanged?.();
        } catch (e) {
            // Every business-rule failure on this API is a 400, not a 409 —
            // BusinessRuleViolationException maps unconditionally to BAD_REQUEST
            // — so status cannot tell "has ads" from "bad name". The server
            // message is the only signal, and it is already a complete sentence.
            setError(e instanceof ApiError ? e.message : t("saveError"));
        }
    }

    if (editing !== undefined) {
        return <BusinessEditor business={editing} onSave={save} onCancel={() => setEditing(undefined)} />;
    }

    return (
        <div className="space-y-4">
            <div className="flex justify-end">
                <button type="button" onClick={() => setEditing(null)}
                    className="rounded-lg bg-gray-900 px-4 py-2 text-white">{t("addBusiness")}</button>
            </div>

            {error && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
                    {error}
                </p>
            )}

            {loading ? (
                <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : rows.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">{t("emptyBusinesses")}</p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-left text-gray-500">
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
                                <td>{row.active ? "✓" : "—"}</td>
                                <td className="text-right">
                                    <button type="button" className="mr-3 underline"
                                        onClick={() => setEditing(row)}>{t("edit")}</button>
                                    <button type="button" className="text-red-600 underline"
                                        onClick={() => void remove(row)}>{t("delete")}</button>
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
        </div>
    );
}
