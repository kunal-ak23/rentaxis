"use client";

import { useState, useEffect } from "react";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import {
    Calendar,
    CreditCard,
    Search,
    Building2,
    X,
    Loader2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrencyCompact, formatNumber } from "@/lib/format";
import { canManagePayments } from "@/lib/rbac";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import { MarkChequeFailedDialog, type PenaltySummary } from "@/components/payments/MarkChequeFailedDialog";
import ChequeScanner from "@/components/cheques/ChequeScanner";
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
    chequeImageUrl: string | null;
    chequeImageBlobPath: string | null;
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

// GET /api/v1/properties returns each property wrapped in a portfolio-summary
// row (property + assignedManagers + occupancy stats), not a flat property
// object — mirrors PropertyStats in finance/transactions/page.tsx.
type PropertySummary = {
    property: {
        id: string;
        nameEn: string;
    };
};

const STATUS_COLORS: Record<string, string> = {
    PENDING: "bg-warning/10 text-warning border border-warning/20",
    COLLECTED: "bg-info/10 text-info border border-info/20",
    DEPOSITED: "bg-warning/10 text-warning border border-warning/20",
    CLEARED: "bg-success/10 text-success border border-success/20",
    BOUNCED: "bg-error/10 text-error border border-error/20",
    REPLACED: "bg-info/10 text-info border border-info/20",
    OVERDUE: "bg-error/10 text-error border border-error/20",
};

const ALL_STATUSES = ["PENDING", "COLLECTED", "DEPOSITED", "CLEARED", "BOUNCED", "REPLACED"];

// Sentinel filter value: "overdue" is a computed view (PENDING/COLLECTED past due),
// not a stored status, so it is sent to the API as `overdue=true` rather than `status`.
const OVERDUE_FILTER = "OVERDUE";

// Sentinel filter value: "cheques to deposit" is a computed view (COLLECTED with
// a post-dated chequeDate that has arrived), served by a dedicated endpoint.
const TO_DEPOSIT_FILTER = "TO_DEPOSIT";

export default function PaymentsPage() {
    const t = useTranslations("Payments");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canManage = userRole ? canManagePayments(userRole) : false;

    const [payments, setPayments] = useState<Payment[]>([]);
    const [summary, setSummary] = useState<PaymentSummary | null>(null);
    const [properties, setProperties] = useState<PropertySummary[]>([]);
    const [loading, setLoading] = useState(true);

    // Allow the dashboard "Overdue" card (and shareable URLs) to deep-link into a
    // pre-selected status filter via ?status=… (e.g. ?status=OVERDUE).
    const searchParams = useSearchParams();
    const initialStatusParam = searchParams.get("status") ?? "";
    const initialStatus = [...ALL_STATUSES, OVERDUE_FILTER, TO_DEPOSIT_FILTER].includes(initialStatusParam)
        ? initialStatusParam
        : "";

    const [selectedProperty, setSelectedProperty] = useState("");
    const [selectedStatus, setSelectedStatus] = useState(initialStatus);
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
        chequeImageUrl: "",
        chequeImageBlobPath: "",
        chequeImageUploadedAt: "",
    });

    // Confirmation dialog state
    const [confirmDialog, setConfirmDialog] = useState<{
        open: boolean;
        title: string;
        message: string;
        onConfirm: () => void;
        isDestructive: boolean;
    }>({ open: false, title: "", message: "", onConfirm: () => {}, isDestructive: false });

    // Mark-failed dialog state (replaces handleBounce confirm flow)
    const [markFailedTarget, setMarkFailedTarget] = useState<{
        paymentId: string;
        installmentNumber: number;
        amount: number;
    } | null>(null);

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
            params.set("page", String(Math.max(currentPage - 1, 0)));
            params.set("size", String(itemsPerPage));

            // "Cheques to deposit" is its own endpoint; all other filters use the
            // main listing endpoint (status / overdue / renter-name search).
            let endpoint = "/api/proxy/v1/payments";
            if (selectedStatus === TO_DEPOSIT_FILTER) {
                endpoint = "/api/proxy/v1/payments/to-deposit";
            } else {
                if (selectedStatus === OVERDUE_FILTER) {
                    params.set("overdue", "true");
                } else if (selectedStatus) {
                    params.set("status", selectedStatus);
                }
                if (debouncedSearchRenterName) params.set("renterName", debouncedSearchRenterName);
            }

            const res = await fetch(`${endpoint}?${params.toString()}`);
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
        setChequeForm({ chequeNumber: "", bankName: "", payerName: "", chequeDate: "", chequeImageUrl: "", chequeImageBlobPath: "", chequeImageUploadedAt: "" });
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

    const onMarkFailedSuccess = (_response: { schedule: unknown; penalty: PenaltySummary }) => {
        refreshData();
    };

    const handleReplace = async (paymentId: string) => {
        setCollectingPaymentId(paymentId);
        setChequeForm({ chequeNumber: "", bankName: "", payerName: "", chequeDate: "", chequeImageUrl: "", chequeImageBlobPath: "", chequeImageUploadedAt: "" });
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
            OVERDUE: t("overdue"),
            TO_DEPOSIT: "To deposit",
        };
        return map[status] || status;
    };
    const getMethodLabel = (payment: Payment): string => (payment.chequeNumber ? "Cheque" : "—");

    const summaryCells = summary
        ? [
              { label: t("cleared"),  value: summary.clearedAmount ?? 0,    sub: `${summary.clearedCount}/${summary.totalPayments} ${t("paymentsCleared")}`, tone: "pos" as const },
              { label: t("collected"), value: summary.collectedAmount ?? 0, sub: `${summary.collectedCount} payments`, tone: "neutral" as const },
              { label: t("overdue"),  value: summary.overdueAmount ?? 0,    sub: `${summary.overdueCount} overdue`,    tone: "neg" as const },
              { label: t("totalDue"), value: summary.totalAmount ?? 0,      sub: `${summary.totalPayments} total`,     tone: "neutral" as const },
          ]
        : [];

    return (
        <div className="flex flex-col gap-[18px]">
            {/* Header */}
            <div className="flex items-end justify-between">
                <div>
                    <p className="text-[12.5px] text-[var(--ink-500)]">Finance</p>
                    <h1 className="font-serif text-[26px] font-semibold tracking-tight m-0">
                        {t("title")}
                    </h1>
                </div>
            </div>

            {/* Summary strip skeleton */}
            {loading && !summary && (
                <div className="bg-surface border border-border rounded-[var(--radius-lg)] px-6 py-5 grid grid-cols-2 md:grid-cols-4 animate-pulse">
                    {[1, 2, 3, 4].map((i) => (
                        <div key={i} className="px-6 first:pl-0 border-r last:border-r-0 border-border">
                            <div className="h-3 w-20 bg-[var(--sand-100)] rounded mb-3" />
                            <div className="h-7 w-32 bg-[var(--sand-100)] rounded mb-2" />
                            <div className="h-3 w-16 bg-[var(--sand-100)] rounded" />
                        </div>
                    ))}
                </div>
            )}

            {/* Summary strip */}
            {summary && (
                <div className="bg-surface border border-border rounded-[var(--radius-lg)] px-6 py-5 grid grid-cols-2 md:grid-cols-4">
                    {summaryCells.map((cell, i) => {
                        const tone =
                            cell.tone === "pos" ? "text-[var(--green-600)]" :
                            cell.tone === "neg" ? "text-[var(--red-600)]"   : "text-[var(--ink-500)]";
                        return (
                            <div
                                key={cell.label}
                                className={cn(
                                    "px-6 first:pl-0",
                                    i < summaryCells.length - 1 ? "border-r border-border" : "",
                                )}
                            >
                                <div className="text-[11.5px] text-[var(--ink-500)] uppercase tracking-[0.06em] font-semibold">
                                    {cell.label}
                                </div>
                                <div className="font-serif font-mono text-[22px] font-semibold text-foreground mt-1 tracking-tight">
                                    {formatCurrencyCompact(cell.value)}
                                </div>
                                <div className={cn("text-[11.5px] font-medium mt-0.5", tone)}>
                                    {cell.sub}
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            {/* Filter chips + search */}
            <div className="flex items-center gap-2 flex-wrap">
                {/* Renter-name search isn't supported by the "to deposit" endpoint, so hide it in that mode. */}
                {selectedStatus !== TO_DEPOSIT_FILTER && (
                    <div className="relative w-full sm:max-w-xs">
                        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--ink-500)]" />
                        <input
                            type="text"
                            value={searchRenterName}
                            onChange={(ev) => setSearchRenterName(ev.target.value)}
                            placeholder="Search renter name"
                            className="w-full bg-surface border border-border pl-9 pr-3 h-9 rounded-[var(--radius)] text-[13px] focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                        />
                    </div>
                )}
                <div className="relative">
                    <select
                        className="appearance-none bg-surface border border-border pl-3 pr-7 h-9 rounded-full text-[12.5px] font-medium cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                        value={selectedProperty}
                        onChange={(ev) => setSelectedProperty(ev.target.value)}
                    >
                        <option value="">{t("allProperties")}</option>
                        {properties.map((p) => (
                            <option key={p.property.id} value={p.property.id}>
                                {p.property.nameEn}
                            </option>
                        ))}
                    </select>
                </div>
                <div className="relative">
                    <select
                        className="appearance-none bg-surface border border-border pl-3 pr-7 h-9 rounded-full text-[12.5px] font-medium cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                        value={selectedStatus}
                        onChange={(ev) => setSelectedStatus(ev.target.value)}
                    >
                        <option value="">{t("allStatuses")}</option>
                        <option value={OVERDUE_FILTER}>{t("overdue")}</option>
                        <option value={TO_DEPOSIT_FILTER}>To deposit</option>
                        {ALL_STATUSES.map((s) => (
                            <option key={s} value={s}>
                                {getStatusLabel(s)}
                            </option>
                        ))}
                    </select>
                </div>
                {summary && (
                    <span className="text-[12px] text-[var(--ink-500)] ml-auto">
                        Showing <strong className="text-foreground">{payments.length}</strong> of {totalItems} payments
                    </span>
                )}
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
            {!loading && payments.length > 0 && <div className="bg-surface border border-border rounded-[var(--radius-lg)] overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full">
                        <thead>
                            <tr className="bg-[var(--sand-100)]">
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
                                    Method
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
                                    className="border-b border-border hover:bg-[var(--sand-50)] transition-colors"
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
                                    <td className="px-5 py-3 text-xs text-[var(--ink-600)] font-medium">
                                        {getMethodLabel(payment)}
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
                                                                setMarkFailedTarget({
                                                                    paymentId: payment.id,
                                                                    installmentNumber: payment.installmentNumber,
                                                                    amount: payment.amount,
                                                                })
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
                                <div className="mb-3">
                                    <ChequeScanner
                                        onExtracted={(data) =>
                                            setChequeForm((prev) => ({
                                                ...prev,
                                                chequeNumber: data.chequeNumber ?? prev.chequeNumber,
                                                bankName: data.bankName ?? prev.bankName,
                                                payerName: data.payerName ?? prev.payerName,
                                                chequeDate: data.chequeDate ?? prev.chequeDate,
                                                chequeImageUrl: data.imageUrl,
                                                chequeImageBlobPath: data.imageBlobPath,
                                                chequeImageUploadedAt: data.imageUploadedAt,
                                            }))
                                        }
                                    />
                                </div>
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

            {/* Mark Cheque Failed Dialog */}
            {markFailedTarget && (
                <MarkChequeFailedDialog
                    paymentId={markFailedTarget.paymentId}
                    installmentNumber={markFailedTarget.installmentNumber}
                    amount={markFailedTarget.amount}
                    isOpen={!!markFailedTarget}
                    onClose={() => setMarkFailedTarget(null)}
                    onSuccess={onMarkFailedSuccess}
                />
            )}
        </div>
    );
}
