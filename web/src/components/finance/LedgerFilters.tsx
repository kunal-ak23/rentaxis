"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Filter, X } from "lucide-react";
import AccountPicker, { loadAccounts } from "./AccountPicker";
import { useNameLookup } from "./useNameLookup";
import type { Account, LedgerQuery } from "@/lib/api/ledger";

const pad = (n: number) => String(n).padStart(2, "0");
const iso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

/**
 * The backend defaults an unfiltered general ledger to the current month, but the
 * UI still sends explicit dates so that what the report covers is visible in the
 * bar rather than implied by a server default the user cannot see.
 */
export function defaultLedgerRange(): { from: string; to: string } {
    const now = new Date();
    return { from: iso(new Date(now.getFullYear(), now.getMonth(), 1)), to: iso(now) };
}

const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type Props = {
    value: LedgerQuery;
    onChange: (next: LedgerQuery) => void;
    onApply?: () => void;
    showAccounts?: boolean;
    showProperty?: boolean;
    showRenter?: boolean;
    /** Disables Apply while a report is in flight. */
    busy?: boolean;
};

/**
 * Controlled: the page owns the query so it can seed it from the URL and hand the
 * same object to the API client. This component only edits it and says when to run.
 */
export default function LedgerFilters({
    value,
    onChange,
    onApply,
    showAccounts = false,
    showProperty = false,
    showRenter = false,
    busy = false,
}: Props) {
    const t = useTranslations("Ledger");
    const properties = useNameLookup("properties", showProperty);
    const renters = useNameLookup("renters", showRenter);
    const [accounts, setAccounts] = useState<Account[]>([]);

    useEffect(() => {
        if (!showAccounts) return;
        loadAccounts()
            .then(setAccounts)
            .catch(() => setAccounts([]));
    }, [showAccounts]);

    const selected = value.accountIds ?? [];

    const addAccount = (id: string) => {
        if (selected.includes(id)) return;
        onChange({ ...value, accountIds: [...selected, id] });
    };
    const removeAccount = (id: string) => {
        const next = selected.filter(a => a !== id);
        onChange({ ...value, accountIds: next.length ? next : undefined });
    };

    const chipLabel = (id: string) => {
        const a = accounts.find(x => x.id === id);
        return a ? `${a.code} — ${a.name}` : id;
    };

    return (
        <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6">
            <div className="flex flex-wrap items-end gap-4">
                <div>
                    <label className={label} htmlFor="ledger-from">{t("from")}</label>
                    <input
                        id="ledger-from"
                        type="date"
                        className={field}
                        value={value.from ?? ""}
                        onChange={ev => onChange({ ...value, from: ev.target.value })}
                    />
                </div>
                <div>
                    <label className={label} htmlFor="ledger-to">{t("to")}</label>
                    <input
                        id="ledger-to"
                        type="date"
                        className={field}
                        value={value.to ?? ""}
                        onChange={ev => onChange({ ...value, to: ev.target.value })}
                    />
                </div>

                {showProperty && (
                    <div>
                        <label className={label} htmlFor="ledger-property">{t("propertyFilter")}</label>
                        <select
                            id="ledger-property"
                            className={`${field} min-w-[12rem]`}
                            value={value.propertyId ?? ""}
                            onChange={ev => onChange({ ...value, propertyId: ev.target.value || undefined })}
                        >
                            <option value="">{t("selectProperty")}</option>
                            {properties.options.map(p => (
                                <option key={p.id} value={p.id}>{p.label}</option>
                            ))}
                        </select>
                    </div>
                )}

                {showRenter && (
                    <div>
                        <label className={label} htmlFor="ledger-renter">{t("tenant")}</label>
                        <select
                            id="ledger-renter"
                            className={`${field} min-w-[14rem]`}
                            value={value.renterId ?? ""}
                            onChange={ev => onChange({ ...value, renterId: ev.target.value || undefined })}
                        >
                            <option value="">{t("selectRenter")}</option>
                            {renters.options.map(r => (
                                <option key={r.id} value={r.id}>{r.label}</option>
                            ))}
                        </select>
                    </div>
                )}

                {showAccounts && (
                    <div className="min-w-[18rem] flex-1">
                        <label className={label}>{t("accounts")}</label>
                        <AccountPicker value={null} onChange={addAccount} placeholder={t("allAccountsWithActivity")} />
                    </div>
                )}

                <button
                    type="button"
                    onClick={onApply}
                    disabled={busy}
                    className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer disabled:opacity-50 focus:ring-2 focus:ring-primary/20 focus:outline-none"
                >
                    <Filter size={13} />
                    {t("apply")}
                </button>
            </div>

            {showAccounts && selected.length > 0 && (
                <div className="flex flex-wrap gap-2 mt-3">
                    {selected.map(id => (
                        <span
                            key={id}
                            className="inline-flex items-center gap-1.5 bg-primary/10 text-primary border border-primary/20 rounded-lg px-2.5 py-1 text-[11px] font-semibold"
                        >
                            {chipLabel(id)}
                            <button
                                type="button"
                                onClick={() => removeAccount(id)}
                                aria-label={`${t("removeLine")} ${chipLabel(id)}`}
                                className="cursor-pointer hover:text-error"
                            >
                                <X size={11} />
                            </button>
                        </span>
                    ))}
                </div>
            )}
        </div>
    );
}
