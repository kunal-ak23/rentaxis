"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { codedOf, serverText } from "@/components/finance/bankrec/serverText";
import {
    RENTAL_UNIT_TYPES, rentalIndexApi, unitTypeForCategory,
    type DldPropertyType, type ParsedResult, type RentalIndexRate, type RentalUnitType,
} from "@/lib/api/rentalIndex";
import { formatCurrency } from "@/lib/format";

export type RentalIndexBuildingOption = { id: string; name: string };

export type RentalIndexAddDialogProps = {
    mode: "pdf" | "manual";
    /** Preset by the property tab, and by the renew dialog's "Attach this unit's DLD result". */
    propertyId: string;
    buildings?: RentalIndexBuildingOption[];
    buildingId?: string | null;
    unitType?: string | null;
    /** The unit the result is for; lets the server cross-check premise, end date and rent. */
    unitId?: string | null;
    onClose: () => void;
    onSaved: (rate: RentalIndexRate) => void;
};

const DLD_TYPES: DldPropertyType[] = ["APARTMENT", "VILLA", "HOTEL_APARTMENT"];
const FIELD = "w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200";
const LABEL = "block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1";

const asUnitType = (v: string | null | undefined): RentalUnitType | "" =>
    (RENTAL_UNIT_TYPES as string[]).includes(v ?? "") ? (v as RentalUnitType) : "";

export function RentalIndexAddDialog({ mode, propertyId, buildings = [], buildingId, unitType, unitId, onClose, onSaved }: RentalIndexAddDialogProps) {
    const t = useTranslations("RentalIndex");
    const tc = useTranslations("Common");
    const [file, setFile] = useState<File | null>(null);
    const [parsed, setParsed] = useState<ParsedResult | null>(null);
    const [busy, setBusy] = useState<"read" | "save" | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [building, setBuilding] = useState(buildingId ?? "");
    const [type, setType] = useState<RentalUnitType | "">(asUnitType(unitType) || (mode === "manual" ? "STUDIO" : ""));
    const [dldType, setDldType] = useState<DldPropertyType>("APARTMENT");
    const [category, setCategory] = useState("");
    const [min, setMin] = useState("");
    const [max, setMax] = useState("");
    const [checkedOn, setCheckedOn] = useState(new Date().toISOString().slice(0, 10));

    const fail = (e: unknown, fallback: string) => setError(serverText(tc, e) || codedOf(e).message || fallback);

    const onFile = async (f: File | null) => {
        setFile(f); setParsed(null); setError(null);
        if (!f) return;
        setBusy("read");
        try {
            const p = await rentalIndexApi.parse(f, { unitId });
            setParsed(p);
            // The caller's unit type wins; otherwise what DLD's category maps to.
            if (!asUnitType(unitType)) setType(asUnitType(p.proposedUnitType) || unitTypeForCategory(p.category) || "");
        } catch (e) { fail(e, t("readFailed")); } finally { setBusy(null); }
    };

    const save = async () => {
        setError(null);
        if (!type) return;
        setBusy("save");
        try {
            let rate: RentalIndexRate;
            if (mode === "pdf") {
                if (!file) return;
                rate = await rentalIndexApi.fromPdf(file, { propertyId, buildingId: building || null, unitType: type, unitId });
            } else {
                const lo = Number(min), hi = Number(max);
                if (!(lo > 0) || !(hi >= lo)) { setError(tc("errors.rentalIndex.rangeInvalid")); return; }
                rate = await rentalIndexApi.manual({
                    propertyId, buildingId: building || null, unitType: type, dldPropertyType: dldType,
                    category: category.trim() || null, indexMin: lo, indexMax: hi, checkedOn,
                });
            }
            onSaved(rate);
        } catch (e) { fail(e, t("saveFailed")); } finally { setBusy(null); }
    };

    const check = (id: string, label: string, v: boolean | null) => v === null ? null : (
        <span data-testid={id} title={v ? t("checkMatches") : t("checkDiffers")}
              className={v ? "text-primary font-bold" : "text-error font-bold"}>{v ? "✓" : "✗"}<span className="sr-only"> {label}</span></span>
    );
    const row = (label: string, value: React.ReactNode, c?: React.ReactNode) => (
        <div className="flex items-center justify-between gap-3 text-xs">
            <span className="text-muted">{label}</span>
            <span className="flex items-center gap-2"><span className="font-semibold text-foreground" dir="ltr">{value}</span>{c}</span>
        </div>
    );

    const canSave = busy === null && !!type && (mode === "manual" ? !!min && !!max && !!checkedOn : !!parsed);

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={onClose}
             onKeyDown={(e) => { if (e.key === "Escape") onClose(); }}>
            <div role="dialog" aria-modal="true" aria-label={mode === "pdf" ? t("addTitle") : t("manualTitle")}
                 className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 p-6 max-h-[90vh] overflow-y-auto"
                 onClick={(e) => e.stopPropagation()}>
                <h3 className="text-lg font-bold text-foreground mb-4">{mode === "pdf" ? t("addTitle") : t("manualTitle")}</h3>
                <div className="space-y-4">
                    {mode === "pdf" && (
                        <div>
                            <label className={LABEL} htmlFor="ri-file">{t("file")}</label>
                            <input id="ri-file" data-testid="ri-file" type="file" accept="application/pdf,.pdf" className={FIELD}
                                   onChange={(e) => onFile(e.target.files?.[0] ?? null)} />
                            {busy === "read" && <p className="text-xs text-muted mt-1">{t("reading")}</p>}
                        </div>
                    )}
                    {mode === "pdf" && parsed && (
                        <div className="rounded-lg border border-border p-3 space-y-1.5">
                            <p className="text-xs font-bold text-foreground">{t("parsedHeading")}</p>
                            {row(t("fPremise"), parsed.premiseNo ?? "-", check("ri-check-premise", t("fPremise"), parsed.premiseMatches))}
                            {row(t("fContractEnd"), parsed.contractEnd ?? "-", check("ri-check-end", t("fContractEnd"), parsed.endMatches))}
                            {row(t("fCurrentRent"), parsed.currentRent != null ? formatCurrency(parsed.currentRent) : "-", check("ri-check-rent", t("fCurrentRent"), parsed.rentMatches))}
                            {row(t("fRange"), `${formatCurrency(parsed.indexMin)} – ${formatCurrency(parsed.indexMax)}`)}
                            {row(t("fAverage"), formatCurrency(parsed.average))}
                            {row(t("fPercent"), `${parsed.dldPercent}%`)}
                            {parsed.category && row(t("fCategory"), parsed.category)}
                            {parsed.building && row(t("fBuilding"), parsed.building)}
                        </div>
                    )}
                    {(mode === "manual" || parsed) && (
                        <>
                            <div>
                                <label className={LABEL} htmlFor="ri-building">{t("building")}</label>
                                <select id="ri-building" data-testid="ri-building" className={FIELD} value={building} onChange={(e) => setBuilding(e.target.value)}>
                                    <option value="">{t("noBuilding")}</option>
                                    {buildings.map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
                                </select>
                            </div>
                            <div>
                                <label className={LABEL} htmlFor="ri-unit-type">{t("unitType")}</label>
                                <select id="ri-unit-type" data-testid="ri-unit-type" className={FIELD} value={type} onChange={(e) => setType(asUnitType(e.target.value))}>
                                    <option value="" disabled />
                                    {RENTAL_UNIT_TYPES.map(u => <option key={u} value={u}>{t(`unitType${u}`)}</option>)}
                                </select>
                            </div>
                        </>
                    )}
                    {mode === "manual" && (
                        <>
                            <div>
                                <label className={LABEL} htmlFor="ri-dld-type">{t("propertyType")}</label>
                                <select id="ri-dld-type" className={FIELD} value={dldType} onChange={(e) => setDldType(e.target.value as DldPropertyType)}>
                                    {DLD_TYPES.map(d => <option key={d} value={d}>{t(`dldType${d}`)}</option>)}
                                </select>
                            </div>
                            <div className="grid grid-cols-2 gap-3">
                                <div>
                                    <label className={LABEL} htmlFor="ri-min">{t("lowest")}</label>
                                    <input id="ri-min" data-testid="ri-min" type="number" inputMode="decimal" dir="ltr" className={FIELD} value={min} onChange={(e) => setMin(e.target.value)} />
                                </div>
                                <div>
                                    <label className={LABEL} htmlFor="ri-max">{t("highest")}</label>
                                    <input id="ri-max" data-testid="ri-max" type="number" inputMode="decimal" dir="ltr" className={FIELD} value={max} onChange={(e) => setMax(e.target.value)} />
                                </div>
                            </div>
                            <div>
                                <label className={LABEL} htmlFor="ri-category">{t("category")}</label>
                                <input id="ri-category" className={FIELD} value={category} onChange={(e) => setCategory(e.target.value)} />
                            </div>
                            <div>
                                <label className={LABEL} htmlFor="ri-checked">{t("checkedOn")}</label>
                                <input id="ri-checked" type="date" dir="ltr" className={FIELD} value={checkedOn} onChange={(e) => setCheckedOn(e.target.value)} />
                            </div>
                        </>
                    )}
                    {error && <p role="alert" className="text-xs font-semibold text-error">{error}</p>}
                    <div className="flex justify-end gap-2">
                        <button type="button" onClick={onClose}
                                className="px-3 py-2 rounded-lg text-xs font-bold border border-border text-muted hover:text-foreground cursor-pointer">{t("cancel")}</button>
                        <button type="button" onClick={save} disabled={mode === "pdf" ? !canSave : busy !== null}
                                className="px-3 py-2 rounded-lg text-xs font-bold bg-primary text-primary-foreground disabled:opacity-50 cursor-pointer">
                            {busy === "save" ? t("saving") : t("saveRange")}
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}
