"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, Pencil, Ban } from "lucide-react";
import { cn } from "@/lib/utils";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import { fetchAmenities, createAmenity, updateAmenity, deactivateAmenity, ApiError } from "@/lib/api/facilities";
import type { AmenityDTO } from "@/types/facility";

export type BuildingOption = { id: string; nameEn: string; nameAr?: string | null };

interface AmenitiesTabProps {
    propertyId: string;
    buildings: BuildingOption[];
    canManage: boolean;
}

const PAGE_SIZE = 10;
const EMPTY_FORM = { nameEn: "", nameAr: "", description: "", bookable: true, buildingIds: [] as string[] };

export function AmenitiesTab({ propertyId, buildings, canManage }: AmenitiesTabProps) {
    const t = useTranslations("Facilities");
    const locale = useLocale();
    const [rows, setRows] = useState<AmenityDTO[]>([]);
    const [totalElements, setTotalElements] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showForm, setShowForm] = useState(false);
    const [editing, setEditing] = useState<AmenityDTO | null>(null);
    const [form, setForm] = useState(EMPTY_FORM);
    const [submitting, setSubmitting] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);
    const [deactivating, setDeactivating] = useState<AmenityDTO | null>(null);

    const load = useCallback(async (p: number) => {
        setLoading(true);
        setError(null);
        try {
            const data = await fetchAmenities(propertyId, p, PAGE_SIZE);
            setRows(data.content);
            setTotalElements(data.totalElements);
            setPage(data.number);
        } catch (err) {
            setError(err instanceof ApiError ? err.message : t("loadError"));
        } finally {
            setLoading(false);
        }
    }, [propertyId, t]);

    useEffect(() => { load(0); }, [load]);

    const openAdd = () => {
        setEditing(null);
        setForm(EMPTY_FORM);
        setFormError(null);
        setShowForm(true);
    };

    const openEdit = (a: AmenityDTO) => {
        setEditing(a);
        setForm({
            nameEn: a.nameEn,
            nameAr: a.nameAr ?? "",
            description: a.description ?? "",
            bookable: a.bookable,
            buildingIds: a.buildingIds,
        });
        setFormError(null);
        setShowForm(true);
    };

    const toggleBuilding = (id: string) => {
        setForm(f => ({
            ...f,
            buildingIds: f.buildingIds.includes(id)
                ? f.buildingIds.filter(b => b !== id)
                : [...f.buildingIds, id],
        }));
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setSubmitting(true);
        setFormError(null);
        try {
            if (editing) {
                await updateAmenity(editing.id, {
                    nameEn: form.nameEn,
                    nameAr: form.nameAr || undefined,
                    description: form.description || undefined,
                    bookable: form.bookable,
                    buildingIds: form.buildingIds,
                });
            } else {
                await createAmenity({
                    propertyId,
                    nameEn: form.nameEn,
                    nameAr: form.nameAr || undefined,
                    description: form.description || undefined,
                    bookable: form.bookable,
                    buildingIds: form.buildingIds,
                });
            }
            setShowForm(false);
            if (editing) {
                load(page);
            } else {
                // Rows are sorted createdAt ASC (backend default), so a freshly
                // created amenity is always the last row overall and lands on the
                // last page, not page 0. `totalElements` here is still the
                // pre-create count N (state hasn't been refetched yet); after
                // insertion there are N+1 rows, so the new row's 0-based index is
                // N and its page is floor(N / PAGE_SIZE) — i.e. simply
                // Math.floor(totalElements / PAGE_SIZE).
                load(Math.floor(totalElements / PAGE_SIZE));
            }
        } catch (err) {
            setFormError(err instanceof ApiError ? err.message : t("saveError"));
        } finally {
            setSubmitting(false);
        }
    };

    const handleDeactivate = async () => {
        if (!deactivating) return;
        try {
            await deactivateAmenity(deactivating.id);
            setDeactivating(null);
            load(page);
        } catch (err) {
            setDeactivating(null);
            setError(err instanceof ApiError ? err.message : t("saveError"));
        }
    };

    const towerLabel = (a: AmenityDTO) => {
        if (a.buildingIds.length === 0) return t("allTowers");
        return a.buildingIds
            .map(id => {
                const b = buildings.find(x => x.id === id);
                if (!b) return null;
                return locale === "ar" && b.nameAr ? b.nameAr : b.nameEn;
            })
            .filter(Boolean)
            .join(", ");
    };

    return (
        <div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-lg font-bold">{t("amenitiesTab")}</h2>
                {canManage && (
                    <button
                        onClick={openAdd}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                    >
                        <Plus size={14} /> {t("addAmenity")}
                    </button>
                )}
            </div>

            {error && (
                <div className="mb-4 px-4 py-3 rounded-xl bg-error/10 text-error border border-error/20 text-xs font-semibold">
                    {error}
                </div>
            )}

            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                        <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                            <tr>
                                <th className="px-6 py-4 text-start">{t("colName")}</th>
                                <th className="px-6 py-4 text-start">{t("colTowers")}</th>
                                <th className="px-6 py-4 text-start">{t("colBookable")}</th>
                                <th className="px-6 py-4 text-start">{t("colStatus")}</th>
                                <th className="px-6 py-4 text-start">{t("colPending")}</th>
                                <th className="px-6 py-4 text-start">{t("colCreated")}</th>
                                {canManage && <th className="px-6 py-4" />}
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {rows.map(a => (
                                <tr key={a.id} className="hover:bg-background/50 transition-all duration-200">
                                    <td className="px-6 py-4">
                                        <p className="font-bold text-foreground">{a.nameEn}</p>
                                        {a.nameAr && <p className="text-xs text-muted font-medium" dir="rtl">{a.nameAr}</p>}
                                    </td>
                                    <td className="px-6 py-4 text-muted text-xs font-medium">{towerLabel(a)}</td>
                                    <td className="px-6 py-4">
                                        <span className={cn(
                                            "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                            a.bookable ? "bg-success/10 text-success" : "bg-input text-muted"
                                        )}>
                                            {a.bookable ? t("bookable") : t("notBookable")}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4">
                                        <span className={cn(
                                            "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                            a.active ? "bg-success/10 text-success" : "bg-error/10 text-error"
                                        )}>
                                            {a.active ? t("active") : t("inactive")}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4">
                                        {a.pendingCount > 0 ? (
                                            <span className="px-2 py-1 text-[10px] font-bold rounded-md bg-warning/10 text-warning tabular-nums">
                                                {a.pendingCount}
                                            </span>
                                        ) : (
                                            <span className="text-xs text-muted">0</span>
                                        )}
                                    </td>
                                    <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                        {new Date(a.createdAt).toLocaleDateString()}
                                    </td>
                                    {canManage && (
                                        <td className="px-6 py-4">
                                            <div className="flex items-center gap-1 justify-end">
                                                <button
                                                    onClick={() => openEdit(a)}
                                                    aria-label={t("editAmenity")}
                                                    className="p-1.5 rounded hover:bg-background text-muted hover:text-foreground transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                >
                                                    <Pencil size={13} />
                                                </button>
                                                {a.active && (
                                                    <button
                                                        onClick={() => setDeactivating(a)}
                                                        aria-label={t("deactivate")}
                                                        className="p-1.5 rounded hover:bg-error/10 text-muted hover:text-error transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                    >
                                                        <Ban size={13} />
                                                    </button>
                                                )}
                                            </div>
                                        </td>
                                    )}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
                {loading && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("loading")}</div>
                )}
                {!loading && rows.length === 0 && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("noAmenities")}</div>
                )}
                {!loading && totalElements > PAGE_SIZE && (
                    <div className="px-6 pb-4">
                        <Pagination
                            currentPage={page + 1}
                            totalItems={totalElements}
                            itemsPerPage={PAGE_SIZE}
                            onPageChange={(p) => load(p - 1)}
                        />
                    </div>
                )}
            </div>

            {/* Add / Edit dialog */}
            {showForm && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setShowForm(false)} onKeyDown={(e) => { if (e.key === 'Escape') setShowForm(false); }}>
                    <div className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 p-6" onClick={(e) => e.stopPropagation()}>
                        <h3 className="text-lg font-bold text-foreground mb-4">
                            {editing ? t("editAmenity") : t("addAmenity")}
                        </h3>
                        <form onSubmit={handleSubmit} className="space-y-4">
                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("nameEn")} *</label>
                                    <input
                                        required
                                        maxLength={160}
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        value={form.nameEn}
                                        onChange={e => setForm({ ...form, nameEn: e.target.value })}
                                    />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("nameAr")}</label>
                                    <input
                                        dir="rtl"
                                        maxLength={160}
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        value={form.nameAr}
                                        onChange={e => setForm({ ...form, nameAr: e.target.value })}
                                    />
                                </div>
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("description")}</label>
                                <textarea
                                    rows={2}
                                    className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none"
                                    value={form.description}
                                    onChange={e => setForm({ ...form, description: e.target.value })}
                                />
                            </div>
                            <label className="flex items-center gap-2 cursor-pointer">
                                <input
                                    type="checkbox"
                                    checked={form.bookable}
                                    onChange={e => setForm({ ...form, bookable: e.target.checked })}
                                    className="accent-[var(--gold-500)]"
                                />
                                <span className="text-xs font-semibold text-foreground">{t("bookable")}</span>
                            </label>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("towers")}</label>
                                <p className="text-[10px] text-muted mb-2">{t("towersHint")}</p>
                                <div className="flex flex-wrap gap-2">
                                    {buildings.map(b => {
                                        const selected = form.buildingIds.includes(b.id);
                                        return (
                                            <button
                                                type="button"
                                                key={b.id}
                                                onClick={() => toggleBuilding(b.id)}
                                                className={cn(
                                                    "px-3 py-1.5 rounded-full text-[11px] font-bold border transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none",
                                                    selected
                                                        ? "bg-primary/10 text-primary border-primary/30"
                                                        : "bg-input text-muted border-border hover:text-foreground"
                                                )}
                                            >
                                                {locale === "ar" && b.nameAr ? b.nameAr : b.nameEn}
                                            </button>
                                        );
                                    })}
                                    {buildings.length === 0 && (
                                        <span className="text-xs text-muted italic">{t("allTowers")}</span>
                                    )}
                                </div>
                            </div>
                            {formError && (
                                <p className="text-xs font-semibold text-error">{formError}</p>
                            )}
                            <div className="flex justify-end gap-2 pt-2">
                                <button
                                    type="button"
                                    onClick={() => setShowForm(false)}
                                    className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200"
                                >
                                    {t("cancel")}
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {submitting ? t("saving") : t("save")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <ConfirmDialog
                isOpen={deactivating !== null}
                onClose={() => setDeactivating(null)}
                onConfirm={handleDeactivate}
                title={t("deactivateAmenityTitle")}
                description={t("deactivateConfirm")}
                confirmText={t("deactivate")}
                isDestructive
            />
        </div>
    );
}
