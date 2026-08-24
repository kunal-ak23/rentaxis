"use client";

import { useState, useEffect, useCallback, useRef} from "react";
import { useSession } from "next-auth/react";
import { useRouter } from "next/navigation";
import { useLocale } from "next-intl";
import { Pagination } from "@/components/ui/Pagination";
import { cn } from "@/lib/utils";
import {
    Bell, CheckCheck, Loader2,
    Ticket, FileText, CreditCard, Info,
} from "lucide-react";

// ── Types ──────────────────────────────────────────────────────────────────

type Notification = {
    id: string;
    title: string;
    message: string;
    type: string;
    referenceType: string | null;
    referenceId: string | null;
    isRead: boolean;
    createdAt: string;
};

// ── Helpers ────────────────────────────────────────────────────────────────

function timeAgo(dateStr: string): string {
    const now = new Date();
    const date = new Date(dateStr);
    const diffMs = now.getTime() - date.getTime();
    const mins = Math.floor(diffMs / 60000);
    if (mins < 1) return "Just now";
    if (mins < 60) return `${mins}m ago`;
    const hours = Math.floor(diffMs / 3600000);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(diffMs / 86400000);
    if (days < 7) return `${days}d ago`;
    return date.toLocaleDateString();
}

function getNotificationIcon(referenceType: string | null) {
    switch (referenceType) {
        case "TICKET":
            return <Ticket size={16} className="text-warning" />;
        case "LEASE":
            return <FileText size={16} className="text-primary" />;
        case "PAYMENT":
            return <CreditCard size={16} className="text-success" />;
        default:
            return <Info size={16} className="text-info" />;
    }
}

// ── Page Component ─────────────────────────────────────────────────────────

export default function NotificationsPage() {
    const { data: session } = useSession();
    const router = useRouter();
    const locale = useLocale();

    const [notifications, setNotifications] = useState<Notification[]>([]);
    const [loading, setLoading] = useState(true);
    const [filter, setFilter] = useState<"ALL" | "UNREAD">("ALL");
    const [totalItems, setTotalItems] = useState(0);
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);
    const [markingAll, setMarkingAll] = useState(false);
    // Monotonic request id. Reads and tab/page switches both fire fetches, and
    // responses can land out of order — an older unreadOnly payload arriving
    // last used to resurrect an already-read row, or overwrite the All tab with
    // unread-only data. Only the newest request is allowed to touch state.
    const requestSeq = useRef(0);

    const fetchNotifications = useCallback(async () => {
        const seq = ++requestSeq.current;
        const isStale = () => seq !== requestSeq.current;
        let stepBack = false;
        try {
            const page = currentPage - 1; // API is 0-indexed
            let url = `/api/proxy/v1/notifications?page=${page}&size=${itemsPerPage}`;
            if (filter === "UNREAD") {
                url += "&unreadOnly=true";
            }
            const res = await fetch(url);
            if (isStale()) return;
            if (res.ok) {
                const data = await res.json();
                if (isStale()) return;
                if (Array.isArray(data)) {
                    // The API returns a plain page-sized list with no total
                    // count. Infer the total from the current offset, and
                    // assume at least one more item whenever this page came
                    // back full so the next-page control stays reachable.
                    if (data.length === 0 && page > 0) {
                        // Walked past the last page (e.g. it was exactly
                        // full) — step back to the previous one. Leave loading
                        // set: committing notifications=[] with loading=false
                        // paints "You're all caught up!" and unmounts the
                        // pagination controls for a frame before the previous
                        // page arrives.
                        stepBack = true;
                        setCurrentPage((p) => Math.max(1, p - 1));
                        return;
                    }
                    setNotifications(data);
                    const loadedSoFar = page * itemsPerPage + data.length;
                    setTotalItems(data.length === itemsPerPage ? loadedSoFar + 1 : loadedSoFar);
                } else {
                    setNotifications(data.content ?? []);
                    setTotalItems(data.totalElements ?? data.content?.length ?? 0);
                }
            }
        } catch { /* ignore */ } finally {
            if (!stepBack && !isStale()) setLoading(false);
        }
    }, [currentPage, itemsPerPage, filter]);

    useEffect(() => {
        if (!session?.user) return;
        setLoading(true);
        fetchNotifications();
    }, [session?.user, fetchNotifications]);

    const markAllRead = async () => {
        setMarkingAll(true);
        try {
            await fetch("/api/proxy/v1/notifications/read-all", { method: "PUT" });
            if (filter === "UNREAD") {
                setNotifications([]);
                setTotalItems(0);
                setCurrentPage(1);
            } else {
                setNotifications((prev) => prev.map((n) => ({ ...n, isRead: true })));
            }
        } catch { /* ignore */ } finally {
            setMarkingAll(false);
        }
    };

    const handleClick = async (n: Notification) => {
        // Mark as read
        if (!n.isRead) {
            try {
                await fetch(`/api/proxy/v1/notifications/${n.id}/read`, { method: "PUT" });
                if (filter === "UNREAD") {
                    // Drop the row locally for immediate feedback, then re-sync
                    // from the server. totalItems is an *inference* (see
                    // fetchNotifications): a full page adds a synthetic +1 so the
                    // next-page control stays reachable, and decrementing it here
                    // would cancel that +1 and hide the remaining unread pages.
                    // Refetching also backfills this page from the next one and
                    // steps back when the current page empties.
                    setNotifications((prev) => prev.filter((x) => x.id !== n.id));
                    // Not awaited when this click also navigates: the refetch is
                    // only needed if the user stays on the page, and awaiting it
                    // stalls the route change behind an extra round trip.
                    const resync = fetchNotifications();
                    if (!(n.referenceType && n.referenceId)) await resync;
                } else {
                    setNotifications((prev) =>
                        prev.map((x) => (x.id === n.id ? { ...x, isRead: true } : x))
                    );
                }
            } catch { /* ignore */ }
        }

        // Navigate based on referenceType
        if (n.referenceType && n.referenceId) {
            const routes: Record<string, string> = {
                TICKET: `/dashboard/tickets/${n.referenceId}`,
                LEASE: `/dashboard/leases/${n.referenceId}`,
                PAYMENT: `/dashboard/finance/payments`,
            };
            const route = routes[n.referenceType];
            if (route) {
                router.push(`/${locale}${route}`);
            }
        }
    };

    const unreadCount = notifications.filter((n) => !n.isRead).length;

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
                    <h1 className="text-lg font-bold text-foreground">Notifications</h1>
                    <p className="text-xs text-muted mt-0.5">
                        Stay up to date with activity across your properties
                    </p>
                </div>
                {unreadCount > 0 && (
                    <button
                        onClick={markAllRead}
                        disabled={markingAll}
                        className="flex items-center gap-2 border border-border text-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-input transition-all cursor-pointer disabled:opacity-50"
                    >
                        {markingAll ? (
                            <Loader2 size={14} className="animate-spin" />
                        ) : (
                            <CheckCheck size={14} />
                        )}
                        Mark All as Read
                    </button>
                )}
            </div>

            {/* Filter Tabs */}
            <div className="flex items-center gap-2 mb-6">
                <button
                    onClick={() => { setFilter("ALL"); setCurrentPage(1); }}
                    className={cn(
                        "px-4 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer",
                        filter === "ALL"
                            ? "bg-primary text-primary-foreground"
                            : "bg-surface border border-border text-muted hover:text-foreground hover:bg-input"
                    )}
                >
                    All
                </button>
                <button
                    onClick={() => { setFilter("UNREAD"); setCurrentPage(1); }}
                    className={cn(
                        "px-4 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer",
                        filter === "UNREAD"
                            ? "bg-primary text-primary-foreground"
                            : "bg-surface border border-border text-muted hover:text-foreground hover:bg-input"
                    )}
                >
                    Unread
                </button>
            </div>

            {/* Notification List */}
            <div className="bg-surface rounded-xl border border-border overflow-hidden">
                <div className="divide-y divide-border">
                    {notifications.map((n) => (
                        <div
                            key={n.id}
                            onClick={() => handleClick(n)}
                            className={cn(
                                "flex items-start gap-4 px-5 py-4 hover:bg-input/50 cursor-pointer transition-colors",
                                !n.isRead && "bg-primary/5"
                            )}
                        >
                            {/* Icon */}
                            <div className="mt-0.5 w-8 h-8 rounded-lg bg-input flex items-center justify-center shrink-0">
                                {getNotificationIcon(n.referenceType)}
                            </div>

                            {/* Content */}
                            <div className="flex-1 min-w-0">
                                <div className="flex items-start justify-between gap-3">
                                    <div className="min-w-0">
                                        <p className={cn(
                                            "text-sm text-foreground",
                                            !n.isRead ? "font-semibold" : "font-medium"
                                        )}>
                                            {n.title}
                                        </p>
                                        <p className="text-xs text-muted mt-0.5">{n.message}</p>
                                    </div>
                                    <div className="flex items-center gap-2 shrink-0">
                                        <span className="text-[10px] text-muted whitespace-nowrap">{timeAgo(n.createdAt)}</span>
                                        {!n.isRead && (
                                            <span className="w-2 h-2 rounded-full bg-primary shrink-0" />
                                        )}
                                    </div>
                                </div>
                                {n.referenceType && (
                                    <span className="inline-block mt-1.5 px-2 py-0.5 rounded-md text-[9px] font-semibold bg-input text-muted">
                                        {n.referenceType}
                                    </span>
                                )}
                            </div>
                        </div>
                    ))}
                </div>

                {notifications.length === 0 && (
                    <div className="text-center py-16 text-muted">
                        <Bell size={28} className="mx-auto mb-3 opacity-40" />
                        <p className="text-sm font-medium">No notifications</p>
                        <p className="text-xs mt-1 text-muted/60">
                            {filter === "UNREAD"
                                ? "You're all caught up!"
                                : "Notifications will appear here when there's activity."}
                        </p>
                    </div>
                )}

                {notifications.length > 0 && (
                    <div className="px-4 pb-3">
                        <Pagination
                            currentPage={currentPage}
                            totalItems={totalItems}
                            itemsPerPage={itemsPerPage}
                            onPageChange={setCurrentPage}
                            onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                        />
                    </div>
                )}
            </div>
        </div>
    );
}
