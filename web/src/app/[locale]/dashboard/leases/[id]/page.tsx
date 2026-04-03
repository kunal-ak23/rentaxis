"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { hasRole, type UserRole } from "@/lib/rbac";
import { formatCurrency, formatCurrencyCompact } from "@/lib/format";
import {
    ArrowLeft, FileText, User, Building2, Calendar, DollarSign, CreditCard,
    Home, Download, Upload, Trash2, Loader2, CheckCircle, Clock, AlertTriangle,
    Hash, Phone, Mail, MapPin, Wrench, X, Ban, Plus,
} from "lucide-react";

type Lease = {
    id: string; unitId: string; renterId: string; unitIdentifier: string;
    renterName: string; startDate: string; endDate: string; status: string;
    rentAmount: number; monthlyRent: number | null; depositAmount: number;
    ejariNumber: string; paymentTerms: number; paymentMethod: string;
    depositPaymentMethod: string; paymentReferenceNumber: string;
    propertyId: string; propertyName: string; hasContract: boolean;
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

type SettlementPreview = {
    depositAmount: number;
    unpaidRentTotal: number;
    penaltyTotal: number;
    suggestedRefund: number;
};

type SettlementDeduction = {
    category: string;
    description: string;
    amount: number;
};

type Settlement = {
    id: string;
    leaseId: string;
    depositAmount: number;
    totalDeductions: number;
    refundAmount: number;
    notes: string;
    settledBy: string;
    settledByName?: string;
    settledAt: string;
    deductions: { category: string; description: string; amount: number }[];
};

const DEDUCTION_CATEGORIES = [
    { value: "PROPERTY_DAMAGE", label: "Property Damage" },
    { value: "EARLY_TERMINATION_FEE", label: "Early Termination Fee" },
    { value: "CLEANING", label: "Cleaning" },
    { value: "UTILITY_ARREARS", label: "Utility Arrears" },
    { value: "KEY_REPLACEMENT", label: "Key Replacement" },
    { value: "OTHER", label: "Other" },
];

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
    const searchParams = useSearchParams();
    const leaseId = params.id as string;
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const isAdmin = hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"]);

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
    const [showSettlementModal, setShowSettlementModal] = useState(false);
    const [settlementPreview, setSettlementPreview] = useState<SettlementPreview | null>(null);
    const [settlementPreviewLoading, setSettlementPreviewLoading] = useState(false);
    const [settlementNotes, setSettlementNotes] = useState("");
    const [autoDeductions, setAutoDeductions] = useState<SettlementDeduction[]>([]);
    const [manualDeductions, setManualDeductions] = useState<SettlementDeduction[]>([]);
    const [terminateLoading, setTerminateLoading] = useState(false);
    const [settlement, setSettlement] = useState<Settlement | null>(null);

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
                // Backend returns { settlement: {...}, deductions: [...] }
                setSettlement({
                    ...data.settlement,
                    deductions: data.deductions || [],
                });
            }
        } catch {}
    }, [leaseId]);

    const openSettlementModal = async () => {
        setSettlementPreviewLoading(true);
        setShowSettlementModal(true);
        setSettlementNotes("");
        setManualDeductions([]);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/settlement/preview`);
            if (res.ok) {
                const preview: SettlementPreview = await res.json();
                setSettlementPreview(preview);
                const auto: SettlementDeduction[] = [];
                if (preview.unpaidRentTotal > 0) {
                    auto.push({ category: "UNPAID_RENT", description: "Outstanding rent", amount: preview.unpaidRentTotal });
                }
                if (preview.penaltyTotal > 0) {
                    auto.push({ category: "PENALTIES", description: "Late payment penalties", amount: preview.penaltyTotal });
                }
                setAutoDeductions(auto);
            }
        } catch {} finally {
            setSettlementPreviewLoading(false);
        }
    };

    const handleTerminateWithSettlement = async () => {
        setTerminateLoading(true);
        try {
            const allDeductions = [
                ...autoDeductions.filter(d => d.amount > 0),
                ...manualDeductions.filter(d => d.amount > 0),
            ];
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/terminate`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    notes: settlementNotes || undefined,
                    deductions: allDeductions,
                }),
            });
            if (res.ok) {
                setShowSettlementModal(false);
                fetchLease();
                fetchPayments();
                fetchPenalties();
            }
        } catch {} finally {
            setTerminateLoading(false);
        }
    };

    const settlementDepositAmount = settlementPreview?.depositAmount ?? 0;
    const totalDeductionAmount = [...autoDeductions, ...manualDeductions].reduce((sum, d) => sum + (d.amount || 0), 0);
    const refundAmount = settlementDepositAmount - totalDeductionAmount;

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

    // Auto-open settlement modal when redirected from list with ?action=terminate
    useEffect(() => {
        if (searchParams.get("action") === "terminate" && lease?.status === "ACTIVE" && !showSettlementModal) {
            openSettlementModal();
        }
    }, [lease?.status, searchParams]);

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

    const handleDownloadContract = async () => {
        if (!lease) return;
        const res = await fetch(`/api/proxy/v1/leases/${lease.id}/documents`);
        if (res.ok) {
            const docs = await res.json();
            if (docs.length > 0) {
                const pdfRes = await fetch(`/api/proxy/v1/leases/documents/${docs[0].id}/download`);
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
                    </div>
                    <p className="text-sm text-muted">{lease.propertyName} &bull; {lease.renterName}</p>
                </div>
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
                        onClick={openSettlementModal}
                        className="flex items-center gap-2 bg-error text-white px-4 py-2 rounded-lg text-xs font-semibold hover:bg-error/90 transition-all cursor-pointer"
                    >
                        <Ban size={14} /> Terminate
                    </button>
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

                    <div className="bg-surface rounded-xl border border-border">
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
                        <div className="border-t border-border pt-3 space-y-2">
                            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">Deductions</p>
                            {settlement.deductions.map((d, i) => (
                                <div key={i} className="flex justify-between items-center">
                                    <div>
                                        <span className="text-xs text-foreground">{DEDUCTION_CATEGORY_LABELS[d.category] || d.category}</span>
                                        {d.description && <span className="text-[10px] text-muted ml-2">({d.description})</span>}
                                    </div>
                                    <span className="text-xs font-medium text-error tabular-nums">- {formatCurrency(d.amount)}</span>
                                </div>
                            ))}
                        </div>
                        <div className="border-t border-border pt-3 flex justify-between items-center">
                            <span className="text-xs font-semibold text-muted">Total Deductions</span>
                            <span className="text-xs font-semibold text-error tabular-nums">- {formatCurrency(settlement.totalDeductions)}</span>
                        </div>
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
                    </div>
                </div>
            )}

            {/* Settlement Modal */}
            {showSettlementModal && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50">
                    <div className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 max-h-[90vh] overflow-y-auto">
                        <div className="px-5 py-4 border-b border-border flex items-center justify-between sticky top-0 bg-surface z-10">
                            <h2 className="text-sm font-bold text-foreground">Terminate Lease &mdash; Settlement</h2>
                            <button onClick={() => setShowSettlementModal(false)} className="p-1 text-muted hover:text-foreground cursor-pointer">
                                <X size={16} />
                            </button>
                        </div>

                        {settlementPreviewLoading ? (
                            <div className="flex items-center justify-center py-12">
                                <Loader2 className="w-5 h-5 animate-spin text-primary opacity-60" />
                            </div>
                        ) : settlementPreview ? (
                            <div className="px-5 py-4 space-y-5">
                                {/* Security Deposit */}
                                <div className="bg-primary/5 border border-primary/20 rounded-lg px-4 py-3">
                                    <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Security Deposit</p>
                                    <p className="text-lg font-bold text-foreground tabular-nums">{formatCurrency(settlementPreview.depositAmount)}</p>
                                </div>

                                {/* Auto-calculated deductions */}
                                {autoDeductions.length > 0 && (
                                    <div>
                                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-2">Auto-calculated Deductions</p>
                                        <div className="space-y-2">
                                            {autoDeductions.map((d, i) => (
                                                <div key={i} className="flex items-center gap-3 bg-input/50 rounded-lg px-3 py-2 border border-border">
                                                    <div className="flex-1 min-w-0">
                                                        <div className="flex items-center gap-2">
                                                            <span className="text-xs font-medium text-foreground">{DEDUCTION_CATEGORY_LABELS[d.category]}</span>
                                                            <span className="px-1.5 py-0.5 rounded text-[8px] font-bold bg-info/10 text-info uppercase">Auto</span>
                                                        </div>
                                                        <p className="text-[10px] text-muted">{d.description}</p>
                                                    </div>
                                                    <input
                                                        type="number"
                                                        value={d.amount}
                                                        min={0}
                                                        step={0.01}
                                                        onChange={(e) => {
                                                            const val = parseFloat(e.target.value) || 0;
                                                            setAutoDeductions(prev => prev.map((dd, ii) => ii === i ? { ...dd, amount: val } : dd));
                                                        }}
                                                        className="w-28 border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground text-end tabular-nums focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                                    />
                                                </div>
                                            ))}
                                        </div>
                                    </div>
                                )}

                                {/* Manual deductions */}
                                <div>
                                    <div className="flex items-center justify-between mb-2">
                                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">Manual Deductions</p>
                                        <button
                                            onClick={() => setManualDeductions(prev => [...prev, { category: "PROPERTY_DAMAGE", description: "", amount: 0 }])}
                                            className="flex items-center gap-1 text-[10px] font-semibold text-primary hover:text-primary/80 cursor-pointer"
                                        >
                                            <Plus size={12} /> Add Deduction
                                        </button>
                                    </div>
                                    {manualDeductions.length > 0 ? (
                                        <div className="space-y-2">
                                            {manualDeductions.map((d, i) => (
                                                <div key={i} className="bg-input/50 rounded-lg px-3 py-2 border border-border space-y-2">
                                                    <div className="flex items-center gap-2">
                                                        <select
                                                            value={d.category}
                                                            onChange={(e) => setManualDeductions(prev => prev.map((dd, ii) => ii === i ? { ...dd, category: e.target.value } : dd))}
                                                            className="flex-1 border border-border rounded-lg bg-surface px-2 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                                        >
                                                            {DEDUCTION_CATEGORIES.map(c => (
                                                                <option key={c.value} value={c.value}>{c.label}</option>
                                                            ))}
                                                        </select>
                                                        <input
                                                            type="number"
                                                            value={d.amount}
                                                            min={0}
                                                            step={0.01}
                                                            placeholder="Amount"
                                                            onChange={(e) => {
                                                                const val = parseFloat(e.target.value) || 0;
                                                                setManualDeductions(prev => prev.map((dd, ii) => ii === i ? { ...dd, amount: val } : dd));
                                                            }}
                                                            className="w-28 border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground text-end tabular-nums focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                                        />
                                                        <button
                                                            onClick={() => setManualDeductions(prev => prev.filter((_, ii) => ii !== i))}
                                                            className="p-1 text-error hover:text-error/80 cursor-pointer"
                                                        >
                                                            <X size={14} />
                                                        </button>
                                                    </div>
                                                    <input
                                                        type="text"
                                                        value={d.description}
                                                        placeholder="Description (optional)"
                                                        onChange={(e) => setManualDeductions(prev => prev.map((dd, ii) => ii === i ? { ...dd, description: e.target.value } : dd))}
                                                        className="w-full border border-border rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                                                    />
                                                </div>
                                            ))}
                                        </div>
                                    ) : (
                                        <p className="text-xs text-muted text-center py-3 bg-input/30 rounded-lg border border-dashed border-border">No manual deductions added.</p>
                                    )}
                                </div>

                                {/* Settlement Summary Bar */}
                                <div className="bg-input/50 rounded-lg border border-border px-4 py-3 space-y-2">
                                    <div className="flex justify-between items-center">
                                        <span className="text-xs text-muted">Security Deposit</span>
                                        <span className="text-xs font-semibold text-foreground tabular-nums">{formatCurrency(settlementDepositAmount)}</span>
                                    </div>
                                    <div className="flex justify-between items-center">
                                        <span className="text-xs text-muted">Total Deductions</span>
                                        <span className="text-xs font-semibold text-error tabular-nums">- {formatCurrency(totalDeductionAmount)}</span>
                                    </div>
                                    <div className="border-t border-border pt-2 flex justify-between items-center">
                                        <span className="text-sm font-bold text-foreground">Refund to Renter</span>
                                        <span className={cn("text-sm font-bold tabular-nums", refundAmount >= 0 ? "text-success" : "text-error")}>
                                            {formatCurrency(refundAmount)}
                                        </span>
                                    </div>
                                </div>

                                {/* Notes */}
                                <div>
                                    <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-2">Notes (optional)</p>
                                    <textarea
                                        value={settlementNotes}
                                        onChange={(e) => setSettlementNotes(e.target.value)}
                                        placeholder="Termination notes..."
                                        rows={3}
                                        className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none"
                                    />
                                </div>

                                {/* Buttons */}
                                <div className="flex justify-end gap-3 pt-2">
                                    <button
                                        onClick={() => setShowSettlementModal(false)}
                                        className="px-4 py-2 rounded-lg text-xs font-semibold text-muted hover:text-foreground bg-input hover:bg-input/80 transition-colors cursor-pointer"
                                    >
                                        Cancel
                                    </button>
                                    <button
                                        onClick={handleTerminateWithSettlement}
                                        disabled={terminateLoading}
                                        className="flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold text-white bg-error hover:bg-error/90 transition-colors cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                                    >
                                        {terminateLoading && <Loader2 size={12} className="animate-spin" />}
                                        Terminate & Settle
                                    </button>
                                </div>
                            </div>
                        ) : (
                            <div className="px-5 py-8 text-center">
                                <p className="text-xs text-muted">Failed to load settlement preview.</p>
                            </div>
                        )}
                    </div>
                </div>
            )}
        </div>
    );
}
