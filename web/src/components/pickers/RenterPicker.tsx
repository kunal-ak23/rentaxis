"use client";

import { useCallback } from "react";
import { useLocale, useTranslations } from "next-intl";
import { AsyncSearchSelect, type AsyncOption } from "@/components/ui/AsyncSearchSelect";
import { lookupApi, type RenterOption } from "@/lib/api/lookup";
import { createLookupCache } from "@/components/pickers/lookupCache";

const renters = createLookupCache<RenterOption>(lookupApi.renterNames);

/** Rows asked for per search; more when some will be hidden by `excludeIds` (server cap 50). */
const PAGE = 20;
const MAX = 50;

type Props = {
    value: string;
    onChange: (id: string, renter: RenterOption | null) => void;
    placeholder?: string;
    className?: string;
    testId?: string;
    disabled?: boolean;
    excludeIds?: string[];
};

/**
 * The renter's name as the pickers show it: Arabic under /ar when there is
 * one; under /en "English (Arabic)", as the lease forms have always shown it.
 */
export function renterLabel(r: RenterOption, locale: string): string {
    if (locale === "ar") return r.nameAr || r.nameEn;
    return r.nameAr ? `${r.nameEn} (${r.nameAr})` : r.nameEn;
}

/** Server-searched renter select: label is the localized name, sublabel the email (or phone). */
export function RenterPicker({ value, onChange, placeholder, className, testId, disabled, excludeIds }: Props) {
    const locale = useLocale();
    const t = useTranslations("Pickers");
    const excludeKey = (excludeIds ?? []).join("\u0000");

    const search = useCallback(
        async (q: string): Promise<AsyncOption[]> => {
            const excluded = new Set(excludeKey ? excludeKey.split("\u0000") : []);
            const rows = renters.remember(
                await lookupApi.searchRenters({ q, limit: Math.min(PAGE + excluded.size, MAX) }),
            );
            return rows
                .filter((r) => !excluded.has(r.id))
                .map((r) => ({ value: r.id, label: renterLabel(r, locale), sublabel: r.email ?? r.phone ?? undefined }));
        },
        [excludeKey, locale],
    );

    const resolved = renters.useResolved(value);

    return (
        <AsyncSearchSelect
            value={value}
            onChange={(id) => onChange(id, id ? (renters.get(id) ?? null) : null)}
            search={search}
            selectedLabel={resolved ? renterLabel(resolved, locale) : undefined}
            placeholder={placeholder ?? t("selectRenter")}
            className={className}
            testId={testId}
            disabled={disabled}
        />
    );
}
