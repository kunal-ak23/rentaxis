"use client";

import { Link } from "@/i18n/routing";
import { useLocale, useTranslations } from "next-intl";
import { accountName, fmtAmount, fmtBalance, type AccountLedger } from "@/lib/api/ledger";
import { useNameLookup } from "./useNameLookup";

const th = "text-start px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-1.5 text-xs";
const num = `${td} text-end tabular-nums`;

/**
 * `new Date("2026-09-11")` is parsed as UTC midnight, so west of Greenwich
 * `toLocaleDateString` renders the day before — a ledger that silently shifts
 * every doc date by one day is worse than no date at all. Building the Date from
 * the parts keeps it at local midnight.
 */
function fmtDate(iso: string, locale: string): string {
    const [y, m, d] = iso.slice(0, 10).split("-").map(Number);
    if (!y || !m || !d) return iso;
    return new Date(y, m - 1, d).toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB");
}

type Props = {
    ledgers: AccountLedger[];
    /** Unit and Tenant columns. Off for account-centric reports that don't need them. */
    showTenantColumns?: boolean;
    /**
     * PACT prints a second, yellow band under each account header naming the
     * party the report was run for. The tenant ledger passes the renter's name.
     */
    subBand?: string;
};

/**
 * The ledger in PACT's layout: an orange band per account, its rows, a Sub Total
 * per account and one Report Total across all of them. One `<table>` for the lot
 * so the columns line up across accounts — a table per account would let each
 * one size its columns independently and the report would look ragged.
 */
export default function LedgerTable({ ledgers, showTenantColumns = true, subBand }: Props) {
    const t = useTranslations("Ledger");
    const locale = useLocale();
    const units = useNameLookup("units", showTenantColumns);
    const renters = useNameLookup("renters", showTenantColumns);
    const cols = showTenantColumns ? 9 : 7;

    const grand = ledgers.reduce(
        (a, l) => ({ dr: a.dr + l.totalDebit, cr: a.cr + l.totalCredit, bal: a.bal + l.closingBalance }),
        { dr: 0, cr: 0, bal: 0 },
    );

    return (
        <div className="bg-surface border border-border rounded-xl overflow-x-auto shadow-sm">
            <table className="w-full min-w-[900px]">
                <thead>
                    <tr className="bg-input/50">
                        <th className={th}>{t("docDate")}</th>
                        <th className={th}>{t("docNo")}</th>
                        <th className={th}>{t("particular")}</th>
                        <th className={`${th} text-end`}>{t("debit")}</th>
                        <th className={`${th} text-end`}>{t("credit")}</th>
                        <th className={`${th} text-end`}>{t("balance")}</th>
                        {showTenantColumns && (
                            <>
                                <th className={th}>{t("unit")}</th>
                                <th className={th}>{t("tenant")}</th>
                            </>
                        )}
                        <th className={th}>{t("narration")}</th>
                    </tr>
                </thead>
                <tbody>
                    {ledgers.map(l => (
                        <LedgerBlock key={l.accountId} ledger={l} />
                    ))}
                    <tr className="bg-warning/10 font-bold border-t-2 border-border">
                        <td className={td} colSpan={3}>{t("reportTotal")}</td>
                        <td className={num}>{fmtAmount(grand.dr)}</td>
                        <td className={num}>{fmtAmount(grand.cr)}</td>
                        <td className={num}>{fmtBalance(grand.bal)}</td>
                        <td className={td} colSpan={cols - 6} />
                    </tr>
                </tbody>
            </table>
        </div>
    );

    function LedgerBlock({ ledger: l }: { ledger: AccountLedger }) {
        return (
            <>
                <tr>
                    <td colSpan={cols} className="px-3 py-1.5 text-xs font-bold text-white" style={{ background: "#C8651B" }}>
                        Account Code :: {l.accountCode} &nbsp;&nbsp; Name :: {accountName(l, locale)}
                    </td>
                </tr>
                {subBand && (
                    <tr>
                        <td colSpan={cols} className="px-3 py-1 text-[11px] font-semibold text-foreground bg-warning/25">
                            {t("tenantName")} : {subBand}
                        </td>
                    </tr>
                )}
                {l.openingBalance !== 0 && (
                    <tr className="bg-input/30">
                        <td className={td} colSpan={3}>{t("openingBalance")}</td>
                        <td className={num} />
                        <td className={num} />
                        <td className={num}>{fmtBalance(l.openingBalance)}</td>
                        <td colSpan={cols - 6} />
                    </tr>
                )}
                {l.rows.map(r => (
                    <tr key={`${r.entryId}-${r.entryNumber}-${r.balance}`} className="border-t border-border hover:bg-input/30">
                        <td className={td}>{fmtDate(r.entryDate, locale)}</td>
                        <td className={td}>
                            <Link href={`/dashboard/finance/journals/${r.entryId}`} className="text-primary hover:underline">
                                {r.entryNumber}
                            </Link>
                        </td>
                        <td className={td}>{r.particular}</td>
                        <td className={num}>{r.debit ? fmtAmount(r.debit) : ""}</td>
                        <td className={num}>{r.credit ? fmtAmount(r.credit) : ""}</td>
                        <td className={num}>{fmtBalance(r.balance)}</td>
                        {showTenantColumns && (
                            <>
                                <td className={td}>{units.name(r.unitId)}</td>
                                <td className={td}>{renters.name(r.renterId)}</td>
                            </>
                        )}
                        <td className={`${td} text-muted`}>{r.narration}</td>
                    </tr>
                ))}
                {l.truncated && (
                    <tr>
                        <td colSpan={cols} className={`${td} text-warning`}>{t("truncated", { n: l.rows.length })}</td>
                    </tr>
                )}
                <tr className="bg-input/40 font-semibold border-t border-border">
                    <td className={td} colSpan={3}>{t("subTotal")}</td>
                    <td className={num}>{fmtAmount(l.totalDebit)}</td>
                    <td className={num}>{fmtAmount(l.totalCredit)}</td>
                    <td className={num}>{fmtBalance(l.closingBalance)}</td>
                    <td colSpan={cols - 6} />
                </tr>
            </>
        );
    }
}
