"use client";

import { useEffect, useMemo, useState } from "react";
import { ledgerApi, type Account, type AccountSubType } from "@/lib/api/ledger";

/**
 * The chart of accounts is small (hundreds of rows), tenant-wide and changes
 * only when someone edits it, so every picker on a page shares one in-flight
 * promise instead of issuing its own GET. `invalidateAccounts()` drops the
 * cache after anything that creates or renames accounts (generate, create,
 * import) so the next picker mount re-fetches.
 */
let cache: Promise<Account[]> | null = null;

export function loadAccounts(): Promise<Account[]> {
    if (!cache) {
        cache = ledgerApi.accounts.list().catch(err => {
            // A failed load must not poison the cache forever.
            cache = null;
            throw err;
        });
    }
    return cache;
}

export function invalidateAccounts() {
    cache = null;
}

type Props = {
    value: string | null;
    onChange: (id: string) => void;
    accountType?: string;
    /**
     * Narrow further than `accountType` — the sub-types a field can actually
     * hold. A cheque's debit account is the case that needs it: the server
     * accepts only BANK or CASH (see {@link SettlementAccountPicker}).
     */
    accountSubTypes?: AccountSubType[];
    /** Only postable (non-group) accounts. Default: true. */
    leafOnly?: boolean;
    /** Only group (non-postable) accounts — the mirror of `leafOnly`, for parent pickers. */
    groupOnly?: boolean;
    /** Restrict to tenant-wide accounts plus the ones tagged to this property. */
    propertyId?: string | null;
    placeholder?: string;
    autoFocus?: boolean;
    disabled?: boolean;
};

export default function AccountPicker({
    value,
    onChange,
    accountType,
    accountSubTypes,
    leafOnly = true,
    groupOnly = false,
    propertyId,
    placeholder,
    autoFocus,
    disabled,
}: Props) {
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [q, setQ] = useState("");

    useEffect(() => {
        loadAccounts().then(setAccounts).catch(() => setAccounts([]));
    }, []);

    const options = useMemo(
        () =>
            accounts
                .filter(a => (groupOnly ? a.group : !leafOnly || !a.group))
                .filter(a => a.active)
                .filter(a => !accountType || a.accountType === accountType)
                .filter(a => !accountSubTypes || (a.accountSubType !== null && accountSubTypes.includes(a.accountSubType)))
                .filter(a => !propertyId || a.propertyId === null || a.propertyId === propertyId)
                .filter(a => !q || `${a.code} ${a.name} ${a.alias ?? ""}`.toLowerCase().includes(q.toLowerCase()))
                .slice(0, 50),
        [accounts, q, accountType, accountSubTypes, leafOnly, groupOnly, propertyId],
    );

    const selected = accounts.find(a => a.id === value);

    return (
        <div className="relative">
            <input
                autoFocus={autoFocus}
                disabled={disabled}
                aria-label={placeholder}
                className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200 disabled:opacity-60 disabled:cursor-not-allowed"
                placeholder={placeholder}
                value={q || (selected ? `${selected.code} — ${selected.name}` : "")}
                onChange={ev => setQ(ev.target.value)}
                onFocus={() => setQ("")}
            />
            {q && (
                <ul className="absolute z-20 mt-1 w-full max-h-64 overflow-auto bg-surface border border-border rounded-lg shadow-lg">
                    {options.map(a => (
                        <li key={a.id}>
                            <button
                                type="button"
                                className="w-full text-start px-3 py-2 text-xs hover:bg-input cursor-pointer"
                                onClick={() => {
                                    onChange(a.id);
                                    setQ("");
                                }}
                            >
                                <span className="font-mono text-muted me-2">{a.code}</span>
                                {a.name}
                            </button>
                        </li>
                    ))}
                    {options.length === 0 && <li className="px-3 py-2 text-xs text-muted">—</li>}
                </ul>
            )}
        </div>
    );
}
