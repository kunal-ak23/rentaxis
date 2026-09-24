"use client";

import { useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Landmark, Lock } from "lucide-react";
import { bankRecApi, dmy, type BankAccountRow } from "@/lib/api/bankRec";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { td, th } from "./styles";

/**
 * Settings → Fiscal & period lock: each bank account's reconciled-through date
 * next to the tenant lock, for orientation (finance-ops spec §4). Both locks
 * apply independently; the stricter one wins on a bank leaf.
 */
export function BankLocksCard() {
    const t = useTranslations("BankRec");
    const { data: session } = useSession();
    const allowed = hasPermission(session?.user?.role as UserRole | undefined, "canReconcileBank");
    const [rows, setRows] = useState<BankAccountRow[] | null>(null);
    useEffect(() => {
        if (allowed) bankRecApi.accounts().then(r => setRows(Array.isArray(r) ? r : null)).catch(() => setRows(null));
    }, [allowed]);
    if (!allowed || !rows || rows.length === 0) return null;
    return (
        <div className="bg-surface border border-border rounded-xl p-4 md:p-5" data-testid="bank-locks">
            <h2 className="text-sm font-bold flex items-center gap-2 mb-1"><Landmark size={14} />{t("bankLocksTitle")}</h2>
            <p className="text-xs text-muted mb-3">{t("bankLocksHint")}</p>
            <table className="w-full">
                <thead className="bg-input"><tr>
                    <th className={th}>{t("bankAccount")}</th><th className={th}>{t("reconciledThrough")}</th>
                </tr></thead>
                <tbody className="divide-y divide-border">
                    {rows.map(r => (
                        <tr key={r.id}>
                            <td className={td}>{r.bankName} <bdi dir="ltr" className="text-muted">{r.accountNumber}</bdi></td>
                            <td className={td}>{r.reconciledThrough
                                ? <span className="inline-flex items-center gap-1"><Lock size={11} /><bdi dir="ltr">{dmy(r.reconciledThrough)}</bdi></span>
                                : t("notReconciled")}</td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
