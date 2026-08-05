"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, Pencil, Ban, Rows3 } from "lucide-react";
import { cn } from "@/lib/utils";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import {
    fetchParkingSpots,
    createParkingSpot,
    updateParkingSpot,
    bulkCreateParkingSpots,
    deactivateParkingSpot,
    parseSpotNumbers,
    ApiError,
    MAX_BULK_SPOT_NUMBERS,
} from "@/lib/api/facilities";
import type { ParkingSpotDTO } from "@/types/facility";
import type { BuildingOption } from "./AmenitiesTab";

interface ParkingTabProps {
    propertyId: string;
    buildings: BuildingOption[];
    canManage: boolean;
}

type FormMode = { kind: "add" } | { kind: "edit"; spot: ParkingSpotDTO } | { kind: "bulk" };

const PAGE_SIZE = 10;

export function ParkingTab({ propertyId, buildings, canManage }: ParkingTabProps) {
    const t = useTranslations("Facilities");
    const locale = useLocale();
    const [rows, setRows] = useState<ParkingSpotDTO[]>([]);
    const [totalElements, setTotalElements] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [mode, setMode] = useState<FormMode | null>(null);
    const [spotNumber, setSpotNumber] = useState("");
    const [bulkInput, setBulkInput] = useState("");
    const [level, setLevel] = useState("");
    const [covered, setCovered] = useState(true);
    const [active, setActive] = useState(true);
    const [buildingIds, setBuildingIds] = useState<string[]>([]);
    const [submitting, setSubmitting] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);
    const [deactivating, setDeactivating] = useState<ParkingSpotDTO | null>(null);

    const load = useCallback(async (p: number) => {
        setLoading(true);
        setError(null);
        try {
            const data = await fetchParkingSpots(propertyId, p, PAGE_SIZE);
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

    const openMode = (m: FormMode) => {
        setMode(m);
        setFormError(null);
        if (m.kind === "edit") {
            setSpotNumber(m.spot.spotNumber);
            setLevel(m.spot.level ?? "");
            setCovered(m.spot.covered);
            setActive(m.spot.active);
            setBuildingIds(m.spot.buildingIds);
        } else {
            setSpotNumber("");
            setBulkInput("");
            setLevel("");
            setCovered(true);
            setActive(true);
            setBuildingIds([]);
        }
    };

    const toggleBuilding = (id: string) => {
        setBuildingIds(ids => ids.includes(id) ? ids.filter(b => b !== id) : [...ids, id]);
    };

    const bulkCount = mode?.kind === "bulk" ? parseSpotNumbers(bulkInput).length : 0;
    const bulkOverLimit = bulkCount > MAX_BULK_SPOT_NUMBERS;

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!mode) return;
        setSubmitting(true);
        setFormError(null);
        try {
            if (mode.kind === "edit") {
                // Raw `level` (not `|| undefined`): the backend treats a
                // missing field as "unchanged" but an explicit "" as
                // "clear it", so an edit that blanks the level must send ""
                // verbatim, not fall back to omitting the field.
                await updateParkingSpot(mode.spot.id, {
                    spotNumber,
                    level,
                    covered,
                    active,
                    buildingIds,
                });
                setMode(null);
                // Editing doesn't change row order — stay on the current page.
                load(page);
            } else if (mode.kind === "add") {
                await createParkingSpot({
                    propertyId,
                    spotNumber,
                    level: level || undefined,
                    covered,
                    buildingIds,
                });
                setMode(null);
                // Rows are sorted createdAt ASC (backend default), so a freshly
                // created spot is always the last row overall. `totalElements`
                // here is still the pre-create count N (state hasn't been
                // refetched yet); after insertion there are N+1 rows, so the new
                // row's 0-based index is N and its page is floor(N / PAGE_SIZE).
                load(Math.floor(totalElements / PAGE_SIZE));
            } else {
                const spotNumbers = parseSpotNumbers(bulkInput);
                if (spotNumbers.length === 0) {
                    setFormError(t("bulkEmpty"));
                    return;
                }
                if (spotNumbers.length > MAX_BULK_SPOT_NUMBERS) {
                    setFormError(t("bulkTooMany", { count: spotNumbers.length }));
                    return;
                }
                // Mirrors the single-add spotNumber input's maxLength={32} —
                // the backend column (and ParkingSpotCreateRequest validation)
                // caps spotNumber at 32 chars, but bulk entries never pass
                // through that input, so a pasted/expanded entry over the
                // limit would otherwise reach the backend and fail the whole
                // batch with a generic 400 instead of naming the culprit here.
                if (spotNumbers.some(n => n.length > 32)) {
                    setFormError(t("spotTooLong"));
                    return;
                }
                await bulkCreateParkingSpots({
                    propertyId,
                    spotNumbers,
                    level: level || undefined,
                    covered,
                    buildingIds,
                });
                setMode(null);
                // K new rows are appended at the end (createdAt ASC); the last
                // page after insertion is floor((N + K - 1) / PAGE_SIZE).
                load(Math.floor((totalElements + spotNumbers.length - 1) / PAGE_SIZE));
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
            await deactivateParkingSpot(deactivating.id);
            setDeactivating(null);
            load(page);
        } catch (err) {
            setDeactivating(null);
            setError(err instanceof ApiError ? err.message : t("saveError"));
        }
    };

    const towerLabel = (s: ParkingSpotDTO) => {
        if (s.buildingIds.length === 0) return t("allTowers");
        return s.buildingIds
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
                <h2 className="text-lg font-bold">{t("parkingTab")}</h2>
                {canManage && (
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => openMode({ kind: "add" })}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Plus size={14} /> {t("addSpot")}
                        </button>
                        <button
                            onClick={() => openMode({ kind: "bulk" })}
                            className="flex items-center gap-2 bg-background text-foreground px-4 py-2 rounded-full text-xs font-bold hover:bg-input transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Rows3 size={14} /> {t("bulkAddSpots")}
                        </button>
                    </div>
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
                                <th className="px-6 py-4 text-start">{t("colSpot")}</th>
                                <th className="px-6 py-4 text-start">{t("colLevel")}</th>
                                <th className="px-6 py-4 text-start">{t("colCovered")}</th>
                                <th className="px-6 py-4 text-start">{t("colTowers")}</th>
                                <th className="px-6 py-4 text-start">{t("colHeld")}</th>
                                <th className="px-6 py-4 text-start">{t("colStatus")}</th>
                                <th className="px-6 py-4 text-start">{t("colPending")}</th>
                                {canManage && <th className="px-6 py-4" />}
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {rows.map(s => (
                                <tr key={s.id} className="hover:bg-background/50 transition-all duration-200">
                                    <td className="px-6 py-4 font-bold text-foreground">{s.spotNumber}</td>
                                    <td className="px-6 py-4 text-muted text-xs">{s.level ?? "—"}</td>
                                    <td className="px-6 py-4 text-xs font-semibold text-muted">
                                        {s.covered ? t("yes") : t("no")}
                                    </td>
                                    <td className="px-6 py-4 text-muted text-xs font-medium">{towerLabel(s)}</td>
                                    <td className="px-6 py-4">
                                        {s.active ? (
                                            <span className={cn(
                                                "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                                s.held ? "bg-warning/10 text-warning" : "bg-success/10 text-success"
                                            )}>
                                                {s.held ? t("held") : t("available")}
                                            </span>
                                        ) : (
                                            <span className="text-muted text-xs">—</span>
                                        )}
                                    </td>
                                    <td className="px-6 py-4">
                                        <span className={cn(
                                            "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                            s.active ? "bg-success/10 text-success" : "bg-error/10 text-error"
                                        )}>
                                            {s.active ? t("active") : t("inactive")}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4">
                                        {s.pendingCount > 0 ? (
                                            <span className="px-2 py-1 text-[10px] font-bold rounded-md bg-warning/10 text-warning tabular-nums">
                                                {s.pendingCount}
                                            </span>
                                        ) : (
                                            <span className="text-xs text-muted">0</span>
                                        )}
                                    </td>
                                    {canManage && (
                                        <td className="px-6 py-4">
                                            <div className="flex items-center gap-1 justify-end">
                                                <button
                                                    onClick={() => openMode({ kind: "edit", spot: s })}
                                                    aria-label={t("editSpot")}
                                                    className="p-1.5 rounded hover:bg-background text-muted hover:text-foreground transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                >
                                                    <Pencil size={13} />
                                                </button>
                                                {s.active && (
                                                    <button
                                                        onClick={() => setDeactivating(s)}
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
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("noSpots")}</div>
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

            {/* Add / Edit / Bulk dialog */}
            {mode && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setMode(null)} onKeyDown={(e) => { if (e.key === 'Escape') setMode(null); }}>
                    <div className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 p-6" onClick={(e) => e.stopPropagation()}>
                        <h3 className="text-lg font-bold text-foreground mb-4">
                            {mode.kind === "edit" ? t("editSpot") : mode.kind === "bulk" ? t("bulkAddSpots") : t("addSpot")}
                        </h3>
                        <form onSubmit={handleSubmit} className="space-y-4">
                            {mode.kind === "bulk" ? (
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("spotNumbers")} *</label>
                                    <textarea
                                        required
                                        rows={3}
                                        placeholder="B1-01, B1-02, P10-P20"
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none font-mono"
                                        value={bulkInput}
                                        onChange={e => setBulkInput(e.target.value)}
                                        dir="ltr"
                                    />
                                    <p className="text-[10px] text-muted mt-1">{t("bulkHint")}</p>
                                    {bulkCount > 0 && (
                                        <p className={cn("text-[10px] font-bold mt-1", bulkOverLimit ? "text-error" : "text-primary")}>
                                            {bulkOverLimit ? t("bulkTooMany", { count: bulkCount }) : t("bulkPreview", { count: bulkCount })}
                                        </p>
                                    )}
                                </div>
                            ) : (
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("spotNumber")} *</label>
                                    <input
                                        required
                                        maxLength={32}
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        value={spotNumber}
                                        onChange={e => setSpotNumber(e.target.value)}
                                        dir="ltr"
                                    />
                                </div>
                            )}
                            <div className="grid grid-cols-2 gap-4 items-end">
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("level")}</label>
                                    <input
                                        maxLength={32}
                                        placeholder={t("levelPlaceholder")}
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        value={level}
                                        onChange={e => setLevel(e.target.value)}
                                        dir="ltr"
                                    />
                                </div>
                                <label className="flex items-center gap-2 cursor-pointer pb-2">
                                    <input
                                        type="checkbox"
                                        checked={covered}
                                        onChange={e => setCovered(e.target.checked)}
                                        className="accent-[var(--gold-500)]"
                                    />
                                    <span className="text-xs font-semibold text-foreground">{t("covered")}</span>
                                </label>
                            </div>
                            {mode.kind === "edit" && (
                                <label className="flex items-center gap-2 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        checked={active}
                                        onChange={e => setActive(e.target.checked)}
                                        className="accent-[var(--gold-500)]"
                                    />
                                    <span className="text-xs font-semibold text-foreground">{t("active")}</span>
                                </label>
                            )}
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("towers")}</label>
                                <p className="text-[10px] text-muted mb-2">{t("towersHint")}</p>
                                <div className="flex flex-wrap gap-2">
                                    {buildings.map(b => {
                                        const selected = buildingIds.includes(b.id);
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
                                    onClick={() => setMode(null)}
                                    className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200"
                                >
                                    {t("cancel")}
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting || (mode.kind === "bulk" && bulkOverLimit)}
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
                title={t("deactivateSpotTitle")}
                description={t("deactivateConfirm")}
                confirmText={t("deactivate")}
                isDestructive
            />
        </div>
    );
}
