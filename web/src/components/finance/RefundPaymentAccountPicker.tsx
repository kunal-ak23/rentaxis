"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { loadAccounts } from "./AccountPicker";
import { bankRecApi } from "@/lib/api/bankRec";

/**
 * F14-36: the "Pay from" field on a deposit-refund BPV — cash, or a bank leaf,
 * by the server's own rule (`VoucherService`'s refund checks, `OwnedBankLeaf`):
 * - any active cash leaf qualifies, since cash has no statement to reconcile against;
 * - once the organisation has set up a bank account in Bank Reconciliation, a bank
 *   leaf qualifies only if some bank account owns it (F14-55's ownership check);
 * - before any has, every active bank leaf qualifies. Requiring an owner then left
 *   cash as the only choice while the voucher defaults to a bank transfer, so a
 *   refund could not be paid from a bank at all (client report 2026-10-29).
 *
 * A saved choice the rule no longer offers (a draft from before a bank account was
 * set up) stays visible, so Post never sends an account the user cannot see; the
 * server refuses it with its reason.
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
    const [byId, setById] = useState<Record<string, { code: string; name: string }>>({});

    useEffect(() => {
        let alive = true;
        Promise.all([loadAccounts(), bankRecApi.accounts().catch(() => [])]).then(([accounts, bankRows]) => {
            if (!alive) return;
            setById(Object.fromEntries(accounts.map(a => [a.id, { code: a.code, name: a.name }])));
            const ownedLeafIds = new Set(bankRows.flatMap(b => b.leaves.map(l => l.id)));
            const bankQualifies = (id: string) => ownedLeafIds.size === 0 || ownedLeafIds.has(id);
            setOptions(
                accounts
                    .filter(a => !a.group && a.active && a.accountType === "ASSET"
                        && (a.accountSubType === "CASH" || (a.accountSubType === "BANK" && bankQualifies(a.id))))
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
            {value && byId[value] && !options.some(o => o.id === value) && (
                <option value={value}>
                    {byId[value].code} {byId[value].name}
                </option>
            )}
        </select>
    );
}
