"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import { FileText, Calendar, DollarSign, Home, CheckCircle, XCircle, Download, Clock, AlertCircle, CreditCard, CalendarDays, Plus, Eye, ChevronLeft, ChevronRight, ChevronDown } from "lucide-react";
import { useSession } from "next-auth/react";
import { cn } from "@/lib/utils";
import { formatCurrencyCompact } from "@/lib/format";
import { Link } from "@/i18n/routing";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import CreateMeetingModal from "@/app/[locale]/dashboard/meetings/CreateMeetingModal";
import RenewalBanner from "@/components/renewals/RenewalBanner";

type Lease = {
    id: string;
    unitId: string;
    renterId: string;
    unitIdentifier: string;
    renterName: string;
    startDate: string;
    endDate: string;
    status: string;
    rentAmount: number;
    depositAmount: number;
    ejariNumber: string;
    paymentTerms: number;
    propertyName: string;
    hasContract: boolean;
};

type Meeting = {
    id: string;
    title: string | null;
    purpose: string;
    type: string;
    status: string;
    slotStart: string;
    slotEnd: string;
    hostName: string | null;
    propertyName: string | null;
    unitNumber: string | null;
};

const MEETING_STATUS_COLORS: Record<string, string> = {
    REQUESTED: "bg-warning/10 text-warning border border-warning/20",
    APPROVED: "bg-info/10 text-info border border-info/20",
    COMPLETED: "bg-success/10 text-success border border-success/20",
    CANCELLED: "bg-input text-muted border border-border",
    NO_SHOW: "bg-error/10 text-error border border-error/20",
};

const MEETINGS_PER_PAGE = 5;

export default function RenterPortalPage() {
    const t = useTranslations("MasterData");
    const tPayments = useTranslations("OnlinePayments");
    const [leases, setLeases] = useState<Lease[]>([]);
    const [loading, setLoading] = useState(true);
    const [nextPayment, setNextPayment] = useState<{ dueDate: string; amount: number; daysUntilDue: number; isOverdue: boolean } | null>(null);
    const [paymentsByLease, setPaymentsByLease] = useState<Record<string, Array<{ id: string; installmentNumber: number; dueDate: string; amount: number; status: string; paymentMethod: string }>>>({});
    const [expandedPlanLeaseId, setExpandedPlanLeaseId] = useState<string | null>(null);
    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        confirmText: string;
        isDestructive: boolean;
        onConfirm: () => void;
    } | null>(null);
    const { data: session } = useSession();

    // Meetings state
    const [meetings, setMeetings] = useState<Meeting[]>([]);
    const [meetingsLoading, setMeetingsLoading] = useState(true);
    const [meetingsPage, setMeetingsPage] = useState(1);
    const [meetingsTotalPages, setMeetingsTotalPages] = useState(1);
    const [showCreateMeeting, setShowCreateMeeting] = useState(false);

    useEffect(() => {
        fetchMyLeases();
        fetchPendingPayments();
    }, []);

    const fetchMyMeetings = useCallback(async () => {
        setMeetingsLoading(true);
        try {
            const res = await fetch(`/api/proxy/v1/meetings/my?page=${meetingsPage - 1}&size=${MEETINGS_PER_PAGE}`);
            if (res.ok) {
                const data = await res.json();
                if (Array.isArray(data)) {
                    setMeetings(data);
                    setMeetingsTotalPages(1);
                } else if (data.content) {
                    setMeetings(data.content);
                    setMeetingsTotalPages(data.totalPages ?? 1);
                }
            }
        } catch (err) {
            console.error(err);
        } finally {
            setMeetingsLoading(false);
        }
    }, [meetingsPage]);

    useEffect(() => {
        fetchMyMeetings();
    }, [fetchMyMeetings]);

    const fetchMyLeases = async () => {
        try {
            const res = await fetch("/api/proxy/v1/leases/my-leases");
            if (res.ok) setLeases(await res.json());
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const fetchPendingPayments = async () => {
        try {
            const res = await fetch("/api/proxy/v1/online-payments/my-payments");
            if (res.ok) {
                const payments = await res.json();

                // Group all payments by lease so we can show the full schedule on
                // the PENDING_SIGNATURE acceptance card before the renter signs.
                const grouped: Record<string, any[]> = {};
                payments.forEach((p: any) => {
                    if (!grouped[p.leaseId]) grouped[p.leaseId] = [];
                    grouped[p.leaseId].push(p);
                });
                Object.values(grouped).forEach(arr =>
                    arr.sort((a, b) => a.installmentNumber - b.installmentNumber)
                );
                setPaymentsByLease(grouped);

                const pending = payments
                    .filter((p: any) => p.status === "PENDING" || p.status === "ONLINE_PENDING")
                    .sort((a: any, b: any) => new Date(a.dueDate).getTime() - new Date(b.dueDate).getTime());

                if (pending.length > 0) {
                    const next = pending[0];
                    const today = new Date();
                    today.setHours(0, 0, 0, 0);
                    const due = new Date(next.dueDate);
                    due.setHours(0, 0, 0, 0);
                    const diffDays = Math.round((due.getTime() - today.getTime()) / (1000 * 60 * 60 * 24));
                    setNextPayment({
                        dueDate: next.dueDate,
                        amount: next.totalPayable || next.amount,
                        daysUntilDue: diffDays,
                        isOverdue: diffDays < 0,
                    });
                }
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleAccept = (id: string) => {
        setConfirmDialog({
            title: "Accept Lease",
            description: "Are you sure you want to accept this lease? This action cannot be undone.",
            confirmText: "Accept Lease",
            isDestructive: false,
            onConfirm: async () => {
                setConfirmDialog(null);
                try {
                    const res = await fetch(`/api/proxy/v1/leases/${id}/accept`, { method: "PUT" });
                    if (res.ok) fetchMyLeases();
                } catch (err) {
                    console.error(err);
                }
            },
        });
    };

    const handleReject = (id: string) => {
        setConfirmDialog({
            title: "Reject Lease",
            description: "Are you sure you want to reject this lease? This action cannot be undone.",
            confirmText: "Reject Lease",
            isDestructive: true,
            onConfirm: async () => {
                setConfirmDialog(null);
                try {
                    const res = await fetch(`/api/proxy/v1/leases/${id}/reject`, { method: "PUT" });
                    if (res.ok) fetchMyLeases();
                } catch (err) {
                    console.error(err);
                }
            },
        });
    };

    const handleDownloadContract = async (id: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/${id}/documents`);
            if (res.ok) {
                const docs = await res.json();
                if (docs.length > 0) {
                    const pdfRes = await fetch(`/api/proxy/v1/leases/documents/${docs[0].id}/download`);
                    if (pdfRes.ok) {
                        const blob = await pdfRes.blob();
                        const url = URL.createObjectURL(blob);
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = `contract-${id}.pdf`;
                        document.body.appendChild(a);
                        a.click();
                        document.body.removeChild(a);
                        URL.revokeObjectURL(url);
                    }
                }
            }
        } catch (err) {
            console.error(err);
        }
    };

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'ACTIVE': return 'bg-success/10 text-success border border-success/20';
            case 'DRAFT': return 'bg-input text-muted border border-border';
            case 'PENDING_SIGNATURE': return 'bg-warning/10 text-warning border border-warning/20';
            case 'TERMINATED': return 'bg-error/10 text-error border border-error/20';
            case 'EXPIRED': return 'bg-warning/10 text-warning border border-warning/20';
            default: return 'bg-info/10 text-info border border-info/20';
        }
    };

    const userName = session?.user?.name || "Renter";

    if (loading) {
        return (
            <div className="p-8 max-w-5xl mx-auto">
                <div className="mb-10">
                    <div className="h-6 w-48 bg-input rounded animate-pulse mb-2" />
                    <div className="h-4 w-64 bg-input rounded animate-pulse" />
                </div>
                <div className="bg-surface rounded-xl p-5 border border-border animate-pulse mb-6">
                    <div className="flex items-center gap-4">
                        <div className="w-12 h-12 bg-input rounded-xl" />
                        <div>
                            <div className="h-4 w-32 bg-input rounded mb-2" />
                            <div className="h-3 w-48 bg-input rounded" />
                        </div>
                    </div>
                </div>
                {[1, 2].map(i => (
                    <div key={i} className="bg-surface rounded-xl p-5 border border-border animate-pulse mb-6">
                        <div className="flex justify-between items-start mb-6">
                            <div className="flex items-center gap-4">
                                <div className="w-12 h-12 bg-input rounded-xl" />
                                <div>
                                    <div className="h-4 w-32 bg-input rounded mb-2" />
                                    <div className="h-3 w-24 bg-input rounded" />
                                </div>
                            </div>
                            <div className="h-6 w-20 bg-input rounded-full" />
                        </div>
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                            {[1, 2, 3, 4].map(j => (
                                <div key={j} className="bg-input rounded-xl p-3 border border-border">
                                    <div className="h-3 w-12 bg-input rounded mb-2" />
                                    <div className="h-4 w-20 bg-input rounded" />
                                </div>
                            ))}
                        </div>
                    </div>
                ))}
            </div>
        );
    }

    return (
        <div className="p-8 max-w-5xl mx-auto">
            <div className="mb-10">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">
                    {t("welcomeRenter")}, {userName}
                </h1>
                <p className="text-xs text-muted font-medium">
                    {t("renterPortalDesc")}
                </p>
            </div>

            <RenewalBanner />

            {/* Next Payment Card */}
            <Link href="/dashboard/renter-portal/payments">
                <div className="bg-surface rounded-xl border border-border hover:shadow-md transition-all duration-200 mb-6 cursor-pointer overflow-hidden">
                    {nextPayment ? (
                        <div className="p-5">
                            <div className="flex items-center justify-between mb-4">
                                <div className="flex items-center gap-3">
                                    <div className="w-10 h-10 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                                        <CreditCard size={18} />
                                    </div>
                                    <div>
                                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">Next Payment</p>
                                        <p className="text-sm font-bold text-foreground tabular-nums">{formatCurrencyCompact(nextPayment.amount)}</p>
                                    </div>
                                </div>
                                <span className={cn(
                                    "inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[10px] font-semibold",
                                    nextPayment.isOverdue
                                        ? "bg-error/10 text-error border border-error/20"
                                        : nextPayment.daysUntilDue <= 7
                                            ? "bg-warning/10 text-warning border border-warning/20"
                                            : "bg-success/10 text-success border border-success/20"
                                )}>
                                    <Clock size={11} />
                                    {nextPayment.isOverdue
                                        ? `${Math.abs(nextPayment.daysUntilDue)} days overdue`
                                        : nextPayment.daysUntilDue === 0
                                            ? "Due today"
                                            : `Due in ${nextPayment.daysUntilDue} days`
                                    }
                                </span>
                            </div>
                            <div className="flex items-center justify-between text-xs text-muted">
                                <span>Due {new Date(nextPayment.dueDate).toLocaleDateString()}</span>
                                <span className="text-primary font-semibold">View all payments &rarr;</span>
                            </div>
                        </div>
                    ) : (
                        <div className="p-5 flex items-center gap-4">
                            <div className="w-10 h-10 bg-success/10 rounded-xl flex items-center justify-center text-success border border-success/20">
                                <CheckCircle size={18} />
                            </div>
                            <div>
                                <p className="text-sm font-bold text-foreground">All payments up to date</p>
                                <p className="text-[10px] text-muted">No pending payments at this time.</p>
                            </div>
                        </div>
                    )}
                </div>
            </Link>

            <div className="space-y-6">
                {leases.map(lease => (
                    <div key={lease.id} className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200">
                        <div className="flex justify-between items-start mb-6">
                            <div className="flex items-center gap-4">
                                <div className="w-12 h-12 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                                    <FileText size={22} />
                                </div>
                                <div>
                                    <h3 className="text-sm font-bold text-foreground tracking-tight">
                                        {t("unit")} {lease.unitIdentifier}
                                    </h3>
                                    <p className="text-[10px] font-bold text-muted">{lease.propertyName}</p>
                                </div>
                            </div>
                            <span className={cn("inline-flex items-center px-3 py-1.5 rounded-full text-[9px] font-bold uppercase tracking-widest border", getStatusColor(lease.status))}>
                                {lease.status.replace('_', ' ')}
                            </span>
                        </div>

                        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
                            <div className="bg-input/70 rounded-xl p-3 border border-border">
                                <div className="flex items-center gap-2 mb-1">
                                    <DollarSign size={12} className="text-muted" />
                                    <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">Rent</span>
                                </div>
                                <p className="text-sm font-bold text-foreground tabular-nums">{formatCurrencyCompact(lease.rentAmount)}</p>
                            </div>
                            <div className="bg-input/70 rounded-xl p-3 border border-border">
                                <div className="flex items-center gap-2 mb-1">
                                    <Calendar size={12} className="text-muted" />
                                    <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">Start</span>
                                </div>
                                <p className="text-xs font-bold text-foreground">{new Date(lease.startDate).toLocaleDateString()}</p>
                            </div>
                            <div className="bg-input/70 rounded-xl p-3 border border-border">
                                <div className="flex items-center gap-2 mb-1">
                                    <Calendar size={12} className="text-muted" />
                                    <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">End</span>
                                </div>
                                <p className="text-xs font-bold text-foreground">{new Date(lease.endDate).toLocaleDateString()}</p>
                            </div>
                            {lease.ejariNumber && (
                                <div className="bg-input/70 rounded-xl p-3 border border-border">
                                    <div className="flex items-center gap-2 mb-1">
                                        <Home size={12} className="text-muted" />
                                        <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">Ejari</span>
                                    </div>
                                    <p className="text-xs font-bold text-foreground">{lease.ejariNumber}</p>
                                </div>
                            )}
                        </div>

                        {lease.status === 'PENDING_SIGNATURE' && paymentsByLease[lease.id]?.length > 0 && (() => {
                            const plan = paymentsByLease[lease.id];
                            const isExpanded = expandedPlanLeaseId === lease.id;
                            const lastAmount = plan[plan.length - 1].amount;
                            const firstAmount = plan[0].amount;
                            const hasResidualLast = plan.length > 1 && lastAmount > firstAmount;
                            return (
                                <div className="border-t border-border pt-4 mb-4">
                                    <button
                                        type="button"
                                        onClick={() => setExpandedPlanLeaseId(isExpanded ? null : lease.id)}
                                        className="w-full flex items-center justify-between text-xs font-bold text-foreground hover:text-primary transition-colors cursor-pointer"
                                    >
                                        <span className="flex items-center gap-2">
                                            <CreditCard size={14} className="text-primary" />
                                            {t("paymentPlan")} ({t("paymentPlanCheques", { count: plan.length })})
                                        </span>
                                        <ChevronDown size={14} className={cn("transition-transform", isExpanded && "rotate-180")} />
                                    </button>
                                    {isExpanded && (
                                        <div className="mt-3 bg-input/40 rounded-xl border border-border overflow-hidden">
                                            <table className="w-full text-xs">
                                                <thead className="bg-input/70">
                                                    <tr className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                                                        <th className="px-3 py-2 text-start">#</th>
                                                        <th className="px-3 py-2 text-start">{t("paymentPlanDueDate")}</th>
                                                        <th className="px-3 py-2 text-end">{t("paymentPlanAmount")}</th>
                                                        <th className="px-3 py-2 text-start">{t("paymentPlanMethod")}</th>
                                                    </tr>
                                                </thead>
                                                <tbody>
                                                    {plan.map((p, idx) => {
                                                        const isLast = idx === plan.length - 1;
                                                        return (
                                                            <tr key={p.id} className={cn("border-t border-border", isLast && hasResidualLast && "bg-primary/5 font-semibold")}>
                                                                <td className="px-3 py-2 tabular-nums">{p.installmentNumber}</td>
                                                                <td className="px-3 py-2 tabular-nums">{new Date(p.dueDate).toLocaleDateString()}</td>
                                                                <td className="px-3 py-2 text-end tabular-nums">{formatCurrencyCompact(p.amount)}</td>
                                                                <td className="px-3 py-2 text-muted">{p.paymentMethod || 'CHEQUE'}</td>
                                                            </tr>
                                                        );
                                                    })}
                                                </tbody>
                                            </table>
                                            {hasResidualLast && (
                                                <p className="px-3 py-2 text-[10px] text-muted border-t border-border bg-input/30">
                                                    {t("paymentPlanDepositNote", { deposit: formatCurrencyCompact(lease.depositAmount) })}
                                                </p>
                                            )}
                                        </div>
                                    )}
                                </div>
                            );
                        })()}

                        <div className="flex gap-3 border-t border-border pt-4">
                            {lease.status === 'DRAFT' && (
                                <div className="flex items-center gap-2 text-xs text-muted font-medium">
                                    <Clock size={14} />
                                    {t("awaitingContract")}
                                </div>
                            )}
                            {lease.status === 'PENDING_SIGNATURE' && (
                                <>
                                    <button
                                        onClick={() => handleDownloadContract(lease.id)}
                                        className="flex items-center gap-2 bg-info/10 text-info hover:bg-info/20 px-4 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-info/30"
                                    >
                                        <Download size={14} />
                                        {t("downloadContract")}
                                    </button>
                                    <button
                                        onClick={() => handleAccept(lease.id)}
                                        className="flex items-center gap-2 bg-success/10 text-success hover:bg-success/20 px-4 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-success/30"
                                    >
                                        <CheckCircle size={14} />
                                        {t("acceptLease")}
                                    </button>
                                    <button
                                        onClick={() => handleReject(lease.id)}
                                        className="flex items-center gap-2 bg-error/10 text-error hover:bg-error/20 px-4 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-error/30"
                                    >
                                        <XCircle size={14} />
                                        {t("rejectLease")}
                                    </button>
                                </>
                            )}
                            {lease.status === 'ACTIVE' && lease.hasContract && (
                                <button
                                    onClick={() => handleDownloadContract(lease.id)}
                                    className="flex items-center gap-2 bg-info/10 text-info hover:bg-info/20 px-4 py-2.5 rounded-xl text-xs font-bold transition-colors"
                                >
                                    <Download size={14} />
                                    {t("downloadContract")}
                                </button>
                            )}
                        </div>
                    </div>
                ))}
            </div>

            {leases.length === 0 && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <AlertCircle size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-2 uppercase tracking-widest">
                        {t("noLeasesFound")}
                    </p>
                    <p className="text-xs text-muted">
                        Your leases will appear here once your landlord creates them.
                    </p>
                </div>
            )}

            {/* ── Meetings Section ─────────────────────────────────────── */}
            <div className="mt-10">
                <div className="flex items-center justify-between mb-4">
                    <div className="flex items-center gap-3">
                        <div className="w-8 h-8 bg-primary/10 rounded-lg flex items-center justify-center text-primary border border-primary/20">
                            <CalendarDays size={16} />
                        </div>
                        <div>
                            <h2 className="text-sm font-bold text-foreground tracking-tight">My Meetings</h2>
                            <p className="text-[10px] text-muted font-medium">View and manage your scheduled meetings</p>
                        </div>
                    </div>
                    <button
                        onClick={() => setShowCreateMeeting(true)}
                        className="flex items-center gap-2 bg-primary text-white px-4 py-2 rounded-xl text-xs font-bold hover:bg-primary/90 transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
                    >
                        <Plus size={14} />
                        Request Meeting
                    </button>
                </div>

                {meetingsLoading ? (
                    <div className="bg-surface rounded-xl border border-border overflow-hidden">
                        {[1, 2, 3].map(i => (
                            <div key={i} className="flex items-center gap-4 p-4 border-b border-border last:border-b-0 animate-pulse">
                                <div className="h-4 w-32 bg-input rounded" />
                                <div className="h-4 w-24 bg-input rounded" />
                                <div className="h-5 w-20 bg-input rounded-full ml-auto" />
                            </div>
                        ))}
                    </div>
                ) : meetings.length === 0 ? (
                    <div className="text-center py-12 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                        <div className="w-12 h-12 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-4">
                            <CalendarDays size={24} />
                        </div>
                        <p className="text-sm font-bold text-muted mb-1 uppercase tracking-widest">No meetings yet</p>
                        <p className="text-xs text-muted mb-4">Request a meeting with your property manager.</p>
                        <button
                            onClick={() => setShowCreateMeeting(true)}
                            className="flex items-center gap-2 bg-primary/10 text-primary px-4 py-2 rounded-xl text-xs font-bold hover:bg-primary/20 transition-all duration-200 cursor-pointer"
                        >
                            <Plus size={14} />
                            Request Meeting
                        </button>
                    </div>
                ) : (
                    <>
                        <div className="bg-surface rounded-xl border border-border overflow-hidden">
                            {/* Table header */}
                            <div className="grid grid-cols-12 gap-3 px-4 py-2.5 bg-input/40 border-b border-border">
                                <div className="col-span-3 text-[9px] font-semibold text-muted uppercase tracking-[0.12em]">Date & Time</div>
                                <div className="col-span-3 text-[9px] font-semibold text-muted uppercase tracking-[0.12em]">Purpose</div>
                                <div className="col-span-3 text-[9px] font-semibold text-muted uppercase tracking-[0.12em]">Property</div>
                                <div className="col-span-2 text-[9px] font-semibold text-muted uppercase tracking-[0.12em]">Status</div>
                                <div className="col-span-1" />
                            </div>
                            {/* Table rows */}
                            {meetings.map((meeting, idx) => (
                                    <div
                                        key={meeting.id}
                                        className={cn(
                                            "grid grid-cols-12 gap-3 px-4 py-3 items-center hover:bg-input/30 transition-colors",
                                            idx !== 0 && "border-t border-border"
                                        )}
                                    >
                                        <div className="col-span-3">
                                            <p className="text-xs font-bold text-foreground tabular-nums">
                                                {new Date(meeting.slotStart).toLocaleDateString()}
                                            </p>
                                            <p className="text-[10px] text-muted">
                                                {new Date(meeting.slotStart).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                                                {" – "}
                                                {new Date(meeting.slotEnd).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                                            </p>
                                        </div>
                                        <div className="col-span-3">
                                            <p className="text-xs font-semibold text-foreground truncate">
                                                {meeting.title || meeting.purpose.replace(/_/g, " ")}
                                            </p>
                                            <p className="text-[10px] text-muted">{meeting.type.replace(/_/g, " ")}</p>
                                        </div>
                                        <div className="col-span-3">
                                            <p className="text-xs text-foreground truncate">{meeting.propertyName || "—"}</p>
                                            {meeting.unitNumber && (
                                                <p className="text-[10px] text-muted">Unit {meeting.unitNumber}</p>
                                            )}
                                        </div>
                                        <div className="col-span-2">
                                            <span className={cn(
                                                "inline-flex items-center px-2 py-1 rounded-full text-[9px] font-bold uppercase tracking-widest",
                                                MEETING_STATUS_COLORS[meeting.status] ?? "bg-input text-muted border border-border"
                                            )}>
                                                {meeting.status.replace(/_/g, " ")}
                                            </span>
                                        </div>
                                        <div className="col-span-1 flex justify-end">
                                            <Link
                                                href={`/dashboard/meetings/${meeting.id}`}
                                                className="w-7 h-7 flex items-center justify-center rounded-lg bg-input/60 hover:bg-input text-muted hover:text-foreground transition-colors"
                                            >
                                                <Eye size={13} />
                                            </Link>
                                        </div>
                                    </div>
                                ))}
                        </div>

                        {/* Pagination */}
                        {meetingsTotalPages > 1 && (
                            <div className="flex items-center justify-between mt-3 px-1">
                                <p className="text-[10px] text-muted">
                                    Page {meetingsPage} of {meetingsTotalPages}
                                </p>
                                <div className="flex items-center gap-1">
                                    <button
                                        onClick={() => setMeetingsPage(p => Math.max(1, p - 1))}
                                        disabled={meetingsPage === 1}
                                        className="w-7 h-7 flex items-center justify-center rounded-lg bg-surface border border-border text-muted hover:text-foreground disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                                    >
                                        <ChevronLeft size={13} />
                                    </button>
                                    <span className="text-[10px] font-semibold text-foreground px-2">
                                        {meetingsPage} / {meetingsTotalPages}
                                    </span>
                                    <button
                                        onClick={() => setMeetingsPage(p => Math.min(meetingsTotalPages, p + 1))}
                                        disabled={meetingsPage >= meetingsTotalPages}
                                        className="w-7 h-7 flex items-center justify-center rounded-lg bg-surface border border-border text-muted hover:text-foreground disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                                    >
                                        <ChevronRight size={13} />
                                    </button>
                                </div>
                            </div>
                        )}
                    </>
                )}
            </div>

            {/* Create Meeting Modal */}
            {showCreateMeeting && (
                <CreateMeetingModal
                    isOpen={showCreateMeeting}
                    onClose={() => setShowCreateMeeting(false)}
                    onSuccess={() => {
                        setShowCreateMeeting(false);
                        fetchMyMeetings();
                    }}
                    session={session}
                />
            )}

            <ConfirmDialog
                isOpen={confirmDialog !== null}
                onClose={() => setConfirmDialog(null)}
                onConfirm={confirmDialog?.onConfirm || (() => {})}
                title={confirmDialog?.title || ""}
                description={confirmDialog?.description}
                confirmText={confirmDialog?.confirmText || "Confirm"}
                isDestructive={confirmDialog?.isDestructive || false}
            />
        </div>
    );
}
