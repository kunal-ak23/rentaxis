"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import Link from "next/link";
import { Loader2 } from "lucide-react";
import LedgerTable from "@/components/finance/LedgerTable";
import { narrowLedgersToLease } from "@/components/finance/narrowLedger";
import { fmtAmount, ledgerApi, type AccountLedger, type JournalEntry } from "@/lib/api/ledger";
import { ApiError, leaseApi } from "@/lib/api/leasing";
import { fmtIsoDate } from "./leaseMath";

/**
 * Everything this contract has written to the books: the entries themselves,
 * and the renter's ledger narrowed to the lease.
 *
 * Both, not one: the entry list answers "what was posted and when", the ledger
 * answers "and where did it land". An accountant checking a contract against
 * PACT asks both questions in the same breath, and the journal list alone
 * would send them to the tenant-ledger page with a filter to re-enter.
 *
 * Links use `next/link` with an explicit locale prefix rather than the
 * `@/i18n/routing` wrapper, which vitest cannot resolve outside a Next runtime
 * — the same workaround the plan-1 journal pages carry.
 */

const th = "text-start px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-1.5 text-xs";

type Props = {
    leaseId: string;
    renterId: string;
    renterName?: string | null;
};

export default function LeaseJournalsTab({ leaseId, renterId, renterName }: Props) {
    const t = useTranslations("Ledger");
    const tl = useTranslations("Leasing");
    const locale = useLocale();

    const [entries, setEntries] = useState<JournalEntry[]>([]);
    const [ledgers, setLedgers] = useState<AccountLedger[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(
        async (signal: { cancelled: boolean }) => {
            setLoading(true);
            setError(null);
            try {
                const [page, renterLedgers] = await Promise.all([
                    leaseApi.journals(leaseId, { size: 100 }),
                    // The renter ledger is not lease-filtered server-side; its rows
                    // carry their own leaseId, so the narrowing happens here.
                    renterId ? ledgerApi.ledger.renter(renterId, {}) : Promise.resolve([] as AccountLedger[]),
                ]);
                if (signal.cancelled) return;
                setEntries(page.content ?? []);
                setLedgers(narrowLedgersToLease(renterLedgers, leaseId));
            } catch (e) {
                if (!signal.cancelled) setError(e instanceof ApiError ? e.message : tl("journalsFailed"));
            } finally {
                if (!signal.cancelled) setLoading(false);
            }
        },
        [leaseId, renterId, tl],
    );

    useEffect(() => {
        const signal = { cancelled: false };
        load(signal);
        return () => {
            signal.cancelled = true;
        };
    }, [load]);

    if (loading) {
        return (
            <div className="flex justify-center py-10">
                <Loader2 size={18} className="animate-spin text-muted" />
            </div>
        );
    }

    return (
        <div className="space-y-6" data-testid="lease-journals-tab">
            {error && <p className="text-xs text-error">{error}</p>}

            <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                <div className="px-4 py-3 border-b border-border">
                    <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("journals")}</h3>
                </div>
                <div className="overflow-x-auto">
                    <table className="w-full min-w-[640px]">
                        <thead>
                            <tr className="bg-input/50">
                                <th className={th}>{t("docDate")}</th>
                                <th className={th}>{t("docNo")}</th>
                                <th className={th}>{t("docType")}</th>
                                <th className={th}>{t("narration")}</th>
                                <th className={`${th} text-end`}>{t("total")}</th>
                                <th className={th}>{t("status")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {entries.map(e => (
                                <tr key={e.id} className="border-t border-border hover:bg-input/20">
                                    <td className={td}>{fmtIsoDate(e.entryDate, locale)}</td>
                                    <td className={td}>
                                        <Link
                                            href={`/${locale}/dashboard/finance/journals/${e.id}`}
                                            className="text-primary hover:underline"
                                        >
                                            {e.entryNumber}
                                        </Link>
                                    </td>
                                    <td className={td}>{e.docType}</td>
                                    <td className={`${td} text-muted`}>{e.narration}</td>
                                    <td className={`${td} text-end tabular-nums`}>{fmtAmount(e.total)}</td>
                                    <td className={td}>
                                        {e.status === "REVERSED" ? t("reversed") : t("posted")}
                                    </td>
                                </tr>
                            ))}
                            {entries.length === 0 && (
                                <tr>
                                    <td className={`${td} text-muted text-center py-6`} colSpan={6}>
                                        {t("noRows")}
                                    </td>
                                </tr>
                            )}
                        </tbody>
                    </table>
                </div>
            </div>

            {ledgers.length > 0 && <LedgerTable ledgers={ledgers} subBand={renterName ?? undefined} />}
        </div>
    );
}
