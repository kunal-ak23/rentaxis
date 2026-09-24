"use client";

import { useState, type ReactNode } from "react";
import { useTranslations } from "next-intl";
import { useNameLookup } from "@/components/finance/useNameLookup";
import {
    periodRange,
    type AllocateBasis,
    type Compare,
    type PeriodKind,
} from "@/lib/api/propertyReports";

export type PnlControlsValue = {
    kind: PeriodKind;
    from: string;
    to: string;
    propertyIds: string[];
    compare: Compare;
    allocate: AllocateBasis;
};

const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

const COMPARES: Compare[] = ["NONE", "PREVIOUS", "LAST_YEAR"];
const BASES: AllocateBasis[] = ["NONE", "UNITS", "RENT", "EQUAL"];

/**
 * The P&L's controls: a period picker (month, quarter, year, custom), a property
 * multi-select, Compare and Allocate unassigned. Edits stay in a draft until
 * Apply, so changing a date does not re-query on every keystroke. A property
 * manager gets no allocation control: the Unassigned column it spreads is a
 * tenant-wide figure the server does not send them.
 */
export default function PnlControls({
    initial,
    scoped,
    busy,
    applyIcon,
    onApply,
    singleProperty = false,
}: {
    initial: PnlControlsValue;
    scoped: boolean;
    busy: boolean;
    applyIcon?: ReactNode;
    onApply: (v: PnlControlsValue) => void;
    /** The statement page picks exactly one property and has no compare / allocate. */
    singleProperty?: boolean;
}) {
    const t = useTranslations("PropertyReports");
    const properties = useNameLookup("properties");
    const [draft, setDraft] = useState<PnlControlsValue>(initial);

    const setKind = (kind: PeriodKind) => {
        if (kind === "custom") setDraft({ ...draft, kind });
        else setDraft({ ...draft, kind, ...periodRange(kind, draft.from) });
    };
    const setAnchor = (month: string) => {
        if (!month || draft.kind === "custom") return;
        setDraft({ ...draft, ...periodRange(draft.kind, `${month}-01`) });
    };
    const toggleProperty = (id: string) => {
        const has = draft.propertyIds.includes(id);
        setDraft({ ...draft, propertyIds: has ? draft.propertyIds.filter(p => p !== id) : [...draft.propertyIds, id] });
    };

    return (
        <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6">
            <div className="flex flex-wrap items-end gap-4">
                <div>
                    <label className={label} htmlFor="pnl-period">{t("period")}</label>
                    <select id="pnl-period" className={field} value={draft.kind}
                        onChange={e => setKind(e.target.value as PeriodKind)}>
                        <option value="month">{t("periodMonth")}</option>
                        <option value="quarter">{t("periodQuarter")}</option>
                        <option value="year">{t("periodYear")}</option>
                        <option value="custom">{t("periodCustom")}</option>
                    </select>
                </div>
                {draft.kind === "custom" ? (
                    <>
                        <div>
                            <label className={label} htmlFor="pnl-from">{t("from")}</label>
                            <input id="pnl-from" type="date" className={field} value={draft.from}
                                onChange={e => setDraft({ ...draft, from: e.target.value })} />
                        </div>
                        <div>
                            <label className={label} htmlFor="pnl-to">{t("to")}</label>
                            <input id="pnl-to" type="date" className={field} value={draft.to}
                                onChange={e => setDraft({ ...draft, to: e.target.value })} />
                        </div>
                    </>
                ) : (
                    <div>
                        <label className={label} htmlFor="pnl-anchor">{t("anchor")}</label>
                        <input id="pnl-anchor" type="month" className={field} value={draft.from.slice(0, 7)}
                            onChange={e => setAnchor(e.target.value)} />
                        <p className="text-[10px] text-muted mt-1"><bdi dir="ltr">{draft.from} – {draft.to}</bdi></p>
                    </div>
                )}

                {singleProperty ? (
                    <div>
                        <label className={label} htmlFor="pnl-property">{t("property")}</label>
                        <select id="pnl-property" className={`${field} min-w-[12rem]`} value={draft.propertyIds[0] ?? ""}
                            onChange={e => setDraft({ ...draft, propertyIds: e.target.value ? [e.target.value] : [] })}>
                            <option value="">{t("selectProperty")}</option>
                            {properties.options.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}
                        </select>
                    </div>
                ) : (
                    <details className="relative" data-testid="property-multiselect">
                        <summary className={`${field} list-none cursor-pointer min-w-[12rem]`}>
                            <span className={label + " inline"}>{t("properties")}: </span>
                            {draft.propertyIds.length === 0
                                ? t("allProperties")
                                : properties.options.filter(p => draft.propertyIds.includes(p.id)).map(p => p.label).join(", ")}
                        </summary>
                        <div className="absolute z-20 mt-1 max-h-64 overflow-auto bg-surface border border-border rounded-lg shadow-lg p-2 min-w-[14rem]">
                            {properties.options.map(p => (
                                <label key={p.id} className="flex items-center gap-2 px-2 py-1 text-xs cursor-pointer hover:bg-input rounded">
                                    <input type="checkbox" checked={draft.propertyIds.includes(p.id)} onChange={() => toggleProperty(p.id)} />
                                    {p.label}
                                </label>
                            ))}
                        </div>
                    </details>
                )}

                {!singleProperty && (
                    <div>
                        <label className={label} htmlFor="pnl-compare">{t("compare")}</label>
                        <select id="pnl-compare" className={field} value={draft.compare}
                            onChange={e => setDraft({ ...draft, compare: e.target.value as Compare })}>
                            {COMPARES.map(c => <option key={c} value={c}>{t(`compare${c}`)}</option>)}
                        </select>
                    </div>
                )}
                {!singleProperty && !scoped && (
                    <div>
                        <label className={label} htmlFor="pnl-allocate">{t("allocate")}</label>
                        <select id="pnl-allocate" className={field} value={draft.allocate}
                            onChange={e => setDraft({ ...draft, allocate: e.target.value as AllocateBasis })}>
                            {BASES.map(b => <option key={b} value={b}>{t(`allocate${b}`)}</option>)}
                        </select>
                    </div>
                )}
                <button
                    type="button"
                    onClick={() => onApply(draft)}
                    disabled={busy || (singleProperty && draft.propertyIds.length === 0)}
                    className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                >
                    {applyIcon}
                    {t("apply")}
                </button>
            </div>
        </div>
    );
}
