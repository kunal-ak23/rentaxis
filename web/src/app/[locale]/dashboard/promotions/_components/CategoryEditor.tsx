"use client";

import { useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { ApiError, isUnknownBusinessError } from "@/lib/api/promotions";
import type { DirectoryCategory, DirectoryCategoryRequest, PromoBusinessDTO } from "@/types/promotion";
import { PromoImageField } from "./PromoImageField";

interface CategoryEditorProps {
    category: DirectoryCategory | null;
    /** The organisation's live businesses: the choices for the category's members. */
    businesses: PromoBusinessDTO[];
    /** Rejects with the server's error; the editor shows it inline and stays open. */
    onSave: (body: DirectoryCategoryRequest) => void | Promise<void>;
    onCancel: () => void;
}

const trimOrNull = (s: string): string | null => (s.trim() === "" ? null : s.trim());

export function CategoryEditor({ category, businesses, onSave, onCancel }: CategoryEditorProps) {
    const t = useTranslations("Promotions");

    const [nameEn, setNameEn] = useState(category?.nameEn ?? "");
    const [nameAr, setNameAr] = useState(category?.nameAr ?? "");
    const [iconUrl, setIconUrl] = useState(category?.iconUrl ?? "");
    const [sortOrder, setSortOrder] = useState(String(category?.sortOrder ?? 0));
    const [active, setActive] = useState(category?.active ?? true);
    const [selected, setSelected] = useState<string[]>(category?.businessIds ?? []);
    const [error, setError] = useState<string | null>(null);
    const [uploading, setUploading] = useState(false);
    const [saving, setSaving] = useState(false);
    const savingRef = useRef(false);

    function toggle(id: string) {
        setSelected(cur => (cur.includes(id) ? cur.filter(x => x !== id) : [...cur, id]));
    }

    async function submit() {
        if (uploading || savingRef.current) return;
        if (trimOrNull(nameEn) === null) {
            setError(t("categoryNameRequired"));
            return;
        }
        setError(null);
        const order = Number.parseInt(sortOrder, 10);
        const body: DirectoryCategoryRequest = {
            nameEn: nameEn.trim(),
            nameAr: trimOrNull(nameAr),
            iconUrl: trimOrNull(iconUrl),
            sortOrder: Number.isNaN(order) ? 0 : order,
            active,
            // A category PUT with null keeps the links; the full list (even
            // empty) is what makes an unticked business actually leave.
            businessIds: selected,
        };
        savingRef.current = true;
        setSaving(true);
        try {
            await onSave(body);
        } catch (e) {
            setError(isUnknownBusinessError(e)
                ? t("categoryUnknownBusiness")
                : e instanceof ApiError ? e.message : t("saveError"));
        } finally {
            savingRef.current = false;
            setSaving(false);
        }
    }

    return (
        <div className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("nameEn")}</span>
                    <input aria-label={t("nameEn")} maxLength={60} className="w-full rounded-lg border px-3 py-2"
                        value={nameEn} onChange={e => setNameEn(e.target.value)} />
                </label>
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("nameAr")}</span>
                    <input aria-label={t("nameAr")} dir="rtl" maxLength={60} className="w-full rounded-lg border px-3 py-2"
                        value={nameAr} onChange={e => setNameAr(e.target.value)} />
                </label>
                <PromoImageField label={t("categoryIcon")} value={iconUrl} onChange={setIconUrl}
                    onBusyChange={setUploading} sizeHint={t("categoryIconSizeHint")} />
                <label className="block">
                    <span className="mb-1 block text-sm font-medium">{t("sortOrder")}</span>
                    <input aria-label={t("sortOrder")} type="number" className="w-full rounded-lg border px-3 py-2"
                        value={sortOrder} onChange={e => setSortOrder(e.target.value)} />
                    <span className="mt-1 block text-xs text-gray-500">{t("sortOrderHint")}</span>
                </label>
            </div>

            <fieldset>
                <legend className="mb-1 text-sm font-medium">{t("categoryBusinesses")}</legend>
                {businesses.length === 0 ? (
                    <p className="text-sm text-gray-500">{t("categoryNoBusinesses")}</p>
                ) : (
                    <div className="grid max-h-64 gap-1 overflow-y-auto rounded-lg border p-3 sm:grid-cols-2">
                        {businesses.map(b => (
                            <label key={b.id} className="flex items-center gap-2 text-sm">
                                <input type="checkbox" checked={selected.includes(b.id)} onChange={() => toggle(b.id)} />
                                {b.nameEn}{b.active === false && <span className="text-gray-500">{t("categoryBusinessPaused")}</span>}
                            </label>
                        ))}
                    </div>
                )}
            </fieldset>

            <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={active} onChange={e => setActive(e.target.checked)} />
                {t("active")}
            </label>

            {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

            <div className="flex gap-2">
                <button type="button" onClick={() => void submit()} disabled={uploading || saving}
                    className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-40">{t("save")}</button>
                <button type="button" onClick={onCancel}
                    className="rounded-lg border px-4 py-2">{t("cancel")}</button>
            </div>
        </div>
    );
}
