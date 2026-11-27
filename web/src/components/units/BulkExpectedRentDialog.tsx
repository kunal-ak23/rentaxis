"use client";

import { useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { X } from "lucide-react";
import { MoneyTextInput } from "@/components/ui/NumberInput";
import { MONEY_MAX_12_2, moneyInputError, moneyValueOrNull } from "@/lib/money";
import { throwIfNotOk } from "@/lib/api/facilities";
import { serverText } from "@/components/finance/bankrec/serverText";

export const UNIT_TYPES = ["STUDIO", "BHK1", "BHK2", "BHK3", "PENTHOUSE", "RETAIL", "OFFICE"] as const;
const RENT_OPTIONS = { allowZero: true, max: MONEY_MAX_12_2 };

type Option = { id: string; label: string };
type UnitLite = { type?: string | null; building?: { id?: string } | null };

/**
 * Sets the expected rent of every unit of a type in one go, e.g. 2 BHK 30,000 and
 * 3 BHK 40,000 across the chosen projects (PUT /units/expected-rent/bulk). A type left
 * blank keeps its rents. With one project, a building can narrow it and the unit
 * counts per type are shown.
 */
export function BulkExpectedRentDialog({ open, onClose, onDone, projects, fixedProjectId, fixedBuildingId, buildings, units }: {
    open: boolean;
    onClose: () => void;
    onDone: () => void;
    projects: Option[];
    fixedProjectId?: string;
    /** A building the caller already filtered to: the update covers only it, and `units` are its units. */
    fixedBuildingId?: string;
    buildings?: Option[];
    units?: UnitLite[];
}) {
    const t = useTranslations("MasterData");
    const tCommon = useTranslations("Common");
    const [selected, setSelected] = useState<string[]>(fixedProjectId ? [fixedProjectId] : projects.map(p => p.id));
    const [buildingId, setBuildingId] = useState(fixedBuildingId ?? "");
    const [rents, setRents] = useState<Record<string, string>>({});
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [done, setDone] = useState<number | null>(null);

    const counts = useMemo(() => {
        if (!units) return null;
        const c: Record<string, number> = {};
        for (const u of units) {
            if (!u.type) continue;
            if (!fixedBuildingId && buildingId && u.building?.id !== buildingId) continue;
            c[u.type] = (c[u.type] ?? 0) + 1;
        }
        return c;
    }, [units, buildingId, fixedBuildingId]);

    if (!open) return null;

    const close = () => {
        setRents({});
        setError(null);
        setDone(null);
        onClose();
    };

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);
        const entered = Object.entries(rents).filter(([, v]) => v.trim() !== "");
        if (entered.some(([, v]) => moneyInputError(v, RENT_OPTIONS))) {
            setError(t("bulkEditRentInvalid"));
            return;
        }
        if (entered.length === 0) {
            setError(t("bulkEditRentNoneEntered"));
            return;
        }
        if (selected.length === 0) {
            setError(t("bulkEditRentNoProject"));
            return;
        }
        const body = {
            propertyIds: selected,
            buildingId: buildingId || null,
            rents: Object.fromEntries(entered.map(([type, v]) => [type, moneyValueOrNull(v, RENT_OPTIONS)])),
        };
        setSaving(true);
        try {
            const res = await fetch("/api/proxy/v1/units/expected-rent/bulk", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            await throwIfNotOk(res);
            const result = await res.json();
            setDone(Number(result?.total ?? 0));
            onDone();
        } catch (err) {
            setError(serverText(tCommon, err));
        } finally {
            setSaving(false);
        }
    };

    const toggle = (id: string) =>
        setSelected(prev => (prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]));
    const shownTypes = counts ? UNIT_TYPES.filter(type => (counts[type] ?? 0) > 0) : UNIT_TYPES;
    const inputClass = "w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none";

    return (
        <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
            <div role="dialog" aria-modal="true" aria-labelledby="bulk-rent-title" data-testid="bulk-rent-dialog"
                className="bg-surface rounded-xl p-8 max-w-xl w-full shadow-2xl border border-border relative max-h-[90vh] overflow-y-auto">
                <button type="button" onClick={close} aria-label={t("close")}
                    className="cursor-pointer absolute end-6 top-6 p-2 text-muted hover:text-foreground rounded-lg focus:ring-2 focus:ring-primary/30 focus:outline-none">
                    <X size={18} />
                </button>
                <h2 id="bulk-rent-title" className="text-lg font-bold mb-1">{t("bulkEditRent")}</h2>
                <p className="text-xs text-muted mb-6 font-medium">{t("bulkEditRentDescription")}</p>

                {done != null ? (
                    <div>
                        <p className="bg-success/10 border border-success/30 rounded-lg px-4 py-3 text-xs text-success font-bold" data-testid="bulk-rent-done">
                            {t("bulkEditRentDone", { count: done })}
                        </p>
                        <div className="flex justify-end mt-6">
                            <button type="button" onClick={close} className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">{t("close")}</button>
                        </div>
                    </div>
                ) : (
                    <form onSubmit={submit} className="space-y-5">
                        {!fixedProjectId && (
                            <fieldset>
                                <legend className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-2">{t("bulkEditRentProjects")}</legend>
                                <label className="flex items-center gap-2 text-xs font-bold mb-2 cursor-pointer">
                                    <input type="checkbox" checked={selected.length === projects.length}
                                        onChange={ev => setSelected(ev.target.checked ? projects.map(p => p.id) : [])} />
                                    {t("bulkEditRentAllProjects")}
                                </label>
                                <div className="grid grid-cols-2 gap-1.5 max-h-40 overflow-y-auto border border-border rounded-lg p-2">
                                    {projects.map(p => (
                                        <label key={p.id} className="flex items-center gap-2 text-xs cursor-pointer">
                                            <input type="checkbox" checked={selected.includes(p.id)} onChange={() => toggle(p.id)} />
                                            {p.label}
                                        </label>
                                    ))}
                                </div>
                            </fieldset>
                        )}
                        {fixedProjectId && !fixedBuildingId && buildings && buildings.length > 0 && (
                            <div>
                                <label htmlFor="bulk-rent-building" className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("bulkEditRentBuilding")}</label>
                                <select id="bulk-rent-building" className={inputClass} value={buildingId} onChange={ev => setBuildingId(ev.target.value)}>
                                    <option value="">{t("bulkEditRentAllBuildings")}</option>
                                    {buildings.map(b => <option key={b.id} value={b.id}>{b.label}</option>)}
                                </select>
                            </div>
                        )}
                        <div>
                            <p className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-2">{t("expectedRent")}</p>
                            {shownTypes.length === 0 ? (
                                <p className="text-xs text-muted">{t("noUnitsFound")}</p>
                            ) : (
                                <div className="grid grid-cols-2 gap-3">
                                    {shownTypes.map(type => (
                                        <label key={type} className="block">
                                            <span className="flex justify-between text-xs font-bold mb-1">
                                                <span>{t(`unitType${type}`)}</span>
                                                {counts && <span className="text-muted font-medium">{t("bulkEditRentUnits", { count: counts[type] ?? 0 })}</span>}
                                            </span>
                                            <MoneyTextInput options={RENT_OPTIONS} value={rents[type] ?? ""} placeholder="AED"
                                                data-testid={`bulk-rent-${type}`}
                                                onChange={v => setRents(prev => ({ ...prev, [type]: v }))} className={inputClass} />
                                        </label>
                                    ))}
                                </div>
                            )}
                        </div>
                        {error && <div role="alert" className="bg-error/10 border border-error/30 rounded-lg px-4 py-3 text-xs text-error">{error}</div>}
                        <div className="flex justify-end gap-2">
                            <button type="button" onClick={close} className="px-4 py-2 text-xs font-bold text-muted cursor-pointer rounded-lg focus:ring-2 focus:ring-primary/30 focus:outline-none">{t("cancel")}</button>
                            <button type="submit" disabled={saving} data-testid="bulk-rent-apply"
                                className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed focus:ring-2 focus:ring-primary/30 focus:outline-none">
                                {saving ? t("saving") : t("bulkEditRentApply")}
                            </button>
                        </div>
                    </form>
                )}
            </div>
        </div>
    );
}
