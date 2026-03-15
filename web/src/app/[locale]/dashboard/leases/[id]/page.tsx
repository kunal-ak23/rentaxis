"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams } from "next/navigation";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { formatCurrency, formatCurrencyCompact } from "@/lib/format";
import {
    ArrowLeft, FileText, User, Building2, Calendar, DollarSign, CreditCard,
    Home, Download, Upload, Trash2, Loader2, CheckCircle, Clock, AlertTriangle,
    Hash, Phone, Mail, MapPin, Wrench,
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
    REPLACED: "bg-input text-muted",
    CANCELLED: "bg-input text-muted",
    ONLINE_PENDING: "bg-warning/10 text-warning",
};

export default function LeaseDetailPage() {
    const params = useParams();
    const leaseId = params.id as string;

    const [lease, setLease] = useState<Lease | null>(null);
    const [renter, setRenter] = useState<Renter | null>(null);
    const [payments, setPayments] = useState<Payment[]>([]);
    const [attachments, setAttachments] = useState<Attachment[]>([]);
    const [loading, setLoading] = useState(true);
    const [docName, setDocName] = useState("");
    const [uploadingDoc, setUploadingDoc] = useState(false);
    const [tickets, setTickets] = useState<Ticket[]>([]);

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
        Promise.all([fetchLease(), fetchPayments(), fetchAttachments()]).finally(() => setLoading(false));
    }, [fetchLease, fetchPayments, fetchAttachments]);

    useEffect(() => {
        if (lease?.unitId) fetchTickets();
    }, [lease?.unitId, fetchTickets]);

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
        </div>
    );
}
