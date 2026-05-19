"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations, useLocale } from "next-intl";
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

type Payment = {
    id: string;
    installmentNumber: number;
    dueDate: string;
    amount: number;
    status: string;
    chequeNumber: string | null;
    propertyName: string;
    unitIdentifier: string;
    renterName: string;
    leaseId: string;
    penaltyAmount: number;
    totalPayable: number;
    daysOverdue: number;
    gracePeriodDays: number;
    statusChangedAt: string | null;
    failureReason: string | null;
};

function pickNextCheque(payments: Payment[]): Payment | null {
    const candidates = payments.filter(p => p.status === "PENDING" || p.status === "OVERDUE");
    if (candidates.length === 0) return null;
    const rank = (s: string) => (s === "OVERDUE" ? 0 : 1);
    candidates.sort((a, b) => {
        const r = rank(a.status) - rank(b.status);
        if (r !== 0) return r;
        const d = new Date(a.dueDate).getTime() - new Date(b.dueDate).getTime();
        if (d !== 0) return d;
        return a.installmentNumber - b.installmentNumber;
    });
    return candidates[0];
}

function formatDate(iso: string | null | undefined, locale: string): string {
    if (!iso) return "";
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    return d.toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB", {
        day: "numeric", month: "short", year: "numeric",
    });
}

function NextChequeHero({ payments, t, locale }: { payments: Payment[]; t: (k: string, v?: Record<string, string | number>) => string; locale: string }) {
    const next = pickNextCheque(payments);
    if (!next) {
        return (
            <div className="bg-surface border border-border rounded-2xl p-5 mb-6 flex items-center gap-3">
                <CheckCircle size={20} className="text-success" />
                <p className="text-sm text-foreground">{t("allCaughtUp")}</p>
            </div>
        );
    }
    const isOverdue = next.status === "OVERDUE";
    return (
        <div className={cn(
            "rounded-2xl p-5 mb-6 border",
            isOverdue ? "bg-error/5 border-error/20" : "bg-accent/5 border-accent/20"
        )}>
            <p className={cn(
                "text-[10px] font-bold tracking-widest uppercase mb-2",
                isOverdue ? "text-error" : "text-muted"
            )}>
                {isOverdue ? t("nextChequeOverdue") : t("nextChequeDue")}
            </p>
            <p className="text-2xl font-bold text-foreground mb-1">
                AED {next.amount.toLocaleString()}
            </p>
            <p className="text-xs text-muted">
                {[
                    `${t("dueDate")}: ${formatDate(next.dueDate, locale)}`,
                    t("cheque", { n: next.installmentNumber }),
                    next.chequeNumber,
                    next.propertyName,
                    next.unitIdentifier,
                ].filter(Boolean).join(" · ")}
            </p>
        </div>
    );
}

export default function RenterPaymentsPage() {
    const t = useTranslations("OnlinePayments");
    const locale = useLocale();
    const [payments, setPayments] = useState<Payment[]>([]);
    const [loading, setLoading] = useState(true);

    const fetchPayments = useCallback(async () => {
        try {
            const res = await fetch("/api/proxy/v1/online-payments/my-payments");
            if (res.ok) {
                const data = await res.json();
                // Always sort by due date (installment order)
                data.sort((a: Payment, b: Payment) => new Date(a.dueDate).getTime() - new Date(b.dueDate).getTime());
                setPayments(data);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchPayments();
    }, [fetchPayments]);

    const pendingPayments = payments.filter(
        (p) => p.status === "PENDING" || p.status === "OVERDUE"
    );
    const completedPayments = payments.filter((p) => p.status === "CLEARED" || p.status === "DEPOSITED" || p.status === "COLLECTED" || p.status === "BOUNCED");

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
                            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                                {[1, 2, 3].map(j => (
                                    <div key={j} className="bg-input rounded-xl p-3 border border-border">
                                        <div className="h-3 w-12 bg-input rounded mb-2" />
                                        <div className="h-4 w-20 bg-input rounded" />
                                    </div>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        );
    }

    return (
        <div className="p-8 max-w-5xl mx-auto">
            {/* Header */}
            <div className="mb-10">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">
                    {t("title")}
                </h1>
                <p className="text-xs text-muted font-medium">
                    {t("description")}
                </p>
            </div>

            {/* Next Cheque Hero */}
            {!loading && payments.length > 0 && (
                <NextChequeHero payments={payments} t={t} locale={locale} />
            )}

            {/* Pending Payments */}
            {pendingPayments.length > 0 ? (
                <div className="space-y-4 mb-10">
                    {pendingPayments.map((payment) => {
                        const isOverdue = payment.daysOverdue > 0;

                        return (
                            <div
                                key={payment.id}
                                className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200"
                            >
                                <div className="flex justify-between items-start mb-4">
                                    <div className="flex items-center gap-4">
                                        <div className="w-12 h-12 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                                            <CreditCard size={22} />
                                        </div>
                                        <div>
                                            <h3 className="text-sm font-bold text-foreground tracking-tight">
                                                {t("cheque", { n: payment.installmentNumber })}
                                            </h3>
                                            <p className="text-[10px] font-bold text-muted">
                                                {payment.propertyName} - {payment.unitIdentifier}
                                            </p>
                                        </div>
                                    </div>
                                    {isOverdue && (
                                        <span className="inline-flex items-center gap-1 px-3 py-1.5 rounded-full text-[9px] font-bold uppercase tracking-widest bg-error/10 text-error border border-error/20">
                                            <AlertTriangle size={10} />
                                            {payment.daysOverdue} {t("daysOverdue")}
                                        </span>
                                    )}
                                </div>

                                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                                    <div className="bg-input/70 rounded-xl p-3 border border-border">
                                        <div className="flex items-center gap-2 mb-1">
                                            <Calendar size={12} className="text-muted" />
                                            <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">
                                                {t("dueDate")}
                                            </span>
                                        </div>
                                        <p
                                            className={cn(
                                                "text-xs font-bold",
                                                isOverdue ? "text-error" : "text-foreground"
                                            )}
                                        >
                                            {new Date(payment.dueDate).toLocaleDateString()}
                                        </p>
                                    </div>
                                    <div className="bg-input/70 rounded-xl p-3 border border-border">
                                        <div className="flex items-center gap-2 mb-1">
                                            <DollarSign size={12} className="text-muted" />
                                            <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">
                                                {t("rentAmount")}
                                            </span>
                                        </div>
                                        <p className="text-sm font-bold text-foreground tabular-nums">
                                            {formatCurrencyCompact(payment.amount)}
                                        </p>
                                    </div>
                                    {payment.penaltyAmount > 0 && (
                                        <div className="bg-error/10 rounded-xl p-3 border border-error/20">
                                            <div className="flex items-center gap-2 mb-1">
                                                <AlertTriangle size={12} className="text-error" />
                                                <span className="text-[9px] font-semibold text-error uppercase tracking-[0.15em]">
                                                    {t("penalty")}
                                                </span>
                                            </div>
                                            <p className="text-sm font-bold text-error tabular-nums">
                                                {formatCurrencyCompact(payment.penaltyAmount)}
                                            </p>
                                        </div>
                                    )}
                                    <div className="bg-primary/5 rounded-xl p-3 border border-primary/10">
                                        <div className="flex items-center gap-2 mb-1">
                                            <DollarSign size={12} className="text-primary" />
                                            <span className="text-[9px] font-semibold text-primary uppercase tracking-[0.15em]">
                                                {t("totalPayable")}
                                            </span>
                                        </div>
                                        <p className="text-sm font-bold text-primary tabular-nums">
                                            {formatCurrencyCompact(payment.totalPayable)}
                                        </p>
                                    </div>
                                </div>

                                {payment.penaltyAmount > 0 && (
                                    <div className="bg-error/5 rounded-xl p-3 mb-4 border border-error/10">
                                        <p className="text-[10px] font-bold text-error">
                                            {t("penaltyBreakdown")}: {t("rentAmount")}{" "}
                                            {formatCurrencyCompact(payment.amount)} + {t("penalty")}{" "}
                                            {formatCurrencyCompact(payment.penaltyAmount)} = {t("totalPayable")}{" "}
                                            {formatCurrencyCompact(payment.totalPayable)}
                                        </p>
                                    </div>
                                )}

                                {(payment.status === "DEPOSITED" || payment.status === "COLLECTED" || payment.status === "BOUNCED" || payment.status === "OVERDUE") && (
                                    <p className="text-[11px] italic text-muted mt-1">
                                        {payment.status === "DEPOSITED" && t("depositedOn", { date: formatDate(payment.statusChangedAt, locale) || "—" })}
                                        {payment.status === "COLLECTED" && t("collectedOn", { date: formatDate(payment.statusChangedAt, locale) || "—" })}
                                        {payment.status === "BOUNCED" && (
                                            <>
                                                {t("bouncedOn", { date: formatDate(payment.statusChangedAt, locale) || "—" })}
                                                {payment.failureReason ? ` · ${payment.failureReason}` : ""}
                                            </>
                                        )}
                                        {payment.status === "OVERDUE" && t("overdueSince", { date: formatDate(payment.dueDate, locale) || "—" })}
                                    </p>
                                )}
                            </div>
                        );
                    })}
                </div>
            ) : (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center mb-10">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-success shadow-sm mb-6">
                        <CheckCircle size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-2 uppercase tracking-widest">
                        {t("noPaymentsDue")}
                    </p>
                    <p className="text-xs text-muted">{t("allPaid")}</p>
                </div>
            )}

            {/* Completed Payments */}
            {completedPayments.length > 0 && (
                <div>
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4">
                        {t("paymentHistory")}
                    </h2>
                    <div className="space-y-3">
                        {completedPayments.map((payment) => (
                            <div
                                key={payment.id}
                                className="bg-surface rounded-xl p-4 border border-border hover:shadow-md transition-all duration-200"
                            >
                                <div className="flex items-center justify-between">
                                    <div className="flex items-center gap-4">
                                        <div className="w-10 h-10 bg-success/10 rounded-xl flex items-center justify-center text-success border border-success/20">
                                            <CheckCircle size={18} />
                                        </div>
                                        <div>
                                            <h4 className="text-xs font-bold text-foreground">
                                                {t("cheque", { n: payment.installmentNumber })}
                                            </h4>
                                            <p className="text-[10px] font-bold text-muted">
                                                {payment.propertyName} - {payment.unitIdentifier}
                                            </p>
                                        </div>
                                    </div>
                                    <div className="flex items-center gap-4">
                                        <div className="text-right">
                                            <p className="text-sm font-bold text-foreground tabular-nums">
                                                {formatCurrencyCompact(payment.amount)}
                                            </p>
                                            <p className="text-[10px] font-bold text-muted">
                                                {new Date(payment.dueDate).toLocaleDateString()}
                                            </p>
                                        </div>
                                        <button
                                            onClick={async (e) => {
                                                e.stopPropagation();
                                                const res = await fetch(`/api/proxy/v1/payments/${payment.id}/receipt`);
                                                if (res.ok) {
                                                    const blob = await res.blob();
                                                    const url = URL.createObjectURL(blob);
                                                    const a = document.createElement('a');
                                                    a.href = url;
                                                    a.download = `receipt-${payment.installmentNumber}.pdf`;
                                                    document.body.appendChild(a);
                                                    a.click();
                                                    document.body.removeChild(a);
                                                    URL.revokeObjectURL(url);
                                                }
                                            }}
                                            className="flex items-center gap-1.5 px-3 py-1.5 bg-primary/10 text-primary rounded-lg text-[10px] font-semibold hover:bg-primary/20 transition-colors cursor-pointer"
                                        >
                                            <Download size={12} />
                                            Receipt
                                        </button>
                                    </div>
                                </div>
                                {(payment.status === "DEPOSITED" || payment.status === "COLLECTED" || payment.status === "BOUNCED") && (
                                    <p className="text-[11px] italic text-muted mt-2">
                                        {payment.status === "DEPOSITED" && t("depositedOn", { date: formatDate(payment.statusChangedAt, locale) || "—" })}
                                        {payment.status === "COLLECTED" && t("collectedOn", { date: formatDate(payment.statusChangedAt, locale) || "—" })}
                                        {payment.status === "BOUNCED" && (
                                            <>
                                                {t("bouncedOn", { date: formatDate(payment.statusChangedAt, locale) || "—" })}
                                                {payment.failureReason ? ` · ${payment.failureReason}` : ""}
                                            </>
                                        )}
                                    </p>
                                )}
                            </div>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
}
