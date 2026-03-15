"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { FileText, Calendar, DollarSign, Home, CheckCircle, XCircle, Download, Clock, AlertCircle, CreditCard } from "lucide-react";
import { useSession } from "next-auth/react";
import { cn } from "@/lib/utils";
import { formatCurrencyCompact } from "@/lib/format";
import { Link } from "@/i18n/routing";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";

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

export default function RenterPortalPage() {
    const t = useTranslations("MasterData");
    const tPayments = useTranslations("OnlinePayments");
    const [leases, setLeases] = useState<Lease[]>([]);
    const [loading, setLoading] = useState(true);
    const [nextPayment, setNextPayment] = useState<{ dueDate: string; amount: number; daysUntilDue: number; isOverdue: boolean } | null>(null);
    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        confirmText: string;
        isDestructive: boolean;
        onConfirm: () => void;
    } | null>(null);
    const { data: session } = useSession();

    useEffect(() => {
        fetchMyLeases();
        fetchPendingPayments();
    }, []);

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
