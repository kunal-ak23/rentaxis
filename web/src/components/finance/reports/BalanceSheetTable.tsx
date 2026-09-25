"use client";

import { Fragment } from "react";
import { useTranslations } from "next-intl";
import { fmtAmount } from "@/lib/api/ledger";
import { formatDate } from "@/lib/format";
import { TOTAL, UNASSIGNED, type BalanceSheet, type PnlAmount, type PnlColumn } from "@/lib/api/propertyReports";

const th = "px-3 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-3 py-2 text-xs";
const num = `${td} text-end tabular-nums whitespace-nowrap`;

/**
 * F14-10: the balance sheet as a table — assets, liabilities and equity by
 * level-2 group, one column per property plus Unassigned and Total, with the
 * comparative date as a second figure per column. The last row is the report's
 * own check: assets − (liabilities + equity), zero when it balances.
 *
 * RTL: logical start / end throughout; amounts in `<bdi dir="ltr">`.
 */
export default function BalanceSheetTable({ data, locale }: { data: BalanceSheet; locale: string }) {
    const t = useTranslations("PropertyReports");
    const ar = locale === "ar";
    const withPrior = data.compareAt !== null;
    const columns = data.columns;
    const colLabel = (c: PnlColumn) =>
        c.kind === UNASSIGNED ? t("unassigned") : c.kind === TOTAL ? t("total") : ar && c.nameAr ? c.nameAr : c.name;
    const pick = (en: string, arName: string | null) => (ar && arName ? arName : en);

    const cells = (values: Record<string, PnlAmount> | undefined, key: string, strong = false) =>
        columns.map(c => {
            const a = values?.[c.key];
            return (
                <Fragment key={`${key}-${c.key}`}>
                    <td className={`${num} ${strong ? "font-bold" : ""}`} data-col={c.key}>
                        <bdi dir="ltr">{a ? fmtAmount(a.amount) : "—"}</bdi>
                    </td>
                    {withPrior && (
                        <td className={`${num} text-muted`}>
                            <bdi dir="ltr">{a?.prior == null ? "—" : fmtAmount(a.prior)}</bdi>
                        </td>
                    )}
                </Fragment>
            );
        });

    return (
        <div className="bg-surface rounded-xl border border-border shadow-sm overflow-x-auto">
            <table className="w-full text-start" data-testid="balance-sheet">
                <thead className="bg-input/50 border-b border-border">
                    <tr>
                        <th className={`${th} text-start`}>{t("line")}</th>
                        {columns.map(c => (
                            <th key={c.key} className={`${th} text-end`} colSpan={withPrior ? 2 : 1}>{colLabel(c)}</th>
                        ))}
                    </tr>
                    {withPrior && (
                        <tr>
                            <th />
                            {columns.map(c => (
                                <Fragment key={`sub-${c.key}`}>
                                    <th className={`${th} text-end`}><bdi dir="ltr">{data.asAt}</bdi></th>
                                    <th className={`${th} text-end`}><bdi dir="ltr">{data.compareAt}</bdi></th>
                                </Fragment>
                            ))}
                        </tr>
                    )}
                </thead>
                <tbody>
                    {data.sections.map(s => (
                        <Fragment key={s.type}>
                            <tr className="bg-input/30">
                                <td className={`${td} font-bold text-foreground`} colSpan={1 + columns.length * (withPrior ? 2 : 1)}>
                                    {t(`bs${s.type}`)}
                                </td>
                            </tr>
                            {s.groups.map(g => (
                                <Fragment key={g.groupId}>
                                    {g.rows.map(r => (
                                        <tr key={r.key} className="border-b border-border/50">
                                            <td className={`${td} ps-6`}>{pick(r.label, r.labelAr)}</td>
                                            {cells(r.cells, r.key)}
                                        </tr>
                                    ))}
                                    <tr className="border-b border-border">
                                        <td className={`${td} font-semibold`}>{pick(g.name, g.nameAr)}</td>
                                        {cells(g.subtotal, `g-${g.groupId}`, true)}
                                    </tr>
                                </Fragment>
                            ))}
                            {s.type === "EQUITY" && (
                                <>
                                    <tr className="border-b border-border/50" data-testid="earlier-years-result">
                                        <td className={`${td} ps-6`}>{t("bsEarlierYearsResult")}</td>
                                        {cells(data.earlierYearsResult, "eyr")}
                                    </tr>
                                    <tr className="border-b border-border/50" data-testid="current-year-result">
                                        <td className={`${td} ps-6`}>{t("bsCurrentYearResult", { from: formatDate(data.fiscalYearStart) })}</td>
                                        {cells(data.currentYearResult, "cyr")}
                                    </tr>
                                </>
                            )}
                            <tr className="border-b-2 border-foreground/40">
                                <td className={`${td} font-bold`}>{t(`bsTotal${s.type}`)}</td>
                                {cells(s.total, `t-${s.type}`, true)}
                            </tr>
                        </Fragment>
                    ))}
                    <tr className="border-b border-border">
                        <td className={`${td} font-bold`}>{t("bsLiabilitiesAndEquity")}</td>
                        {cells(data.liabilitiesAndEquity, "le", true)}
                    </tr>
                    <tr data-testid="bs-check-row" className={data.ok ? "text-success" : "text-error"}>
                        <td className={`${td} font-bold`}>{t("bsCheck")}</td>
                        {cells(data.check, "check", true)}
                    </tr>
                </tbody>
            </table>
        </div>
    );
}
