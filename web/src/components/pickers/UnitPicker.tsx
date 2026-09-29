"use client";

import { useCallback } from "react";
import { useTranslations } from "next-intl";
import { AsyncSearchSelect, type AsyncOption } from "@/components/ui/AsyncSearchSelect";
import { lookupApi, type UnitOption } from "@/lib/api/lookup";
import { unitCache as units } from "@/components/pickers/caches";

/** Rows asked for per search; more when some will be hidden by `excludeIds` (server cap 50). */
const PAGE = 20;
const MAX = 50;

type Props = {
    value: string;
    onChange: (id: string, unit: UnitOption | null) => void;
    propertyId?: string;
    /** Narrows the search to one building (the contract wizard's Building filter). */
    buildingId?: string;
    status?: "VACANT" | "OCCUPIED" | "RESERVED" | "MAINTENANCE";
    placeholder?: string;
    className?: string;
    testId?: string;
    disabled?: boolean;
    excludeIds?: string[];
    /** Id of the trigger, for a `<label htmlFor>`. */
    id?: string;
};

/** Server-searched unit select: label is the unit number, sublabel its property (· building) and a commercial tag. */
export function UnitPicker({ value, onChange, propertyId, buildingId, status, placeholder, className, testId, disabled, excludeIds, id }: Props) {
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
                await lookupApi.searchUnits({ q, propertyId, buildingId, status, limit: Math.min(PAGE + excluded.size, MAX) }),
            );
            return rows.filter((u) => !excluded.has(u.id)).map(toOption);
        },
        [propertyId, buildingId, status, excludeKey, toOption],
    );

    const resolved = units.useResolved(value);

    return (
        <AsyncSearchSelect
            value={value}
            onChange={(id) => onChange(id, id ? (units.get(id) ?? null) : null)}
            search={search}
            selectedLabel={resolved?.unitNumber}
            placeholder={placeholder ?? t("selectUnit")}
            className={className}
            testId={testId}
            disabled={disabled}
            id={id}
        />
    );
}
