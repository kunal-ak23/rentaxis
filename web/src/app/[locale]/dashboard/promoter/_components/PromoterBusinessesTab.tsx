"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Pagination } from "@/components/ui/Pagination";
import {
    ApiError, createPromoterBusiness, updatePromoterBusiness,
} from "@/lib/api/promoter";
import type { PromoBusinessDTO, PromoBusinessRequest } from "@/types/promotion";
import { BusinessEditor } from "../../promotions/_components/BusinessEditor";

const PAGE_SIZE = 10;

interface PromoterBusinessesTabProps {
    /** The businesses this promoter was granted (the portal loads them once). */
    businesses: PromoBusinessDTO[];
    /** Whether an admin switched on "can create businesses" for this promoter. */
    canCreate: boolean;
    /** Reload the portal's lookups after a save. */
    onChanged: () => void | Promise<void>;
}

/**
 * The businesses a promoter may advertise. With "can create businesses" off the
 * list is read-only: an administrator manages them. With it on the promoter can
 * add businesses (granted to them automatically) and edit theirs; the server
 * sends a business's live ads back for approval on every edit.
 */
export function PromoterBusinessesTab({ businesses, canCreate, onChanged }: PromoterBusinessesTabProps) {
    const t = useTranslations("Promotions");
    const tp = useTranslations("Promoter");

    const [page, setPage] = useState(0);
    const [editing, setEditing] = useState<PromoBusinessDTO | null | undefined>(undefined);
    const [error, setError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);

    async function save(body: PromoBusinessRequest) {
        const wasEdit = Boolean(editing);
        try {
            if (editing) {
                await updatePromoterBusiness(editing.id, body);
            } else {
                await createPromoterBusiness(body);
            }
            setEditing(undefined);
            setError(null);
            setNotice(wasEdit ? tp("businessSavedAdsPending") : tp("businessSaved"));
            await onChanged();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("saveError"));
        }
    }

    if (editing !== undefined) {
        return (
            <div className="space-y-4">
                {error && (
                    <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
                )}
                <BusinessEditor mode="promoter" business={editing}
                    onSave={body => void save(body)} onCancel={() => { setError(null); setEditing(undefined); }} />
            </div>
        );
    }

    const lastPage = Math.max(0, Math.ceil(businesses.length / PAGE_SIZE) - 1);
    const current = Math.min(page, lastPage);
    const rows = businesses.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE);

    return (
        <div className="space-y-4">
            <div className="flex items-center justify-between gap-3">
                <p className="text-sm text-gray-600">
                    {canCreate ? tp("businessesCanCreateHint") : tp("businessesReadOnlyHint")}
                </p>
                {canCreate && (
                    <button type="button" onClick={() => { setNotice(null); setError(null); setEditing(null); }}
                        className="shrink-0 rounded-lg bg-gray-900 px-4 py-2 text-white">
                        {t("addBusiness")}
                    </button>
                )}
            </div>

            {notice && (
                <p role="status" className="rounded-lg bg-green-50 px-3 py-2 text-sm text-green-800">{notice}</p>
            )}
            {error && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>
            )}

            {businesses.length === 0 ? (
                <p className="py-10 text-center text-sm text-gray-500">
                    {canCreate ? tp("emptyBusinessesCanCreate") : tp("emptyBusinesses")}
                </p>
            ) : (
                <table className="w-full text-sm">
                    <thead>
                        <tr className="border-b text-gray-500">
                            <th className="py-2 text-start">{t("name")}</th>
                            <th className="text-start">{t("category")}</th>
                            <th className="text-start">{t("phone")}</th>
                            <th className="text-start">{t("adCount")}</th>
                            <th className="text-start">{t("active")}</th>
                            <th />
                        </tr>
                    </thead>
                    <tbody>
                        {rows.map(row => (
                            <tr key={row.id} className="border-b">
                                <td className="py-2">{row.nameEn}</td>
                                <td>{t(`category${row.category}`)}</td>
                                <td dir="ltr" className="text-start">{row.phoneE164 ?? "—"}</td>
                                <td>{row.adCount}</td>
                                <td>{row.active ? "✓" : "—"}</td>
                                <td className="text-end">
                                    {canCreate && (
                                        <button type="button" className="underline"
                                            onClick={() => { setNotice(null); setError(null); setEditing(row); }}>{t("edit")}</button>
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            )}

            <Pagination
                currentPage={current + 1}
                totalItems={businesses.length}
                itemsPerPage={PAGE_SIZE}
                onPageChange={p => setPage(p - 1)}
            />
        </div>
    );
}
