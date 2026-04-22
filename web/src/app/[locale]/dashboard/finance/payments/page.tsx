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
    Search,
    Building2,
    X,
    Loader2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrency, formatCurrencyCompact, formatNumber } from "@/lib/format";
import { canViewPayments, canManagePayments } from "@/lib/rbac";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
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
    chequeDate: string | null;
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
    PENDING: "bg-warning/10 text-warning border border-warning/20",
    COLLECTED: "bg-info/10 text-info border border-info/20",
    DEPOSITED: "bg-warning/10 text-warning border border-warning/20",
    CLEARED: "bg-success/10 text-success border border-success/20",
    BOUNCED: "bg-error/10 text-error border border-error/20",
    REPLACED: "bg-info/10 text-info border border-info/20",
};

const ALL_STATUSES = ["PENDING", "COLLECTED", "DEPOSITED", "CLEARED", "BOUNCED", "REPLACED"];

export default function PaymentsPage() {
    const t = useTranslations("Payments");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = userRole ? canViewPayments(userRole) : false;
    const canManage = userRole ? canManagePayments(userRole) : false;

    const [payments, setPayments] = useState<Payment[]>([]);
    const [summary, setSummary] = useState<PaymentSummary | null>(null);
    const [properties, setProperties] = useState<Property[]>([]);
    const [loading, setLoading] = useState(true);

    const [selectedProperty, setSelectedProperty] = useState("");
    const [selectedStatus, setSelectedStatus] = useState("");
    const [searchRenterName, setSearchRenterName] = useState("");
    const [debouncedSearchRenterName, setDebouncedSearchRenterName] = useState("");
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);
    const [totalItems, setTotalItems] = useState(0);

    // Cheque collection modal state
    const [showChequeModal, setShowChequeModal] = useState(false);
    const [collectingPaymentId, setCollectingPaymentId] = useState<string | null>(null);
    const [submittingCheque, setSubmittingCheque] = useState(false);
    const [chequeForm, setChequeForm] = useState({
        chequeNumber: "",
        bankName: "",
        payerName: "",
        chequeDate: "",
    });

    // Confirmation dialog state
    const [confirmDialog, setConfirmDialog] = useState<{
        open: boolean;
        title: string;
        message: string;
        onConfirm: () => void;
        isDestructive: boolean;
    }>({ open: false, title: "", message: "", onConfirm: () => {}, isDestructive: false });

    useEffect(() => {
        fetchProperties();
    }, []);

    useEffect(() => {
        const timer = setTimeout(() => {
            setDebouncedSearchRenterName(searchRenterName.trim());
        }, 350);
        return () => clearTimeout(timer);
    }, [searchRenterName]);

    useEffect(() => {
        setCurrentPage(1);
    }, [selectedProperty, selectedStatus, debouncedSearchRenterName, itemsPerPage]);

    useEffect(() => {
        fetchPayments();
    }, [selectedProperty, selectedStatus, debouncedSearchRenterName, currentPage, itemsPerPage]);

    useEffect(() => {
        fetchSummary();
    }, [selectedProperty]);

    const fetchPayments = async () => {
        setLoading(true);
        try {
            const params = new URLSearchParams();
            if (selectedProperty) params.set("propertyId", selectedProperty);
            if (selectedStatus) params.set("status", selectedStatus);
            if (debouncedSearchRenterName) params.set("renterName", debouncedSearchRenterName);
            params.set("page", String(Math.max(currentPage - 1, 0)));
            params.set("size", String(itemsPerPage));

            const res = await fetch(`/api/proxy/v1/payments?${params.toString()}`);
            if (res.ok) {
                const data = await res.json();
                if (Array.isArray(data)) {
                    setPayments(data);
                    setTotalItems(data.length);
                } else {
                    setPayments(data.content ?? []);
                    setTotalItems(data.totalElements ?? 0);
                }
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
        setChequeForm({ chequeNumber: "", bankName: "", payerName: "", chequeDate: "" });
        setShowChequeModal(true);
    };

    const submitCollect = async (ev: React.FormEvent) => {
        ev.preventDefault();
        if (!collectingPaymentId) return;
        setSubmittingCheque(true);
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
        } finally {
            setSubmittingCheque(false);
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
            isDestructive: false,
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
            isDestructive: true,
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
        setChequeForm({ chequeNumber: "", bankName: "", payerName: "", chequeDate: "" });
        setShowChequeModal(true);
    };

    const submitReplace = async (ev: React.FormEvent) => {
        ev.preventDefault();
        if (!collectingPaymentId) return;
        setSubmittingCheque(true);
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
        } finally {
            setSubmittingCheque(false);
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
              { label: t("totalDue"), value: summary.totalAmount ?? 0, color: "text-info", bg: "bg-info/10", icon: DollarSign },
              { label: t("collected"), value: summary.collectedAmount ?? 0, color: "text-warning", bg: "bg-warning/10", icon: ArrowRightCircle },
              { label: t("cleared"), value: summary.clearedAmount ?? 0, color: "text-success", bg: "bg-success/10", icon: CheckCircle },
              { label: t("overdue"), value: summary.overdueAmount ?? 0, color: "text-error", bg: "bg-error/10", icon: AlertCircle },
          ]
        : [];

    return (
        <div>
            {/* Header */}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <CreditCard size={20} className="text-primary" />
                        {t("title")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("description")}</p>
                </div>
            </div>

            {/* Summary Cards Skeleton */}
            {loading && !summary && (
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
                    {[1, 2, 3, 4].map((i) => (
                        <div key={i} className="bg-surface border border-border rounded-xl p-5 animate-pulse">
                            <div className="flex items-center gap-3 mb-3">
                                <div className="w-9 h-9 rounded-xl bg-input" />
                                <div className="h-3 w-16 bg-input rounded" />
                            </div>
                            <div className="h-5 w-24 bg-input rounded" />
                        </div>
                    ))}
                </div>
            )}

            {/* Summary Cards */}
            {summary && (
                <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
                    {summaryCards.map((card) => {
                        const Icon = card.icon;
                        return (
                            <div
                                key={card.label}
                                className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200"
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
                                    <span className="text-xs font-semibold text-muted uppercase tracking-[0.15em]">
                                        {card.label}
                                    </span>
                                </div>
                                <p className={cn("text-lg font-bold tabular-nums", card.color)}>
                                    {formatCurrencyCompact(card.value)}
                                </p>
                                {card.label === t("cleared") && summary.totalPayments > 0 && (
                                    <p className="text-[10px] text-muted mt-1 font-medium">
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
                <div className="relative w-full sm:max-w-xs">
                    <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
                    <input
                        type="text"
                        value={searchRenterName}
                        onChange={(ev) => setSearchRenterName(ev.target.value)}
                        placeholder="Search renter name"
                        className="w-full bg-surface border border-border pl-9 pr-3 py-2.5 rounded-lg text-xs font-medium focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                    />
                </div>
                <div className="relative">
                    <select
                        className="appearance-none bg-surface border border-border px-4 py-2.5 rounded-lg text-xs font-bold pr-8 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
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
                        className="appearance-none bg-surface border border-border px-4 py-2.5 rounded-lg text-xs font-bold pr-8 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
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

            {/* Table Skeleton */}
            {loading && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden">
                    <div className="animate-pulse">
                        <div className="h-12 bg-input/70 border-b border-border" />
                        {[1, 2, 3, 4, 5].map((i) => (
                            <div key={i} className="flex gap-4 px-5 py-4 border-b border-border">
                                <div className="h-3 w-8 bg-input rounded" />
                                <div className="h-3 w-24 bg-input rounded" />
                                <div className="h-3 w-20 bg-input rounded" />
                                <div className="h-3 w-32 bg-input rounded" />
                                <div className="h-3 w-24 bg-input rounded" />
                                <div className="h-3 w-16 bg-input rounded" />
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {/* Payments Table */}
            {!loading && payments.length > 0 && <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full">
                        <thead>
                            <tr className="bg-input/70">
                                <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                    #
                                </th>
                                <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                    {t("dueDate")}
                                </th>
                                <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                    {t("amount")} (AED)
                                </th>
                                <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                    {t("propertyUnit")}
                                </th>
                                <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                    {t("renter")}
                                </th>
                                <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                    {t("chequeNumber")}
                                </th>
                                <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                    {t("status")}
                                </th>
                                {canManage && (
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">
                                        {t("actions")}
                                    </th>
                                )}
                            </tr>
                        </thead>
                        <tbody>
                            {payments.map((payment) => (
                                <tr
                                    key={payment.id}
                                    className="border-b border-border hover:bg-input/30 transition-colors"
                                >
                                    <td className="px-5 py-3 text-xs font-bold text-foreground">
                                        {payment.installmentNumber}
                                    </td>
                                    <td className="px-5 py-3 text-xs text-foreground font-medium">
                                        <div className="flex items-center gap-1.5">
                                            <Calendar size={12} className="text-muted" />
                                            {new Date(payment.dueDate).toLocaleDateString()}
                                        </div>
                                    </td>
                                    <td className="px-5 py-3 text-right text-xs font-bold text-foreground tabular-nums">
                                        {formatNumber(payment.amount ?? 0)}
                                    </td>
                                    <td className="px-5 py-3">
                                        <div className="flex items-center gap-1.5">
                                            {payment.propertyName ? (
                                                <>
                                                    <Building2
                                                        size={12}
                                                        className="text-muted"
                                                    />
                                                    <span className="text-xs text-foreground font-medium">
                                                        {payment.propertyName}
                                                    </span>
                                                </>
                                            ) : (
                                                <span className="text-[10px] text-muted">
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
                                                    "bg-input text-muted border border-border"
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
                                                        className="px-3 py-1.5 bg-info/10 text-info hover:bg-info/20 rounded-lg text-[10px] font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                                    >
                                                        {t("collect")}
                                                    </button>
                                                )}
                                                {payment.status === "COLLECTED" && (
                                                    <button
                                                        onClick={() => handleDeposit(payment.id)}
                                                        className="px-3 py-1.5 bg-warning/10 text-warning hover:bg-warning/20 rounded-lg text-[10px] font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
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
                                                            className="px-3 py-1.5 bg-success/10 text-success hover:bg-success/20 rounded-lg text-[10px] font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                                        >
                                                            {t("clear")}
                                                        </button>
                                                        <button
                                                            onClick={() =>
                                                                handleBounce(payment.id)
                                                            }
                                                            className="px-3 py-1.5 bg-error/10 text-error hover:bg-error/20 rounded-lg text-[10px] font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                                        >
                                                            {t("bounce")}
                                                        </button>
                                                    </>
                                                )}
                                                {payment.status === "BOUNCED" && (
                                                    <button
                                                        onClick={() => handleReplace(payment.id)}
                                                        className="px-3 py-1.5 bg-info/10 text-info hover:bg-info/20 rounded-lg text-[10px] font-bold transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
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
                <div className="px-5 pb-4">
                    <Pagination
                        currentPage={currentPage}
                        totalItems={totalItems}
                        itemsPerPage={itemsPerPage}
                        onPageChange={setCurrentPage}
                        onItemsPerPageChange={setItemsPerPage}
                    />
                </div>
            </div>}

            {/* Empty State */}
            {payments.length === 0 && !loading && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center mt-6">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <CreditCard size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-2 uppercase tracking-widest">
                        {t("noPayments")}
                    </p>
                    <p className="text-xs text-muted font-medium">{t("activateLeaseFirst")}</p>
                </div>
            )}

            {/* Confirmation Dialog */}
            <ConfirmDialog
                isOpen={confirmDialog.open}
                onClose={() => setConfirmDialog((prev) => ({ ...prev, open: false }))}
                onConfirm={confirmDialog.onConfirm}
                title={confirmDialog.title}
                description={confirmDialog.message}
                confirmText={confirmDialog.title}
                cancelText="Cancel"
                isDestructive={confirmDialog.isDestructive}
            />

            {/* Cheque Details Modal */}
            {showChequeModal && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-md w-full shadow-2xl border border-border relative">
                        <button
                            onClick={() => {
                                setShowChequeModal(false);
                                setCollectingPaymentId(null);
                            }}
                            aria-label="Close modal"
                            className="absolute right-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg"
                        >
                            <X size={18} />
                        </button>
                        <h2 className="text-lg font-bold mb-1">
                            {isReplaceAction ? t("replacementCheque") : t("chequeDetails")}
                        </h2>
                        <p className="text-xs text-muted mb-8 font-medium">
                            {t("enterChequeDetails")}
                        </p>
                        <form
                            onSubmit={isReplaceAction ? submitReplace : submitCollect}
                            className="space-y-5"
                        >
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {t("chequeNumber")}
                                </label>
                                <input
                                    required
                                    placeholder="CHQ-000001"
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
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
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {t("bankName")}
                                </label>
                                <input
                                    required
                                    placeholder="Emirates NBD"
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
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
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {t("payerName")}
                                </label>
                                <input
                                    required
                                    placeholder="John Doe"
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                    value={chequeForm.payerName}
                                    onChange={(ev) =>
                                        setChequeForm({
                                            ...chequeForm,
                                            payerName: ev.target.value,
                                        })
                                    }
                                />
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {t("chequeDate")}
                                </label>
                                <input
                                    type="date"
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                    value={chequeForm.chequeDate}
                                    onChange={(ev) =>
                                        setChequeForm({
                                            ...chequeForm,
                                            chequeDate: ev.target.value,
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
                                    className="px-6 py-3 text-xs font-bold text-muted cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    disabled={submittingCheque}
                                    className={cn(
                                        "px-8 py-3 rounded-lg text-xs font-bold text-white cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 flex items-center gap-2",
                                        isReplaceAction
                                            ? "bg-purple-600 hover:bg-purple-700"
                                            : "bg-primary hover:opacity-90"
                                    )}
                                >
                                    {submittingCheque && <Loader2 size={14} className="animate-spin" />}
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
