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
} from "lucide-react";
import { useSession } from "next-auth/react";
import { cn } from "@/lib/utils";

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
            if (res.ok) setPayments(await res.json());
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
                    ondismiss: () => {
                        setProcessingPaymentId(null);
                        fetchPayments();
                    },
                },
            };

            const rzp = new window.Razorpay(options);
            rzp.on("payment.failed", () => {
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
                    <div className="h-6 w-40 bg-gray-200 rounded animate-pulse mb-2" />
                    <div className="h-4 w-56 bg-gray-100 rounded animate-pulse" />
                </div>
                <div className="space-y-4">
                    {[1, 2].map(i => (
                        <div key={i} className="bg-white rounded-[2rem] p-6 shadow-sm border border-gray-100 animate-pulse">
                            <div className="flex justify-between items-start mb-4">
                                <div className="flex items-center gap-4">
                                    <div className="w-12 h-12 bg-gray-200 rounded-2xl" />
                                    <div>
                                        <div className="h-4 w-28 bg-gray-200 rounded mb-2" />
                                        <div className="h-3 w-40 bg-gray-100 rounded" />
                                    </div>
                                </div>
                            </div>
                            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                                {[1, 2, 3].map(j => (
                                    <div key={j} className="bg-gray-50 rounded-xl p-3 border border-gray-100/50">
                                        <div className="h-3 w-12 bg-gray-200 rounded mb-2" />
                                        <div className="h-4 w-20 bg-gray-200 rounded" />
                                    </div>
                                ))}
                            </div>
                            <div className="border-t border-gray-100 pt-4">
                                <div className="h-9 w-28 bg-gray-200 rounded-xl" />
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
                <h1 className="text-xl font-black text-foreground tracking-tight mb-1">
                    {t("title")}
                </h1>
                <p className="text-xs text-gray-500 font-medium">
                    {t("description")}
                </p>
            </div>

            {/* Error Message */}
            {errorMessage && (
                <div className="mb-6 bg-red-50 border border-red-200 rounded-2xl p-4 flex items-center gap-3">
                    <XCircle size={18} className="text-red-500 shrink-0" />
                    <p className="text-sm font-semibold text-red-700">{errorMessage}</p>
                    <button
                        onClick={() => setErrorMessage(null)}
                        className="ml-auto text-red-400 hover:text-red-600 cursor-pointer transition-colors duration-200 focus:outline-none focus:ring-2 focus:ring-red-300 rounded-full p-1"
                        aria-label="Dismiss error"
                    >
                        <XCircle size={16} />
                    </button>
                </div>
            )}

            {/* Gateway not configured warning */}
            {!gatewayConfig?.isActive && (
                <div className="mb-6 bg-yellow-50 border border-yellow-200 rounded-2xl p-4 flex items-center gap-3">
                    <AlertTriangle size={18} className="text-yellow-600 shrink-0" />
                    <p className="text-sm font-semibold text-yellow-700">
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
                                className="bg-white rounded-[2rem] p-6 shadow-sm border border-gray-100 hover:shadow-md transition-all duration-200"
                            >
                                <div className="flex justify-between items-start mb-4">
                                    <div className="flex items-center gap-4">
                                        <div className="w-12 h-12 bg-primary/10 rounded-2xl flex items-center justify-center text-primary border border-primary/20">
                                            <CreditCard size={22} />
                                        </div>
                                        <div>
                                            <h3 className="text-sm font-black text-foreground tracking-tight">
                                                {t("Payments.installment", { defaultMessage: "Installment" })} #{payment.installmentNumber}
                                            </h3>
                                            <p className="text-[10px] font-bold text-gray-400">
                                                {payment.propertyName} - {payment.unitIdentifier}
                                            </p>
                                        </div>
                                    </div>
                                    {isOverdue && (
                                        <span className="inline-flex items-center gap-1 px-3 py-1.5 rounded-full text-[9px] font-bold uppercase tracking-widest border bg-red-50 text-red-600 border-red-200">
                                            <AlertTriangle size={10} />
                                            {payment.daysOverdue} {t("daysOverdue")}
                                        </span>
                                    )}
                                    {isOnlinePending && (
                                        <span className="inline-flex items-center gap-1 px-3 py-1.5 rounded-full text-[9px] font-bold uppercase tracking-widest border bg-yellow-50 text-yellow-600 border-yellow-200">
                                            <Loader2 size={10} className="animate-spin" />
                                            {t("onlinePending")}
                                        </span>
                                    )}
                                </div>

                                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                                    <div className="bg-gray-50 rounded-xl p-3 border border-gray-100/50">
                                        <div className="flex items-center gap-2 mb-1">
                                            <Calendar size={12} className="text-gray-400" />
                                            <span className="text-[9px] font-bold text-gray-400 uppercase">
                                                {t("dueDate")}
                                            </span>
                                        </div>
                                        <p
                                            className={cn(
                                                "text-xs font-bold",
                                                isOverdue ? "text-red-600" : "text-foreground"
                                            )}
                                        >
                                            {new Date(payment.dueDate).toLocaleDateString()}
                                        </p>
                                    </div>
                                    <div className="bg-gray-50 rounded-xl p-3 border border-gray-100/50">
                                        <div className="flex items-center gap-2 mb-1">
                                            <DollarSign size={12} className="text-gray-400" />
                                            <span className="text-[9px] font-bold text-gray-400 uppercase">
                                                {t("rentAmount")}
                                            </span>
                                        </div>
                                        <p className="text-sm font-black text-foreground">
                                            AED {payment.amount.toLocaleString()}
                                        </p>
                                    </div>
                                    {payment.penaltyAmount > 0 && (
                                        <div className="bg-red-50 rounded-xl p-3 border border-red-100/50">
                                            <div className="flex items-center gap-2 mb-1">
                                                <AlertTriangle size={12} className="text-red-400" />
                                                <span className="text-[9px] font-bold text-red-400 uppercase">
                                                    {t("penalty")}
                                                </span>
                                            </div>
                                            <p className="text-sm font-black text-red-600">
                                                AED {payment.penaltyAmount.toLocaleString()}
                                            </p>
                                        </div>
                                    )}
                                    <div className="bg-primary/5 rounded-xl p-3 border border-primary/10">
                                        <div className="flex items-center gap-2 mb-1">
                                            <DollarSign size={12} className="text-primary" />
                                            <span className="text-[9px] font-bold text-primary uppercase">
                                                {t("totalPayable")}
                                            </span>
                                        </div>
                                        <p className="text-sm font-black text-primary">
                                            AED {payment.totalPayable.toLocaleString()}
                                        </p>
                                    </div>
                                </div>

                                {payment.penaltyAmount > 0 && (
                                    <div className="bg-red-50/50 rounded-xl p-3 mb-4 border border-red-100/50">
                                        <p className="text-[10px] font-bold text-red-500">
                                            {t("penaltyBreakdown")}: {t("rentAmount")} AED{" "}
                                            {payment.amount.toLocaleString()} + {t("penalty")} AED{" "}
                                            {payment.penaltyAmount.toLocaleString()} = {t("totalPayable")}{" "}
                                            AED {payment.totalPayable.toLocaleString()}
                                        </p>
                                    </div>
                                )}

                                <div className="flex gap-3 border-t border-gray-100 pt-4">
                                    {isOnlinePending ? (
                                        <div className="flex items-center gap-2 text-xs text-yellow-600 font-medium">
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
                                                "flex items-center gap-2 px-6 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 focus:outline-none focus:ring-2 focus:ring-blue-300",
                                                isProcessing || !gatewayConfig?.isActive
                                                    ? "bg-gray-100 text-gray-400 cursor-not-allowed"
                                                    : "bg-blue-600 text-white hover:bg-blue-700 active:scale-95 cursor-pointer"
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
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center mb-10">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-green-400 shadow-sm mb-6">
                        <CheckCircle size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-2 uppercase tracking-widest">
                        {t("noPaymentsDue")}
                    </p>
                    <p className="text-xs text-gray-400">{t("allPaid")}</p>
                </div>
            )}

            {/* Completed Payments */}
            {completedPayments.length > 0 && (
                <div>
                    <h2 className="text-sm font-black text-foreground tracking-tight mb-4">
                        {t("paymentHistory")}
                    </h2>
                    <div className="space-y-3">
                        {completedPayments.map((payment) => (
                            <div
                                key={payment.id}
                                className="bg-white rounded-2xl p-4 shadow-sm border border-gray-100 flex items-center justify-between"
                            >
                                <div className="flex items-center gap-4">
                                    <div className="w-10 h-10 bg-green-50 rounded-xl flex items-center justify-center text-green-500 border border-green-100">
                                        <CheckCircle size={18} />
                                    </div>
                                    <div>
                                        <h4 className="text-xs font-bold text-foreground">
                                            Installment #{payment.installmentNumber}
                                        </h4>
                                        <p className="text-[10px] font-bold text-gray-400">
                                            {payment.propertyName} - {payment.unitIdentifier}
                                        </p>
                                    </div>
                                </div>
                                <div className="text-right">
                                    <p className="text-sm font-black text-foreground">
                                        AED {payment.amount.toLocaleString()}
                                    </p>
                                    <p className="text-[10px] font-bold text-gray-400">
                                        {new Date(payment.dueDate).toLocaleDateString()}
                                    </p>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {/* Success Modal */}
            {successModal && (
                <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4">
                    <div className="bg-white rounded-[2rem] p-8 max-w-sm w-full text-center shadow-xl">
                        <div className="w-16 h-16 bg-green-50 rounded-2xl flex items-center justify-center text-green-500 mx-auto mb-6">
                            <CheckCircle size={36} />
                        </div>
                        <h3 className="text-lg font-black text-foreground mb-2">
                            {t("paymentSuccessful")}
                        </h3>
                        <p className="text-xs text-gray-500 mb-6">
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
