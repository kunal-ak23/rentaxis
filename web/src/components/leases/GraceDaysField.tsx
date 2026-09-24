"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

/**
 * A lease's grace before a cheque counts as overdue (gap #65).
 *
 * Empty means "the property's default", and is sent as null so the server
 * reads `rent_collection_settings.grace_period_days` and marks the lease as
 * inheriting it. The field used to start at 0 and always send it, so a
 * building's "5 days" never reached a lease drafted here — every one of them
 * was overdue on day one. A typed number, zero included, is this lease's own.
 */
export function GraceDaysField({
    value,
    onChange,
    propertyDefault,
    className,
}: {
    value: number | null;
    onChange: (value: number | null) => void;
    /** The property's current default, or null while unknown. */
    propertyDefault: number | null;
    className?: string;
}) {
    const t = useTranslations("Leasing");
    // The typed text, kept so a half-typed number is not rewritten under the
    // cursor; shown only while it still says what `value` says, so a value set
    // from outside (a reset, "use property default") replaces it.
    const [text, setText] = useState(value == null ? "" : String(value));
    const typed = text === "" ? null : Number(text);
    const shown = typed === value ? text : value == null ? "" : String(value);

    return (
        <div>
            <input
                type="number"
                min={0}
                max={90}
                step={1}
                data-testid="grace-days-input"
                className={className}
                value={shown}
                placeholder={propertyDefault == null
                    ? t("gracePropertyDefaultUnknown")
                    : t("gracePropertyDefaultPlaceholder", { days: propertyDefault })}
                onChange={e => {
                    const next = e.target.value;
                    setText(next);
                    if (next === "") {
                        onChange(null);
                        return;
                    }
                    const parsed = Number(next);
                    if (!Number.isNaN(parsed)) onChange(Math.max(0, Math.trunc(parsed)));
                }}
            />
            {value != null && (
                <button
                    type="button"
                    data-testid="grace-use-property-default"
                    className="mt-1 ms-1 text-[10px] font-semibold text-primary hover:underline"
                    onClick={() => {
                        setText("");
                        onChange(null);
                    }}
                >
                    {t("graceUsePropertyDefault")}
                </button>
            )}
        </div>
    );
}

/**
 * The property's default grace, for the placeholder. `rent-settings` answers
 * 204 when the property has no collection policy, which the server treats as
 * no grace — so does this. Null while loading or when it cannot be read.
 */
export function usePropertyDefaultGrace(propertyId: string | null | undefined): number | null {
    // Keyed by the property it was read for, so switching unit never shows the
    // previous property's number while the new one loads.
    const [read, setRead] = useState<{ propertyId: string; days: number } | null>(null);
    useEffect(() => {
        if (!propertyId) return;
        let cancelled = false;
        fetch(`/api/proxy/v1/rent-settings/${propertyId}`)
            .then(async res => {
                if (cancelled) return;
                if (res.status === 204) {
                    setRead({ propertyId, days: 0 });
                    return;
                }
                if (!res.ok) return;
                const body = await res.json();
                if (!cancelled) {
                    const days = typeof body?.gracePeriodDays === "number" ? Math.max(0, body.gracePeriodDays) : 0;
                    setRead({ propertyId, days });
                }
            })
            .catch(() => {});
        return () => {
            cancelled = true;
        };
    }, [propertyId]);
    return read && read.propertyId === propertyId ? read.days : null;
}
