"use client";

import { useState, useEffect, useCallback } from "react";
import dynamic from "next/dynamic";
import dayGridPlugin from "@fullcalendar/daygrid";
import timeGridPlugin from "@fullcalendar/timegrid";
import interactionPlugin from "@fullcalendar/interaction";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { useRouter } from "@/i18n/routing";
import { Link } from "@/i18n/routing";
import { Pagination } from "@/components/ui/Pagination";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { cn } from "@/lib/utils";
import { Plus, Loader2, Eye, CalendarDays, List } from "lucide-react";

// Dynamic import to avoid SSR issues with FullCalendar
const FullCalendar = dynamic(() => import("@fullcalendar/react"), { ssr: false });

// ── Types ──────────────────────────────────────────────────────────────────

type Meeting = {
    id: string;
    title: string | null;
    purpose: string;
    type: string;
    status: string;
    slotStart: string;
    slotEnd: string;
    hostName: string | null;
    requesterName: string | null;
    propertyName: string | null;
    unitNumber: string | null;
    notes: string | null;
    createdAt: string;
};

// ── Badge Maps ─────────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
    REQUESTED: "bg-warning/10 text-warning",
    APPROVED: "bg-info/10 text-info",
    COMPLETED: "bg-success/10 text-success",
    CANCELLED: "bg-input text-muted",
    NO_SHOW: "bg-error/10 text-error",
};

const CALENDAR_COLORS: Record<string, string> = {
    REQUESTED: "#F59E0B",
    APPROVED: "#3B82F6",
    COMPLETED: "#10B981",
    CANCELLED: "#6B7280",
    NO_SHOW: "#EF4444",
};

// ── Page Component ─────────────────────────────────────────────────────────

export default function MeetingsPage() {
    const { data: session } = useSession();
    const t = useTranslations("Meetings");
    const router = useRouter();
    const userRole = session?.user?.role as UserRole | undefined;
    const isRenter = userRole === "RENTER";

    // Data
    const [meetings, setMeetings] = useState<Meeting[]>([]);
    const [loading, setLoading] = useState(true);

    // View toggle
    const [view, setView] = useState<"calendar" | "list">("calendar");

    // Filters
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [typeFilter, setTypeFilter] = useState("ALL");

    // Pagination
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(20);

    // Create modal placeholder
    const [showCreateModal, setShowCreateModal] = useState(false);

    // ── Fetch data ──────────────────────────────────────────────────────

    const fetchMeetings = useCallback(async () => {
        try {
            const endpoint = isRenter
                ? "/api/proxy/v1/meetings/my"
                : "/api/proxy/v1/meetings";
            const params = new URLSearchParams({
                page: String(currentPage - 1), // backend is 0-indexed
                size: String(itemsPerPage),
            });
            const res = await fetch(`${endpoint}?${params.toString()}`);
            if (res.ok) {
                const data = await res.json();
                // Handle both paginated and plain array responses
                if (Array.isArray(data)) {
                    setMeetings(data);
                } else if (data.content) {
                    setMeetings(data.content);
                }
            }
        } catch { /* ignore */ }
    }, [isRenter, currentPage, itemsPerPage]);

    useEffect(() => {
        if (session !== undefined) {
            fetchMeetings().finally(() => setLoading(false));
        }
    }, [fetchMeetings, session]);

    // ── Filtering ───────────────────────────────────────────────────────

    const filtered = meetings.filter((m) => {
        if (statusFilter !== "ALL" && m.status !== statusFilter) return false;
        if (typeFilter !== "ALL" && m.type !== typeFilter) return false;
        return true;
    });

    const totalItems = filtered.length;
    const paginated = filtered.slice(
        (currentPage - 1) * itemsPerPage,
        currentPage * itemsPerPage,
    );

    // ── FullCalendar events ─────────────────────────────────────────────

    const calendarEvents = filtered.map((m) => ({
        id: m.id,
        title: m.title || m.purpose,
        start: m.slotStart,
        end: m.slotEnd,
        backgroundColor: CALENDAR_COLORS[m.status] ?? "#6B7280",
        borderColor: CALENDAR_COLORS[m.status] ?? "#6B7280",
    }));

    // ── Loading state ───────────────────────────────────────────────────

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    // ── Render ──────────────────────────────────────────────────────────

    return (
        <div>
            {/* Header */}
            <div className="flex items-center justify-between mb-6">
                <div>
                    <h1 className="text-lg font-bold text-foreground">{t("title")}</h1>
                    <p className="text-xs text-muted mt-0.5">
                        Schedule and manage property meetings
                    </p>
                </div>
                <div className="flex items-center gap-3">
                    {/* View toggle */}
                    <div className="flex items-center border border-border rounded-lg overflow-hidden">
                        <button
                            onClick={() => setView("calendar")}
                            className={cn(
                                "flex items-center gap-1.5 px-3 py-2 text-xs font-semibold transition-colors cursor-pointer",
                                view === "calendar"
                                    ? "bg-primary text-primary-foreground"
                                    : "bg-surface text-muted hover:bg-input",
                            )}
                        >
                            <CalendarDays size={13} />
                            {t("calendar")}
                        </button>
                        <button
                            onClick={() => setView("list")}
                            className={cn(
                                "flex items-center gap-1.5 px-3 py-2 text-xs font-semibold transition-colors cursor-pointer",
                                view === "list"
                                    ? "bg-primary text-primary-foreground"
                                    : "bg-surface text-muted hover:bg-input",
                            )}
                        >
                            <List size={13} />
                            {t("list")}
                        </button>
                    </div>

                    <button
                        onClick={() => setShowCreateModal(true)}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer"
                    >
                        <Plus size={14} /> {t("newMeeting")}
                    </button>
                </div>
            </div>

            {/* Filters */}
            <div className="bg-surface rounded-xl border border-border p-4 mb-6">
                <div className="flex flex-wrap items-center gap-3">
                    {/* Status filter */}
                    <select
                        value={statusFilter}
                        onChange={(e) => { setStatusFilter(e.target.value); setCurrentPage(1); }}
                        className="border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                    >
                        <option value="ALL">All Statuses</option>
                        <option value="REQUESTED">{t("status.REQUESTED")}</option>
                        <option value="APPROVED">{t("status.APPROVED")}</option>
                        <option value="COMPLETED">{t("status.COMPLETED")}</option>
                        <option value="CANCELLED">{t("status.CANCELLED")}</option>
                        <option value="NO_SHOW">{t("status.NO_SHOW")}</option>
                    </select>

                    {/* Type filter */}
                    <select
                        value={typeFilter}
                        onChange={(e) => { setTypeFilter(e.target.value); setCurrentPage(1); }}
                        className="border border-border rounded-lg bg-surface px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none cursor-pointer"
                    >
                        <option value="ALL">All Types</option>
                        <option value="OFFICE_VISIT">{t("officeVisit")}</option>
                        <option value="PROPERTY_VISIT">{t("propertyVisit")}</option>
                        <option value="CHEQUE_REPLACEMENT">{t("chequeReplacement")}</option>
                        <option value="LEASE_RENEWAL">{t("leaseRenewal")}</option>
                        <option value="PROPERTY_VIEWING">{t("propertyViewing")}</option>
                        <option value="OTHER">{t("other")}</option>
                    </select>
                </div>
            </div>

            {/* Calendar view */}
            {view === "calendar" && (
                <div className="bg-surface rounded-xl border border-border p-4">
                    <FullCalendar
                        plugins={[dayGridPlugin, timeGridPlugin, interactionPlugin]}
                        initialView="dayGridMonth"
                        headerToolbar={{
                            left: "prev,next today",
                            center: "title",
                            right: "dayGridMonth,timeGridWeek,timeGridDay",
                        }}
                        events={calendarEvents}
                        eventClick={(info) => {
                            router.push(`/dashboard/meetings/${info.event.id}`);
                        }}
                        height="auto"
                    />
                </div>
            )}

            {/* List / Table view */}
            {view === "list" && (
                <div className="bg-surface rounded-xl border border-border overflow-hidden">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="bg-input/50">
                                    <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Date / Time</th>
                                    <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Type</th>
                                    <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Purpose</th>
                                    <th className="px-4 py-2.5 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">{t("host")}</th>
                                    <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-muted uppercase tracking-wider">Status</th>
                                    <th className="px-4 py-2.5 text-center text-[11px] font-semibold text-muted uppercase tracking-wider">Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {paginated.map((meeting) => (
                                    <tr key={meeting.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                        <td className="px-4 py-2.5 text-xs tabular-nums">
                                            <div className="font-medium text-foreground">
                                                {new Date(meeting.slotStart).toLocaleDateString()}
                                            </div>
                                            <div className="text-muted text-[10px]">
                                                {new Date(meeting.slotStart).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                                                {" – "}
                                                {new Date(meeting.slotEnd).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                                            </div>
                                        </td>
                                        <td className="px-4 py-2.5 text-xs text-muted">
                                            {meeting.type.replace(/_/g, " ")}
                                        </td>
                                        <td className="px-4 py-2.5 max-w-[200px]">
                                            <div className="text-xs font-medium text-foreground truncate">
                                                {meeting.title || meeting.purpose}
                                            </div>
                                            {meeting.propertyName && (
                                                <div className="text-[10px] text-muted truncate">
                                                    {meeting.propertyName}
                                                    {meeting.unitNumber && ` — Unit ${meeting.unitNumber}`}
                                                </div>
                                            )}
                                        </td>
                                        <td className="px-4 py-2.5 text-xs text-muted">
                                            {meeting.hostName || "—"}
                                        </td>
                                        <td className="px-4 py-2.5 text-center">
                                            <span className={cn(
                                                "px-2 py-0.5 rounded-md text-[9px] font-semibold",
                                                STATUS_COLORS[meeting.status] || "bg-input text-muted",
                                            )}>
                                                {meeting.status.replace(/_/g, " ")}
                                            </span>
                                        </td>
                                        <td className="px-4 py-2.5 text-center">
                                            <Link
                                                href={`/dashboard/meetings/${meeting.id}`}
                                                className="inline-flex items-center gap-1 text-[10px] font-semibold text-primary hover:text-primary/80"
                                            >
                                                <Eye size={12} /> View
                                            </Link>
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                        {paginated.length === 0 && (
                            <div className="text-center py-12 text-muted">
                                <CalendarDays size={28} className="mx-auto mb-3 opacity-40" />
                                <p className="text-xs">{t("noMeetings")}</p>
                                <p className="text-[10px] mt-1 text-muted/60">
                                    Create a new meeting to get started.
                                </p>
                            </div>
                        )}
                    </div>

                    <div className="px-4 pb-3">
                        <Pagination
                            currentPage={currentPage}
                            totalItems={totalItems}
                            itemsPerPage={itemsPerPage}
                            onPageChange={setCurrentPage}
                            onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                        />
                    </div>
                </div>
            )}

            {/* Create Modal placeholder — implemented in Task 10 */}
            {showCreateModal && (
                <div className="fixed inset-0 z-50 flex items-center justify-center">
                    <div
                        className="absolute inset-0 bg-black/40"
                        onClick={() => setShowCreateModal(false)}
                    />
                    <div className="relative bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 px-6 py-8 text-center">
                        <CalendarDays size={32} className="mx-auto mb-3 text-primary opacity-60" />
                        <p className="text-sm font-semibold text-foreground mb-1">Create Meeting</p>
                        <p className="text-xs text-muted">Meeting creation form coming in Task 10.</p>
                        <button
                            onClick={() => setShowCreateModal(false)}
                            className="mt-4 px-4 py-2 rounded-lg text-xs font-semibold text-muted hover:text-foreground hover:bg-input transition-colors cursor-pointer"
                        >
                            Close
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
