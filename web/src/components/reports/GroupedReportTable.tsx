"use client";

import type { ReactNode } from "react";
import { useTranslations } from "next-intl";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Link } from "@/i18n/routing";
import { fmtDrCr, fmtMoney, fmtReportDate } from "@/lib/reports/format";
import type { GridColumn, GridItem, ReportGrid } from "@/lib/api/reports";
import { cn } from "@/lib/utils";

// The rule under the header is an inset shadow: a collapsed border would scroll away from a sticky cell.
const th = "px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap bg-surface shadow-[inset_0_-1px_0_var(--color-border)]";
const td = "px-3 py-1.5 text-xs";

/** Amount-like kinds: end-aligned, tabular, isolated left-to-right so "8,000.00Cr" never flips under RTL. */
const NUMERIC = new Set<GridColumn["kind"]>(["MONEY", "BALANCE", "INT", "SERIAL", "PERCENT"]);

const BAND_CLASS: Record<NonNullable<GridItem["band"]>, string> = {
    ACCOUNT: "bg-report-band text-report-band-fg font-bold",
    PARTY: "bg-report-party text-report-party-fg font-semibold",
    GROUP: "bg-report-group text-report-group-fg font-bold",
};

/** A cell's display text. Amounts are re-punctuated only — the server did every sum. */
function display(col: GridColumn, v: string | undefined): string {
    if (v == null || v === "") return "";
    switch (col.kind) {
        case "DATE": return fmtReportDate(v);
        case "MONEY": return fmtMoney(v);
        case "BALANCE": return fmtDrCr(v);
        default: return v;
    }
}

/** Where a drill column points for this row, or null when the row carries no matching ref. */
function drillHref(col: GridColumn, refs: Record<string, string>): string | null {
    if (col.link === "journal" && refs.entryId) return `/dashboard/finance/journals/${refs.entryId}`;
    if (col.link === "lease" && refs.leaseId) return `/dashboard/leases/${refs.leaseId}`;
    // There is no cheque detail page yet, so a cheque reference prints as text.
    return null;
}

/** A non-empty cell's content: amounts isolated left-to-right, dates and ids unbroken, drill columns linked. */
function cellContent(col: GridColumn, text: string, refs: Record<string, string>): ReactNode {
    if (NUMERIC.has(col.kind)) return <bdi dir="ltr" className="tabular-nums">{text}</bdi>;
    const content: ReactNode = col.kind === "DATE" || col.kind === "ID_TEXT"
        ? <bdi dir="ltr" className={col.kind === "DATE" ? "tabular-nums" : undefined}>{text}</bdi>
        : <bdi>{text}</bdi>;
    const href = drillHref(col, refs);
    return href ? <Link href={href} className="text-primary hover:underline">{content}</Link> : content;
}

/**
 * One cell. `lead` is a totals or opening row's label when the first visible
 * column itself carries a figure: the label then shares that cell, at the start,
 * so neither the label nor the figure is lost.
 */
function Cell({ col, value, refs, lead }: { col: GridColumn; value: string | undefined; refs: Record<string, string>; lead?: string }) {
    const text = display(col, value);
    const numeric = NUMERIC.has(col.kind);
    if (lead) {
        return (
            <td className={cn(td, "whitespace-nowrap")}>
                <span className="flex items-baseline justify-between gap-3">
                    <bdi>{lead}</bdi>
                    {text && cellContent(col, text, refs)}
                </span>
            </td>
        );
    }
    if (!text) return <td className={cn(td, numeric && "text-end")} />;
    const nowrap = numeric || col.kind === "DATE" || col.kind === "ID_TEXT";
    return <td className={cn(td, numeric && "text-end", nowrap && "whitespace-nowrap")}>{cellContent(col, text, refs)}</td>;
}

/**
 * A `ReportGridDTO` page as one table (PACT report spec §1.5): account and
 * party bands, the brought-forward (or, on a later page, carried-forward)
 * balance, the rows, each group's Sub Total and the REPORT TOTAL — all printed
 * as the server sent them. One `<table>` for the page so columns line up across
 * groups; it scrolls inside its own box, so a 390 px screen never scrolls
 * sideways, and its header stays in view while the rows scroll.
 */
export function GroupedReportTable({ grid, onPage, groupsLabel }: {
    grid: ReportGrid;
    onPage?: (page: number) => void;
    groupsLabel?: string;
}) {
    const t = useTranslations("Reports");
    const cols = grid.columns.filter(c => !c.hidden);
    const balanceAt = cols.findIndex(c => c.kind === "BALANCE");
    const { page, pageCount, continuesOnNextPage } = grid.page;

    function totalsRow(it: GridItem, key: number, testId: string, className: string) {
        // The label takes the leading columns that carry no total (PACT prints "Sub Total" across them).
        const firstTotalled = cols.findIndex(c => (it.cells[c.key] ?? "") !== "");
        if (firstTotalled === 0) {
            // The first column has a total of its own: the label shares its cell instead of covering it.
            return (
                <tr key={key} data-testid={testId} className={className}>
                    {cols.map((c, j) => <Cell key={c.key} col={c} value={it.cells[c.key]} refs={{}} lead={j === 0 ? it.label ?? undefined : undefined} />)}
                </tr>
            );
        }
        const span = firstTotalled === -1 ? cols.length : firstTotalled;
        return (
            <tr key={key} data-testid={testId} className={className}>
                <td className={td} colSpan={span}><bdi>{it.label ?? ""}</bdi></td>
                {cols.slice(span).map(c => <Cell key={c.key} col={c} value={it.cells[c.key]} refs={{}} />)}
            </tr>
        );
    }

    function renderItem(it: GridItem, i: number) {
        switch (it.type) {
            case "band":
                return (
                    <tr key={i} data-testid={`grid-band-${it.level}`}>
                        <td colSpan={cols.length} className={cn("px-3 py-1.5 text-xs", BAND_CLASS[it.band ?? "GROUP"])}>
                            <bdi>{it.label ?? ""}</bdi>
                        </td>
                    </tr>
                );
            case "opening": {
                if (balanceAt === -1) {
                    return (
                        <tr key={i} data-testid="grid-opening" className="bg-input/30">
                            <td className={td} colSpan={cols.length}><bdi>{it.label ?? ""}</bdi></td>
                        </tr>
                    );
                }
                const balance = cols[balanceAt];
                const after = cols.length - balanceAt - 1;
                return (
                    <tr key={i} data-testid="grid-opening" data-carried-forward={it.carriedForward || undefined}
                        className={cn("bg-input/30", it.carriedForward && "italic")}>
                        {balanceAt > 0 && <td className={td} colSpan={balanceAt}><bdi>{it.label ?? ""}</bdi></td>}
                        {/* With Balance first there is no column before it, so the label shares its cell. */}
                        <Cell col={balance} value={it.cells[balance.key]} refs={{}} lead={balanceAt === 0 ? it.label ?? undefined : undefined} />
                        {after > 0 && <td colSpan={after} />}
                    </tr>
                );
            }
            case "row":
                return (
                    <tr key={i} data-testid="grid-row"
                        className={cn("border-t border-border", it.style === "HIGHLIGHT" ? "bg-report-highlight" : "hover:bg-input/30")}>
                        {cols.map(c => <Cell key={c.key} col={c} value={it.cells[c.key]} refs={it.refs} />)}
                    </tr>
                );
            case "subtotal":
                return totalsRow(it, i, `grid-subtotal-${it.level}`, "bg-input/40 font-semibold border-t border-border");
            case "total":
                return totalsRow(it, i, "grid-total", "bg-warning/10 font-bold border-t-2 border-border");
        }
    }

    return (
        <div>
            <div className="bg-surface border border-border rounded-xl overflow-x-auto overflow-y-auto max-h-[75vh] shadow-sm">
                <table className="w-full min-w-[900px]">
                    <thead className="sticky top-0 z-10">
                        <tr>
                            {cols.map(c => (
                                <th key={c.key} scope="col" className={cn(th, NUMERIC.has(c.kind) ? "text-end" : "text-start")}>
                                    {c.label}
                                </th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>{grid.items.map(renderItem)}</tbody>
                </table>
            </div>
            {pageCount > 1 && (
                <nav className="mt-3 flex flex-wrap items-center justify-between gap-3 text-xs" data-testid="report-pager">
                    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-muted">
                        <span className="tabular-nums">{t("tablePageOf", { page: String(page + 1), count: String(pageCount) })}</span>
                        {groupsLabel && <span className="tabular-nums">{groupsLabel}</span>}
                        {continuesOnNextPage && <span className="text-info font-semibold">{t("tableContinues")}</span>}
                    </div>
                    <div className="flex items-center gap-2">
                        <button
                            type="button"
                            onClick={() => onPage?.(page - 1)}
                            disabled={!onPage || page <= 0}
                            className="flex items-center gap-1 px-3 py-1.5 rounded-lg border border-border bg-surface font-semibold cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed hover:bg-input transition-colors"
                        >
                            <ChevronLeft size={14} className="rtl:rotate-180" aria-hidden />
                            {t("tablePrevious")}
                        </button>
                        <button
                            type="button"
                            onClick={() => onPage?.(page + 1)}
                            disabled={!onPage || page >= pageCount - 1}
                            className="flex items-center gap-1 px-3 py-1.5 rounded-lg border border-border bg-surface font-semibold cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed hover:bg-input transition-colors"
                        >
                            {t("tableNext")}
                            <ChevronRight size={14} className="rtl:rotate-180" aria-hidden />
                        </button>
                    </div>
                </nav>
            )}
        </div>
    );
}

export default GroupedReportTable;
