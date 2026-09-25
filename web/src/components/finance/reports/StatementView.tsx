"use client";

import { useTranslations } from "next-intl";
import { CheckCircle2, Clock } from "lucide-react";
import { fmtAmount } from "@/lib/api/ledger";
import { formatDate } from "@/lib/format";
import type { PropertyStatement, StatementSection, StatementTable } from "@/lib/api/propertyReports";

const th = "px-3 py-2 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap";
const td = "px-3 py-1.5 text-xs";

const NUMERIC = new Set(["amount", "prior", "delta", "net", "vat", "outputVat", "daysOverdue"]);

/**
 * The nine statement sections as cards with tables (finance-ops spec §1). Each
 * card names its source — ledger, register, derived — because the register's
 * operational figures do not tie to the GL and the pack says so. Amounts in
 * `<bdi dir="ltr">`, Latin digits in both languages.
 */
export default function StatementView({ data, locale }: { data: PropertyStatement; locale: string }) {
    const t = useTranslations("PropertyReports");
    const ar = locale === "ar";

    const cell = (table: StatementTable, row: (string | number | null)[], col: number) => {
        const column = table.columns[col];
        let v = row[col];
        if (column === "line" && ar) {
            const arIdx = table.columns.indexOf("lineAr");
            if (arIdx >= 0 && typeof row[arIdx] === "string" && row[arIdx]) v = row[arIdx];
        }
        if (v === null || v === undefined || v === "") return "";
        if (column === "daysOverdue") return String(v);
        if (typeof v === "number") return fmtAmount(v);
        if (column === "mode") return t(`mode.${v}`);
        if (column === "type") return t(`type.${v}`);
        if (column === "basis") return t(`basis.${v}`);
        return String(v);
    };

    const visible = (table: StatementTable) =>
        table.columns.map((c, i) => ({ c, i })).filter(({ c }) => c !== "lineAr");

    return (
        <div className="space-y-4" data-testid="statement">
            {data.sections.map((s: StatementSection) => (
                <section key={s.key} className="bg-surface border border-border rounded-xl shadow-sm p-4" data-testid={`section-${s.key}`}>
                    <div className="flex items-baseline justify-between gap-2 mb-3">
                        <h2 className="text-sm font-bold text-foreground">
                            {s.number}. {t(`section.${s.key}`)}
                        </h2>
                        <span className="text-[10px] uppercase tracking-wider text-muted">{t(`source.${s.source}`)}</span>
                    </div>
                    {s.figures.length > 0 && (
                        <dl className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-x-6 gap-y-1 mb-2">
                            {s.figures.map(f => (
                                <div key={f.key} className="flex justify-between gap-3 text-xs border-b border-border/50 py-1">
                                    <dt className="text-muted">
                                        {t(`figure.${f.key}`)}
                                        {f.count !== null && <span className="ms-1">(<bdi dir="ltr">{f.count}</bdi>)</span>}
                                    </dt>
                                    <dd className="tabular-nums font-semibold"><bdi dir="ltr">{fmtAmount(f.amount ?? 0)}</bdi></dd>
                                </div>
                            ))}
                        </dl>
                    )}
                    {s.tables.filter(tb => tb.rows.length > 0).map(tb => (
                        <div key={tb.key} className="overflow-x-auto mt-2">
                            <table className="w-full min-w-[480px]">
                                <thead>
                                    <tr className="bg-input/50">
                                        {visible(tb).map(({ c }) => (
                                            <th key={c} className={`${th} ${NUMERIC.has(c) ? "text-end" : "text-start"}`}>{t(`column.${c}`)}</th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {tb.rows.map((row, ri) => (
                                        <tr key={ri} className="border-t border-border">
                                            {visible(tb).map(({ c, i }) => (
                                                <td key={c} className={`${td} ${NUMERIC.has(c) ? "text-end tabular-nums" : ""}`}>
                                                    {NUMERIC.has(c) ? <bdi dir="ltr">{cell(tb, row, i)}</bdi> : cell(tb, row, i)}
                                                </td>
                                            ))}
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    ))}
                    {s.notes.map(n => (
                        <p key={n} className="text-[11px] text-muted mt-2">{t(`note.${n}`)}</p>
                    ))}
                    {s.meta?.priorFrom && s.meta?.priorTo && (
                        <p className="text-[11px] text-muted mt-1">{t("priorPeriod", { from: formatDate(s.meta.priorFrom), to: formatDate(s.meta.priorTo) })}</p>
                    )}
                </section>
            ))}
            <footer className="flex items-center gap-2 text-xs text-muted" data-testid="statement-footer">
                {data.footer.isFinal
                    ? <span className="flex items-center gap-1 text-success"><CheckCircle2 size={13} />{t("footerFinal")}</span>
                    : <span className="flex items-center gap-1 text-warning"><Clock size={13} />{t("footerProvisional")}</span>}
                <span>
                    {data.footer.generatedBy
                        ? t("generatedBy", { at: new Date(data.footer.generatedAt).toLocaleString("en-GB"), name: data.footer.generatedBy })
                        : t("generated", { at: new Date(data.footer.generatedAt).toLocaleString("en-GB") })}
                </span>
            </footer>
        </div>
    );
}
