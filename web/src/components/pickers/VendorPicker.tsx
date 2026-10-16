"use client";

import { useCallback, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { AsyncSearchSelect, type AsyncOption } from "@/components/ui/AsyncSearchSelect";
import { vendorLookupApi, type VendorOption } from "@/lib/api/payables";

type Props = {
    value: string;
    onChange: (id: string, vendor: VendorOption | null) => void;
    /** Active suppliers only (the default), as the whole-list selects this replaces offered. */
    activeOnly?: boolean;
    placeholder?: string;
    className?: string;
    testId?: string;
    disabled?: boolean;
    id?: string;
};

const vendorLabel = (v: VendorOption, locale: string) => (locale === "ar" ? v.nameAr || v.nameEn : v.nameEn || v.nameAr || "");

/**
 * Server-searched vendor select (scale follow-up): the first 20 matches of `GET /vendors/paged`
 * (names, TRN, email, phone, contact person), by name, instead of every vendor the organisation has.
 */
export function VendorPicker({ value, onChange, activeOnly = true, placeholder, className, testId, disabled, id }: Props) {
    const locale = useLocale();
    const t = useTranslations("Pickers");
    const [seen, setSeen] = useState<Record<string, VendorOption>>({});

    const search = useCallback(
        async (q: string): Promise<AsyncOption[]> => {
            const page = await vendorLookupApi.search({ q, active: activeOnly ? true : undefined, size: 20 });
            const rows = page.content ?? [];
            setSeen(prev => ({ ...prev, ...Object.fromEntries(rows.map(v => [v.id, v])) }));
            return rows.map(v => ({ value: v.id, label: vendorLabel(v, locale), sublabel: v.trn || undefined }));
        },
        [activeOnly, locale],
    );

    const picked = value ? seen[value] : undefined;

    return (
        <AsyncSearchSelect
            value={value}
            onChange={(next) => onChange(next, next ? (seen[next] ?? null) : null)}
            search={search}
            selectedLabel={picked ? vendorLabel(picked, locale) : undefined}
            placeholder={placeholder ?? t("selectVendor")}
            className={className}
            testId={testId}
            disabled={disabled}
            id={id}
        />
    );
}
