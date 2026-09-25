"use client";

import { Link } from "@/i18n/routing";
import { useLocale, useTranslations } from "next-intl";
import { accountName, fmtAmount, type AccountLedger } from "@/lib/api/ledger";
import { buildLedgerReport, drCr, type LedgerGroup } from "@/lib/finance/ledgerReport";
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
    /** Unit, Tower and Tenant columns. Off for account-centric reports that don't need them. */
    showTenantColumns?: boolean;
    /**
     * Always open each account with its balance brought forward (the balance
     * before the period), even when it is zero — the General and Tenant Ledger
     * reports run over a period. Off: the row shows only when non-zero.
     */
    broughtForward?: boolean;
    /**
     * PACT prints a second, yellow band under each account header naming the
     * party the report was run for. The tenant ledger passes the renter's name.
     */
    subBand?: string;
};

/**
 * The ledger in PACT's layout: an orange band per account, its balance brought
 * forward, its rows (Doc Date · Doc No · Particular · Debit · Credit · Balance ·
 * Unit · Tower · Tenant · Narration), a Sub Total
 * per account and one Report Total across all of them. One `<table>` for the lot
 * so the columns line up across accounts — a table per account would let each
 * one size its columns independently and the report would look ragged.
 */
export default function LedgerTable({ ledgers, showTenantColumns = true, subBand, broughtForward = false }: Props) {
    const t = useTranslations("Ledger");
    const locale = useLocale();
    const units = useNameLookup("units", showTenantColumns);
    const renters = useNameLookup("renters", showTenantColumns);
    const towers = useNameLookup("properties", showTenantColumns);
    const cols = showTenantColumns ? 10 : 7;
    // Sub-totals and the report total are summed here from the rows (PACT
    // layout, spec "Terminology — Ledger report layout").
    const report = buildLedgerReport(ledgers);

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
                                <th className={th} data-testid="ledger-col-tower">{t("tower")}</th>
                                <th className={th}>{t("tenant")}</th>
                            </>
                        )}
                        <th className={th}>{t("narration")}</th>
                    </tr>
                </thead>
                <tbody>
                    {report.groups.map(g => (
                        <LedgerBlock key={g.accountId} group={g} />
                    ))}
                    <tr className="bg-warning/10 font-bold border-t-2 border-border" data-testid="ledger-report-total">
                        <td className={`${td} uppercase`} colSpan={3}>{t("reportTotal")}</td>
                        <td className={num}>{fmtAmount(report.total.debit)}</td>
                        <td className={num}>{fmtAmount(report.total.credit)}</td>
                        <td className={num}>{drCr(report.total.balance)}</td>
                        <td className={td} colSpan={cols - 6} />
                    </tr>
                </tbody>
            </table>
        </div>
    );

    function LedgerBlock({ group: g }: { group: LedgerGroup }) {
        const l = g.ledger;
        return (
            <>
                <tr>
                    <td colSpan={cols} className="px-3 py-1.5 text-xs font-bold text-white" style={{ background: "#C8651B" }}>
                        {t("accountCodeLabel")} :: <bdi dir="ltr">{l.accountCode}</bdi> &nbsp;&nbsp; {t("accountNameLabel")} :: <bdi>{accountName(l, locale)}</bdi>
                    </td>
                </tr>
                {subBand && (
                    <tr>
                        <td colSpan={cols} className="px-3 py-1 text-[11px] font-semibold text-foreground bg-warning/25">
                            {t("tenantName")} : {subBand}
                        </td>
                    </tr>
                )}
                {(broughtForward || g.opening !== 0) && (
                    <tr className="bg-input/30" data-testid={`ledger-bf-${l.accountId}`}>
                        <td className={td} colSpan={3}>{broughtForward ? t("broughtForward") : t("openingBalance")}</td>
                        <td className={num} />
                        <td className={num} />
                        <td className={num}>{drCr(g.opening)}</td>
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
                        <td className={num}>{drCr(r.balance)}</td>
                        {showTenantColumns && (
                            <>
                                <td className={td}>{units.name(r.unitId)}</td>
                                <td className={td}>{towers.name(r.propertyId)}</td>
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
                <tr className="bg-input/40 font-semibold border-t border-border" data-testid={`ledger-subtotal-${l.accountId}`}>
                    <td className={td} colSpan={3}>{t("subTotal")}</td>
                    <td className={num}>{fmtAmount(g.subTotal.debit)}</td>
                    <td className={num}>{fmtAmount(g.subTotal.credit)}</td>
                    <td className={num}>{drCr(g.subTotal.balance)}</td>
                    <td colSpan={cols - 6} />
                </tr>
            </>
        );
    }
}
