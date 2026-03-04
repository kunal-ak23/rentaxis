"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import {
    CreditCard,
    Calendar,
    DollarSign,
    AlertCircle,
    CheckCircle,
    XCircle,
    ArrowRightCircle,
    RefreshCw,
    Building2,
    X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { canViewPayments, canManagePayments } from "@/lib/rbac";
import type { UserRole } from "@/lib/rbac";

type Payment = {
    id: string;
    installmentNumber: number;
    dueDate: string;
    amount: number;
    status: string;
    chequeNumber: string | null;
    bankName: string | null;
    payerName: string | null;
    propertyName: string | null;
    unitIdentifier: string | null;
    renterName: string | null;
    leaseId: string;
};

type PaymentSummary = {
    totalPayments: number;
    pendingCount: number;
    collectedCount: number;
    depositedCount: number;
    clearedCount: number;
    bouncedCount: number;
    overdueCount: number;
    totalAmount: number;
    pendingAmount: number;
    collectedAmount: number;
    clearedAmount: number;
    overdueAmount: number;
};

type Property = {
    id: string;
    nameEn: string;
};

const STATUS_COLORS: Record<string, string> = {
    PENDING: "bg-gray-100 text-gray-700 border-gray-200",
    COLLECTED: "bg-blue-100 text-blue-700 border-blue-200",
    DEPOSITED: "bg-amber-100 text-amber-700 border-amber-200",
    CLEARED: "bg-green-100 text-green-700 border-green-200",
    BOUNCED: "bg-red-100 text-red-700 border-red-200",
    REPLACED: "bg-purple-100 text-purple-700 border-purple-200",
};

const ALL_STATUSES = ["PENDING", "COLLECTED", "DEPOSITED", "CLEARED", "BOUNCED", "REPLACED"];

export default function PaymentsPage() {
    const t = useTranslations("Payments");
    const { data: session } = useSession();
    const userRole = (session?.user as any)?.role as UserRole | undefined;
    const canView = userRole ? canViewPayments(userRole) : false;
    const canManage = userRole ? canManagePayments(userRole) : false;

    const [payments, setPayments] = useState<Payment[]>([]);
    const [summary, setSummary] = useState<PaymentSummary | null>(null);
    const [properties, setProperties] = useState<Property[]>([]);
    const [loading, setLoading] = useState(true);

    const [selectedProperty, setSelectedProperty] = useState("");
    const [selectedStatus, setSelectedStatus] = useState("");

    // Cheque collection modal state
    const [showChequeModal, setShowChequeModal] = useState(false);
    const [collectingPaymentId, setCollectingPaymentId] = useState<string | null>(null);
    const [chequeForm, setChequeForm] = useState({
        chequeNumber: "",
        bankName: "",
        payerName: "",
    });

    // Confirmation dialog state (replaces window.confirm)
    const [confirmDialog, setConfirmDialog] = useState<{
        open: boolean;
        title: string;
        message: string;
        onConfirm: () => void;
        variant: "green" | "red";
    }>({ open: false, title: "", message: "", onConfirm: () => {}, variant: "green" });

    useEffect(() => {
        fetchPayments();
        fetchSummary();
        fetchProperties();
    }, []);

    useEffect(() => {
        fetchPayments();
        fetchSummary();
    }, [selectedProperty, selectedStatus]);

    const fetchPayments = async () => {
        try {
            const params = new URLSearchParams();
            if (selectedProperty) params.set("propertyId", selectedProperty);
            if (selectedStatus) params.set("status", selectedStatus);

            const res = await fetch(`/api/proxy/v1/payments?${params.toString()}`);
            if (res.ok) {
                const data = await res.json();
                setPayments(data);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const fetchSummary = async () => {
        try {
            const params = new URLSearchParams();
            if (selectedProperty) params.set("propertyId", selectedProperty);

            const res = await fetch(`/api/proxy/v1/payments/summary?${params.toString()}`);
            if (res.ok) {
                const data = await res.json();
                setSummary(data);
            }
        } catch (err) {
            console.error(err);
        }
    };

    const fetchProperties = async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) setProperties(await res.json());
        } catch (err) {
            console.error(err);
        }
    };

    const refreshData = () => {
        fetchPayments();
        fetchSummary();
    };

    const handleCollect = (paymentId: string) => {
        setCollectingPaymentId(paymentId);
        setChequeForm({ chequeNumber: "", bankName: "", payerName: "" });
        setShowChequeModal(true);
    };

    const submitCollect = async (ev: React.FormEvent) => {
        ev.preventDefault();
        if (!collectingPaymentId) return;
        try {
            const res = await fetch(`/api/proxy/v1/payments/${collectingPaymentId}/collect`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(chequeForm),
            });
            if (res.ok) {
                setShowChequeModal(false);
                setCollectingPaymentId(null);
                refreshData();
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleDeposit = async (paymentId: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/payments/${paymentId}/deposit`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({}),
            });
            if (res.ok) refreshData();
        } catch (err) {
            console.error(err);
        }
    };

    const handleClear = (paymentId: string) => {
        setConfirmDialog({
            open: true,
            title: t("clear"),
            message: t("confirmClear"),
            variant: "green",
            onConfirm: async () => {
                setConfirmDialog((prev) => ({ ...prev, open: false }));
                try {
                    const res = await fetch(`/api/proxy/v1/payments/${paymentId}/clear`, {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({}),
                    });
                    if (res.ok) refreshData();
                } catch (err) {
                    console.error(err);
                }
            },
        });
    };

    const handleBounce = (paymentId: string) => {
        setConfirmDialog({
            open: true,
            title: t("bounce"),
            message: t("confirmBounce"),
            variant: "red",
            onConfirm: async () => {
                setConfirmDialog((prev) => ({ ...prev, open: false }));
                try {
                    const res = await fetch(`/api/proxy/v1/payments/${paymentId}/bounce`, {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({}),
                    });
                    if (res.ok) refreshData();
                } catch (err) {
                    console.error(err);
                }
            },
        });
    };

    const handleReplace = async (paymentId: string) => {
        setCollectingPaymentId(paymentId);
        setChequeForm({ chequeNumber: "", bankName: "", payerName: "" });
        setShowChequeModal(true);
    };

    const submitReplace = async (ev: React.FormEvent) => {
        ev.preventDefault();
        if (!collectingPaymentId) return;
        try {
            const res = await fetch(`/api/proxy/v1/payments/${collectingPaymentId}/replace`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(chequeForm),
            });
            if (res.ok) {
                setShowChequeModal(false);
                setCollectingPaymentId(null);
                refreshData();
            }
        } catch (err) {
            console.error(err);
        }
    };

    // Determine if the modal is for replace or collect
    const isReplaceAction = collectingPaymentId
        ? payments.find((p) => p.id === collectingPaymentId)?.status === "BOUNCED"
        : false;

    const getStatusLabel = (status: string): string => {
        const map: Record<string, string> = {
            PENDING: t("pending"),
            COLLECTED: t("collected"),
            DEPOSITED: t("deposited"),
            CLEARED: t("cleared"),
            BOUNCED: t("bounced"),
            REPLACED: t("replaced"),
        };
        return map[status] || status;
    };

    const summaryCards = summary
        ? [
              { label: t("totalDue"), value: summary.totalAmount ?? 0, color: "text-blue-600", bg: "bg-blue-50", icon: DollarSign },
              { label: t("collected"), value: summary.collectedAmount ?? 0, color: "text-amber-600", bg: "bg-amber-50", icon: ArrowRightCircle },
              { label: t("cleared"), value: summary.clearedAmount ?? 0, color: "text-green-600", bg: "bg-green-50", icon: CheckCircle },
              { label: t("overdue"), value: summary.overdueAmount ?? 0, color: "text-red-600", bg: "bg-red-50", icon: AlertCircle },
          ]
        : [];

    return (
        <div>
            {/* Header */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <CreditCard size={20} className="text-primary" />
                        {t("title")}
                    </h1>
                    <p className="text-xs text-gray-500 font-medium">{t("description")}</p>
                </div>
            </div>

            {/* Summary Cards */}
            {summary && (
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
                    {summaryCards.map((card) => {
                        const Icon = card.icon;
                        return (
                            <div
                                key={card.label}
                                className="bg-white border border-gray-100 rounded-2xl p-5 shadow-sm"
                            >
                                <div className="flex items-center gap-3 mb-3">
                                    <div
                                        className={cn(
                                            "w-9 h-9 rounded-xl flex items-center justify-center",
                                            card.bg
                                        )}
                                    >
                                        <Icon size={16} className={card.color} />
                                    </div>
                                    <span className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {card.label}
                                    </span>
                                </div>
                                <p className={cn("text-lg font-black", card.color)}>
                                    AED {card.value.toLocaleString()}
                                </p>
                                {card.label === t("cleared") && summary.totalPayments > 0 && (
                                    <p className="text-[10px] text-gray-400 mt-1 font-medium">
                                        {summary.clearedCount}/{summary.totalPayments} {t("paymentsCleared")}
                                    </p>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}

            {/* Filters */}
            <div className="flex flex-col sm:flex-row gap-3 mb-6">
                <div className="relative">
                    <select
                        className="appearance-none bg-white border border-border px-4 py-2.5 rounded-xl text-xs font-bold pr-8 cursor-pointer"
                        value={selectedProperty}
                        onChange={(ev) => setSelectedProperty(ev.target.value)}
                    >
                        <option value="">{t("allProperties")}</option>
                        {properties.map((p) => (
                            <option key={p.id} value={p.id}>
                                {p.nameEn}
                            </option>
                        ))}
                    </select>
                </div>
                <div className="relative">
                    <select
                        className="appearance-none bg-white border border-border px-4 py-2.5 rounded-xl text-xs font-bold pr-8 cursor-pointer"
                        value={selectedStatus}
                        onChange={(ev) => setSelectedStatus(ev.target.value)}
                    >
                        <option value="">{t("allStatuses")}</option>
                        {ALL_STATUSES.map((s) => (
                            <option key={s} value={s}>
                                {getStatusLabel(s)}
                            </option>
                        ))}
                    </select>
                </div>
            </div>

            {/* Payments Table */}
            <div className="bg-white border border-border rounded-2xl overflow-hidden shadow-sm">
                <div className="overflow-x-auto">
                    <table className="w-full">
                        <thead>
                            <tr className="border-b border-gray-100">
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                    #
                                </th>
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                    {t("dueDate")}
                                </th>
                                <th className="text-right px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                    {t("amount")} (AED)
                                </th>
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                    {t("propertyUnit")}
                                </th>
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                    {t("renter")}
                                </th>
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                    {t("chequeNumber")}
                                </th>
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                    {t("status")}
                                </th>
                                {canManage && (
                                    <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                                        {t("actions")}
                                    </th>
                                )}
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-50">
                            {payments.map((payment) => (
                                <tr
                                    key={payment.id}
                                    className="hover:bg-gray-50/50 transition-colors"
                                >
                                    <td className="px-5 py-3 text-xs font-bold text-foreground">
                                        {payment.installmentNumber}
                                    </td>
                                    <td className="px-5 py-3 text-xs text-foreground font-medium">
                                        <div className="flex items-center gap-1.5">
                                            <Calendar size={12} className="text-gray-300" />
                                            {new Date(payment.dueDate).toLocaleDateString()}
                                        </div>
                                    </td>
                                    <td className="px-5 py-3 text-right text-xs font-black text-foreground">
                                        {(payment.amount ?? 0).toLocaleString()} AED
                                    </td>
                                    <td className="px-5 py-3">
                                        <div className="flex items-center gap-1.5">
                                            {payment.propertyName ? (
                                                <>
                                                    <Building2
                                                        size={12}
                                                        className="text-gray-300"
                                                    />
                                                    <span className="text-xs text-foreground font-medium">
                                                        {payment.propertyName}
                                                    </span>
                                                </>
                                            ) : (
                                                <span className="text-[10px] text-gray-400">
                                                    --
                                                </span>
                                            )}
                                            {payment.unitIdentifier && (
                                                <span className="text-[9px] text-primary bg-primary/5 px-1.5 py-0.5 rounded font-bold ml-1">
                                                    {payment.unitIdentifier}
                                                </span>
                                            )}
                                        </div>
                                    </td>
                                    <td className="px-5 py-3 text-xs text-foreground font-medium">
                                        {payment.renterName || "--"}
                                    </td>
                                    <td className="px-5 py-3 text-xs text-foreground font-medium">
                                        {payment.chequeNumber || "--"}
                                    </td>
                                    <td className="px-5 py-3">
                                        <span
                                            className={cn(
                                                "inline-flex items-center px-2.5 py-1 rounded-full text-[9px] font-bold uppercase tracking-widest border",
                                                STATUS_COLORS[payment.status] ||
                                                    "bg-gray-100 text-gray-500"
                                            )}
                                        >
                                            {getStatusLabel(payment.status)}
                                        </span>
                                    </td>
                                    {canManage && (
                                        <td className="px-5 py-3">
                                            <div className="flex gap-2">
                                                {payment.status === "PENDING" && (
                                                    <button
                                                        onClick={() => handleCollect(payment.id)}
                                                        className="px-3 py-1.5 bg-blue-50 text-blue-700 hover:bg-blue-100 rounded-lg text-[10px] font-bold transition-colors"
                                                    >
                                                        {t("collect")}
                                                    </button>
                                                )}
                                                {payment.status === "COLLECTED" && (
                                                    <button
                                                        onClick={() => handleDeposit(payment.id)}
                                                        className="px-3 py-1.5 bg-amber-50 text-amber-700 hover:bg-amber-100 rounded-lg text-[10px] font-bold transition-colors"
                                                    >
                                                        {t("deposit")}
                                                    </button>
                                                )}
                                                {payment.status === "DEPOSITED" && (
                                                    <>
                                                        <button
                                                            onClick={() =>
                                                                handleClear(payment.id)
                                                            }
                                                            className="px-3 py-1.5 bg-green-50 text-green-700 hover:bg-green-100 rounded-lg text-[10px] font-bold transition-colors"
                                                        >
                                                            {t("clear")}
                                                        </button>
                                                        <button
                                                            onClick={() =>
                                                                handleBounce(payment.id)
                                                            }
                                                            className="px-3 py-1.5 bg-red-50 text-red-600 hover:bg-red-100 rounded-lg text-[10px] font-bold transition-colors"
                                                        >
                                                            {t("bounce")}
                                                        </button>
                                                    </>
                                                )}
                                                {payment.status === "BOUNCED" && (
                                                    <button
                                                        onClick={() => handleReplace(payment.id)}
                                                        className="px-3 py-1.5 bg-purple-50 text-purple-700 hover:bg-purple-100 rounded-lg text-[10px] font-bold transition-colors"
                                                    >
                                                        {t("replace")}
                                                    </button>
                                                )}
                                            </div>
                                        </td>
                                    )}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </div>

            {/* Empty State */}
            {payments.length === 0 && !loading && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center mt-6">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <CreditCard size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-2 uppercase tracking-widest">
                        {t("noPayments")}
                    </p>
                    <p className="text-xs text-gray-400 font-medium">{t("activateLeaseFirst")}</p>
                </div>
            )}

            {/* Confirmation Dialog */}
            {confirmDialog.open && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-sm w-full shadow-2xl border border-gray-100">
                        <h2 className="text-lg font-black mb-2">{confirmDialog.title}</h2>
                        <p className="text-sm text-gray-500 mb-8">{confirmDialog.message}</p>
                        <div className="flex justify-end gap-3">
                            <button
                                onClick={() => setConfirmDialog((prev) => ({ ...prev, open: false }))}
                                className="px-6 py-3 text-xs font-bold text-gray-500"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={confirmDialog.onConfirm}
                                className={cn(
                                    "px-8 py-3 rounded-xl text-xs font-bold text-white",
                                    confirmDialog.variant === "green"
                                        ? "bg-green-600 hover:bg-green-700"
                                        : "bg-red-600 hover:bg-red-700"
                                )}
                            >
                                {confirmDialog.title}
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Cheque Details Modal */}
            {showChequeModal && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-md w-full shadow-2xl border border-gray-100 relative">
                        <button
                            onClick={() => {
                                setShowChequeModal(false);
                                setCollectingPaymentId(null);
                            }}
                            className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-black mb-1">
                            {isReplaceAction ? t("replacementCheque") : t("chequeDetails")}
                        </h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">
                            {t("enterChequeDetails")}
                        </p>
                        <form
                            onSubmit={isReplaceAction ? submitReplace : submitCollect}
                            className="space-y-5"
                        >
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("chequeNumber")}
                                </label>
                                <input
                                    required
                                    placeholder="CHQ-000001"
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs"
                                    value={chequeForm.chequeNumber}
                                    onChange={(ev) =>
                                        setChequeForm({
                                            ...chequeForm,
                                            chequeNumber: ev.target.value,
                                        })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("bankName")}
                                </label>
                                <input
                                    required
                                    placeholder="Emirates NBD"
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs"
                                    value={chequeForm.bankName}
                                    onChange={(ev) =>
                                        setChequeForm({
                                            ...chequeForm,
                                            bankName: ev.target.value,
                                        })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">
                                    {t("payerName")}
                                </label>
                                <input
                                    required
                                    placeholder="John Doe"
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs"
                                    value={chequeForm.payerName}
                                    onChange={(ev) =>
                                        setChequeForm({
                                            ...chequeForm,
                                            payerName: ev.target.value,
                                        })
                                    }
                                />
                            </div>
                            <div className="flex justify-end gap-3 mt-6">
                                <button
                                    type="button"
                                    onClick={() => {
                                        setShowChequeModal(false);
                                        setCollectingPaymentId(null);
                                    }}
                                    className="px-6 py-3 text-xs font-bold text-gray-500"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    className={cn(
                                        "px-8 py-3 rounded-xl text-xs font-bold text-white",
                                        isReplaceAction
                                            ? "bg-purple-600 hover:bg-purple-700"
                                            : "bg-primary hover:opacity-90"
                                    )}
                                >
                                    {isReplaceAction ? t("replace") : t("collect")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}
        </div>
    );
}
