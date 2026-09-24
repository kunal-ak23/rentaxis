"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { loadAccounts } from "./AccountPicker";
import { bankRecApi } from "@/lib/api/bankRec";

/**
 * F14-36: the "Pay from" field on a deposit-refund BPV. Narrower than
 * {@link SettlementAccountPicker}'s plain BANK/CASH-subtype filter: a BANK
 * leaf only qualifies once some bank account actually owns it (reconciled
 * against a real statement, per F14-55's ownership check) — any cash leaf
 * still qualifies outright, since cash has no statement to reconcile
 * against.
 */
export default function RefundPaymentAccountPicker({
    value,
    onChange,
    disabled,
}: {
    value: string | null;
    onChange: (id: string) => void;
    disabled?: boolean;
}) {
    const t = useTranslations("Vouchers");
    const [options, setOptions] = useState<{ id: string; code: string; name: string }[]>([]);

    useEffect(() => {
        let alive = true;
        Promise.all([loadAccounts(), bankRecApi.accounts().catch(() => [])]).then(([accounts, bankRows]) => {
            if (!alive) return;
            const ownedLeafIds = new Set(bankRows.flatMap(b => b.leaves.map(l => l.id)));
            setOptions(
                accounts
                    .filter(a => !a.group && a.active && a.accountType === "ASSET"
                        && (a.accountSubType === "CASH" || (a.accountSubType === "BANK" && ownedLeafIds.has(a.id))))
                    .map(a => ({ id: a.id, code: a.code, name: a.name })),
            );
        });
        return () => {
            alive = false;
        };
    }, []);

    return (
        <select
            data-testid="refund-payment-account"
            aria-label={t("paymentAccount")}
            className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200 disabled:opacity-60 disabled:cursor-not-allowed"
            disabled={disabled}
            value={value ?? ""}
            onChange={e => onChange(e.target.value)}
        >
            <option value="" />
            {options.map(o => (
                <option key={o.id} value={o.id}>
                    {o.code} {o.name}
                </option>
            ))}
        </select>
    );
}
