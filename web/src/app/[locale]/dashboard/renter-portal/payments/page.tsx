"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import {
    CreditCard,
    Calendar,
    DollarSign,
    Loader2,
    CheckCircle,
    XCircle,
    AlertTriangle,
    Clock,
    AlertCircle,
    Download,
} from "lucide-react";
import { useSession } from "next-auth/react";
import { cn } from "@/lib/utils";
import { formatCurrencyCompact } from "@/lib/format";

declare global {
    interface Window {
        Razorpay: any;
    }
}

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
};

type GatewayConfig = {
    id: string;
    gatewayCode: string;
    gatewayName: string;
    isActive: boolean;
    isTestMode: boolean;
} | null;

export default function RenterPaymentsPage() {
    const t = useTranslations("OnlinePayments");
    const [payments, setPayments] = useState<Payment[]>([]);
    const [loading, setLoading] = useState(true);
    const [gatewayConfig, setGatewayConfig] = useState<GatewayConfig>(null);
    const [processingPaymentId, setProcessingPaymentId] = useState<string | null>(null);
    const [successModal, setSuccessModal] = useState(false);
    const [errorMessage, setErrorMessage] = useState<string | null>(null);
    const { data: session } = useSession();

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

    const fetchGatewayConfig = useCallback(async () => {
        try {
            const res = await fetch("/api/proxy/v1/gateway-config");
            if (res.ok) {
                const data = await res.json();
                setGatewayConfig(data);
            }
        } catch (err) {
            console.error(err);
        }
    }, []);

    useEffect(() => {
        fetchPayments();
        fetchGatewayConfig();
    }, [fetchPayments, fetchGatewayConfig]);

    const loadRazorpayScript = (sdkJsUrl: string): Promise<void> => {
        return new Promise((resolve, reject) => {
            if (window.Razorpay) {
                resolve();
                return;
            }
            const script = document.createElement("script");
            script.src = sdkJsUrl;
            script.onload = () => resolve();
            script.onerror = () => reject(new Error("Failed to load payment SDK"));
            document.body.appendChild(script);
        });
    };

    const handlePayNow = async (payment: Payment) => {
        setProcessingPaymentId(payment.id);
        setErrorMessage(null);

        try {
            // Step 1: Create order
            const orderRes = await fetch("/api/proxy/v1/online-payments/create-order", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ paymentScheduleId: payment.id }),
            });

            if (!orderRes.ok) {
                throw new Error("Failed to create payment order");
            }

            const orderData = await orderRes.json();

            // Step 2: Load Razorpay SDK dynamically
            await loadRazorpayScript(orderData.sdkJsUrl);

            // Step 3: Open Razorpay checkout
            const options = {
                key: orderData.gatewayKey,
                amount: orderData.amount,
                currency: orderData.currency,
                order_id: orderData.orderId,
                name: "RentAxis",
                prefill: {
                    name: orderData.renterName,
                    email: orderData.renterEmail,
                },
                handler: async (response: any) => {
                    // Step 4: Verify payment
                    try {
                        const verifyRes = await fetch("/api/proxy/v1/online-payments/verify", {
                            method: "POST",
                            headers: { "Content-Type": "application/json" },
                            body: JSON.stringify({
                                gatewayOrderId: response.razorpay_order_id,
                                gatewayPaymentId: response.razorpay_payment_id,
                                gatewaySignature: response.razorpay_signature,
                            }),
                        });

                        if (verifyRes.ok) {
                            setSuccessModal(true);
                        } else {
                            setErrorMessage(t("paymentFailed"));
                        }
                    } catch {
                        setErrorMessage(t("paymentFailed"));
                    } finally {
                        setProcessingPaymentId(null);
                        fetchPayments();
                    }
                },
                modal: {
                    ondismiss: async () => {
                        // Revert ONLINE_PENDING back to PENDING
                        try {
                            await fetch(`/api/proxy/v1/online-payments/cancel/${payment.id}`, { method: "POST" });
                        } catch {}
                        setProcessingPaymentId(null);
                        fetchPayments();
                    },
                },
            };

            const rzp = new window.Razorpay(options);
            rzp.on("payment.failed", async () => {
                try {
                    await fetch(`/api/proxy/v1/online-payments/cancel/${payment.id}`, { method: "POST" });
                } catch {}
                setErrorMessage(t("paymentFailed"));
                setProcessingPaymentId(null);
                fetchPayments();
            });
            rzp.open();
        } catch (err) {
            console.error(err);
            setErrorMessage(t("paymentFailed"));
            setProcessingPaymentId(null);
            fetchPayments();
        }
    };

    const pendingPayments = payments.filter(
        (p) => p.status === "PENDING" || p.status === "ONLINE_PENDING"
    );
    const completedPayments = payments.filter((p) => p.status === "CLEARED");

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
                            <div className="border-t border-border pt-4">
                                <div className="h-9 w-28 bg-input rounded-xl" />
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

            {/* Error Message */}
            {errorMessage && (
                <div className="mb-6 bg-error/10 border border-error/20 rounded-xl p-4 flex items-center gap-3">
                    <XCircle size={18} className="text-error shrink-0" />
                    <p className="text-sm font-semibold text-error">{errorMessage}</p>
                    <button
                        onClick={() => setErrorMessage(null)}
                        className="ml-auto text-error/60 hover:text-error cursor-pointer transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-error/30 rounded-full p-1"
                        aria-label="Dismiss error"
                    >
                        <XCircle size={16} />
                    </button>
                </div>
            )}

            {/* Gateway not configured warning */}
            {!gatewayConfig?.isActive && (
                <div className="mb-6 bg-warning/10 border border-warning/20 rounded-xl p-4 flex items-center gap-3">
                    <AlertTriangle size={18} className="text-warning shrink-0" />
                    <p className="text-sm font-semibold text-warning">
                        {t("gatewayNotConfigured")}
                    </p>
                </div>
            )}

            {/* Pending Payments */}
            {pendingPayments.length > 0 ? (
                <div className="space-y-4 mb-10">
                    {pendingPayments.map((payment) => {
                        const isOverdue = payment.daysOverdue > 0;
                        const isOnlinePending = payment.status === "ONLINE_PENDING";
                        const isProcessing = processingPaymentId === payment.id;

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
                                                Installment #{payment.installmentNumber}
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
                                    {isOnlinePending && (
                                        <span className="inline-flex items-center gap-1 px-3 py-1.5 rounded-full text-[9px] font-bold uppercase tracking-widest bg-warning/10 text-warning border border-warning/20">
                                            <Loader2 size={10} className="animate-spin" />
                                            {t("onlinePending")}
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

                                <div className="flex gap-3 border-t border-border pt-4">
                                    {isOnlinePending ? (
                                        <div className="flex items-center gap-2 text-xs text-warning font-medium">
                                            <Loader2 size={14} className="animate-spin" />
                                            {t("paymentProcessing")}
                                        </div>
                                    ) : (
                                        <button
                                            onClick={() => handlePayNow(payment)}
                                            disabled={
                                                isProcessing || !gatewayConfig?.isActive
                                            }
                                            className={cn(
                                                "flex items-center gap-2 px-6 py-2.5 rounded-xl text-xs font-semibold transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-accent/30",
                                                isProcessing || !gatewayConfig?.isActive
                                                    ? "bg-input text-muted cursor-not-allowed"
                                                    : "bg-accent text-accent-foreground hover:brightness-110 active:scale-95 cursor-pointer"
                                            )}
                                        >
                                            {isProcessing ? (
                                                <Loader2 size={14} className="animate-spin" />
                                            ) : (
                                                <CreditCard size={14} />
                                            )}
                                            {t("payNow")}
                                        </button>
                                    )}
                                </div>
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
                                className="bg-surface rounded-xl p-4 border border-border hover:shadow-md transition-all duration-200 flex items-center justify-between"
                            >
                                <div className="flex items-center gap-4">
                                    <div className="w-10 h-10 bg-success/10 rounded-xl flex items-center justify-center text-success border border-success/20">
                                        <CheckCircle size={18} />
                                    </div>
                                    <div>
                                        <h4 className="text-xs font-bold text-foreground">
                                            Installment #{payment.installmentNumber}
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
                        ))}
                    </div>
                </div>
            )}

            {/* Success Modal */}
            {successModal && (
                <div className="fixed inset-0 bg-foreground/40 z-50 flex items-center justify-center p-4">
                    <div className="bg-surface rounded-xl p-8 max-w-sm w-full text-center shadow-xl border border-border">
                        <div className="w-16 h-16 bg-success/10 rounded-xl flex items-center justify-center text-success mx-auto mb-6">
                            <CheckCircle size={36} />
                        </div>
                        <h3 className="text-lg font-bold text-foreground mb-2">
                            {t("paymentSuccessful")}
                        </h3>
                        <p className="text-xs text-muted mb-6">
                            {t("allPaid")}
                        </p>
                        <button
                            onClick={() => setSuccessModal(false)}
                            className="bg-primary text-primary-foreground px-6 py-2.5 rounded-xl text-xs font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
                        >
                            OK
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
