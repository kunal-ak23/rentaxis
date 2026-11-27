"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { Check, Pencil, X } from "lucide-react";
import { MoneyTextInput } from "@/components/ui/NumberInput";
import { MONEY_MAX_12_2, moneyValueOrNull } from "@/lib/money";
import { formatCurrencyCompact } from "@/lib/format";
import { throwIfNotOk } from "@/lib/api/facilities";
import { serverText } from "@/components/finance/bankrec/serverText";

const RENT_OPTIONS = { allowZero: true, max: MONEY_MAX_12_2 };

/**
 * A unit's expected (asking) rent, editable in place by the roles that may price units.
 * Saves through PUT /units/{id}/expected-rent; a signed contract keeps its own rent.
 */
export function ExpectedRentCell({ unitId, value, canEdit, onSaved }: {
    unitId: string;
    value: number | null | undefined;
    canEdit: boolean;
    onSaved: (rent: number) => void;
}) {
    const t = useTranslations("MasterData");
    const tCommon = useTranslations("Common");
    const [editing, setEditing] = useState(false);
    const [draft, setDraft] = useState("");
    const [saving, setSaving] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const start = () => {
        setDraft(value == null ? "" : String(value));
        setError(null);
        setEditing(true);
    };

    const save = async () => {
        const rent = moneyValueOrNull(draft, RENT_OPTIONS);
        if (rent == null) {
            setError(t("bulkEditRentInvalid"));
            return;
        }
        setSaving(true);
        setError(null);
        try {
            const res = await fetch(`/api/proxy/v1/units/${unitId}/expected-rent`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ expectedRent: rent }),
            });
            await throwIfNotOk(res);
            onSaved(rent);
            setEditing(false);
        } catch (err) {
            setError(serverText(tCommon, err));
        } finally {
            setSaving(false);
        }
    };

    if (!editing) {
        return (
            <span className="inline-flex items-center gap-1.5 justify-end">
                <span>{formatCurrencyCompact(value ?? 0)}</span>
                {canEdit && (
                    <button type="button" onClick={start} aria-label={t("editExpectedRent")} title={t("editExpectedRent")}
                        data-testid={`expected-rent-edit-${unitId}`}
                        className="p-1 text-muted hover:text-primary rounded cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
                        <Pencil size={12} />
                    </button>
                )}
            </span>
        );
    }

    return (
        <span className="inline-flex flex-col items-end gap-1">
            <span className="inline-flex items-center gap-1">
                <MoneyTextInput options={RENT_OPTIONS} autoFocus value={draft} onChange={setDraft}
                    aria-label={t("expectedRent")} data-testid={`expected-rent-input-${unitId}`}
                    onKeyDown={e => { if (e.key === "Enter") { e.preventDefault(); save(); } if (e.key === "Escape") setEditing(false); }}
                    className="w-28 bg-input border border-border rounded-lg p-1.5 text-xs text-end focus:ring-2 focus:ring-primary/30 focus:outline-none" />
                <button type="button" onClick={save} disabled={saving} aria-label={t("save")} data-testid={`expected-rent-save-${unitId}`}
                    className="p-1 text-success rounded cursor-pointer disabled:opacity-50 focus:ring-2 focus:ring-primary/30 focus:outline-none">
                    <Check size={14} />
                </button>
                <button type="button" onClick={() => setEditing(false)} aria-label={t("cancel")}
                    className="p-1 text-muted rounded cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
                    <X size={14} />
                </button>
            </span>
            {error && <span role="alert" className="text-[10px] text-error font-medium">{error}</span>}
        </span>
    );
}
