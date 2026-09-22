"use client";

import AccountPicker from "./AccountPicker";
import type { AccountSubType } from "@/lib/api/ledger";

/**
 * The debit account of a cheque — the account the money settles into.
 *
 * Mirrors `ChequeService.requireSettlementAccount`
 * (backend/src/main/java/com/datagami/rentaxis/core/service/cheque/ChequeService.java:1083-1093):
 * an active, non-group ASSET leaf whose sub-type is BANK or CASH, and nothing
 * else — "Debit account 410200 must be a bank or cash account".
 *
 * One wrapper rather than the same two props repeated at seven call sites
 * (the draft grid and its generate form, the extension grid, and the deposit,
 * bounce-era, receive, replace and single-row action dialogs). Every one of
 * them used to pass `leafOnly` alone and so offered income, liability and
 * expense leaves for a field that can only ever hold a bank or a cash account.
 */
const SETTLEMENT_SUB_TYPES: AccountSubType[] = ["BANK", "CASH"];

type Props = {
    value: string | null;
    onChange: (id: string) => void;
    /** Tenant-wide accounts plus the ones tagged to this property. */
    propertyId?: string | null;
    placeholder?: string;
    disabled?: boolean;
};

export default function SettlementAccountPicker({ value, onChange, propertyId, placeholder, disabled }: Props) {
    return (
        <AccountPicker
            value={value}
            onChange={onChange}
            accountType="ASSET"
            accountSubTypes={SETTLEMENT_SUB_TYPES}
            leafOnly
            propertyId={propertyId}
            placeholder={placeholder}
            disabled={disabled}
        />
    );
}
