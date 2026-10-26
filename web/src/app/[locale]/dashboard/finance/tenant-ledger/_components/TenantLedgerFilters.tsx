"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Filter, X } from "lucide-react";
import AccountPicker, { loadAccounts } from "@/components/finance/AccountPicker";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { LeasePicker } from "@/components/pickers/LeasePicker";
import { RenterPicker } from "@/components/pickers/RenterPicker";
import type { Account } from "@/lib/api/ledger";
import { reportsApi, type ReceivableAccount, type TenantLedgerQuery } from "@/lib/api/reports";

const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

type Props = {
    value: TenantLedgerQuery;
    onChange: (next: TenantLedgerQuery) => void;
    /**
     * Never disabled while a page loads: applying again supersedes (and aborts)
     * the request in flight, so a slow report never locks the bar.
     */
    onApply: () => void;
};

/**
 * The receivable view's filter bar (PACT report spec §2, "Rules"): period,
 * property, the receivable accounts (every one by default; pick some as chips,
 * or add any other account so the screen works as a party ledger), an optional
 * tenant and contract, "only tenants with a balance" and "hide reversed pairs".
 *
 * Controlled: the view owns the draft and applies it; this only edits it.
 */
export default function TenantLedgerFilters({ value, onChange, onApply }: Props) {
    const t = useTranslations("Reports");
    const tLedger = useTranslations("Ledger");
    const locale = useLocale();
    const properties = useNameLookup("properties");
    const [receivable, setReceivable] = useState<ReceivableAccount[]>([]);
    const [others, setOthers] = useState<Account[]>([]);

    // The receivable leaves on offer follow the property picked in the bar.
    useEffect(() => {
        let alive = true;
        reportsApi.tenantLedger.accounts(value.propertyId).then(
            rows => { if (alive) setReceivable(rows); },
            () => { if (alive) setReceivable([]); },
        );
        return () => { alive = false; };
    }, [value.propertyId]);

    // Names for a non-receivable pick (the party-ledger case); module-cached, one GET per page load.
    useEffect(() => {
        let alive = true;
        loadAccounts().then(
            rows => { if (alive) setOthers(rows); },
            () => { if (alive) setOthers([]); },
        );
        return () => { alive = false; };
    }, []);

    const selected = value.accountIds ?? [];
    const known = (id: string): { code: string; name: string; propertyId: string | null } | undefined => {
        const r = receivable.find(a => a.id === id);
        if (r) return { code: r.code, name: locale === "ar" ? r.nameAr || r.name : r.name, propertyId: r.propertyId };
        const o = others.find(a => a.id === id);
        return o ? { code: o.code, name: o.name, propertyId: o.propertyId } : undefined;
    };
    const accountLabel = (id: string) => {
        const a = known(id);
        return a ? `${a.code} — ${a.name}` : id;
    };

    const addAccount = (id: string) => {
        if (!id || selected.includes(id)) return;
        onChange({ ...value, accountIds: [...selected, id] });
    };
    const removeAccount = (id: string) => {
        const next = selected.filter(a => a !== id);
        onChange({ ...value, accountIds: next.length ? next : undefined });
    };

    /**
     * Picking a property drops the account picks that belong to another one
     * (they could only empty the report); organisation-wide accounts and picks
     * not yet known stay. Clearing the property keeps every pick.
     */
    const withProperty = (propertyId: string | undefined): TenantLedgerQuery => {
        if (!propertyId || selected.length === 0) return { ...value, propertyId };
        const keep = selected.filter(id => {
            const a = known(id);
            return !a || a.propertyId == null || a.propertyId === propertyId;
        });
        return { ...value, propertyId, accountIds: keep.length ? keep : undefined };
    };

    const offered = receivable.filter(a => !selected.includes(a.id));

    return (
        <div className="bg-surface border border-border rounded-xl shadow-sm p-4 mb-6" data-testid="tl-filters">
            <div className="flex flex-wrap items-end gap-4">
                <div>
                    <label className={label} htmlFor="tl-from">{tLedger("from")}</label>
                    <input
                        id="tl-from"
                        type="date"
                        className={field}
                        value={value.from ?? ""}
                        onChange={ev => onChange({ ...value, from: ev.target.value })}
                    />
                </div>
                <div>
                    <label className={label} htmlFor="tl-to">{tLedger("to")}</label>
                    <input
                        id="tl-to"
                        type="date"
                        className={field}
                        value={value.to ?? ""}
                        onChange={ev => onChange({ ...value, to: ev.target.value })}
                    />
                </div>

                <div className="w-full sm:w-auto">
                    <label className={label} htmlFor="tl-property">{tLedger("propertyFilter")}</label>
                    <select
                        id="tl-property"
                        className={`${field} w-full sm:w-auto sm:min-w-[12rem]`}
                        value={value.propertyId ?? ""}
                        onChange={ev => onChange(withProperty(ev.target.value || undefined))}
                    >
                        <option value="">{tLedger("selectProperty")}</option>
                        {properties.options.map(p => (
                            <option key={p.id} value={p.id}>{p.label}</option>
                        ))}
                    </select>
                </div>

                <div className="w-full sm:w-auto">
                    <label className={label} htmlFor="tl-accounts">{t("tlReceivableAccounts")}</label>
                    <select
                        id="tl-accounts"
                        className={`${field} w-full sm:w-auto sm:min-w-[14rem]`}
                        value=""
                        onChange={ev => addAccount(ev.target.value)}
                    >
                        <option value="">{selected.length ? t("tlAddReceivable") : t("tlAllReceivable")}</option>
                        {offered.map(a => (
                            <option key={a.id} value={a.id}>{accountLabel(a.id)}</option>
                        ))}
                    </select>
                </div>

                <div className="w-full sm:w-auto sm:min-w-[14rem]">
                    <span className={label}>{t("tlOtherAccount")}</span>
                    <AccountPicker value={null} onChange={addAccount} propertyId={value.propertyId ?? null} placeholder={t("tlOtherAccount")} />
                </div>

                <div className="w-full sm:w-auto">
                    <label className={label} htmlFor="tl-renter">{t("tlTenantOptional")}</label>
                    <RenterPicker
                        id="tl-renter"
                        testId="tl-renter-filter"
                        className={`${field} w-full sm:w-auto sm:min-w-[14rem]`}
                        value={value.renterId ?? ""}
                        onChange={id => onChange({ ...value, renterId: id || undefined })}
                        placeholder={tLedger("selectRenter")}
                    />
                </div>

                <div className="w-full sm:w-auto">
                    <label className={label} htmlFor="tl-lease">{t("tlContract")}</label>
                    <LeasePicker
                        id="tl-lease"
                        testId="tl-lease-filter"
                        className={`${field} w-full sm:w-auto sm:min-w-[14rem]`}
                        value={value.leaseId ?? ""}
                        onChange={id => onChange({ ...value, leaseId: id || undefined })}
                    />
                </div>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 mt-4">
                <div className="flex flex-wrap gap-x-5 gap-y-2">
                    <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground cursor-pointer">
                        <input
                            type="checkbox"
                            className="accent-primary"
                            checked={!!value.onlyWithBalance}
                            onChange={ev => onChange({ ...value, onlyWithBalance: ev.target.checked })}
                        />
                        {t("tlOnlyWithBalance")}
                    </label>
                    <label className="inline-flex items-center gap-2 text-xs font-medium text-foreground cursor-pointer">
                        <input
                            type="checkbox"
                            className="accent-primary"
                            checked={!!value.hideReversed}
                            onChange={ev => onChange({ ...value, hideReversed: ev.target.checked })}
                        />
                        {t("tlHideReversed")}
                    </label>
                </div>
                <button
                    type="button"
                    onClick={onApply}
                    className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                >
                    <Filter size={13} aria-hidden />
                    {tLedger("apply")}
                </button>
            </div>

            {selected.length > 0 && (
                <div className="flex flex-wrap gap-2 mt-3" data-testid="tl-account-chips">
                    {selected.map(id => (
                        <span
                            key={id}
                            className="inline-flex max-w-full items-center gap-1.5 bg-primary/10 text-primary border border-primary/20 rounded-lg px-2.5 py-1 text-[11px] font-semibold"
                        >
                            <bdi className="min-w-0 break-words">{accountLabel(id)}</bdi>
                            <button
                                type="button"
                                onClick={() => removeAccount(id)}
                                aria-label={t("tlRemoveAccount", { name: accountLabel(id) })}
                                className="shrink-0 cursor-pointer hover:text-error"
                            >
                                <X size={11} aria-hidden />
                            </button>
                        </span>
                    ))}
                </div>
            )}
        </div>
    );
}
