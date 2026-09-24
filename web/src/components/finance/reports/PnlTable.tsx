"use client";

import { Fragment } from "react";
import { useTranslations } from "next-intl";
import { fmtAmount } from "@/lib/api/ledger";
import {
    TOTAL,
    UNASSIGNED,
    type PnlAmount,
    type PnlColumn,
    type PnlRow,
    type PropertyPnl,
} from "@/lib/api/propertyReports";

/**
 * Which figure a drill-down asks for, in one column: a row (`rowKey`), a group
 * subtotal (`groupId`) or NOI (neither). `accountIds` is the row's own leaves,
 * for the general-ledger link only — never sent for a group or NOI.
 */
export type DrillTarget = {
    column: PnlColumn;
    label: string;
    rowKey?: string;
    groupId?: string;
    accountIds?: string[];
};

const th = "px-3 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-3 py-2 text-xs";
const num = `${td} text-end tabular-nums whitespace-nowrap`;

/**
 * The property P&L as a table (finance-ops spec §1): report lines as rows under
 * the level-2 group headers, one column per property plus Unassigned and Total.
 *
 * With a comparison, every column becomes a group `This period | Prior | Δ | Δ%`.
 * With exactly one property on the report (a manager with one building, or one
 * property picked) the table pivots to that single column with its comparison,
 * and the tenant-wide Unassigned / Total columns are not shown.
 *
 * RTL: headers and cells use logical start / end, so the column order mirrors
 * under `dir="rtl"`; amounts sit in `<bdi dir="ltr">` so a minus sign stays with
 * its number, in Latin digits in both languages.
 */
export default function PnlTable({
    data,
    locale,
    onDrill,
}: {
    data: PropertyPnl;
    locale: string;
    onDrill?: (target: DrillTarget) => void;
}) {
    const t = useTranslations("PropertyReports");
    const withPrior = data.priorFrom !== null;
    const propertyColumns = data.columns.filter(c => c.kind === "PROPERTY");
    const pivot = propertyColumns.length === 1;
    const columns = pivot ? propertyColumns : data.columns;
    const ar = locale === "ar";

    const columnLabel = (c: PnlColumn) =>
        c.kind === UNASSIGNED ? t("unassigned")
            : c.kind === TOTAL ? t("total")
                : ar && c.nameAr ? c.nameAr : c.name;
    const rowLabel = (r: PnlRow) => (ar && r.labelAr ? r.labelAr : r.label);

    const subHeads = withPrior
        ? [t("thisPeriod"), t("prior"), t("delta"), t("deltaPct")]
        : [t("thisPeriod")];
    const span = subHeads.length;

    const amountCells = (
        cells: Record<string, PnlAmount> | undefined,
        drill: ((c: PnlColumn) => DrillTarget) | null,
        key: string,
    ) =>
        columns.map(c => {
            const a = cells?.[c.key];
            const main = a ? fmtAmount(a.amount) : "—";
            const target = drill && a && a.amount !== 0 && onDrill ? drill(c) : null;
            return [
                <td key={`${key}-${c.key}-a`} className={num} data-col={c.key}>
                    {target ? (
                        <button
                            type="button"
                            onClick={() => onDrill!(target)}
                            className="text-primary hover:underline cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/20 rounded"
                            data-testid={`drill-${key}-${c.key}`}
                        >
                            <bdi dir="ltr">{main}</bdi>
                        </button>
                    ) : (
                        <bdi dir="ltr">{main}</bdi>
                    )}
                </td>,
                ...(withPrior
                    ? [
                          <td key={`${key}-${c.key}-p`} className={`${num} text-muted`}>
                              <bdi dir="ltr">{a?.prior == null ? "—" : fmtAmount(a.prior)}</bdi>
                          </td>,
                          <td key={`${key}-${c.key}-d`} className={num}>
                              <bdi dir="ltr">{a?.delta == null ? "—" : fmtAmount(a.delta)}</bdi>
                          </td>,
                          <td key={`${key}-${c.key}-pct`} className={`${num} text-muted`}>
                              <bdi dir="ltr">{a?.deltaPct == null ? "" : `${a.deltaPct.toFixed(2)}%`}</bdi>
                          </td>,
                      ]
                    : []),
            ];
        });

    const plainCells = (values: Record<string, number> | undefined, key: string) =>
        columns.map(c => [
            <td key={`${key}-${c.key}`} className={num}>
                <bdi dir="ltr">{values && values[c.key] != null ? fmtAmount(values[c.key]) : ""}</bdi>
            </td>,
            ...(withPrior ? [<td key={`${key}-${c.key}-x`} className={td} colSpan={span - 1} />] : []),
        ]);

    const hasRows = data.groups.some(g => g.rows.length > 0);

    return (
        <div className="bg-surface border border-border rounded-xl overflow-x-auto shadow-sm">
            <table className="w-full min-w-[640px]" data-testid="pnl-table" data-pivot={pivot ? "single" : "multi"}>
                <thead>
                    <tr className="bg-input/50">
                        <th className={`${th} text-start`} rowSpan={withPrior ? 2 : 1}>{t("line")}</th>
                        {columns.map(c => (
                            <th key={c.key} className={`${th} text-end`} colSpan={span} data-testid={`col-${c.kind}`}>
                                {columnLabel(c)}
                            </th>
                        ))}
                    </tr>
                    {withPrior && (
                        <tr className="bg-input/30">
                            {columns.flatMap(c =>
                                subHeads.map(h => (
                                    <th key={`${c.key}-${h}`} className={`${th} text-end`}>{h}</th>
                                )),
                            )}
                        </tr>
                    )}
                </thead>
                <tbody>
                    {!hasRows && (
                        <tr>
                            <td className={`${td} text-muted text-center py-8`} colSpan={1 + columns.length * span}>
                                {t("noActivity")}
                            </td>
                        </tr>
                    )}
                    {data.groups.map(g => (
                        <Fragment key={g.groupId}>
                            <tr>
                                <td
                                    colSpan={1 + columns.length * span}
                                    className="px-3 py-1.5 text-xs font-bold text-white"
                                    style={{ background: "#C8651B" }}
                                >
                                    {ar && g.nameAr ? g.nameAr : g.name}
                                </td>
                            </tr>
                            {g.rows.map(r => (
                                <tr key={r.key} className="border-t border-border hover:bg-input/30" data-testid={`row-${r.key}`}>
                                    <td className={td}>{rowLabel(r)}</td>
                                    {amountCells(r.cells, c => ({ column: c, label: rowLabel(r), rowKey: r.key, accountIds: r.accountIds }), r.key)}
                                </tr>
                            ))}
                            <tr className="bg-input/40 font-semibold border-t border-border">
                                <td className={td}>{`${t("subtotal")} — ${ar && g.nameAr ? g.nameAr : g.name}`}</td>
                                {amountCells(g.subtotal, c => ({
                                    column: c,
                                    label: ar && g.nameAr ? g.nameAr : g.name,
                                    groupId: g.groupId,
                                }), `sub-${g.code}`)}
                            </tr>
                        </Fragment>
                    ))}
                    <tr className="border-t-2 border-border font-semibold">
                        <td className={td}>{t("income")}</td>
                        {amountCells(data.income, null, "income")}
                    </tr>
                    <tr className="font-semibold">
                        <td className={td}>{t("expenses")}</td>
                        {amountCells(data.expenses, null, "expenses")}
                    </tr>
                    <tr className="bg-warning/10 font-bold border-t border-border" data-testid="noi-row">
                        <td className={td}>{t("noi")}</td>
                        {amountCells(data.noi, c => ({ column: c, label: t("noi") }), "noi")}
                    </tr>
                    {data.allocation && (
                        <>
                            <tr className="text-muted italic" data-testid="allocation-row">
                                <td className={td}>{t("allocatedRow")}</td>
                                {plainCells(data.allocation.allocated, "alloc")}
                            </tr>
                            <tr className="font-semibold">
                                <td className={td}>{t("noiAfter")}</td>
                                {plainCells(data.allocation.noiAfter, "after")}
                            </tr>
                        </>
                    )}
                </tbody>
            </table>
        </div>
    );
}
