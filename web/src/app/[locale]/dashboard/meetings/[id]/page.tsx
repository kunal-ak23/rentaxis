"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams } from "next/navigation";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { hasPermission, hasRole, type UserRole } from "@/lib/rbac";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { formatNumber } from "@/lib/format";
import {
    ArrowLeft, Loader2, Calendar, Clock, User, Building2, Home,
    FileText, CheckCircle, X, AlertTriangle, Hash,
} from "lucide-react";

// ── Types ──────────────────────────────────────────────────────────────────

type MeetingDetail = {
    detailType: string;
    paymentScheduleIds?: string[];
    proposedStartDate?: string;
    proposedEndDate?: string;
    proposedRentAmount?: number;
    notes?: string;
};

type Meeting = {
    id: string;
    type: string;
    status: string;
    purpose: string;
    title: string | null;
    notes: string | null;
    slotStart: string;
    slotEnd: string;
    hostUserId: string | null;
    hostName: string | null;
    requesterUserId: string | null;
    requesterName: string | null;
    leaseId: string | null;
    leaseLabel: string | null;
    propertyId: string | null;
    propertyName: string | null;
    unitId: string | null;
    unitNumber: string | null;
    details: MeetingDetail | null;
    createdAt: string;
    updatedAt: string;
};

// ── Badge Maps ─────────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
    REQUESTED: "bg-warning/10 text-warning border-warning/20",
    APPROVED: "bg-info/10 text-info border-info/20",
    COMPLETED: "bg-success/10 text-success border-success/20",
    CANCELLED: "bg-input text-muted border-border",
    NO_SHOW: "bg-error/10 text-error border-error/20",
};


// ── Page Component ─────────────────────────────────────────────────────────

export default function MeetingDetailPage() {
    const params = useParams();
    const meetingId = params.id as string;
    const { data: session } = useSession();
    const t = useTranslations("Meetings");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const dateLocale = locale === "ar" ? "ar-AE" : "en-GB";
    // Enum codes to labels; an unknown code falls back to its readable form.
    const label = (group: "status" | "typeLabel" | "purposeLabel", code: string) =>
        t.has(`${group}.${code}`) ? t(`${group}.${code}`) : code.replace(/_/g, " ");
    const userRole = session?.user?.role as UserRole | undefined;
    const userId = session?.user?.id as string | undefined;

    const [meeting, setMeeting] = useState<Meeting | null>(null);

    const [loadError, setLoadError] = useState<string | null>(null);
    const [loading, setLoading] = useState(true);
    const [actionLoading, setActionLoading] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);

    // ── Fetch ────────────────────────────────────────────────────────────

    const fetchMeeting = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/meetings/${meetingId}`);
            if (res.ok) {
                setMeeting(await res.json());
                setLoadError(null);
            } else if (res.status !== 404) {
                // Only a 404 means the meeting is genuinely gone. Anything else
                // used to fall through to the same "Meeting not found." screen,
                // so a 500 or an expired session read as a deleted meeting.
                setLoadError(tCommon("loadFailedMeetings"));
            }
        } catch {
            setLoadError(tCommon("loadFailedMeetings"));
        }
    }, [meetingId, tCommon]);

    useEffect(() => {
        fetchMeeting().finally(() => setLoading(false));
    }, [fetchMeeting]);

    // ── Actions ──────────────────────────────────────────────────────────

    const performAction = async (action: string) => {
        setActionLoading(action);
        setActionError(null);
        try {
            const res = await fetch(`/api/proxy/v1/meetings/${meetingId}/${action}`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
            });
            // Surface backend failures (e.g. "Cannot approve meeting in
            // status: ...") instead of silently doing nothing.
            await throwIfNotOk(res);
            await fetchMeeting();
        } catch (err) {
            setActionError(err instanceof ApiError ? err.message : t("actionFailed"));
        } finally {
            setActionLoading(null);
        }
    };

    // ── Loading / Not Found ──────────────────────────────────────────────

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    if (!meeting) {
        return (
            <div className="text-center py-24">
                {loadError
                    ? <LoadErrorBanner message={loadError} onRetry={() => { setLoadError(null); fetchMeeting(); }} />
                    : <p className="text-sm text-muted">{t("notFound")}</p>}
                <Link href="/dashboard/meetings" className="text-xs text-primary font-semibold mt-2 inline-block">
                    {t("backToMeetings")}
                </Link>
            </div>
        );
    }

    // ── Role helpers ─────────────────────────────────────────────────────

    const isRenter = userRole === "RENTER";
    const isPMOrAdmin = hasPermission(userRole, "canManageMeetings");
    const isRequester = meeting.requesterUserId === userId;

    // ── Date / Time helpers ──────────────────────────────────────────────

    const slotStart = new Date(meeting.slotStart);
    const slotEnd = new Date(meeting.slotEnd);
    const durationMs = slotEnd.getTime() - slotStart.getTime();
    const durationMin = Math.round(durationMs / 60000);

    const formattedDate = slotStart.toLocaleDateString(dateLocale, {
        weekday: "long", year: "numeric", month: "long", day: "numeric",
    });
    const formattedTime = `${slotStart.toLocaleTimeString(dateLocale, { hour: "2-digit", minute: "2-digit" })} – ${slotEnd.toLocaleTimeString(dateLocale, { hour: "2-digit", minute: "2-digit" })}`;

    // ── Action button visibility ─────────────────────────────────────────

    const canApprove = isPMOrAdmin && meeting.status === "REQUESTED";
    const canComplete = isPMOrAdmin && meeting.status === "APPROVED";
    const canNoShow = isPMOrAdmin && meeting.status === "APPROVED";
    const canCancel =
        (isPMOrAdmin && (meeting.status === "REQUESTED" || meeting.status === "APPROVED")) ||
        ((isRenter || isRequester) && (meeting.status === "REQUESTED" || meeting.status === "APPROVED"));

    const hasActions = canApprove || canComplete || canNoShow || canCancel;

    // ── Render ───────────────────────────────────────────────────────────

    return (
        <div className="max-w-3xl mx-auto space-y-5">

            {/* Back button */}
            <Link
                href="/dashboard/meetings"
                className="inline-flex items-center gap-1.5 text-xs text-muted hover:text-foreground transition-colors"
            >
                <ArrowLeft size={14} className="rtl:rotate-180" />
                {t("backToMeetings")}
            </Link>

            {/* Header */}
            <div className="bg-surface rounded-xl border border-border p-5">
                <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="flex-1 min-w-0">
                        <h1 className="text-base font-bold text-foreground truncate">
                            {meeting.title || label("purposeLabel", meeting.purpose)}
                        </h1>
                        <p className="text-xs text-muted mt-0.5">
                            {meeting.title && meeting.title !== meeting.purpose ? label("purposeLabel", meeting.purpose) : ""}
                        </p>
                    </div>
                    <div className="flex items-center gap-2 flex-shrink-0">
                        {/* Type badge */}
                        <span className="px-2 py-0.5 rounded-md text-[10px] font-semibold bg-primary/10 text-primary border border-primary/20">
                            {label("typeLabel", meeting.type)}
                        </span>
                        {/* Status badge */}
                        <span className={cn(
                            "px-2 py-0.5 rounded-md text-[10px] font-semibold border",
                            STATUS_COLORS[meeting.status] ?? "bg-input text-muted border-border",
                        )}>
                            {label("status", meeting.status)}
                        </span>
                    </div>
                </div>
            </div>

            {/* Action buttons */}
            {hasActions && (
                <div className="flex flex-wrap items-center gap-2">
                    {canApprove && (
                        <button
                            onClick={() => performAction("approve")}
                            disabled={actionLoading !== null}
                            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-info text-white text-xs font-semibold hover:bg-info/90 transition-all disabled:opacity-60 cursor-pointer"
                        >
                            {actionLoading === "approve"
                                ? <Loader2 size={13} className="animate-spin" />
                                : <CheckCircle size={13} />}
                            {t("approve")}
                        </button>
                    )}
                    {canComplete && (
                        <button
                            onClick={() => performAction("complete")}
                            disabled={actionLoading !== null}
                            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-success text-white text-xs font-semibold hover:bg-success/90 transition-all disabled:opacity-60 cursor-pointer"
                        >
                            {actionLoading === "complete"
                                ? <Loader2 size={13} className="animate-spin" />
                                : <CheckCircle size={13} />}
                            {t("complete")}
                        </button>
                    )}
                    {canNoShow && (
                        <button
                            onClick={() => performAction("no-show")}
                            disabled={actionLoading !== null}
                            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-warning text-white text-xs font-semibold hover:bg-warning/90 transition-all disabled:opacity-60 cursor-pointer"
                        >
                            {actionLoading === "no-show"
                                ? <Loader2 size={13} className="animate-spin" />
                                : <AlertTriangle size={13} />}
                            {t("noShow")}
                        </button>
                    )}
                    {canCancel && (
                        <button
                            onClick={() => performAction("cancel")}
                            disabled={actionLoading !== null}
                            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-error text-white text-xs font-semibold hover:bg-error/90 transition-all disabled:opacity-60 cursor-pointer"
                        >
                            {actionLoading === "cancel"
                                ? <Loader2 size={13} className="animate-spin" />
                                : <X size={13} />}
                            {t("cancel")}
                        </button>
                    )}
                </div>
            )}

            {/* Action error */}
            {actionError && (
                <div className="bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg px-3 py-2" role="alert">
                    {actionError}
                </div>
            )}

            {/* Details card */}
            <div className="bg-surface rounded-xl border border-border p-5">
                <h2 className="text-xs font-semibold text-muted uppercase tracking-wider mb-4">{t("meetingDetails")}</h2>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    {/* Date */}
                    <div className="flex items-start gap-2.5">
                        <Calendar size={14} className="text-muted mt-0.5 flex-shrink-0" />
                        <div>
                            <p className="text-[10px] text-muted uppercase tracking-wider">{t("date")}</p>
                            <p className="text-xs font-medium text-foreground">{formattedDate}</p>
                        </div>
                    </div>
                    {/* Time */}
                    <div className="flex items-start gap-2.5">
                        <Clock size={14} className="text-muted mt-0.5 flex-shrink-0" />
                        <div>
                            <p className="text-[10px] text-muted uppercase tracking-wider">{t("time")}</p>
                            <p className="text-xs font-medium text-foreground">{formattedTime}</p>
                            <p className="text-[10px] text-muted">{t("durationMin", { count: durationMin })}</p>
                        </div>
                    </div>
                    {/* Host */}
                    <div className="flex items-start gap-2.5">
                        <User size={14} className="text-muted mt-0.5 flex-shrink-0" />
                        <div>
                            <p className="text-[10px] text-muted uppercase tracking-wider">{t("host")}</p>
                            <p className="text-xs font-medium text-foreground">{meeting.hostName || "—"}</p>
                        </div>
                    </div>
                    {/* Requester */}
                    <div className="flex items-start gap-2.5">
                        <User size={14} className="text-muted mt-0.5 flex-shrink-0" />
                        <div>
                            <p className="text-[10px] text-muted uppercase tracking-wider">{t("requestedBy")}</p>
                            <p className="text-xs font-medium text-foreground">{meeting.requesterName || "—"}</p>
                        </div>
                    </div>
                </div>
            </div>

            {/* Linked entity card */}
            {(meeting.leaseId || meeting.propertyId || meeting.unitId) && (
                <div className="bg-surface rounded-xl border border-border p-5">
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-wider mb-4">{t("linkedTo")}</h2>
                    <div className="space-y-3">
                        {meeting.leaseId && (
                            <div className="flex items-center gap-2.5">
                                <FileText size={14} className="text-muted flex-shrink-0" />
                                <div>
                                    <p className="text-[10px] text-muted uppercase tracking-wider">{t("lease")}</p>
                                    <Link
                                        href={`/dashboard/leases/${meeting.leaseId}`}
                                        className="text-xs font-medium text-primary hover:text-primary/80 transition-colors"
                                    >
                                        {meeting.leaseLabel || meeting.leaseId}
                                    </Link>
                                </div>
                            </div>
                        )}
                        {meeting.propertyId && (
                            <div className="flex items-center gap-2.5">
                                <Building2 size={14} className="text-muted flex-shrink-0" />
                                <div>
                                    <p className="text-[10px] text-muted uppercase tracking-wider">{t("property")}</p>
                                    <Link
                                        href={`/dashboard/properties/${meeting.propertyId}`}
                                        className="text-xs font-medium text-primary hover:text-primary/80 transition-colors"
                                    >
                                        {meeting.propertyName || meeting.propertyId}
                                    </Link>
                                </div>
                            </div>
                        )}
                        {meeting.unitId && (
                            <div className="flex items-center gap-2.5">
                                <Home size={14} className="text-muted flex-shrink-0" />
                                <div>
                                    <p className="text-[10px] text-muted uppercase tracking-wider">{t("unit")}</p>
                                    <p className="text-xs font-medium text-foreground">
                                        {meeting.unitNumber ? t("unitInline", { unit: meeting.unitNumber }) : meeting.unitId}
                                    </p>
                                </div>
                            </div>
                        )}
                    </div>
                </div>
            )}

            {/* Meeting details card (CHEQUE_REPLACEMENT / LEASE_RENEWAL) */}
            {meeting.details && (
                <div className="bg-surface rounded-xl border border-border p-5">
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-wider mb-4">
                        {meeting.details.detailType === "CHEQUE_REPLACEMENT"
                            ? t("chequeReplacementDetails")
                            : meeting.details.detailType === "LEASE_RENEWAL"
                                ? t("leaseRenewalProposal")
                                : t("additionalDetails")}
                    </h2>

                    {meeting.details.detailType === "CHEQUE_REPLACEMENT" && (
                        <div className="space-y-2">
                            {(meeting.details.paymentScheduleIds?.length ?? 0) > 0 && (
                                <div className="flex items-center gap-2.5">
                                    <Hash size={14} className="text-muted flex-shrink-0" />
                                    <p className="text-xs text-foreground">
                                        {t("chequesToReplace", { count: meeting.details.paymentScheduleIds!.length })}
                                    </p>
                                </div>
                            )}
                        </div>
                    )}

                    {meeting.details.detailType === "LEASE_RENEWAL" && (
                        <table className="w-full text-xs">
                            <tbody>
                                {meeting.details.proposedStartDate && (
                                    <tr className="border-b border-border">
                                        <td className="py-2 text-muted w-1/2">{t("create.reviewRenewalStart")}</td>
                                        <td className="py-2 font-medium text-foreground text-end">
                                            {fmtIsoDate(meeting.details.proposedStartDate, locale)}
                                        </td>
                                    </tr>
                                )}
                                {meeting.details.proposedStartDate && meeting.details.proposedEndDate && (() => {
                                    const start = new Date(meeting.details.proposedStartDate!);
                                    const end = new Date(meeting.details.proposedEndDate!);
                                    const months = (end.getFullYear() - start.getFullYear()) * 12 + (end.getMonth() - start.getMonth());
                                    return months > 0 ? (
                                        <tr className="border-b border-border">
                                            <td className="py-2 text-muted">{t("create.reviewDuration")}</td>
                                            <td className="py-2 font-medium text-foreground text-end">
                                                {t("create.durationMonths", { n: months })}
                                            </td>
                                        </tr>
                                    ) : null;
                                })()}
                                {meeting.details.proposedRentAmount != null && (
                                    <tr>
                                        <td className="py-2 text-muted">{t("create.reviewProposedRent")}</td>
                                        <td className="py-2 font-medium text-foreground text-end">
                                            {t("create.rentAmount", { amount: formatNumber(Number(meeting.details.proposedRentAmount)) })}
                                        </td>
                                    </tr>
                                )}
                            </tbody>
                        </table>
                    )}

                    {meeting.details.notes && (
                        <p className="text-xs text-muted mt-3 pt-3 border-t border-border">
                            {meeting.details.notes}
                        </p>
                    )}
                </div>
            )}

            {/* Notes */}
            {meeting.notes && (
                <div className="bg-surface rounded-xl border border-border p-5">
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-wider mb-3">{t("notes")}</h2>
                    <p className="text-xs text-foreground whitespace-pre-wrap">{meeting.notes}</p>
                </div>
            )}

        </div>
    );
}
