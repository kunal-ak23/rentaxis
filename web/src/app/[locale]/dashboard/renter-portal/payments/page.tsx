"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import {
    CreditCard,
    Calendar,
    DollarSign,
    CheckCircle,
    AlertTriangle,
    Download,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrencyCompact } from "@/lib/format";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { fmtAmount } from "@/lib/api/ledger";
import { ApiError, chequeApi, onlinePayApi, type RenterCheque } from "@/lib/api/leasing";
import PayOnlineButton from "@/components/renter/PayOnlineButton";
import RenterTaxInvoices from "@/components/renter/RenterTaxInvoices";

/**
 * The renter's own payments screen, on accounting-v2's own cheque register
 * rather than v1's payment schedules (spec §9.3, §11 "Renter portal").
 *
 * `RenterChequeDTO.due` decides the "Due now" list — it already accounts for
 * the property's grace period and folds in an approved-penalty collection row
 * exactly like any other due instrument. History is CLEARED (with a receipt
 * link, `chequeApi.receiptUrl`) and BOUNCED (flagged, not payable through this
 * screen — a bounce is replaced from the register, not re-paid online).
 */

function pickNextDue(rows: RenterCheque[]): RenterCheque | null {
    const due = rows.filter(r => r.due);
    if (due.length === 0) return null;
    return [...due].sort((a, b) => new Date(a.dueDate).getTime() - new Date(b.dueDate).getTime())[0];
}

export default function RenterPaymentsPage() {
    const t = useTranslations("OnlinePayments");
    const tCheques = useTranslations("Cheques");
    const tCommon = useTranslations("Common");
    const locale = useLocale();

    const [rows, setRows] = useState<RenterCheque[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    const fetchPayments = useCallback(async () => {
        try {
            const data = await onlinePayApi.myPayments();
            setRows([...data].sort((a, b) => new Date(a.dueDate).getTime() - new Date(b.dueDate).getTime()));
            setLoadError(null);
        } catch (err) {
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailedPayments"));
        } finally {
            setLoading(false);
        }
    }, [tCommon]);

    useEffect(() => {
        fetchPayments();
    }, [fetchPayments]);

    if (loading) {
        return (
            <div className="p-8 max-w-5xl mx-auto">
                <div className="mb-10">
                    <div className="h-6 w-40 bg-input rounded animate-pulse mb-2" />
                    <div className="h-4 w-56 bg-input rounded animate-pulse" />
                </div>
                <div className="space-y-4">
                    {[1, 2].map(i => (
                        <div key={i} className="bg-surface rounded-xl p-5 border border-border animate-pulse">
                            <div className="flex justify-between items-start mb-4">
                                <div className="flex items-center gap-4">
                                    <div className="w-12 h-12 bg-input rounded-xl" />
                                    <div>
                                        <div className="h-4 w-28 bg-input rounded mb-2" />
                                        <div className="h-3 w-40 bg-input rounded" />
                                    </div>
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        );
    }

    const reload = () => {
        setLoadError(null);
        setLoading(true);
        fetchPayments();
    };

    const dueRows = rows.filter(r => r.due);
    const historyRows = rows.filter(r => r.status === "CLEARED" || r.status === "BOUNCED");
    const nextDue = pickNextDue(rows);

    // PACT-style running total — a cumulative "amount due so far" per row, in
    // the same visual language as the post-dated cheques and collection-batch
    // screens, so the total at the bottom of the list is never a surprise.
    let runningTotal = 0;
    const dueWithRunning = dueRows.map(r => {
        runningTotal += r.payable;
        return { row: r, running: runningTotal };
    });
    const amountDue = runningTotal;

    return (
        <div className="p-8 max-w-5xl mx-auto">
            {loadError && <LoadErrorBanner message={loadError} onRetry={reload} />}
            <div className="mb-10">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t("title")}</h1>
                <p className="text-xs text-muted font-medium">{t("description")}</p>
            </div>

            {rows.length === 0 ? (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center mb-10">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-success shadow-sm mb-6">
                        <CheckCircle size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-2 uppercase tracking-widest">{t("noPaymentsDue")}</p>
                    <p className="text-xs text-muted">{t("allPaid")}</p>
                </div>
            ) : (
                <>
                    {dueRows.length === 0 ? (
                        <div className="bg-surface border border-border rounded-2xl p-5 mb-10 flex items-center gap-3">
                            <CheckCircle size={20} className="text-success" />
                            <p className="text-sm text-foreground">{t("allCaughtUp")}</p>
                        </div>
                    ) : (
                        <div className="mb-10">
                            <div className="flex items-center justify-between mb-4">
                                <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em]">
                                    {t("dueNowTitle")}
                                </h2>
                                <div className="text-end" data-testid="amount-due-total">
                                    <p className="text-[10px] font-bold text-muted uppercase tracking-widest">{t("amountDue")}</p>
                                    <p className="text-lg font-bold text-foreground tabular-nums">{fmtAmount(amountDue)}</p>
                                </div>
                            </div>

                            {nextDue && (
                                <div
                                    className={cn(
                                        "rounded-2xl p-5 mb-4 border",
                                        nextDue.overdue ? "bg-error/5 border-error/20" : "bg-accent/5 border-accent/20",
                                    )}
                                >
                                    <p className={cn("text-[10px] font-bold tracking-widest uppercase mb-2", nextDue.overdue ? "text-error" : "text-muted")}>
                                        {nextDue.overdue ? t("nextChequeOverdue") : t("nextChequeDue")}
                                    </p>
                                    <p className="text-2xl font-bold text-foreground mb-1">{fmtAmount(nextDue.payable)}</p>
                                    <p className="text-xs text-muted">
                                        {[
                                            `${t("dueDate")}: ${fmtIsoDate(nextDue.dueDate, locale)}`,
                                            t("cheque", { n: nextDue.installmentNumber }),
                                            nextDue.chequeNumber,
                                            nextDue.propertyName,
                                            nextDue.unitIdentifier,
                                        ].filter(Boolean).join(" · ")}
                                    </p>
                                </div>
                            )}

                            <div className="space-y-4">
                                {dueWithRunning.map(({ row, running }) => {
                                    const isPenalty = !!row.penaltyAssessmentId;
                                    return (
                                        <div
                                            key={row.id}
                                            data-testid={`due-row-${row.id}`}
                                            className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200"
                                        >
                                            <div className="flex justify-between items-start mb-4">
                                                <div className="flex items-center gap-4">
                                                    <div className="w-12 h-12 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                                                        <CreditCard size={22} />
                                                    </div>
                                                    <div>
                                                        <h3 className="text-sm font-bold text-foreground tracking-tight flex items-center gap-2">
                                                            {t("cheque", { n: row.installmentNumber })}
                                                            {isPenalty && (
                                                                <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[9px] font-bold uppercase tracking-widest bg-error/10 text-error border border-error/20">
                                                                    {t("penaltyRow")}
                                                                </span>
                                                            )}
                                                        </h3>
                                                        <p className="text-[10px] font-bold text-muted">
                                                            {row.propertyName} - {row.unitIdentifier}
                                                        </p>
                                                    </div>
                                                </div>
                                                {row.overdue && (
                                                    <span className="inline-flex items-center gap-1 px-3 py-1.5 rounded-full text-[9px] font-bold uppercase tracking-widest bg-error/10 text-error border border-error/20">
                                                        <AlertTriangle size={10} />
                                                        {row.daysOverdue} {t("daysOverdue")}
                                                    </span>
                                                )}
                                            </div>

                                            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                                                <div className="bg-input/70 rounded-xl p-3 border border-border">
                                                    <div className="flex items-center gap-2 mb-1">
                                                        <Calendar size={12} className="text-muted" />
                                                        <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">{t("dueDate")}</span>
                                                    </div>
                                                    <p className={cn("text-xs font-bold", row.overdue ? "text-error" : "text-foreground")}>
                                                        {fmtIsoDate(row.dueDate, locale)}
                                                    </p>
                                                </div>
                                                <div className="bg-primary/5 rounded-xl p-3 border border-primary/10">
                                                    <div className="flex items-center gap-2 mb-1">
                                                        <DollarSign size={12} className="text-primary" />
                                                        <span className="text-[9px] font-semibold text-primary uppercase tracking-[0.15em]">{t("totalPayable")}</span>
                                                    </div>
                                                    <p className="text-sm font-bold text-primary tabular-nums">{formatCurrencyCompact(row.payable)}</p>
                                                </div>
                                                <div className="bg-input/70 rounded-xl p-3 border border-border">
                                                    <div className="flex items-center gap-2 mb-1">
                                                        <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">
                                                            {tCheques("runningTotal")}
                                                        </span>
                                                    </div>
                                                    <p className="text-sm font-bold text-foreground tabular-nums" data-testid={`due-running-${row.id}`}>
                                                        {formatCurrencyCompact(running)}
                                                    </p>
                                                </div>
                                                <div className="flex items-center">
                                                    <PayOnlineButton cheque={row} onPaid={reload} />
                                                </div>
                                            </div>

                                            {row.overdue && (
                                                <p className="text-[11px] italic text-muted mt-1">
                                                    {t("overdueSince", { date: fmtIsoDate(row.dueDate, locale) })}
                                                </p>
                                            )}
                                        </div>
                                    );
                                })}
                            </div>
                        </div>
                    )}

                    {historyRows.length > 0 && (
                        <div>
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4">{t("paymentHistory")}</h2>
                            <div className="space-y-3">
                                {historyRows.map(row => (
                                    <div
                                        key={row.id}
                                        data-testid={`history-row-${row.id}`}
                                        className="bg-surface rounded-xl p-4 border border-border hover:shadow-md transition-all duration-200"
                                    >
                                        <div className="flex items-center justify-between">
                                            <div className="flex items-center gap-4">
                                                <div
                                                    className={cn(
                                                        "w-10 h-10 rounded-xl flex items-center justify-center border",
                                                        row.status === "BOUNCED" ? "bg-error/10 text-error border-error/20" : "bg-success/10 text-success border-success/20",
                                                    )}
                                                >
                                                    {row.status === "BOUNCED" ? <AlertTriangle size={18} /> : <CheckCircle size={18} />}
                                                </div>
                                                <div>
                                                    <h4 className="text-xs font-bold text-foreground">{t("cheque", { n: row.installmentNumber })}</h4>
                                                    <p className="text-[10px] font-bold text-muted">
                                                        {row.propertyName} - {row.unitIdentifier}
                                                    </p>
                                                </div>
                                            </div>
                                            <div className="flex items-center gap-4">
                                                <div className="text-end">
                                                    <p className="text-sm font-bold text-foreground tabular-nums">{formatCurrencyCompact(row.amount)}</p>
                                                    <p className="text-[10px] font-bold text-muted">{fmtIsoDate(row.dueDate, locale)}</p>
                                                </div>
                                                {row.status === "CLEARED" && (
                                                    <a
                                                        href={chequeApi.receiptUrl(row.id)}
                                                        target="_blank"
                                                        rel="noopener noreferrer"
                                                        data-testid={`receipt-link-${row.id}`}
                                                        className="flex items-center gap-1.5 px-3 py-1.5 bg-primary/10 text-primary rounded-lg text-[10px] font-semibold hover:bg-primary/20 transition-colors cursor-pointer"
                                                    >
                                                        <Download size={12} />
                                                        {tCheques("receipt")}
                                                    </a>
                                                )}
                                            </div>
                                        </div>
                                        {row.status === "BOUNCED" && (
                                            <p className="text-[11px] italic text-error mt-2">
                                                {t("bouncedOn", { date: fmtIsoDate(row.statusChangedAt, locale) })}
                                                {row.failureReason ? ` · ${tCheques(`failureReasons.${row.failureReason}`)}` : ""}
                                            </p>
                                        )}
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}
                </>
            )}
            <RenterTaxInvoices />
        </div>
    );
}
