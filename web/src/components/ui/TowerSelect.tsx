"use client";

import { useTranslations } from "next-intl";
import { useBuildings } from "@/hooks/useBuildings";

/**
 * S16-02: the Tower/Building filter for the Units, Contracts and Tickets
 * lists — a `buildingId` select, narrowed to the picked property's towers.
 * Hidden entirely (renders null) when that property has no towers, so a
 * property with a flat unit list never shows an empty picker.
 */
export function TowerSelect({
    propertyId,
    value,
    onChange,
    testId = "tower-filter",
    className = "max-w-[12rem] bg-surface border border-border rounded-lg px-3 py-2 text-xs text-foreground cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none",
}: {
    propertyId: string;
    value: string;
    onChange: (buildingId: string) => void;
    testId?: string;
    className?: string;
}) {
    const t = useTranslations("Towers");
    const { buildings, hasBuildings, label } = useBuildings(propertyId || null);

    if (!propertyId || !hasBuildings) return null;

    return (
        <select
            aria-label={t("label")}
            data-testid={testId}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            className={className}
        >
            <option value="">{t("all")}</option>
            {buildings.map(b => <option key={b.id} value={b.id}>{label(b)}</option>)}
        </select>
    );
}
