"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import Link from "next/link";
import { FileDown, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { fmtAmount } from "@/lib/api/ledger";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import {
    ApiError,
    vatApi,
    type TaxInvoice,
    type VatTaxPoint,
    type VatTaxPointStatus,
} from "@/lib/api/leasing";
import { fmtIsoDate } from "./leaseMath";

/**
 * One contract's VAT schedule (spec 2026-09-24 §1), next to its Recognition
 * schedule: each instalment's tax point — min(cheque date, receipt date) — what it
 * declares, whether it has, and the tax invoice it issued; plus a termination's
 * settling adjustment when there is one.
 *
 * The footer adds the live points (PLANNED + POSTED) and compares them with the
 * contract's VAT: every fils the contract parked in "Output VAT – not yet due" has
 * to have a tax point that will move it, or it is stranded. After a termination
 * the plan is deliberately different (the adjustment settles it), so the footer
 * reports declared against planned instead of claiming a match.
 *
 * Read-only: the tab is open to a property manager for their buildings, and
 * posting tax points is a finance act on the month-end screen.
 */

const th = "text-start px-3 py-2 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-3 py-1.5 text-xs";

const STATUS_CLASS: Record<VatTaxPointStatus, string> = {
    PLANNED: "bg-input text-muted border-border",
    POSTED: "bg-success/10 text-success border-success/20",
    CANCELLED: "bg-input text-muted/70 border-border line-through",
};

function sum(values: number[]): number {
    return Math.round(values.reduce((s, v) => s + (v ?? 0), 0) * 100) / 100;
}

type Props = {
    leaseId: string;
    /** Σ of the lines' VAT, for the footer's check. */
    contractVat?: number | null;
    terminated?: boolean;
};

export default function VatScheduleTab({ leaseId, contractVat, terminated }: Props) {
    const t = useTranslations("VatSchedule");
    const locale = useLocale();

    const [points, setPoints] = useState<VatTaxPoint[]>([]);
    const [invoices, setInvoices] = useState<TaxInvoice[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            const [p, i] = await Promise.all([vatApi.schedule(leaseId), vatApi.leaseInvoices(leaseId)]);
            setPoints(p);
            setInvoices(i);
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [leaseId, t]);

    useEffect(() => {
        load();
    }, [load]);

    const instalments = useMemo(() => points.filter(p => p.kind === "INSTALMENT"), [points]);
    const live = useMemo(() => sum(instalments.filter(p => p.status !== "CANCELLED").map(p => p.vatAmount)), [instalments]);
    const declared = useMemo(() => sum(points.filter(p => p.status === "POSTED").map(p => p.vatAmount)), [points]);
    const difference = contractVat == null ? null : Math.round((live - contractVat) * 100) / 100;

    if (loading) {
        return (
            <div className="flex justify-center py-10">
                <Loader2 size={18} className="animate-spin text-muted" />
            </div>
        );
    }
    if (error) {
        return <LoadErrorBanner message={error} onRetry={load} />;
    }

    return (
        <div className="space-y-6">
            <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm" data-testid="vat-schedule">
                <div className="px-4 py-3 border-b border-border">
                    <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("title")}</h3>
                    <p className="text-[11px] text-muted mt-1">{t("intro")}</p>
                </div>
                <div className="overflow-x-auto">
                    <table className="w-full min-w-[760px]">
                        <thead>
                            <tr className="bg-input/50">
                                <th className={th}>{t("taxPoint")}</th>
                                <th className={th}>{t("instalment")}</th>
                                <th className={`${th} text-end`}>{t("taxable")}</th>
                                <th className={`${th} text-end`}>{t("vat")}</th>
                                <th className={th}>{t("status")}</th>
                                <th className={th}>{t("journal")}</th>
                                <th className={th}>{t("invoice")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {points.map((p, i) => (
                                <tr key={p.id} data-testid={`vat-point-${i}`} className="border-t border-border hover:bg-input/20">
                                    <td className={`${td} tabular-nums`}>{fmtIsoDate(p.taxPointDate, locale)}</td>
                                    <td className={td}>
                                        {p.kind === "TERMINATION_ADJUSTMENT"
                                            ? t("terminationAdjustment")
                                            : p.kind === "CONTRACT"
                                            ? t("contractTaxPoint")
                                            : t("instalmentLabel", {
                                                  seq: p.chequeSeqNo ?? "—",
                                                  number: p.chequeNumber ?? "—",
                                              })}
                                    </td>
                                    <td className={cn(`${td} text-end tabular-nums`, p.status === "CANCELLED" && "line-through text-muted")}>
                                        {fmtAmount(p.taxableAmount)}
                                    </td>
                                    <td
                                        data-testid={`vat-point-amount-${i}`}
                                        className={cn(`${td} text-end tabular-nums`, p.status === "CANCELLED" && "line-through text-muted")}
                                    >
                                        {fmtAmount(p.vatAmount)}
                                    </td>
                                    <td className={td}>
                                        <span className={cn("px-2 py-0.5 rounded-lg text-[10px] font-semibold border", STATUS_CLASS[p.status])}>
                                            {t(`status${p.status}`)}
                                        </span>
                                    </td>
                                    <td className={td}>
                                        {p.journalId && p.journalNumber ? (
                                            <Link
                                                href={`/${locale}/dashboard/finance/journals/${p.journalId}`}
                                                className="text-primary hover:underline"
                                            >
                                                {p.journalNumber}
                                            </Link>
                                        ) : (
                                            <span className="text-muted">—</span>
                                        )}
                                    </td>
                                    <td className={td}>
                                        {p.invoiceId && p.invoiceNumber ? (
                                            <a
                                                href={vatApi.pdfUrl(p.invoiceId)}
                                                target="_blank"
                                                rel="noopener noreferrer"
                                                className="text-primary hover:underline"
                                            >
                                                {p.invoiceNumber}
                                            </a>
                                        ) : (
                                            <span className="text-muted">—</span>
                                        )}
                                    </td>
                                </tr>
                            ))}
                            {points.length === 0 && (
                                <tr>
                                    <td className={`${td} text-muted text-center py-6`} colSpan={7}>
                                        {t("empty")}
                                    </td>
                                </tr>
                            )}
                        </tbody>
                        {points.length > 0 && (
                            <tfoot>
                                <tr className="border-t-2 border-border bg-input/30">
                                    <td className={`${td} font-semibold`} colSpan={3}>
                                        {t("total")}
                                    </td>
                                    <td className={`${td} text-end tabular-nums font-semibold`} data-testid="vat-schedule-total">
                                        {fmtAmount(live)}
                                    </td>
                                    <td className={td} colSpan={3}>
                                        {terminated || difference === null ? (
                                            <span data-testid="vat-schedule-check" className="text-[11px] text-muted">
                                                {t("declaredOfPlanned", { declared: fmtAmount(declared), planned: fmtAmount(live) })}
                                            </span>
                                        ) : (
                                            <span
                                                data-testid="vat-schedule-check"
                                                className={cn("text-[11px]", difference === 0 ? "text-success" : "text-warning")}
                                            >
                                                {difference === 0
                                                    ? t("matchesContract", { contract: fmtAmount(contractVat ?? 0) })
                                                    : t("differsFromContract", { amount: fmtAmount(Math.abs(difference)) })}
                                            </span>
                                        )}
                                    </td>
                                </tr>
                            </tfoot>
                        )}
                    </table>
                </div>
            </div>

            <TaxInvoiceList invoices={invoices} />
        </div>
    );
}

/**
 * The tax invoices and credit notes issued on a lease, each a PDF download.
 * Shared by the lease page and the renter's portal — the server decides whose
 * invoices a caller may list and download.
 */
export function TaxInvoiceList({ invoices, title }: { invoices: TaxInvoice[]; title?: string }) {
    const t = useTranslations("VatSchedule");
    const locale = useLocale();
    return (
        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm" data-testid="tax-invoices">
            <div className="px-4 py-3 border-b border-border">
                <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">{title ?? t("invoicesTitle")}</h3>
            </div>
            <div className="overflow-x-auto">
                <table className="w-full min-w-[640px]">
                    <thead>
                        <tr className="bg-input/50">
                            <th className={th}>{t("invoiceNumber")}</th>
                            <th className={th}>{t("issueDate")}</th>
                            <th className={th}>{t("period")}</th>
                            <th className={`${th} text-end`}>{t("taxable")}</th>
                            <th className={`${th} text-end`}>{t("vat")}</th>
                            <th className={`${th} text-end`}>{t("invoiceTotal")}</th>
                            <th className={th} />
                        </tr>
                    </thead>
                    <tbody>
                        {invoices.map((inv, i) => (
                            <tr key={inv.id} data-testid={`tax-invoice-${i}`} className="border-t border-border hover:bg-input/20">
                                <td className={td}>
                                    <span className="font-semibold">{inv.invoiceNumber}</span>
                                    <span className="ms-2 text-[10px] text-muted">{t(`kind${inv.kind}`)}</span>
                                </td>
                                <td className={`${td} tabular-nums`}>{fmtIsoDate(inv.issueDate, locale)}</td>
                                <td className={`${td} tabular-nums`}>
                                    {inv.periodStart && inv.periodEnd
                                        ? `${fmtIsoDate(inv.periodStart, locale)} – ${fmtIsoDate(inv.periodEnd, locale)}`
                                        : "—"}
                                </td>
                                <td className={`${td} text-end tabular-nums`}>{fmtAmount(inv.taxableAmount)}</td>
                                <td className={`${td} text-end tabular-nums`}>{fmtAmount(inv.vatAmount)}</td>
                                <td className={`${td} text-end tabular-nums`}>{fmtAmount(inv.totalAmount)}</td>
                                <td className={`${td} text-end`}>
                                    <a
                                        href={vatApi.pdfUrl(inv.id)}
                                        target="_blank"
                                        rel="noopener noreferrer"
                                        data-testid={`tax-invoice-download-${i}`}
                                        className="inline-flex items-center gap-1 text-primary hover:underline"
                                    >
                                        <FileDown size={12} /> {t("download")}
                                    </a>
                                </td>
                            </tr>
                        ))}
                        {invoices.length === 0 && (
                            <tr>
                                <td className={`${td} text-muted text-center py-6`} colSpan={7}>
                                    {t("noInvoices")}
                                </td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
