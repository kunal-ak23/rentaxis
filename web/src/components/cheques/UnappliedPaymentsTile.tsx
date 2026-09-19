"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { ChevronDown, ChevronUp } from "lucide-react";
import { fmtAmount } from "@/lib/api/ledger";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { ApiError, onlinePayApi, type UnappliedOnlinePayment, type UnappliedOnlinePaymentCount } from "@/lib/api/leasing";

/**
 * "Online payments to refund" — captured gateway payments whose cheque row
 * never cleared, so finance owes the renter a refund (Ruling §5).
 *
 * The count tile is the register's own summary-tile language; opening it
 * lazily fetches the list on first expand rather than on every register
 * page load, since most visits to the register are not about a refund
 * backlog. A failed count fetch hides the tile rather than surfacing an
 * error banner — this is a secondary worklist, not the register's own data,
 * and the register must still work when it is unreachable.
 */

const th = "text-start px-3 py-2 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-3 py-2 text-xs";

type Props = {
    /** SA/TA/ACCOUNTANT — reuses `canCancelCheques`'s exact role set. */
    visible: boolean;
};

export default function UnappliedPaymentsTile({ visible }: Props) {
    const t = useTranslations("Cheques");
    const tl = useTranslations("Leasing");
    const tLedger = useTranslations("Ledger");
    const locale = useLocale();

    const [summary, setSummary] = useState<UnappliedOnlinePaymentCount | null>(null);
    const [open, setOpen] = useState(false);
    const [rows, setRows] = useState<UnappliedOnlinePayment[]>([]);
    const [loading, setLoading] = useState(false);
    const [listError, setListError] = useState<string | null>(null);

    useEffect(() => {
        if (!visible) {
            setSummary(null);
            return;
        }
        onlinePayApi
            .unappliedCount()
            .then(setSummary)
            // Best-effort: the backend endpoint may not be reachable yet, and
            // that must never take the whole register page down with it.
            .catch(() => setSummary(null));
    }, [visible]);

    if (!visible || !summary || summary.count === 0) return null;

    const toggle = async () => {
        const next = !open;
        setOpen(next);
        if (!next || rows.length > 0) return;
        setLoading(true);
        setListError(null);
        try {
            const page = await onlinePayApi.unapplied({ size: 100 });
            setRows(page.content ?? []);
        } catch (e) {
            setListError(e instanceof ApiError ? e.message : t("unappliedLoadFailed"));
        } finally {
            setLoading(false);
        }
    };

    return (
        <div data-testid="unapplied-payments-tile" className="bg-surface border border-border rounded-xl px-5 py-4 mb-4">
            <button
                type="button"
                data-testid="unapplied-payments-toggle"
                onClick={toggle}
                className="w-full flex items-center justify-between text-start cursor-pointer"
            >
                <div>
                    <div className="text-[10.5px] text-muted uppercase tracking-wider font-semibold">{t("onlinePaymentsToRefund")}</div>
                    <div className="font-mono text-[16px] font-bold text-foreground tabular-nums">{fmtAmount(summary.totalAmount)}</div>
                    <div className="text-[10.5px] text-muted">{summary.count}</div>
                </div>
                {open ? <ChevronUp size={16} className="text-muted shrink-0" /> : <ChevronDown size={16} className="text-muted shrink-0" />}
            </button>

            {open && (
                <div className="mt-4 border-t border-border pt-4">
                    {loading ? (
                        <div className="text-xs text-muted py-4 text-center">…</div>
                    ) : listError ? (
                        <p className="text-[11px] text-error" data-testid="unapplied-payments-error">{listError}</p>
                    ) : (
                        <div className="overflow-x-auto">
                            <table className="w-full min-w-[880px]">
                                <thead>
                                    <tr className="bg-input/50">
                                        <th className={th}>{t("tenant")}</th>
                                        <th className={th}>{tLedger("propertyFilter")}</th>
                                        <th className={th}>{tl("unit")}</th>
                                        <th className={th}>{tl("contractNumber")}</th>
                                        <th className={th}>{tl("chequeNo")}</th>
                                        <th className={`${th} text-end`}>{tl("amount")}</th>
                                        <th className={th}>{t("gatewayPaymentId")}</th>
                                        <th className={th}>{t("failureReason")}</th>
                                        <th className={th}>{t("capturedAt")}</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {rows.map(r => (
                                        <tr key={r.id} data-testid={`unapplied-payments-row-${r.id}`} className="border-t border-border">
                                            <td className={td}>{r.renterName || "—"}</td>
                                            <td className={`${td} text-muted`}>{r.propertyName || "—"}</td>
                                            <td className={td}>{r.unitIdentifier || "—"}</td>
                                            <td className={td}>{r.displayContractNumber || "—"}</td>
                                            <td className={td}>{r.chequeNumber || "—"}</td>
                                            <td className={`${td} text-end tabular-nums font-semibold`}>{fmtAmount(r.amount)}</td>
                                            <td className={`${td} text-muted`}>{r.gatewayPaymentId || "—"}</td>
                                            <td className={td}>{r.failureReason || "—"}</td>
                                            <td className={`${td} text-muted`}>{r.capturedAt ? fmtIsoDate(r.capturedAt, locale) : "—"}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
