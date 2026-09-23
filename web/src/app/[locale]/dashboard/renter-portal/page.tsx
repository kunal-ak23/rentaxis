"use client";

import { useState, useEffect, useCallback } from "react";
import { useLocale, useTranslations } from "next-intl";
import { FileText, Calendar, DollarSign, Home, CheckCircle, XCircle, Download, Clock, AlertCircle, CreditCard, CalendarDays, Plus, Eye, ChevronLeft, ChevronRight, ChevronDown, Dumbbell } from "lucide-react";
import { useSession } from "next-auth/react";
import { cn } from "@/lib/utils";
import { formatCurrencyCompact } from "@/lib/format";
import { Link } from "@/i18n/routing";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import CreateMeetingModal from "@/app/[locale]/dashboard/meetings/CreateMeetingModal";
import RenewalBanner from "@/components/renewals/RenewalBanner";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import type { RenterCheque } from "@/lib/api/leasing";
import { fmtIsoDate } from "@/components/leases/leaseMath";

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

// The `Meetings` namespace already carries translated labels for each enum
// value; these maps only route the wire value to its key.
const MEETING_TYPE_KEYS: Record<string, string> = {
    OFFICE_VISIT: "officeVisit",
    PROPERTY_VISIT: "propertyVisit",
};
const MEETING_PURPOSE_KEYS: Record<string, string> = {
    CHEQUE_REPLACEMENT: "chequeReplacement",
    LEASE_RENEWAL: "leaseRenewal",
    PROPERTY_VIEWING: "propertyViewing",
    OTHER: "other",
};

export default function RenterPortalPage() {
    const t = useTranslations("MasterData");
    const tCommon = useTranslations("Common");
    const tPayments = useTranslations("OnlinePayments");
    const tFacilities = useTranslations("Facilities");
    const tLeasing = useTranslations("Leasing");
    const tHome = useTranslations("RenterHome");
    const tMeetings = useTranslations("Meetings");
    const locale = useLocale();
    const [leases, setLeases] = useState<Lease[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [nextPayment, setNextPayment] = useState<{ dueDate: string; amount: number; daysUntilDue: number; isOverdue: boolean } | null>(null);
    // `/online-payments/my-payments` returns RenterChequeDTO rows. The local
    // shape this used to declare carried a `paymentMethod` the DTO has never
    // had, which is how the Method column came to render a hardcoded literal.
    const [paymentsByLease, setPaymentsByLease] = useState<Record<string, RenterCheque[]>>({});
    const [expandedPlanLeaseId, setExpandedPlanLeaseId] = useState<string | null>(null);
    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        confirmText: string;
        isDestructive: boolean;
        onConfirm: () => void;
    } | null>(null);
    const { data: session } = useSession();
    // The portal is the signed-in renter's own tenancy. Anyone else — a
    // SUPER_ADMIN following the sidebar — has nothing here, and firing the
    // renter-only endpoints for them painted a red "couldn't load this" banner
    // over the page: a transport error for what is really "you have no
    // tenancy", offering a retry that could never succeed.
    const isRenter = (session?.user?.role as string | undefined) === "RENTER";

    // Meetings state
    const [meetings, setMeetings] = useState<Meeting[]>([]);
    const [meetingsLoading, setMeetingsLoading] = useState(true);
    const [meetingsPage, setMeetingsPage] = useState(1);
    const [meetingsTotalPages, setMeetingsTotalPages] = useState(1);
    const [showCreateMeeting, setShowCreateMeeting] = useState(false);

    useEffect(() => {
        if (!isRenter) return;
        fetchMyLeases();
        fetchPendingPayments();
    }, [isRenter]);

    const fetchMyMeetings = useCallback(async () => {
        if (!isRenter) { setMeetingsLoading(false); return; }
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
            } else {
                // A non-2xx used to leave the state at its initial empty
                // value, so a failed request rendered as "nothing here".
                setLoadError(tCommon("loadFailed"));
            }
        } catch (err) {
            console.error(err);
        } finally {
            setMeetingsLoading(false);
        }
    }, [meetingsPage, isRenter]);

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

                // RenterChequeDTO's own `due` flag (accounting-v2) replaces the v1
                // PENDING/OVERDUE status strings, which this endpoint no longer
                // returns — a cheque row is REGISTERED, DEPOSITED, CLEARED, etc.
                const pending = payments
                    .filter((p: any) => p.due)
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
                        amount: next.payable ?? next.amount,
                        daysUntilDue: diffDays,
                        isOverdue: diffDays < 0,
                    });
                }
            } else {
                // A non-2xx used to leave the state at its initial empty
                // value, so a failed request rendered as "nothing here".
                setLoadError(tCommon("loadFailed"));
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleAccept = (id: string) => {
        setConfirmDialog({
            title: tHome("acceptTitle"),
            description: tHome("acceptDescription"),
            confirmText: tHome("acceptTitle"),
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
            title: tHome("rejectTitle"),
            description: tHome("rejectDescription"),
            confirmText: tHome("rejectTitle"),
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
            } else {
                // A non-2xx used to leave the state at its initial empty
                // value, so a failed request rendered as "nothing here".
                setLoadError(tCommon("loadFailed"));
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

    const timeLocale = locale === "ar" ? "ar-AE" : "en-GB";
    const userName = session?.user?.name || tHome("renterFallback");

    // Before the loading skeleton: a non-renter is not waiting for anything, so
    // showing them a spinner for data that will never arrive is its own bug.
    if (session && !isRenter) {
        return (
            <div className="p-8 max-w-5xl mx-auto">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t("renterPortal")}</h1>
                <p className="text-sm text-muted">{t("renterPortalNoTenancy")}</p>
            </div>
        );
    }

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

    const reload = () => {
        setLoadError(null);
        fetchMyMeetings();
        fetchMyLeases();
        fetchPendingPayments();
    };

    return (
        <div className="p-8 max-w-5xl mx-auto">
            {loadError && <LoadErrorBanner message={loadError} onRetry={reload} />}
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
                                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">{tHome("nextPayment")}</p>
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
                                        ? tHome("daysOverdue", { count: Math.abs(nextPayment.daysUntilDue) })
                                        : nextPayment.daysUntilDue === 0
                                            ? tHome("dueToday")
                                            : tHome("dueInDays", { count: nextPayment.daysUntilDue })
                                    }
                                </span>
                            </div>
                            <div className="flex items-center justify-between text-xs text-muted">
                                <span>{tHome("dueOn", { date: fmtIsoDate(nextPayment.dueDate, locale) })}</span>
                                <span className="text-primary font-semibold inline-flex items-center gap-1">
                                    {tHome("viewAllPayments")}
                                    <ChevronRight size={12} className="rtl:rotate-180" />
                                </span>
                            </div>
                        </div>
                    ) : (
                        <div className="p-5 flex items-center gap-4">
                            <div className="w-10 h-10 bg-success/10 rounded-xl flex items-center justify-center text-success border border-success/20">
                                <CheckCircle size={18} />
                            </div>
                            <div>
                                <p className="text-sm font-bold text-foreground">{tHome("allPaymentsUpToDate")}</p>
                                <p className="text-[10px] text-muted">{tHome("noPendingPayments")}</p>
                            </div>
                        </div>
                    )}
                </div>
            </Link>

            {/* Facilities & Parking entry card */}
            <Link href="/dashboard/renter-portal/facilities">
                <div className="bg-surface rounded-xl border border-border hover:shadow-md transition-all duration-200 mb-6 cursor-pointer p-5 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                            <Dumbbell size={18} />
                        </div>
                        <div>
                            <p className="text-sm font-bold text-foreground">{tFacilities("entryCardTitle")}</p>
                            <p className="text-[10px] text-muted">{tFacilities("entryCardDesc")}</p>
                        </div>
                    </div>
                    <ChevronRight size={16} className="text-primary rtl:rotate-180" />
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
                                {tLeasing(`leaseStatus.${lease.status}`)}
                            </span>
                        </div>

                        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
                            <div className="bg-input/70 rounded-xl p-3 border border-border">
                                <div className="flex items-center gap-2 mb-1">
                                    <DollarSign size={12} className="text-muted" />
                                    <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">{tHome("rent")}</span>
                                </div>
                                <p className="text-sm font-bold text-foreground tabular-nums">{formatCurrencyCompact(lease.rentAmount)}</p>
                            </div>
                            <div className="bg-input/70 rounded-xl p-3 border border-border">
                                <div className="flex items-center gap-2 mb-1">
                                    <Calendar size={12} className="text-muted" />
                                    <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">{tHome("start")}</span>
                                </div>
                                <p className="text-xs font-bold text-foreground">{fmtIsoDate(lease.startDate, locale)}</p>
                            </div>
                            <div className="bg-input/70 rounded-xl p-3 border border-border">
                                <div className="flex items-center gap-2 mb-1">
                                    <Calendar size={12} className="text-muted" />
                                    <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">{tHome("end")}</span>
                                </div>
                                <p className="text-xs font-bold text-foreground">{fmtIsoDate(lease.endDate, locale)}</p>
                            </div>
                            {lease.ejariNumber && (
                                <div className="bg-input/70 rounded-xl p-3 border border-border">
                                    <div className="flex items-center gap-2 mb-1">
                                        <Home size={12} className="text-muted" />
                                        <span className="text-[9px] font-semibold text-muted uppercase tracking-[0.15em]">{tHome("ejari")}</span>
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
                                                                <td className="px-3 py-2 tabular-nums">{fmtIsoDate(p.dueDate, locale)}</td>
                                                                <td className="px-3 py-2 text-end tabular-nums">{formatCurrencyCompact(p.amount)}</td>
                                                                {/*
                                                                  * These rows are RenterChequeDTO, which has
                                                                  * no `paymentMethod` — the column was a
                                                                  * hardcoded, untranslated "CHEQUE" on every
                                                                  * row, bank transfers and online ones
                                                                  * included. `mode` is the field that exists.
                                                                  */}
                                                                <td className="px-3 py-2 text-muted">
                                                                    {p.mode ? tLeasing(`mode.${p.mode}`) : "—"}
                                                                </td>
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
                        {tHome("noLeasesHint")}
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
                            <h2 className="text-sm font-bold text-foreground tracking-tight">{tHome("myMeetings")}</h2>
                            <p className="text-[10px] text-muted font-medium">{tHome("myMeetingsDesc")}</p>
                        </div>
                    </div>
                    <button
                        onClick={() => setShowCreateMeeting(true)}
                        className="flex items-center gap-2 bg-primary text-white px-4 py-2 rounded-xl text-xs font-bold hover:bg-primary/90 transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
                    >
                        <Plus size={14} />
                        {tHome("requestMeeting")}
                    </button>
                </div>

                {meetingsLoading ? (
                    <div className="bg-surface rounded-xl border border-border overflow-hidden">
                        {[1, 2, 3].map(i => (
                            <div key={i} className="flex items-center gap-4 p-4 border-b border-border last:border-b-0 animate-pulse">
                                <div className="h-4 w-32 bg-input rounded" />
                                <div className="h-4 w-24 bg-input rounded" />
                                <div className="h-5 w-20 bg-input rounded-full ms-auto" />
                            </div>
                        ))}
                    </div>
                ) : meetings.length === 0 ? (
                    <div className="text-center py-12 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                        <div className="w-12 h-12 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-4">
                            <CalendarDays size={24} />
                        </div>
                        <p className="text-sm font-bold text-muted mb-1 uppercase tracking-widest">{tHome("noMeetingsYet")}</p>
                        <p className="text-xs text-muted mb-4">{tHome("noMeetingsHint")}</p>
                        <button
                            onClick={() => setShowCreateMeeting(true)}
                            className="flex items-center gap-2 bg-primary/10 text-primary px-4 py-2 rounded-xl text-xs font-bold hover:bg-primary/20 transition-all duration-200 cursor-pointer"
                        >
                            <Plus size={14} />
                            {tHome("requestMeeting")}
                        </button>
                    </div>
                ) : (
                    <>
                        <div className="bg-surface rounded-xl border border-border overflow-hidden">
                            {/* Table header */}
                            <div className="grid grid-cols-12 gap-3 px-4 py-2.5 bg-input/40 border-b border-border">
                                <div className="col-span-3 text-[9px] font-semibold text-muted uppercase tracking-[0.12em]">{tHome("colDateTime")}</div>
                                <div className="col-span-3 text-[9px] font-semibold text-muted uppercase tracking-[0.12em]">{tHome("colPurpose")}</div>
                                <div className="col-span-3 text-[9px] font-semibold text-muted uppercase tracking-[0.12em]">{tHome("colProperty")}</div>
                                <div className="col-span-2 text-[9px] font-semibold text-muted uppercase tracking-[0.12em]">{tHome("colStatus")}</div>
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
                                                {fmtIsoDate(meeting.slotStart, locale)}
                                            </p>
                                            <p className="text-[10px] text-muted">
                                                {new Date(meeting.slotStart).toLocaleTimeString(timeLocale, { hour: "2-digit", minute: "2-digit" })}
                                                {" – "}
                                                {new Date(meeting.slotEnd).toLocaleTimeString(timeLocale, { hour: "2-digit", minute: "2-digit" })}
                                            </p>
                                        </div>
                                        <div className="col-span-3">
                                            <p className="text-xs font-semibold text-foreground truncate">
                                                {meeting.title || (MEETING_PURPOSE_KEYS[meeting.purpose] ? tMeetings(MEETING_PURPOSE_KEYS[meeting.purpose]) : meeting.purpose)}
                                            </p>
                                            <p className="text-[10px] text-muted">{MEETING_TYPE_KEYS[meeting.type] ? tMeetings(MEETING_TYPE_KEYS[meeting.type]) : meeting.type}</p>
                                        </div>
                                        <div className="col-span-3">
                                            <p className="text-xs text-foreground truncate">{meeting.propertyName || "—"}</p>
                                            {meeting.unitNumber && (
                                                <p className="text-[10px] text-muted">{tHome("unitLabel", { unit: meeting.unitNumber })}</p>
                                            )}
                                        </div>
                                        <div className="col-span-2">
                                            <span className={cn(
                                                "inline-flex items-center px-2 py-1 rounded-full text-[9px] font-bold uppercase tracking-widest",
                                                MEETING_STATUS_COLORS[meeting.status] ?? "bg-input text-muted border border-border"
                                            )}>
                                                {MEETING_STATUS_COLORS[meeting.status] ? tMeetings(`status.${meeting.status}`) : meeting.status}
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
                                    {tHome("pageOf", { page: meetingsPage, total: meetingsTotalPages })}
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
                confirmText={confirmDialog?.confirmText || tHome("confirm")}
                isDestructive={confirmDialog?.isDestructive || false}
            />
        </div>
    );
}
