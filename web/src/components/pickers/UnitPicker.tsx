"use client";

import { useCallback } from "react";
import { useTranslations } from "next-intl";
import { AsyncSearchSelect, type AsyncOption } from "@/components/ui/AsyncSearchSelect";
import { lookupApi, type UnitOption } from "@/lib/api/lookup";
import { createLookupCache } from "@/components/pickers/lookupCache";

const units = createLookupCache<UnitOption>(lookupApi.unitNames);

/** Rows asked for per search; more when some will be hidden by `excludeIds` (server cap 50). */
const PAGE = 20;
const MAX = 50;

type Props = {
    value: string;
    onChange: (id: string, unit: UnitOption | null) => void;
    propertyId?: string;
    status?: "VACANT" | "OCCUPIED" | "RESERVED" | "MAINTENANCE";
    placeholder?: string;
    className?: string;
    testId?: string;
    disabled?: boolean;
    excludeIds?: string[];
};

/** Server-searched unit select: label is the unit number, sublabel its property (· building) and a commercial tag. */
export function UnitPicker({ value, onChange, propertyId, status, placeholder, className, testId, disabled, excludeIds }: Props) {
    const t = useTranslations("Pickers");
    const commercial = t("commercial");
    const excludeKey = (excludeIds ?? []).join("\u0000");

    const toOption = useCallback(
        (u: UnitOption): AsyncOption => {
            const where = [u.propertyName, u.buildingName].filter(Boolean).join(" · ");
            const tag = u.propertyType === "COMMERCIAL" ? `[${commercial}]` : "";
            const sublabel = [where, tag].filter(Boolean).join(" ");
            return { value: u.id, label: u.unitNumber, sublabel: sublabel || undefined };
        },
        [commercial],
    );

    const search = useCallback(
        async (q: string) => {
            const excluded = new Set(excludeKey ? excludeKey.split("\u0000") : []);
            const rows = units.remember(
                await lookupApi.searchUnits({ q, propertyId, status, limit: Math.min(PAGE + excluded.size, MAX) }),
            );
            return rows.filter((u) => !excluded.has(u.id)).map(toOption);
        },
        [propertyId, status, excludeKey, toOption],
    );

    const resolved = units.useResolved(value);

    return (
        <AsyncSearchSelect
            value={value}
            onChange={(id) => onChange(id, id ? (units.get(id) ?? null) : null)}
            search={search}
            selectedLabel={resolved?.unitNumber}
            placeholder={placeholder}
            className={className}
            testId={testId}
            disabled={disabled}
        />
    );
}
