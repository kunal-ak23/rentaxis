"use client";

import { useEffect, useRef } from "react";
import { useTranslations } from "next-intl";
import { useBuildings } from "@/hooks/useBuildings";
import { FilterChip } from "@/components/ui/FiltersButton";

/**
 * S16-02: the Tower/Building filter for the Units, Contracts and Tickets
 * lists — a `buildingId` select, narrowed to the picked property's towers.
 * Hidden entirely (renders null) when that property has no towers, so a
 * property with a flat unit list never shows an empty picker.
 *
 * R1 P2-2: a caller must not go on sending `value` once this renders null —
 * that leaves a filter applied with no control left to clear it (a shared
 * link, an ACCOUNTANT `GET /buildings/property/{id}` refuses, or a property
 * with no towers). `onAvailabilityChange` reports whether the select is
 * showing, once known (not while still loading, and not while `propertyId`
 * is merely mid-change) — the caller drops `value` when it turns false. A
 * visible selection also gets a removable chip, so clearing it never
 * requires reopening the select.
 */
export function TowerSelect({
    propertyId,
    value,
    onChange,
    testId = "tower-filter",
    className = "max-w-[12rem] bg-surface border border-border rounded-lg px-3 py-2 text-xs text-foreground cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none",
    onAvailabilityChange,
}: {
    propertyId: string;
    value: string;
    onChange: (buildingId: string) => void;
    testId?: string;
    className?: string;
    /** Fired with the resolved availability (never while still unknown/loading). */
    onAvailabilityChange?: (available: boolean) => void;
}) {
    const t = useTranslations("Towers");
    const tList = useTranslations("ListActions");
    const { buildings, hasBuildings, loading, label } = useBuildings(propertyId || null);

    // Resolved as soon as there is no propertyId (definitely unavailable); while
    // one is set, only once the read has actually settled — not mid-flight,
    // which would otherwise report "unavailable" for a property that turns out
    // to have towers a moment later.
    const resolved = !propertyId || !loading;
    const available = !!propertyId && hasBuildings;
    const onAvailabilityChangeRef = useRef(onAvailabilityChange);
    useEffect(() => {
        onAvailabilityChangeRef.current = onAvailabilityChange;
    });
    useEffect(() => {
        if (resolved) onAvailabilityChangeRef.current?.(available);
    }, [resolved, available]);

    if (!propertyId || !hasBuildings) return null;

    const selected = buildings.find(b => b.id === value);

    return (
        <>
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
            {selected && (
                <FilterChip
                    testId={`${testId}-chip`}
                    label={label(selected)}
                    removeLabel={tList("removeFilter", { name: label(selected) })}
                    onRemove={() => onChange("")}
                />
            )}
        </>
    );
}
