"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { AsyncSearchSelect, type AsyncOption } from "@/components/ui/AsyncSearchSelect";
import { leaseApi, type LeaseDetail, type LeaseStatus } from "@/lib/api/leasing";

/** Rows asked for per search. */
const PAGE = 20;

/** Every contract a picker has seen on this page, by id, so a picked id keeps its row. */
const seen = new Map<string, LeaseDetail>();

type Props = {
    value: string;
    onChange: (id: string, lease: LeaseDetail | null) => void;
    /** Only contracts in this state (server filter). */
    status?: LeaseStatus;
    /** The row's label; defaults to "unit · property". */
    label?: (lease: LeaseDetail) => string;
    placeholder?: string;
    className?: string;
    testId?: string;
    disabled?: boolean;
    /** Id of the trigger, for a `<label htmlFor>`. */
    id?: string;
};

const defaultLabel = (l: LeaseDetail) => [l.unitIdentifier, l.propertyName].filter(Boolean).join(" · ") || l.id;

/**
 * Server-searched contract select (scale #6): GET /leases/paged?search=&status=,
 * the first {@link PAGE} matches on unit, tenant, property or Ejari, instead of
 * loading every contract of the organisation into a `<select>`. The tenant's
 * name is the sublabel, so two contracts on one unit can be told apart.
 */
export function LeasePicker({ value, onChange, status, label, placeholder, className, testId, disabled, id }: Props) {
    const t = useTranslations("Pickers");
    const toLabel = label ?? defaultLabel;

    const toOption = useCallback(
        (l: LeaseDetail): AsyncOption => ({ value: l.id, label: toLabel(l), sublabel: l.renterName ?? undefined }),
        [toLabel],
    );

    const search = useCallback(
        async (q: string) => {
            const page = await leaseApi.paged({ search: q || undefined, status, page: 0, size: PAGE });
            const rows = page.content ?? [];
            for (const l of rows) seen.set(l.id, l);
            return rows.map(toOption);
        },
        [status, toOption],
    );

    // A value this page never searched for (set by the parent) is read once by id.
    const [fetched, setFetched] = useState<LeaseDetail | null>(null);
    useEffect(() => {
        if (!value || seen.has(value)) return;
        let alive = true;
        leaseApi.get(value).then(
            (l) => { seen.set(l.id, l); if (alive) setFetched(l); },
            () => undefined,
        );
        return () => { alive = false; };
    }, [value]);
    const resolved = value ? (seen.get(value) ?? (fetched?.id === value ? fetched : null)) : null;

    return (
        <AsyncSearchSelect
            value={value}
            onChange={(next) => onChange(next, next ? (seen.get(next) ?? null) : null)}
            search={search}
            selectedLabel={resolved ? toLabel(resolved) : undefined}
            placeholder={placeholder ?? t("selectContract")}
            searchPlaceholder={t("searchContracts")}
            className={className}
            testId={testId}
            disabled={disabled}
            id={id}
        />
    );
}
