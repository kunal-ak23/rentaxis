"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { hasRole, type UserRole } from "@/lib/rbac";
import { formatCurrency, formatCurrencyCompact } from "@/lib/format";
import {
    ArrowLeft, FileText, User, Building2, Calendar, DollarSign, CreditCard,
    Home, Download, Upload, Trash2, Loader2, CheckCircle, Clock, AlertTriangle,
    Hash, Phone, Mail, MapPin, Wrench, X, Ban, CalendarClock, Sparkles, RefreshCw,
} from "lucide-react";
import { useTranslations } from "next-intl";
import PaymentScheduleEditor from "../PaymentScheduleEditor";

type Lease = {
    id: string; unitId: string; renterId: string; unitIdentifier: string;
    renterName: string; startDate: string; endDate: string; status: string;
    rentAmount: number; monthlyRent: number | null; depositAmount: number;
    ejariNumber: string; paymentTerms: number; paymentMethod: string;
    depositPaymentMethod: string; paymentReferenceNumber: string;
    propertyId: string; propertyName: string; hasContract: boolean;
    contractNumber?: string | null;
};

type Payment = {
    id: string; installmentNumber: number; dueDate: string; amount: number;
    status: string; chequeNumber: string; bankName: string; payerName: string;
};

type Renter = {
    id: string; nameEn: string; nameAr: string; email: string; phone: string;
    primaryLanguage: string;
};

type Attachment = {
    id: string; name: string; fileUrl: string; fileType: string;
    fileSize: number; uploadedAt: string;
};

type Ticket = {
    id: string; title: string; status: string; priority: string;
    category: string; createdAt: string; assignedToName: string | null;
};

type PaymentPenalty = {
    id: string;
    paymentScheduleId: string;
    leaseId: string;
    penaltyAmount: number;
    daysOverdue: number;
    penaltyType: string;
    penaltyRate: number;
    gracePeriodDays: number;
    waived: boolean;
    waivedBy?: string;
    waivedReason?: string;
    waivedAt?: string;
    lastCalculatedAt: string;
};

type Settlement = {
    id: string;
    leaseId: string;
    depositAmount: number;
    totalDeductions: number;
    totalAdditions?: number;
    refundAmount: number;
    notes: string;
    status: string;
    settledBy: string;
    settledByName?: string;
    settledAt: string;
    createdAt: string;
    deductions: { id?: string; category: string; description: string; amount: number; autoCalculated: boolean; attachments: { id: string; name: string; fileUrl: string; fileType: string | null; fileSize: number; uploadedAt: string }[] }[];
};

const DEDUCTION_CATEGORY_LABELS: Record<string, string> = {
    UNPAID_RENT: "Unpaid Rent",
    PENALTIES: "Late Penalties",
    PROPERTY_DAMAGE: "Property Damage",
    EARLY_TERMINATION_FEE: "Early Termination Fee",
    CLEANING: "Cleaning",
    UTILITY_ARREARS: "Utility Arrears",
    KEY_REPLACEMENT: "Key Replacement",
    OTHER: "Other",
};

const TICKET_STATUS_COLORS: Record<string, string> = {
    OPEN: "bg-warning/10 text-warning", ASSIGNED: "bg-info/10 text-info",
    IN_PROGRESS: "bg-primary/10 text-primary", RESOLVED: "bg-success/10 text-success",
    CLOSED: "bg-input text-muted", REOPENED: "bg-error/10 text-error",
};

const TICKET_PRIORITY_COLORS: Record<string, string> = {
    LOW: "bg-input text-muted", MEDIUM: "bg-info/10 text-info",
    HIGH: "bg-warning/10 text-warning", URGENT: "bg-error/10 text-error",
};

const STATUS_COLORS: Record<string, string> = {
    ACTIVE: "bg-success/10 text-success border-success/20",
    DRAFT: "bg-input text-muted border-border",
    PENDING_SIGNATURE: "bg-warning/10 text-warning border-warning/20",
    TERMINATED: "bg-error/10 text-error border-error/20",
    EXPIRED: "bg-warning/10 text-warning border-warning/20",
    CLOSED: "bg-input text-muted border-border",
    NOTICE_GIVEN: "bg-warning/10 text-warning border-warning/20",
};

const PAYMENT_STATUS_COLORS: Record<string, string> = {
    PENDING: "bg-warning/10 text-warning",
    COLLECTED: "bg-info/10 text-info",
    DEPOSITED: "bg-primary/10 text-primary",
    CLEARED: "bg-success/10 text-success",
    BOUNCED: "bg-error/10 text-error",
    OVERDUE: "bg-error/10 text-error",
    REPLACED: "bg-input text-muted",
    CANCELLED: "bg-input text-muted",
    ONLINE_PENDING: "bg-warning/10 text-warning",
};

export default function LeaseDetailPage() {
    const params = useParams();
    const leaseId = params.id as string;
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const isAdmin = hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"]);
    // Contract generation is restricted to SUPER_ADMIN and TENANT_ADMIN on the
    // backend; matching the gate here so PROPERTY_MANAGER doesn't see a button
    // that 403s when clicked.
    const canGenerateContract = hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN"]);
    const t = useTranslations("MasterData");

    const [lease, setLease] = useState<Lease | null>(null);
    const [renter, setRenter] = useState<Renter | null>(null);
    const [payments, setPayments] = useState<Payment[]>([]);
    const [penalties, setPenalties] = useState<PaymentPenalty[]>([]);
    const [attachments, setAttachments] = useState<Attachment[]>([]);
    const [loading, setLoading] = useState(true);
    const [docName, setDocName] = useState("");
    const [uploadingDoc, setUploadingDoc] = useState(false);
    const [tickets, setTickets] = useState<Ticket[]>([]);
    const [waiveModalPenalty, setWaiveModalPenalty] = useState<PaymentPenalty | null>(null);
    const [waiveReason, setWaiveReason] = useState("");
    const [waiving, setWaiving] = useState(false);

    // Settlement state
    const [settlement, setSettlement] = useState<Settlement | null>(null);

    // Extend lease state
    const [extendOpen, setExtendOpen] = useState(false);
    const [extendDate, setExtendDate] = useState("");
    const [extending, setExtending] = useState(false);
    const [extendError, setExtendError] = useState<string | null>(null);

    // Generate contract preview modal state
    const [previewOpen, setPreviewOpen] = useState(false);
    const [previewBlobUrl, setPreviewBlobUrl] = useState<string | null>(null);
    const [previewLoading, setPreviewLoading] = useState(false);
    const [confirmSaving, setConfirmSaving] = useState(false);
    const [contractError, setContractError] = useState<string | null>(null);

    const fetchLease = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}`);
            if (res.ok) {
                const data = await res.json();
                setLease(data);
                // Fetch renter details
                if (data.renterId) {
                    const rRes = await fetch(`/api/proxy/v1/renters/${data.renterId}`);
                    if (rRes.ok) setRenter(await rRes.json());
                }
            }
        } catch {}
    }, [leaseId]);

    const fetchPayments = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/payments/lease/${leaseId}`);
            if (res.ok) {
                const data = await res.json();
                data.sort((a: Payment, b: Payment) => a.installmentNumber - b.installmentNumber);
                setPayments(data);
            }
        } catch {}
    }, [leaseId]);

    const fetchAttachments = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/attachments`);
            if (res.ok) setAttachments(await res.json());
        } catch {}
    }, [leaseId]);

    const fetchPenalties = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/penalties`);
            if (res.ok) setPenalties(await res.json());
        } catch {}
    }, [leaseId]);

    const handleWaivePenalty = async () => {
        if (!waiveModalPenalty || !waiveReason.trim()) return;
        setWaiving(true);
        try {
            const res = await fetch(`/api/proxy/v1/penalties/${waiveModalPenalty.id}/waive`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ reason: waiveReason.trim() }),
            });
            if (res.ok) {
                setWaiveModalPenalty(null);
                setWaiveReason("");
                fetchPenalties();
            }
        } catch {} finally { setWaiving(false); }
    };

    const fetchSettlement = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement`);
            if (res.ok) {
                const data = await res.json();
                setSettlement({
                    ...data,
                    deductions: data.deductions || [],
                });
            }
        } catch {}
    }, [leaseId]);


    const fetchTickets = useCallback(async () => {
        try {
            const res = await fetch("/api/proxy/v1/tickets");
            if (res.ok) {
                const all = await res.json();
                // Filter tickets for this lease's unit
                setTickets(all.filter((t: Ticket & { unitId?: string }) => t.unitId === lease?.unitId));
            }
        } catch {}
    }, [lease?.unitId]);

    useEffect(() => {
        Promise.all([fetchLease(), fetchPayments(), fetchAttachments(), fetchPenalties()]).finally(() => setLoading(false));
    }, [fetchLease, fetchPayments, fetchAttachments, fetchPenalties]);

    useEffect(() => {
        if (lease?.unitId) fetchTickets();
    }, [lease?.unitId, fetchTickets]);


    useEffect(() => {
        if (lease?.status === "TERMINATED" || lease?.status === "CLOSED") {
            fetchSettlement();
        }
    }, [lease?.status, fetchSettlement]);

    const handleDocUpload = async (file: File) => {
        if (!docName.trim()) return;
        setUploadingDoc(true);
        try {
            const formData = new FormData();
            formData.append("file", file);
            formData.append("name", docName);
            const res = await fetch(`/api/upload?path=/api/v1/leases/${leaseId}/attachments`, {
                method: "POST", body: formData,
            });
            if (res.ok) { setDocName(""); fetchAttachments(); }
        } catch {} finally { setUploadingDoc(false); }
    };

    const handleDocDelete = async (attachmentId: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/attachments/${attachmentId}`, { method: "DELETE" });
            if (res.ok) fetchAttachments();
        } catch {}
    };

    const handleDocDownload = async (attachmentId: string, fileName: string) => {
        const res = await fetch(`/api/proxy/v1/leases/attachments/${attachmentId}/download`);
        if (res.ok) {
            const blob = await res.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a"); a.href = url; a.download = fileName;
            document.body.appendChild(a); a.click(); document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }
    };

    const handleSettlementAttachmentDownload = async (attachmentId: string, fileName: string) => {
        const res = await fetch(`/api/proxy/v1/settlements/attachments/${attachmentId}/download`);
        if (res.ok) {
            const blob = await res.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a"); a.href = url; a.download = fileName;
            document.body.appendChild(a); a.click(); document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }
    };

    const handleExtendLease = async () => {
        if (!extendDate || !lease) return;
        setExtending(true);
        setExtendError(null);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/extend`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ newEndDate: extendDate }),
            });
            if (res.ok) {
                const updated = await res.json();
                setLease(updated);
                setExtendOpen(false);
                setExtendDate("");
            } else {
                const err = await res.json().catch(() => ({}));
                setExtendError(err.message || "Failed to extend lease");
            }
        } catch {
            setExtendError("Failed to extend lease");
        } finally {
            setExtending(false);
        }
    };

    const handlePreviewContract = async () => {
        if (!lease) return;
        setPreviewLoading(true);
        setContractError(null);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${lease.id}/generate-contract/preview`, { method: "POST" });
            if (res.ok) {
                // Wrap the response bytes in an explicit application/pdf blob so
                // the browser's built-in PDF viewer renders it inline in the
                // <object> tag instead of treating it as a generic download.
                const buf = await res.arrayBuffer();
                const pdfBlob = new Blob([buf], { type: "application/pdf" });
                const url = URL.createObjectURL(pdfBlob);
                setPreviewBlobUrl(url);
                setPreviewOpen(true);
            } else if (res.status === 403) {
                setContractError("You don't have permission to generate a contract for this lease.");
            } else {
                let detail: string | null = null;
                try {
                    const body = await res.json();
                    detail = body?.message || body?.error || null;
                } catch {}
                setContractError(detail || "Failed to generate contract preview. Please try again.");
            }
        } catch (err) {
            console.error(err);
            setContractError("Network error while generating preview. Check your connection and try again.");
        } finally {
            setPreviewLoading(false);
        }
    };

    const handleClosePreview = () => {
        setPreviewOpen(false);
        if (previewBlobUrl) {
            URL.revokeObjectURL(previewBlobUrl);
            setPreviewBlobUrl(null);
        }
    };

    const handleConfirmAndSave = async () => {
        if (!lease) return;
        setConfirmSaving(true);
        setContractError(null);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${lease.id}/generate-contract`, { method: "POST" });
            if (res.ok) {
                handleClosePreview();
                fetchLease();
            } else if (res.status === 403) {
                setContractError("You don't have permission to save this contract.");
            } else {
                let detail: string | null = null;
                try {
                    const body = await res.json();
                    detail = body?.message || body?.error || null;
                } catch {}
                setContractError(detail || "Failed to save contract. Please try again.");
            }
        } catch (err) {
            console.error(err);
            setContractError("Network error while saving contract. Check your connection and try again.");
        } finally {
            setConfirmSaving(false);
        }
    };

    const handleDownloadContract = async () => {
        if (!lease) return;
        const res = await fetch(`/api/proxy/v1/leases/${lease.id}/documents`);
        if (res.ok) {
            const docs = await res.json();
            if (docs.length > 0) {
                // Pick the most recently created CONTRACT document so a
                // download after regeneration always returns the latest PDF
                // (defensive: backend deletes old contract docs on regen, but
                // ordering of getDocuments isn't guaranteed otherwise).
                const sorted = [...docs].sort((a, b) => {
                    const at = new Date(a.createdAt || 0).getTime();
                    const bt = new Date(b.createdAt || 0).getTime();
                    return bt - at; // newest first
                });
                const latest = sorted.find((d) => (d.type || "CONTRACT") === "CONTRACT") || sorted[0];
                const pdfRes = await fetch(`/api/proxy/v1/leases/documents/${latest.id}/download`);
                if (pdfRes.ok) {
                    const blob = await pdfRes.blob();
                    const url = URL.createObjectURL(blob);
                    const a = document.createElement("a"); a.href = url; a.download = `contract-${lease.id.substring(0, 8)}.pdf`;
                    document.body.appendChild(a); a.click(); document.body.removeChild(a);
                    URL.revokeObjectURL(url);
                }
            }
        }
    };

    const handleDownloadReceipt = async (paymentId: string, installmentNo: number) => {
        const res = await fetch(`/api/proxy/v1/payments/${paymentId}/receipt`);
        if (res.ok) {
            const blob = await res.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a"); a.href = url; a.download = `receipt-${installmentNo}.pdf`;
            document.body.appendChild(a); a.click(); document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    if (!lease) {
        return (
            <div className="text-center py-24">
                <p className="text-sm text-muted">Lease not found.</p>
                <Link href="/dashboard/leases" className="text-xs text-primary font-semibold mt-2 inline-block">Back to Leases</Link>
            </div>
        );
    }

    const clearedCount = payments.filter(p => p.status === "CLEARED").length;
    const pendingCount = payments.filter(p => p.status === "PENDING" || p.status === "ONLINE_PENDING").length;
    const totalPaid = payments.filter(p => p.status === "CLEARED").reduce((sum, p) => sum + p.amount, 0);

    return (
        <>
        <div>
            {/* Header */}
            <div className="flex items-center gap-4 mb-8">
                <Link href="/dashboard/leases" className="p-2 rounded-lg hover:bg-input transition-colors text-muted hover:text-foreground">
                    <ArrowLeft size={18} />
                </Link>
                <div className="flex-1">
                    <div className="flex items-center gap-3">
                        <h1 className="mb-0">Unit {lease.unitIdentifier}</h1>
                        <span className={cn("px-2.5 py-1 rounded-lg text-[10px] font-semibold border", STATUS_COLORS[lease.status] || "bg-input text-muted border-border")}>
                            {lease.status.replace("_", " ")}
                        </span>
                        {lease.contractNumber && (
                            <span className="px-2.5 py-1 rounded-lg text-[10px] font-semibold bg-primary/10 text-primary border border-primary/20">
                                {t("contractNumber")} {lease.contractNumber}
                            </span>
                        )}
                    </div>
                    <p className="text-sm text-muted">{lease.propertyName} &bull; {lease.renterName}</p>
                </div>
                {canGenerateContract && (
                    <div className="flex flex-col items-end gap-1">
                        <button
                            onClick={handlePreviewContract}
                            disabled={previewLoading}
                            className={cn(
                                "flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer disabled:opacity-50",
                                lease.hasContract
                                    ? "bg-amber-50 text-amber-700 border border-amber-200 hover:bg-amber-100"
                                    : "bg-blue-50 text-blue-700 border border-blue-200 hover:bg-blue-100"
                            )}
                            title={lease.hasContract
                                ? "Generate a fresh contract PDF and replace the saved one (the previous PDF is deleted from storage)"
                                : "Generate a contract preview PDF"}
                        >
                            {previewLoading
                                ? <Loader2 size={14} className="animate-spin" />
                                : (lease.hasContract ? <RefreshCw size={14} /> : <Sparkles size={14} />)}
                            {lease.hasContract ? "Regenerate Contract" : t("generatePreview")}
                        </button>
                        {contractError && !previewOpen && (
                            <p className="text-xs text-error max-w-xs text-right">{contractError}</p>
                        )}
                    </div>
                )}
                {lease.hasContract && (
                    <button
                        onClick={handleDownloadContract}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer"
                    >
                        <Download size={14} /> Download Contract
                    </button>
                )}
                {lease.status === "ACTIVE" && hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN"]) && (
                    <button
                        onClick={() => { setExtendOpen(true); setExtendError(null); setExtendDate(""); }}
                        className="flex items-center gap-2 bg-primary/10 text-primary border border-primary/20 px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/20 transition-all cursor-pointer"
                    >
                        <CalendarClock size={14} /> Extend Lease
                    </button>
                )}
                {(lease.status === "ACTIVE" || lease.status === "NOTICE_GIVEN") && hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"]) && (
                    <Link
                        href={`/dashboard/leases/${leaseId}/settlement`}
                        className="flex items-center gap-2 bg-error text-white px-4 py-2 rounded-lg text-xs font-semibold hover:bg-error/90 transition-all"
                    >
                        <Ban size={14} /> Terminate
                    </Link>
                )}
            </div>

            {/* KPI Summary */}
            <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
                <div className="bg-surface rounded-xl p-4 border border-border">
                    <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Monthly Rent</p>
                    <p className="text-lg font-bold text-foreground tabular-nums">{formatCurrencyCompact(lease.monthlyRent || lease.rentAmount)}</p>
                </div>
                <div className="bg-surface rounded-xl p-4 border border-border">
                    <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Total Paid</p>
                    <p className="text-lg font-bold text-success tabular-nums">{formatCurrencyCompact(totalPaid)}</p>
                </div>
                <div className="bg-surface rounded-xl p-4 border border-border">
                    <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Payments</p>
                    <p className="text-lg font-bold text-foreground">{clearedCount}/{payments.length} cleared</p>
                </div>
                <div className="bg-surface rounded-xl p-4 border border-border">
                    <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Pending</p>
                    <p className={cn("text-lg font-bold", pendingCount > 0 ? "text-warning" : "text-success")}>{pendingCount}</p>
                </div>
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                {/* Left Column: Lease + Renter Details */}
                <div className="space-y-6">
                    {/* Lease Details */}
                    <div className="bg-surface rounded-xl border border-border">
                        <div className="px-5 py-3.5 border-b border-border">
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2"><FileText size={13} /> Lease Details</h2>
                        </div>
                        <div className="px-5 py-3 space-y-3">
                            {[
                                ["Property", lease.propertyName],
                                ["Unit", lease.unitIdentifier],
                                ["Start Date", new Date(lease.startDate).toLocaleDateString()],
                                ["End Date", new Date(lease.endDate).toLocaleDateString()],
                                ["Total Rent", formatCurrency(lease.rentAmount)],
                                ["Deposit", formatCurrency(lease.depositAmount)],
                                ["Payment Terms", `${lease.paymentTerms || 1} cheque(s)`],
                                ["Payment Method", lease.paymentMethod || "—"],
                                ["Ejari #", lease.ejariNumber || "—"],
                            ].map(([label, value]) => (
                                <div key={label} className="flex justify-between items-center">
                                    <span className="text-xs text-muted">{label}</span>
                                    <span className="text-xs font-medium text-foreground tabular-nums">{value}</span>
                                </div>
                            ))}
                        </div>
                    </div>

                    {/* Renter Details */}
                    {renter && (
                        <div className="bg-surface rounded-xl border border-border">
                            <div className="px-5 py-3.5 border-b border-border">
                                <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2"><User size={13} /> Renter Details</h2>
                            </div>
                            <div className="px-5 py-3 space-y-3">
                                <div className="flex justify-between items-center">
                                    <span className="text-xs text-muted">Name</span>
                                    <span className="text-xs font-medium text-foreground">{renter.nameEn}</span>
                                </div>
                                {renter.nameAr && (
                                    <div className="flex justify-between items-center">
                                        <span className="text-xs text-muted">Name (AR)</span>
                                        <span className="text-xs font-medium text-foreground" dir="rtl">{renter.nameAr}</span>
                                    </div>
                                )}
                                <div className="flex justify-between items-center">
                                    <span className="text-xs text-muted flex items-center gap-1"><Mail size={10} /> Email</span>
                                    <span className="text-xs font-medium text-foreground">{renter.email || "—"}</span>
                                </div>
                                <div className="flex justify-between items-center">
                                    <span className="text-xs text-muted flex items-center gap-1"><Phone size={10} /> Phone</span>
                                    <span className="text-xs font-medium text-foreground">{renter.phone || "—"}</span>
                                </div>
                                <div className="flex justify-between items-center">
                                    <span className="text-xs text-muted">Language</span>
                                    <span className="text-xs font-medium text-foreground">{renter.primaryLanguage}</span>
                                </div>
                            </div>
                        </div>
                    )}

                    {/* Documents */}
                    <div className="bg-surface rounded-xl border border-border">
                        <div className="px-5 py-3.5 border-b border-border">
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2"><FileText size={13} /> Supporting Documents</h2>
                        </div>
                        <div className="p-4">
                            {attachments.length > 0 ? (
                                <div className="space-y-2 mb-4">
                                    {attachments.map(doc => (
                                        <div key={doc.id} className="flex items-center justify-between bg-input/50 rounded-lg px-3 py-2 border border-border">
                                            <div className="min-w-0">
                                                <p className="text-xs font-medium text-foreground truncate">{doc.name}</p>
                                                <p className="text-[10px] text-muted">{(doc.fileSize / 1024).toFixed(0)} KB</p>
                                            </div>
                                            <div className="flex items-center gap-2 shrink-0">
                                                <button onClick={() => handleDocDownload(doc.id, doc.name)} className="p-1 text-primary hover:text-primary/80 cursor-pointer"><Download size={13} /></button>
                                                <button onClick={() => handleDocDelete(doc.id)} className="p-1 text-error hover:text-error/80 cursor-pointer"><Trash2 size={13} /></button>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            ) : (
                                <p className="text-xs text-muted text-center py-3">No documents attached.</p>
                            )}
                            <div className="flex items-center gap-2 pt-2 border-t border-border">
                                <input
                                    type="text" value={docName} onChange={(e) => setDocName(e.target.value)}
                                    placeholder="Document name"
                                    className="flex-1 border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                />
                                <label className={cn(
                                    "flex items-center gap-1 px-2.5 py-1.5 rounded-lg text-[10px] font-semibold transition-colors shrink-0",
                                    docName.trim() && !uploadingDoc ? "bg-primary text-primary-foreground hover:bg-primary/90 cursor-pointer" : "bg-input text-muted cursor-not-allowed"
                                )}>
                                    {uploadingDoc ? <Loader2 size={11} className="animate-spin" /> : <Upload size={11} />} Attach
                                    <input type="file" className="hidden" disabled={!docName.trim() || uploadingDoc}
                                        onChange={(e) => { const f = e.target.files?.[0]; if (f) handleDocUpload(f); if (e.target) e.target.value = ""; }}
                                    />
                                </label>
                            </div>
                        </div>
                    </div>
                    {/* Maintenance Tickets */}
                    <div className="bg-surface rounded-xl border border-border">
                        <div className="px-5 py-3.5 border-b border-border flex items-center justify-between">
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2"><Wrench size={13} /> Maintenance Tickets</h2>
                            <Link href="/dashboard/tickets" className="text-[10px] font-semibold text-primary hover:text-primary/80">View All</Link>
                        </div>
                        <div className="p-4">
                            {tickets.length > 0 ? (
                                <div className="space-y-2">
                                    {tickets.slice(0, 5).map(t => (
                                        <Link key={t.id} href={`/dashboard/tickets/${t.id}`} className="flex items-center justify-between bg-input/50 rounded-lg px-3 py-2 border border-border hover:bg-input transition-colors">
                                            <div className="min-w-0 flex-1">
                                                <p className="text-xs font-medium text-foreground truncate">{t.title}</p>
                                                <p className="text-[10px] text-muted">{t.category} &bull; {new Date(t.createdAt).toLocaleDateString()}</p>
                                            </div>
                                            <div className="flex items-center gap-2 shrink-0 ml-3">
                                                <span className={cn("px-2 py-0.5 rounded-md text-[9px] font-semibold", TICKET_PRIORITY_COLORS[t.priority] || "bg-input text-muted")}>{t.priority}</span>
                                                <span className={cn("px-2 py-0.5 rounded-md text-[9px] font-semibold", TICKET_STATUS_COLORS[t.status] || "bg-input text-muted")}>{t.status.replace("_", " ")}</span>
                                            </div>
                                        </Link>
                                    ))}
                                    {tickets.length > 5 && (
                                        <p className="text-[10px] text-muted text-center pt-1">+{tickets.length - 5} more tickets</p>
                                    )}
                                </div>
                            ) : (
                                <p className="text-xs text-muted text-center py-3">No maintenance tickets for this unit.</p>
                            )}
                        </div>
                    </div>
                </div>

                {/* Right Column: Payment Schedule */}
                <div className="lg:col-span-2">
                    {/* Penalty Summary */}
                    {(() => {
                        const activePenalties = penalties.filter(p => !p.waived);
                        const totalOutstanding = activePenalties.reduce((sum, p) => sum + p.penaltyAmount, 0);
                        if (activePenalties.length === 0) return null;
                        return (
                            <div className="bg-warning/10 border border-warning/20 rounded-xl px-5 py-3 mb-4 flex items-center gap-2">
                                <AlertTriangle size={14} className="text-warning shrink-0" />
                                <span className="text-sm font-semibold text-warning">Total Outstanding Penalties: {formatCurrency(totalOutstanding)}</span>
                            </div>
                        );
                    })()}

                    {/* Editable schedule for DRAFT / PENDING_SIGNATURE — admin can adjust dates,
                        cheque/bank/method per row before contract finalization. */}
                    {lease && (lease.status === "DRAFT" || lease.status === "PENDING_SIGNATURE") && (
                        <div className="bg-surface rounded-xl border border-border">
                            <div className="px-5 py-3.5 border-b border-border">
                                <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2">
                                    <CreditCard size={13} /> Payment Schedule
                                    <span className="ml-2 px-2 py-0.5 rounded-full text-[9px] font-semibold bg-amber-50 text-amber-700 border border-amber-200">
                                        Editable while {lease.status.replace("_", " ")}
                                    </span>
                                </h2>
                            </div>
                            <div className="px-5 py-4">
                                <PaymentScheduleEditor
                                    leaseId={lease.id}
                                    leaseStatus={lease.status}
                                    canManage={canGenerateContract}
                                    onSaved={fetchPayments}
                                />
                            </div>
                        </div>
                    )}

                    <div className={cn(
                        "bg-surface rounded-xl border border-border",
                        lease && (lease.status === "DRAFT" || lease.status === "PENDING_SIGNATURE") && "hidden"
                    )}>
                        <div className="px-5 py-3.5 border-b border-border">
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2"><CreditCard size={13} /> Payment Schedule</h2>
                        </div>
                        <div className="overflow-x-auto">
                            <table className="w-full">
                                <thead>
                                    <tr className="bg-input/50">
                                        <th className="px-4 py-2.5 text-start text-[10px] font-semibold text-muted uppercase tracking-wider">#</th>
                                        <th className="px-4 py-2.5 text-start text-[10px] font-semibold text-muted uppercase tracking-wider">Due Date</th>
                                        <th className="px-4 py-2.5 text-end text-[10px] font-semibold text-muted uppercase tracking-wider">Amount</th>
                                        <th className="px-4 py-2.5 text-center text-[10px] font-semibold text-muted uppercase tracking-wider">Status</th>
                                        <th className="px-4 py-2.5 text-end text-[10px] font-semibold text-muted uppercase tracking-wider">Penalty</th>
                                        <th className="px-4 py-2.5 text-start text-[10px] font-semibold text-muted uppercase tracking-wider">Cheque #</th>
                                        <th className="px-4 py-2.5 text-start text-[10px] font-semibold text-muted uppercase tracking-wider">Bank</th>
                                        <th className="px-4 py-2.5 text-end text-[10px] font-semibold text-muted uppercase tracking-wider">Receipt</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {payments.map(p => (
                                        <tr key={p.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                            <td className="px-4 py-2.5 text-xs text-muted">{p.installmentNumber}</td>
                                            <td className="px-4 py-2.5 text-xs text-foreground tabular-nums">{new Date(p.dueDate).toLocaleDateString()}</td>
                                            <td className="px-4 py-2.5 text-xs font-medium text-foreground text-end tabular-nums">{formatCurrency(p.amount)}</td>
                                            <td className="px-4 py-2.5 text-center">
                                                <span className={cn("px-2 py-0.5 rounded-md text-[9px] font-semibold", PAYMENT_STATUS_COLORS[p.status] || "bg-input text-muted")}>
                                                    {p.status.replace("_", " ")}
                                                </span>
                                            </td>
                                            <td className="px-4 py-2.5 text-end">
                                                {(() => {
                                                    const penalty = penalties.find(pen => pen.paymentScheduleId === p.id);
                                                    if (!penalty) return <span className="text-xs text-muted">—</span>;
                                                    if (penalty.waived) return <span className="text-xs text-muted line-through">Waived</span>;
                                                    return (
                                                        <span className="inline-flex items-center gap-1.5">
                                                            <span className="text-xs font-medium text-error tabular-nums">{formatCurrency(penalty.penaltyAmount)}</span>
                                                            {isAdmin && (
                                                                <button
                                                                    onClick={() => { setWaiveModalPenalty(penalty); setWaiveReason(""); }}
                                                                    className="text-[9px] font-semibold text-warning hover:text-warning/80 cursor-pointer underline"
                                                                >
                                                                    Waive
                                                                </button>
                                                            )}
                                                        </span>
                                                    );
                                                })()}
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted">{p.chequeNumber || "—"}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted">{p.bankName || "—"}</td>
                                            <td className="px-4 py-2.5 text-end">
                                                {p.status === "CLEARED" && (
                                                    <button
                                                        onClick={() => handleDownloadReceipt(p.id, p.installmentNumber)}
                                                        className="text-[10px] font-semibold text-primary hover:text-primary/80 cursor-pointer"
                                                    >
                                                        <Download size={12} />
                                                    </button>
                                                )}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                            {payments.length === 0 && (
                                <div className="text-center py-8 text-muted">
                                    <CreditCard size={24} className="mx-auto mb-2 opacity-40" />
                                    <p className="text-xs">No payment schedule generated yet.</p>
                                </div>
                            )}
                        </div>
                    </div>
                </div>
            </div>

            {/* Waive Penalty Modal */}
            {waiveModalPenalty && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
                    <div className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-sm mx-4">
                        <div className="flex items-center justify-between px-5 py-3.5 border-b border-border">
                            <h3 className="text-sm font-semibold text-foreground">Waive Penalty</h3>
                            <button onClick={() => setWaiveModalPenalty(null)} className="p-1 text-muted hover:text-foreground cursor-pointer"><X size={14} /></button>
                        </div>
                        <div className="p-5 space-y-4">
                            <div>
                                <p className="text-xs text-muted mb-1">Penalty Amount</p>
                                <p className="text-sm font-semibold text-error">{formatCurrency(waiveModalPenalty.penaltyAmount)}</p>
                                <p className="text-[10px] text-muted mt-0.5">{waiveModalPenalty.daysOverdue} days overdue</p>
                            </div>
                            <div>
                                <label className="text-xs font-medium text-foreground block mb-1.5">Reason for waiving</label>
                                <textarea
                                    value={waiveReason}
                                    onChange={(e) => setWaiveReason(e.target.value)}
                                    placeholder="Enter reason..."
                                    rows={3}
                                    className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none"
                                />
                            </div>
                            <div className="flex items-center gap-2 justify-end">
                                <button
                                    onClick={() => setWaiveModalPenalty(null)}
                                    className="px-3 py-1.5 rounded-lg text-xs font-semibold text-muted hover:text-foreground border border-border hover:bg-input transition-colors cursor-pointer"
                                >
                                    Cancel
                                </button>
                                <button
                                    onClick={handleWaivePenalty}
                                    disabled={!waiveReason.trim() || waiving}
                                    className={cn(
                                        "px-3 py-1.5 rounded-lg text-xs font-semibold transition-colors cursor-pointer",
                                        waiveReason.trim() && !waiving
                                            ? "bg-warning text-white hover:bg-warning/90"
                                            : "bg-input text-muted cursor-not-allowed"
                                    )}
                                >
                                    {waiving ? <Loader2 size={12} className="animate-spin" /> : "Confirm Waive"}
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Settlement Summary for TERMINATED/CLOSED leases */}
            {(lease.status === "TERMINATED" || lease.status === "CLOSED") && settlement && (
                settlement.status === "DRAFT" ? (
                    <div className="mt-6 bg-surface rounded-xl border border-border px-5 py-4 flex items-center justify-between">
                        <div className="flex items-center gap-2 text-xs text-muted">
                            <DollarSign size={13} />
                            <span>Settlement in progress</span>
                        </div>
                        <Link
                            href={`/dashboard/leases/${leaseId}/settlement`}
                            className="text-xs font-semibold text-primary hover:underline"
                        >
                            Continue Settlement →
                        </Link>
                    </div>
                ) : (
                    <div className="mt-6 bg-surface rounded-xl border border-border">
                        <div className="px-5 py-3.5 border-b border-border">
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2">
                                <DollarSign size={13} /> Settlement Summary
                            </h2>
                        </div>
                        <div className="px-5 py-4 space-y-3">
                            <div className="flex justify-between items-center">
                                <span className="text-xs text-muted">Security Deposit</span>
                                <span className="text-xs font-semibold text-foreground tabular-nums">{formatCurrency(settlement.depositAmount)}</span>
                            </div>
                            <div className="border-t border-border pt-3 space-y-3">
                                <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">Deductions</p>
                                {settlement.deductions.map((d, i) => (
                                    <div key={i} className="space-y-1.5">
                                        <div className="flex justify-between items-center">
                                            <div>
                                                <span className="text-xs text-foreground">{DEDUCTION_CATEGORY_LABELS[d.category] || d.category}</span>
                                                {d.description && <span className="text-[10px] text-muted ml-2">({d.description})</span>}
                                            </div>
                                            <span className="text-xs font-medium text-error tabular-nums">- {formatCurrency(d.amount)}</span>
                                        </div>
                                        {d.attachments && d.attachments.length > 0 && (
                                            <div className="ml-2 space-y-1">
                                                {d.attachments.map((att) => (
                                                    <div key={att.id} className="flex items-center gap-2 bg-input rounded-lg px-2 py-1.5">
                                                        {att.fileType?.startsWith("image/") ? (
                                                            <img src={att.fileUrl} className="w-12 h-10 rounded object-cover flex-shrink-0" alt={att.name} />
                                                        ) : (
                                                            <FileText size={14} className="text-muted flex-shrink-0" />
                                                        )}
                                                        <div className="flex-1 min-w-0">
                                                            <p className="text-[10px] font-medium text-foreground truncate">{att.name}</p>
                                                            <p className="text-[10px] text-muted">{(att.fileSize / 1024).toFixed(0)} KB</p>
                                                        </div>
                                                        <button
                                                            onClick={() => handleSettlementAttachmentDownload(att.id, att.name)}
                                                            className="p-1 text-primary hover:text-primary/80 cursor-pointer flex-shrink-0"
                                                            title="Download"
                                                        >
                                                            <Download size={12} />
                                                        </button>
                                                    </div>
                                                ))}
                                            </div>
                                        )}
                                    </div>
                                ))}
                            </div>
                            <div className="border-t border-border pt-3 flex justify-between items-center">
                                <span className="text-xs font-semibold text-muted">Total Deductions</span>
                                <span className="text-xs font-semibold text-error tabular-nums">- {formatCurrency(settlement.totalDeductions)}</span>
                            </div>
                            {settlement.totalAdditions != null && settlement.totalAdditions > 0 && (
                                <div className="flex justify-between items-center">
                                    <span className="text-xs font-semibold text-muted">Total Additions</span>
                                    <span className="text-xs font-semibold text-success tabular-nums">
                                        + {formatCurrency(settlement.totalAdditions)}
                                    </span>
                                </div>
                            )}
                            <div className="border-t-2 border-border pt-3 flex justify-between items-center">
                                <span className="text-sm font-bold text-foreground">Refund to Renter</span>
                                <span className={cn("text-sm font-bold tabular-nums", settlement.refundAmount >= 0 ? "text-success" : "text-error")}>
                                    {formatCurrency(settlement.refundAmount)}
                                </span>
                            </div>
                            {settlement.notes && (
                                <div className="border-t border-border pt-3">
                                    <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Notes</p>
                                    <p className="text-xs text-foreground">{settlement.notes}</p>
                                </div>
                            )}
                            <div className="border-t border-border pt-3 flex justify-between items-center text-[10px] text-muted">
                                <span>Settled by: {settlement.settledByName || settlement.settledBy}</span>
                                <span>{new Date(settlement.settledAt).toLocaleDateString()} {new Date(settlement.settledAt).toLocaleTimeString()}</span>
                            </div>
                            <div className="border-t border-border pt-3 flex justify-end">
                                <Link
                                    href={`/dashboard/leases/${leaseId}/settlement`}
                                    className="text-[10px] font-semibold text-primary hover:underline"
                                >
                                    Manage attachments →
                                </Link>
                            </div>
                        </div>
                    </div>
                )
            )}

        </div>

        {/* Generate Contract Preview Modal */}
        {previewOpen && previewBlobUrl && (
            <div className="fixed inset-0 z-50 flex flex-col bg-black/70">
                <div className="flex items-center justify-between bg-surface border-b border-border px-5 py-3 shrink-0">
                    <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
                        <Sparkles size={15} className="text-primary" /> Contract Preview
                    </h3>
                    <div className="flex items-center gap-2">
                        <a
                            href={previewBlobUrl}
                            target="_blank"
                            rel="noreferrer"
                            className="px-4 py-2 rounded-lg text-xs font-semibold text-primary hover:bg-input border border-primary/30 transition-colors cursor-pointer flex items-center gap-1.5"
                            title="Open the preview PDF in a new tab"
                        >
                            <FileText size={12} /> Open in new tab
                        </a>
                        <button
                            onClick={handleClosePreview}
                            className="px-4 py-2 rounded-lg text-xs font-semibold text-muted hover:bg-input border border-border transition-colors cursor-pointer"
                        >
                            {t("cancel")}
                        </button>
                        <button
                            onClick={handleConfirmAndSave}
                            disabled={confirmSaving}
                            className="flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 transition-colors cursor-pointer disabled:opacity-50"
                        >
                            {confirmSaving ? <Loader2 size={12} className="animate-spin" /> : <CheckCircle size={12} />}
                            {t("confirmAndSave")}
                        </button>
                        <button onClick={handleClosePreview} className="p-1.5 text-muted hover:text-foreground cursor-pointer ml-1">
                            <X size={16} />
                        </button>
                    </div>
                </div>
                {contractError && (
                    <div className="bg-error/10 border-b border-error/20 px-5 py-2 shrink-0">
                        <p className="text-xs text-error">{contractError}</p>
                    </div>
                )}
                <div className="flex-1 overflow-hidden bg-input flex items-center justify-center">
                    {/* <object> renders the PDF inline using the browser's built-in
                        viewer. If the browser has no plugin (or the PDF MIME type
                        is suppressed), the fallback content shows an obvious
                        "Open in new tab" link rather than a blank iframe. */}
                    <object
                        data={previewBlobUrl}
                        type="application/pdf"
                        className="w-full h-full"
                        aria-label="Contract Preview"
                    >
                        <div className="text-center p-8">
                            <p className="text-sm text-muted mb-3">
                                Your browser couldn&apos;t render the PDF inline.
                            </p>
                            <a
                                href={previewBlobUrl}
                                target="_blank"
                                rel="noreferrer"
                                className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
                            >
                                <FileText size={14} /> Open preview in a new tab
                            </a>
                        </div>
                    </object>
                </div>
            </div>
        )}

        {/* Extend Lease Modal */}
        {extendOpen && (
            <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
                <div className="bg-surface rounded-2xl border border-border shadow-xl w-full max-w-sm p-6">
                    <div className="flex items-center justify-between mb-4">
                        <h3 className="text-sm font-semibold text-foreground flex items-center gap-2">
                            <CalendarClock size={15} className="text-primary" /> Extend Lease
                        </h3>
                        <button onClick={() => setExtendOpen(false)} className="text-muted hover:text-foreground cursor-pointer">
                            <X size={16} />
                        </button>
                    </div>
                    <p className="text-xs text-muted mb-4">
                        Current end date: <strong>{lease.endDate}</strong>. Enter a new end date after this date.
                    </p>
                    <div className="mb-4">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                            New End Date *
                        </label>
                        <input
                            type="date"
                            value={extendDate}
                            min={lease.endDate}
                            onChange={e => { setExtendDate(e.target.value); setExtendError(null); }}
                            className="w-full border border-border rounded-lg px-3 py-2 text-sm text-foreground bg-surface focus:outline-none focus:ring-2 focus:ring-primary/30"
                        />
                    </div>
                    {extendError && (
                        <p className="text-xs text-error mb-3">{extendError}</p>
                    )}
                    <div className="flex gap-2 justify-end">
                        <button
                            onClick={() => setExtendOpen(false)}
                            className="px-4 py-2 rounded-lg text-xs font-semibold text-muted hover:bg-input transition-colors cursor-pointer"
                        >
                            Cancel
                        </button>
                        <button
                            onClick={handleExtendLease}
                            disabled={extending || !extendDate}
                            className="flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 transition-colors cursor-pointer disabled:opacity-50"
                        >
                            {extending ? <Loader2 size={12} className="animate-spin" /> : <CalendarClock size={12} />}
                            Extend Lease
                        </button>
                    </div>
                </div>
            </div>
        )}
        </>
    );
}
