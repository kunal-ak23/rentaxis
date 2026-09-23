"use client";

import { useState, useEffect, useCallback } from "react";
import { useParams } from "next/navigation";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { useSession } from "next-auth/react";
import { hasPermission, hasRole, type UserRole } from "@/lib/rbac";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import {
    ArrowLeft, Loader2, Send, Upload, Download, Star, Clock, User,
    Wrench, Building2, Home, Tag, AlertTriangle, CheckCircle, X, FileText, Trash2,
} from "lucide-react";
import { ImageLightbox } from "@/components/ui/ImageLightbox";
import { assetSrc } from "@/lib/assetUrl";

// ── Types ──────────────────────────────────────────────────────────────────

type Ticket = {
    id: string;
    /** "TKT-yy/n" (#20). */
    reference?: string | null;
    title: string;
    description: string;
    status: string;
    priority: string;
    category: string;
    propertyId: string;
    propertyName: string;
    unitId: string;
    unitNumber: string;
    reporterName: string;
    reportedBy: string;
    assignedTo: string | null;
    assigneeName: string | null;
    reportedDate: string;
    createdAt: string;
    updatedAt: string;
    estimatedResolutionHours: number | null;
    closureOtp: string | null;
    /**
     * Staff only: PUT /tickets/{id}/status with CLOSED would succeed for this
     * caller (no renter can confirm with a code, the tenant does not require
     * one, or OTP closure is locked and the caller is an admin).
     */
    closableWithoutOtp?: boolean;
    /** Staff only: OTP closure is locked for good after too many wrong codes. */
    otpLocked?: boolean;
    satisfactionRating: number | null;
    satisfactionComment: string | null;
    attachments: Attachment[];
};

type Reply = {
    id: string;
    ticketId: string;
    userId: string;
    userName: string;
    message: string;
    createdAt: string;
};

type Attachment = {
    id: string;
    name: string;
    fileUrl: string;
    fileType: string;
    fileSize: number;
    uploadedAt: string;
};

type StaffUser = {
    id: string;
    name: string;
    email: string;
    role: string;
};

// ── Badge Maps ─────────────────────────────────────────────────────────────

const PRIORITY_COLORS: Record<string, string> = {
    LOW: "bg-input text-muted",
    MEDIUM: "bg-info/10 text-info",
    HIGH: "bg-warning/10 text-warning",
    URGENT: "bg-error/10 text-error",
};

const STATUS_COLORS: Record<string, string> = {
    OPEN: "bg-warning/10 text-warning border-warning/20",
    ASSIGNED: "bg-info/10 text-info border-info/20",
    IN_PROGRESS: "bg-primary/10 text-primary border-primary/20",
    RESOLVED: "bg-success/10 text-success border-success/20",
    CLOSED: "bg-input text-muted border-border",
    REOPENED: "bg-error/10 text-error border-error/20",
};

// ── Page Component ─────────────────────────────────────────────────────────

export default function TicketDetailPage() {
    const params = useParams();
    const ticketId = params.id as string;
    const t = useTranslations("Tickets");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const userId = session?.user?.id as string | undefined;

    // Data
    const [ticket, setTicket] = useState<Ticket | null>(null);
    const [replies, setReplies] = useState<Reply[]>([]);
    const [staffUsers, setStaffUsers] = useState<StaffUser[]>([]);
    const [loading, setLoading] = useState(true);

    // Reply input
    const [replyText, setReplyText] = useState("");
    const [sendingReply, setSendingReply] = useState(false);

    // Actions
    const [actionLoading, setActionLoading] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);
    const [staffError, setStaffError] = useState<string | null>(null);
    const [otpInput, setOtpInput] = useState("");
    const [confirmingClose, setConfirmingClose] = useState(false);
    const [actionNotice, setActionNotice] = useState<string | null>(null);
    const [etaInput, setEtaInput] = useState("");
    const [showAssignDropdown, setShowAssignDropdown] = useState(false);

    // Rating
    const [ratingValue, setRatingValue] = useState(0);
    const [ratingComment, setRatingComment] = useState("");
    const [ratingSubmitting, setRatingSubmitting] = useState(false);

    // Attachments, History & Lightbox
    const [attachments, setAttachments] = useState<Attachment[]>([]);
    const [lightboxIndex, setLightboxIndex] = useState<number | null>(null);
    const [history, setHistory] = useState<{ id: string; action: string; fromStatus: string; toStatus: string; performedByName: string; notes: string; createdAt: string }[]>([]);

    // ── Fetch ───────────────────────────────────────────────────────────

    const fetchTicket = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/tickets/${ticketId}`);
            if (res.ok) setTicket(await res.json());
        } catch { /* ignore */ }
    }, [ticketId]);

    const fetchReplies = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/tickets/${ticketId}/replies`);
            if (res.ok) setReplies(await res.json());
        } catch { /* ignore */ }
    }, [ticketId]);

    const fetchAttachments = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/tickets/${ticketId}/attachments`);
            if (res.ok) setAttachments(await res.json());
        } catch { /* ignore */ }
    }, [ticketId]);

    const fetchHistory = useCallback(async () => {
        try {
            const res = await fetch(`/api/proxy/v1/tickets/${ticketId}/history`);
            if (res.ok) setHistory(await res.json());
        } catch {}
    }, [ticketId]);

    const fetchStaff = useCallback(async () => {
        // GET /api/admin/users is restricted to SUPER_ADMIN/TENANT_ADMIN
        // (UserController), so a PROPERTY_MANAGER would always get a 403 —
        // don't fire a guaranteed-failing request. PMs keep "Assign to Me"
        // (PUT /v1/tickets/{id}/assign allows PROPERTY_MANAGER); the
        // "Assign To..." staff list stays admin-only until a PM-accessible
        // assignee endpoint exists.
        if (!hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN"])) return;
        try {
            const res = await fetch("/api/proxy/admin/users");
            if (res.ok) {
                const data = await res.json();
                setStaffUsers(Array.isArray(data) ? data : []);
                setStaffError(null);
            } else {
                setStaffError(`Couldn't load the staff list (status ${res.status}) — "Assign To..." is unavailable.`);
            }
        } catch {
            setStaffError('Couldn\'t load the staff list — "Assign To..." is unavailable.');
        }
    }, [userRole]);

    useEffect(() => {
        Promise.all([fetchTicket(), fetchReplies(), fetchAttachments(), fetchHistory(), fetchStaff()]).finally(() => setLoading(false));
    }, [fetchTicket, fetchReplies, fetchAttachments, fetchHistory, fetchStaff]);

    // ── Send reply ──────────────────────────────────────────────────────

    const handleSendReply = async () => {
        if (!replyText.trim()) return;
        setSendingReply(true);
        try {
            const res = await fetch(`/api/proxy/v1/tickets/${ticketId}/replies`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ message: replyText }),
            });
            if (res.ok) {
                setReplyText("");
                fetchReplies();
            }
        } catch { /* ignore */ } finally {
            setSendingReply(false);
        }
    };

    // ── Status actions ──────────────────────────────────────────────────

    const performAction = async (
        action: string,
        body?: Record<string, unknown>,
        method: "PUT" | "POST" = "PUT",
    ): Promise<boolean> => {
        setActionLoading(action);
        setActionError(null);
        setActionNotice(null);
        try {
            const res = await fetch(`/api/proxy/v1/tickets/${ticketId}/${action}`, {
                method,
                headers: { "Content-Type": "application/json" },
                body: body ? JSON.stringify(body) : undefined,
            });
            // Surface backend failures (invalid OTP, illegal status
            // transition, ...) instead of silently doing nothing.
            await throwIfNotOk(res);
            fetchTicket();
            fetchHistory();
            return true;
        } catch (err) {
            setActionError(err instanceof ApiError ? err.message : "Something went wrong. Please try again.");
            return false;
        } finally {
            setActionLoading(null);
        }
    };

    const handleAssignToMe = () => performAction("assign", { assignTo: userId });
    const handleAssign = (assigneeId: string) => {
        setShowAssignDropdown(false);
        performAction("assign", { assignTo: assigneeId });
    };
    const handleStartWork = () => performAction("status", { status: "IN_PROGRESS" });
    const handleResolve = () => performAction("status", { status: "RESOLVED" });
    const handleReopen = () => performAction("status", { status: "REOPENED" });
    const handleClose = () => {
        if (otpInput.length !== 6) return;
        performAction("close", { otp: otpInput });
    };
    // Closing without a code goes through the status route, which the backend
    // allows only when nobody can confirm (closableWithoutOtp) and records in
    // the ticket history.
    const handleCloseWithoutOtp = async () => {
        setConfirmingClose(false);
        await performAction("status", { status: "CLOSED" });
    };
    // POST /closure-otp: a fresh code to the renter. The backend caps it at 3
    // per 24 hours; its message is shown as any other action error.
    const handleReissueOtp = async () => {
        if (await performAction("closure-otp", undefined, "POST")) {
            setActionNotice(t("reissueOtpSent"));
        }
    };
    const handleSetEta = () => {
        if (!etaInput) return;
        performAction("estimate", { hours: Number(etaInput) });
        setEtaInput("");
    };

    // ── Upload attachment ───────────────────────────────────────────────

    const handleUploadAttachment = async (file: File) => {
        const formData = new FormData();
        formData.append("file", file);
        formData.append("name", file.name);
        await fetch(`/api/upload?path=/api/v1/tickets/${ticketId}/attachments`, {
            method: "POST",
            body: formData,
        });
        fetchAttachments();
    };

    // ── Download attachment ─────────────────────────────────────────────

    const handleDownloadAttachment = async (attachmentId: string, fileName: string) => {
        const res = await fetch(`/api/proxy/v1/tickets/attachments/${attachmentId}/download`);

        if (res.ok) {
            const blob = await res.blob();
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        }
    };

    const handleDeleteAttachment = async (attachmentId: string) => {
        try {
            const res = await fetch(`/api/proxy/v1/tickets/attachments/${attachmentId}`, { method: "DELETE" });
            if (res.ok) fetchAttachments();
        } catch {}
    };

    // ── Rating ──────────────────────────────────────────────────────────

    const handleSubmitRating = async () => {
        if (ratingValue === 0) return;
        setRatingSubmitting(true);
        try {
            const res = await fetch(`/api/proxy/v1/tickets/${ticketId}/rate`, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ rating: ratingValue, comment: ratingComment }),
            });
            if (res.ok) { fetchTicket(); fetchHistory(); }
        } catch { /* ignore */ } finally {
            setRatingSubmitting(false);
        }
    };

    // ── Loading / Not Found ─────────────────────────────────────────────

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    if (!ticket) {
        return (
            <div className="text-center py-24">
                <p className="text-sm text-muted">Ticket not found.</p>
                <Link href="/dashboard/tickets" className="text-xs text-primary font-semibold mt-2 inline-block">
                    Back to Tickets
                </Link>
            </div>
        );
    }

    // ── Role helpers ────────────────────────────────────────────────────

    const isRenter = userRole === "RENTER";
    const isAdmin = hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN"]);
    const isPM = hasRole(userRole, ["PROPERTY_MANAGER"]);
    const canManage = isAdmin || isPM;

    // ── Render ──────────────────────────────────────────────────────────

    return (
        <div>
            {/* Header */}
            <div className="flex items-center gap-4 mb-6">
                <Link href="/dashboard/tickets" className="p-2 rounded-lg hover:bg-input transition-colors text-muted hover:text-foreground">
                    <ArrowLeft size={18} />
                </Link>
                <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-3">
                        <h1 className="text-lg font-bold text-foreground truncate">{ticket.title}</h1>
                        <span className={cn(
                            "px-2.5 py-1 rounded-lg text-[10px] font-semibold border shrink-0",
                            STATUS_COLORS[ticket.status] || "bg-input text-muted border-border",
                        )}>
                            {ticket.status.replace(/_/g, " ")}
                        </span>
                    </div>
                    <p className="text-xs text-muted mt-0.5">
                        <span dir="ltr" data-testid="ticket-detail-reference">{ticket.reference ?? ticket.id.substring(0, 8)}</span> &bull; {ticket.propertyName} &bull; Unit {ticket.unitNumber}
                    </p>
                </div>
            </div>

            {/* ── Top: Two-column layout ── */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 mb-6">
                {/* Left Column (2/3): Description + Attachments */}
                <div className="lg:col-span-2 space-y-6">
                    {/* Description */}
                    <div className="bg-surface rounded-xl border border-border p-5">
                        <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-3">Description</h3>
                        <p className="text-sm text-foreground/80 whitespace-pre-wrap leading-relaxed">
                            {ticket.description || "No description provided."}
                        </p>
                    </div>

                    {/* Attachments Gallery */}
                    <div className="bg-surface rounded-xl border border-border p-5">
                        <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-3">
                            Attachments {attachments.length > 0 && <span className="text-muted/60">({attachments.length})</span>}
                        </h3>

                        {attachments.length > 0 ? (
                            <div className="grid grid-cols-3 md:grid-cols-4 gap-2 mb-3">
                                {attachments.map((att, idx) => {
                                    const isImage = att.fileType?.startsWith("image/");
                                    const isVideo = att.fileType?.startsWith("video/");
                                    // Compute media index for lightbox (images + videos)
                                    const mediaIndex = attachments.slice(0, idx).filter(a => a.fileType?.startsWith("image/") || a.fileType?.startsWith("video/")).length;
                                    return (
                                        <div key={att.id} className="group relative rounded-lg border border-border overflow-hidden bg-input/30">
                                            {isImage ? (
                                                <div
                                                    className="aspect-square bg-cover bg-center cursor-pointer hover:opacity-90 transition-opacity"
                                                    style={{ backgroundImage: `url(${assetSrc(att.fileUrl)})` }}
                                                    onClick={() => setLightboxIndex(mediaIndex)}
                                                />
                                            ) : isVideo ? (
                                                <div
                                                    className="aspect-square relative cursor-pointer hover:opacity-90 transition-opacity"
                                                    onClick={() => setLightboxIndex(mediaIndex)}
                                                >
                                                    <video src={assetSrc(att.fileUrl)} className="w-full h-full object-cover" preload="metadata" muted />
                                                    <div className="absolute inset-0 flex items-center justify-center bg-black/20">
                                                        <div className="w-10 h-10 rounded-full bg-white/90 flex items-center justify-center">
                                                            <div className="w-0 h-0 border-t-[8px] border-t-transparent border-b-[8px] border-b-transparent border-l-[14px] border-l-foreground ml-1" />
                                                        </div>
                                                    </div>
                                                </div>
                                            ) : (
                                                <div
                                                    className="aspect-square flex flex-col items-center justify-center gap-1 cursor-pointer hover:bg-input transition-colors"
                                                    onClick={() => handleDownloadAttachment(att.id, att.fileUrl.split("/").pop() || "file")}
                                                >
                                                    <FileText size={20} className="text-muted" />
                                                    <p className="text-[9px] font-semibold text-muted uppercase">{att.fileType?.split("/")[1] || "FILE"}</p>
                                                </div>
                                            )}
                                            <div className="absolute bottom-0 left-0 right-0 bg-gradient-to-t from-black/60 to-transparent p-2 opacity-0 group-hover:opacity-100 transition-opacity">
                                                <div className="flex items-center justify-between">
                                                    <p className="text-[9px] text-white font-medium truncate flex-1">{(att.fileSize / 1024).toFixed(0)} KB</p>
                                                    <div className="flex items-center gap-1">
                                                        <button onClick={(e) => { e.stopPropagation(); handleDownloadAttachment(att.id, att.fileUrl.split("/").pop() || "file"); }} className="p-1 text-white hover:text-white/80 cursor-pointer"><Download size={11} /></button>
                                                        <button onClick={(e) => { e.stopPropagation(); handleDeleteAttachment(att.id); }} className="p-1 text-red-400 hover:text-red-300 cursor-pointer"><Trash2 size={11} /></button>
                                                    </div>
                                                </div>
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>
                        ) : (
                            <p className="text-xs text-muted text-center py-3 mb-3">No attachments yet.</p>
                        )}

                        <label className="flex items-center justify-center gap-2 border-2 border-dashed border-border rounded-lg px-4 py-3 cursor-pointer hover:border-primary/40 hover:bg-input/30 transition-all">
                            <Upload size={14} className="text-muted" />
                            <span className="text-xs text-muted">Add photos, videos or files</span>
                            <input type="file" className="hidden" accept="image/*,video/*,.pdf,.doc,.docx" multiple
                                onChange={(e) => { const files = e.target.files; if (files) Array.from(files).forEach(f => handleUploadAttachment(f)); if (e.target) e.target.value = ""; }}
                            />
                        </label>
                    </div>

                    {/* Renter OTP notice */}
                    {isRenter && ticket.status === "RESOLVED" && ticket.closureOtp && (
                        <div className="bg-success/5 border border-success/20 rounded-xl p-4">
                            <p className="text-xs font-semibold text-success mb-1">Share this code with your property manager to close the ticket:</p>
                            <p className="text-2xl font-bold text-success tracking-[0.3em] text-center py-2">{ticket.closureOtp}</p>
                        </div>
                    )}

                    {/* Rating display (renter, after rated) */}
                    {isRenter && ticket.satisfactionRating && (
                        <div className="bg-surface rounded-xl border border-border p-5">
                            <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-3">Your Rating</h3>
                            <div className="flex items-center gap-1 mb-1">
                                {[1,2,3,4,5].map(s => <Star key={s} size={20} className={cn(s <= ticket.satisfactionRating! ? "text-accent fill-accent" : "text-border")} />)}
                            </div>
                            {ticket.satisfactionComment && <p className="text-xs text-muted mt-2">&ldquo;{ticket.satisfactionComment}&rdquo;</p>}
                        </div>
                    )}

                    {/* Rating form (renter, after CLOSED, not yet rated) */}
                    {isRenter && ticket.status === "CLOSED" && !ticket.satisfactionRating && (
                        <div className="bg-surface rounded-xl border border-border p-5">
                            <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-3">Rate this service</h3>
                            <div className="flex items-center gap-1 mb-3">
                                {[1,2,3,4,5].map(star => (
                                    <button key={star} onClick={() => setRatingValue(star)} className="cursor-pointer p-0.5">
                                        <Star size={24} className={cn("transition-colors", star <= ratingValue ? "text-accent fill-accent" : "text-border")} />
                                    </button>
                                ))}
                            </div>
                            <textarea value={ratingComment} onChange={(e) => setRatingComment(e.target.value)} placeholder="Optional comment..." rows={2}
                                className="w-full border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none mb-3" />
                            <button onClick={handleSubmitRating} disabled={ratingValue === 0 || ratingSubmitting}
                                className={cn("flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer", ratingValue === 0 || ratingSubmitting ? "bg-input text-muted cursor-not-allowed" : "bg-primary text-primary-foreground hover:bg-primary/90")}>
                                {ratingSubmitting && <Loader2 size={12} className="animate-spin" />} Submit Rating
                            </button>
                        </div>
                    )}

                    {/* Conversation */}
                    <div className="bg-surface rounded-xl border border-border">
                        <div className="px-5 py-3.5 border-b border-border">
                            <h2 className="text-xs font-semibold text-muted uppercase tracking-wider flex items-center gap-2"><Send size={13} /> Conversation</h2>
                        </div>
                        <div className="divide-y divide-border max-h-96 overflow-y-auto">
                            {replies.length === 0 && (
                                <div className="text-center py-8 text-muted">
                                    <Send size={20} className="mx-auto mb-2 opacity-40" />
                                    <p className="text-xs">No replies yet. Start the conversation.</p>
                                </div>
                            )}
                            {replies.map(reply => (
                                <div key={reply.id} className="px-5 py-4">
                                    <div className="flex items-start gap-3">
                                        <div className="w-8 h-8 rounded-full bg-primary/10 text-primary flex items-center justify-center text-xs font-bold shrink-0">
                                            {reply.userName?.charAt(0)?.toUpperCase() || "?"}
                                        </div>
                                        <div className="flex-1 min-w-0">
                                            <div className="flex items-center gap-2 mb-1">
                                                <span className="text-xs font-semibold text-foreground">{reply.userName}</span>
                                                <span className="text-[10px] text-muted">{new Date(reply.createdAt).toLocaleString()}</span>
                                            </div>
                                            <p className="text-xs text-foreground/80 whitespace-pre-wrap">{reply.message}</p>
                                        </div>
                                    </div>
                                </div>
                            ))}
                        </div>
                        <div className="px-5 py-4 border-t border-border">
                            <div className="flex items-end gap-3">
                                <textarea value={replyText} onChange={(e) => setReplyText(e.target.value)} placeholder="Type your reply..." rows={2}
                                    className="flex-1 border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none"
                                    onKeyDown={(e) => { if (e.key === "Enter" && (e.metaKey || e.ctrlKey)) handleSendReply(); }}
                                />
                                <button onClick={handleSendReply} disabled={!replyText.trim() || sendingReply}
                                    className={cn("flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer shrink-0",
                                        !replyText.trim() || sendingReply ? "bg-input text-muted cursor-not-allowed" : "bg-primary text-primary-foreground hover:bg-primary/90")}>
                                    {sendingReply ? <Loader2 size={12} className="animate-spin" /> : <Send size={12} />} Send
                                </button>
                            </div>
                        </div>
                    </div>
                </div>

                {/* Right Column (1/3): Info + Actions + History */}
                <div className="space-y-6">
                    {/* Ticket Info */}
                    <div className="bg-surface rounded-xl border border-border p-5">
                        <div className="text-center mb-4">
                            <span className={cn("inline-block px-4 py-1.5 rounded-lg text-xs font-bold border", STATUS_COLORS[ticket.status] || "bg-input text-muted border-border")}>
                                {ticket.status.replace(/_/g, " ")}
                            </span>
                        </div>
                        <div className="space-y-3">
                            <DetailRow icon={<Tag size={12} />} label="Category" value={ticket.category?.replace(/_/g, " ") || "—"} />
                            <DetailRow icon={<AlertTriangle size={12} />} label="Priority">
                                <span className={cn("px-2 py-0.5 rounded-md text-[9px] font-semibold", PRIORITY_COLORS[ticket.priority] || "bg-input text-muted")}>{ticket.priority}</span>
                            </DetailRow>
                            <DetailRow icon={<Building2 size={12} />} label="Property" value={ticket.propertyName || "—"} />
                            <DetailRow icon={<Home size={12} />} label="Unit" value={ticket.unitNumber || "—"} />
                            <DetailRow icon={<User size={12} />} label="Reporter" value={ticket.reporterName || "—"} />
                            <DetailRow icon={<Wrench size={12} />} label="Assigned To" value={ticket.assigneeName || "Unassigned"} />
                            <DetailRow icon={<Clock size={12} />} label={t("reportedOn")} value={fmtIsoDate(ticket.reportedDate, locale)} />
                            <DetailRow icon={<Clock size={12} />} label="Created" value={new Date(ticket.createdAt).toLocaleDateString()} />
                            {ticket.estimatedResolutionHours && <DetailRow icon={<Clock size={12} />} label="ETA" value={`${ticket.estimatedResolutionHours} hours`} />}
                        </div>
                    </div>

                    {/* Action buttons */}
                    {canManage && (
                        <div className="bg-surface rounded-xl border border-border p-5 space-y-3">
                            <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-2">Actions</h3>
                            {actionError && (
                                <div className="bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg px-3 py-2" role="alert">
                                    {actionError}
                                </div>
                            )}
                            {actionNotice && (
                                <div className="bg-success/10 border border-success/20 text-success text-xs font-medium rounded-lg px-3 py-2" role="status">
                                    {actionNotice}
                                </div>
                            )}
                            {staffError && (
                                <p className="text-[10px] text-warning font-medium">{staffError}</p>
                            )}
                            {canManage && (ticket.status === "OPEN" || ticket.status === "REOPENED" || ticket.status === "ASSIGNED" || ticket.status === "IN_PROGRESS") && (
                                <button onClick={handleAssignToMe} disabled={actionLoading === "assign"} className="w-full flex items-center justify-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer disabled:opacity-50">
                                    {actionLoading === "assign" && <Loader2 size={12} className="animate-spin" />} Assign to Me
                                </button>
                            )}
                            {canManage && (ticket.status === "OPEN" || ticket.status === "REOPENED" || ticket.status === "ASSIGNED" || ticket.status === "IN_PROGRESS") && staffUsers.length > 0 && (
                                <div className="relative">
                                    <button onClick={() => setShowAssignDropdown(!showAssignDropdown)} className="w-full flex items-center justify-center gap-2 bg-info/10 text-info px-4 py-2 rounded-lg text-xs font-semibold hover:bg-info/20 transition-all cursor-pointer">Assign To...</button>
                                    {showAssignDropdown && (
                                        <div className="absolute top-full left-0 right-0 mt-1 bg-surface border border-border rounded-lg shadow-lg z-10 max-h-48 overflow-y-auto">
                                            {staffUsers.filter(u => u.role === "PROPERTY_MANAGER" || u.role === "TENANT_ADMIN").map(user => (
                                                <button key={user.id} onClick={() => handleAssign(user.id)} className="w-full text-left px-3 py-2 text-xs hover:bg-input transition-colors cursor-pointer">
                                                    <span className="font-medium text-foreground">{user.name}</span> <span className="text-muted">({user.role.replace(/_/g, " ")})</span>
                                                </button>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            )}
                            {(ticket.status === "ASSIGNED" || ticket.status === "REOPENED") && (
                                <button onClick={handleStartWork} disabled={actionLoading === "status"} className="w-full flex items-center justify-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer disabled:opacity-50">
                                    {actionLoading === "status" && <Loader2 size={12} className="animate-spin" />} Start Work
                                </button>
                            )}
                            {ticket.status === "IN_PROGRESS" && (
                                <button onClick={handleResolve} disabled={actionLoading === "status"} className="w-full flex items-center justify-center gap-2 bg-success/10 text-success px-4 py-2 rounded-lg text-xs font-semibold hover:bg-success/20 transition-all cursor-pointer disabled:opacity-50">
                                    {actionLoading === "status" && <Loader2 size={12} className="animate-spin" />} <CheckCircle size={12} /> Mark Resolved
                                </button>
                            )}
                            {ticket.status === "RESOLVED" && ticket.closableWithoutOtp && (
                                confirmingClose ? (
                                    <div className="space-y-2 rounded-lg border border-warning/30 bg-warning/5 p-3" role="alertdialog" aria-labelledby="close-without-otp-title">
                                        <p id="close-without-otp-title" className="text-xs font-semibold text-foreground">{t("closeTicketConfirmTitle")}</p>
                                        <p className="text-xs text-muted">{ticket.otpLocked ? t("closeWithoutOtpLocked") : t("closeWithoutOtpNoRenter")}</p>
                                        <div className="flex items-center gap-2">
                                            <button onClick={handleCloseWithoutOtp} disabled={actionLoading === "status"} className="flex-1 flex items-center justify-center gap-2 bg-primary text-primary-foreground px-3 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer disabled:opacity-50">
                                                {actionLoading === "status" && <Loader2 size={12} className="animate-spin" />} {t("closeTicketConfirm")}
                                            </button>
                                            <button onClick={() => setConfirmingClose(false)} className="flex-1 bg-input text-foreground px-3 py-2 rounded-lg text-xs font-semibold hover:bg-input/80 transition-all cursor-pointer">
                                                {t("cancel")}
                                            </button>
                                        </div>
                                    </div>
                                ) : (
                                    <button onClick={() => setConfirmingClose(true)} disabled={actionLoading === "status"} className="w-full flex items-center justify-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer disabled:opacity-50">
                                        <CheckCircle size={12} /> {t("closeTicket")}
                                    </button>
                                )
                            )}
                            {ticket.status === "RESOLVED" && !ticket.closableWithoutOtp && ticket.otpLocked && (
                                <p className="text-xs text-warning font-medium">{t("otpLockedStaffHint")}</p>
                            )}
                            {ticket.status === "RESOLVED" && (
                                <>
                                    {!ticket.closableWithoutOtp && !ticket.otpLocked && (
                                    <>
                                    <div className="space-y-2">
                                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider">Close with OTP</label>
                                        <div className="flex items-center gap-2">
                                            <input type="text" value={otpInput} onChange={(e) => setOtpInput(e.target.value.replace(/\D/g, "").slice(0, 6))} placeholder="6-digit OTP" maxLength={6}
                                                className="flex-1 border border-border rounded-lg bg-surface px-3 py-2 text-xs text-center tracking-[0.3em] font-mono focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none" />
                                            <button onClick={handleClose} disabled={otpInput.length !== 6 || actionLoading === "close"}
                                                className={cn("flex items-center gap-1 px-3 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer shrink-0", otpInput.length !== 6 || actionLoading === "close" ? "bg-input text-muted cursor-not-allowed" : "bg-primary text-primary-foreground hover:bg-primary/90")}>
                                                {actionLoading === "close" && <Loader2 size={12} className="animate-spin" />} Close
                                            </button>
                                        </div>
                                    </div>
                                    <button onClick={handleReissueOtp} disabled={actionLoading === "closure-otp"} className="w-full flex items-center justify-center gap-2 bg-info/10 text-info px-4 py-2 rounded-lg text-xs font-semibold hover:bg-info/20 transition-all cursor-pointer disabled:opacity-50">
                                        {actionLoading === "closure-otp" && <Loader2 size={12} className="animate-spin" />} {t("reissueOtp")}
                                    </button>
                                    </>
                                    )}
                                    <button onClick={handleReopen} disabled={actionLoading === "reopen"} className="w-full flex items-center justify-center gap-2 bg-error/10 text-error px-4 py-2 rounded-lg text-xs font-semibold hover:bg-error/20 transition-all cursor-pointer disabled:opacity-50">
                                        {actionLoading === "reopen" && <Loader2 size={12} className="animate-spin" />} Reopen
                                    </button>
                                </>
                            )}
                            {ticket.status === "CLOSED" && (
                                <button onClick={handleReopen} disabled={actionLoading === "reopen"} className="w-full flex items-center justify-center gap-2 bg-error/10 text-error px-4 py-2 rounded-lg text-xs font-semibold hover:bg-error/20 transition-all cursor-pointer disabled:opacity-50">
                                    {actionLoading === "reopen" && <Loader2 size={12} className="animate-spin" />} Reopen Ticket
                                </button>
                            )}
                            {(ticket.status === "ASSIGNED" || ticket.status === "IN_PROGRESS" || ticket.status === "REOPENED") && (
                                <div className="space-y-2 pt-2 border-t border-border">
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider">Set Estimated Hours</label>
                                    <div className="flex items-center gap-2">
                                        <input type="number" value={etaInput} onChange={(e) => setEtaInput(e.target.value)} placeholder="Hours" min={1}
                                            className="flex-1 border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none" />
                                        <button onClick={handleSetEta} disabled={!etaInput || actionLoading === "estimate"}
                                            className={cn("flex items-center gap-1 px-3 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer shrink-0", !etaInput || actionLoading === "estimate" ? "bg-input text-muted cursor-not-allowed" : "bg-primary text-primary-foreground hover:bg-primary/90")}>
                                            {actionLoading === "estimate" && <Loader2 size={12} className="animate-spin" />} Set ETA
                                        </button>
                                    </div>
                                </div>
                            )}
                        </div>
                    )}

                    {/* Activity History */}
                    {history.length > 0 && (
                        <div className="bg-surface rounded-xl border border-border p-5">
                            <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-3 flex items-center gap-2"><Clock size={13} /> Activity History</h3>
                            <div className="space-y-3 max-h-64 overflow-y-auto">
                                {history.map(h => (
                                    <div key={h.id} className="flex gap-3">
                                        <div className="w-2 h-2 rounded-full bg-primary mt-1.5 shrink-0" />
                                        <div className="flex-1 min-w-0">
                                            <p className="text-[11px] text-foreground font-medium">{h.notes || h.action}</p>
                                            <p className="text-[10px] text-muted">
                                                by {h.performedByName || "System"} &bull; {new Date(h.createdAt).toLocaleString()}
                                            </p>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Rating display */}
                    {ticket.satisfactionRating && (
                        <div className="bg-surface rounded-xl border border-border p-4">
                            <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-2">Service Rating</h3>
                            <div className="flex items-center gap-1 mb-1">
                                {[1,2,3,4,5].map(s => <Star key={s} size={16} className={cn(s <= ticket.satisfactionRating! ? "text-accent fill-accent" : "text-border")} />)}
                            </div>
                            {ticket.satisfactionComment && <p className="text-xs text-muted mt-1">&ldquo;{ticket.satisfactionComment}&rdquo;</p>}
                        </div>
                    )}
                </div>
            </div>

            {/* Image/Video Lightbox */}
            {lightboxIndex !== null && (() => {
                const mediaItems = attachments.filter(a => a.fileType?.startsWith("image/") || a.fileType?.startsWith("video/"));
                return mediaItems.length > 0 ? (
                    <ImageLightbox
                        images={mediaItems.map(a => ({ url: assetSrc(a.fileUrl), name: a.fileUrl.split("/").pop() || "file", type: a.fileType }))}
                        initialIndex={Math.min(lightboxIndex, mediaItems.length - 1)}
                        onClose={() => setLightboxIndex(null)}
                        onDownload={(url, name) => {
                            const att = attachments.find(a => assetSrc(a.fileUrl) === url);
                            if (att) handleDownloadAttachment(att.id, name);
                        }}
                    />
                ) : null;
            })()}
        </div>
    );
}

// ── Detail Row Helper ──────────────────────────────────────────────────────

function DetailRow({
    icon,
    label,
    value,
    children,
}: {
    icon: React.ReactNode;
    label: string;
    value?: string;
    children?: React.ReactNode;
}) {
    return (
        <div className="flex items-center justify-between">
            <span className="flex items-center gap-1.5 text-xs text-muted">
                {icon} {label}
            </span>
            {children || <span className="text-xs font-medium text-foreground">{value}</span>}
        </div>
    );
}
