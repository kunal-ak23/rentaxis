"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { ExternalLink, Loader2, X } from "lucide-react";
import { Link } from "@/i18n/routing";
import { ApiError } from "@/lib/api/facilities";
import { accountName, fmtAmount } from "@/lib/api/ledger";
import { propertyReportsApi, type PnlLines } from "@/lib/api/propertyReports";
import type { DrillTarget } from "./PnlTable";

const th = "text-start px-3 py-2 text-[10px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-1.5 text-xs";
const num = `${td} text-end tabular-nums`;

/**
 * The journal lines behind one P&L figure, served by the P&L endpoint itself
 * (`/property-pl/lines`) so a property manager — whom the general ledger refuses —
 * drills within the same scope. For a tenant-wide caller on a property column it
 * also offers the general ledger with `effectiveProperty=true`, the same rule.
 */
export default function DrillDrawer({
    target,
    from,
    to,
    locale,
    canOpenLedger,
    onClose,
}: {
    target: DrillTarget;
    from: string;
    to: string;
    locale: string;
    canOpenLedger: boolean;
    onClose: () => void;
}) {
    const t = useTranslations("PropertyReports");
    const [data, setData] = useState<PnlLines | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        // The page keys this drawer by its target, so a new figure mounts a fresh
        // drawer rather than resetting this one's state from inside the effect.
        let live = true;
        propertyReportsApi
            .lines({ from, to, column: target.column.key, accountIds: target.accountIds })
            .then(d => live && setData(d))
            .catch(e => live && setError(e instanceof ApiError ? e.message : String(e)));
        return () => {
            live = false;
        };
    }, [target, from, to]);

    const ledgerHref = target.column.propertyId
        ? `/dashboard/finance/general-ledger?${new URLSearchParams({
              accountIds: target.accountIds.join(","),
              propertyId: target.column.propertyId,
              from,
              to,
              effectiveProperty: "true",
          }).toString()}`
        : null;

    return (
        <div className="fixed inset-0 z-50 flex justify-end bg-black/30" role="dialog" aria-modal="true" aria-label={t("drillTitle")}>
            <div className="h-full w-full max-w-3xl bg-surface border-s border-border shadow-xl flex flex-col">
                <div className="flex items-start justify-between gap-3 p-4 border-b border-border">
                    <div>
                        <h2 className="text-sm font-bold text-foreground">{t("drillTitle")}</h2>
                        <p className="text-xs text-muted">
                            {target.label} · <bdi dir="ltr">{from} – {to}</bdi>
                        </p>
                    </div>
                    <div className="flex items-center gap-2">
                        {canOpenLedger && ledgerHref && (
                            <Link href={ledgerHref} className="flex items-center gap-1 text-xs text-primary hover:underline">
                                <ExternalLink size={12} />
                                {t("openInLedger")}
                            </Link>
                        )}
                        <button type="button" onClick={onClose} aria-label={t("close")}
                            className="p-1.5 rounded-lg hover:bg-input cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/20">
                            <X size={16} />
                        </button>
                    </div>
                </div>
                <div className="flex-1 overflow-auto">
                    {error && <p role="alert" className="p-4 text-xs text-error">{error}</p>}
                    {!data && !error && (
                        <div className="flex justify-center p-8"><Loader2 size={20} className="animate-spin text-muted" /></div>
                    )}
                    {data && data.lines.length === 0 && <p className="p-4 text-xs text-muted">{t("noLines")}</p>}
                    {data && data.lines.length > 0 && (
                        <table className="w-full min-w-[560px]" data-testid="drill-lines">
                            <thead>
                                <tr className="bg-input/50">
                                    <th className={th}>{t("date")}</th>
                                    <th className={th}>{t("entry")}</th>
                                    <th className={th}>{t("account")}</th>
                                    <th className={th}>{t("narration")}</th>
                                    <th className={`${th} text-end`}>{t("debit")}</th>
                                    <th className={`${th} text-end`}>{t("credit")}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {data.lines.map((l, i) => (
                                    <tr key={`${l.entryId}-${i}`} className="border-t border-border">
                                        <td className={td}><bdi dir="ltr">{l.entryDate}</bdi></td>
                                        <td className={td}>
                                            {canOpenLedger ? (
                                                <Link href={`/dashboard/finance/journals/${l.entryId}`} className="text-primary hover:underline font-mono">
                                                    {l.entryNumber}
                                                </Link>
                                            ) : (
                                                <span className="font-mono">{l.entryNumber}</span>
                                            )}
                                            <span className="ms-1 text-muted">{l.docType}</span>
                                        </td>
                                        <td className={td}>{accountName({ name: l.accountName, nameAr: l.accountNameAr }, locale)}</td>
                                        <td className={`${td} text-muted`}>{l.narration}</td>
                                        <td className={num}><bdi dir="ltr">{l.debit ? fmtAmount(l.debit) : ""}</bdi></td>
                                        <td className={num}><bdi dir="ltr">{l.credit ? fmtAmount(l.credit) : ""}</bdi></td>
                                    </tr>
                                ))}
                                <tr className="border-t-2 border-border font-semibold">
                                    <td className={td} colSpan={4} />
                                    <td className={num}><bdi dir="ltr">{fmtAmount(data.totalDebit)}</bdi></td>
                                    <td className={num}><bdi dir="ltr">{fmtAmount(data.totalCredit)}</bdi></td>
                                </tr>
                            </tbody>
                        </table>
                    )}
                    {data?.truncated && <p className="p-4 text-xs text-warning">{t("truncated", { count: data.lines.length })}</p>}
                </div>
            </div>
        </div>
    );
}
