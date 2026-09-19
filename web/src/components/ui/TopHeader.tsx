"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import { useSession, signOut } from "next-auth/react";
import { Link } from "@/i18n/routing";
import { usePathname, useRouter } from "next/navigation";
import { useLocale } from "next-intl";
import { cn } from "@/lib/utils";
import { LogOut, User, ChevronDown, Bell } from "lucide-react";
import { getRoleLabel, getRoleLabelKey, type UserRole } from "@/lib/rbac";
import GlobalSearch from "./GlobalSearch";

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

export function TopHeader() {
    const tRoles = useTranslations("Roles");
    // t.has guards a role the catalogue does not know; getRoleLabel is the
    // English fallback rather than letting next-intl throw.
    const roleLabel = (role: string) =>
        tRoles.has(getRoleLabelKey(role)) ? tRoles(getRoleLabelKey(role)) : getRoleLabel(role);
    const { data: session } = useSession();
    const pathname = usePathname();
    const locale = useLocale();
    const router = useRouter();
    const userRole = session?.user?.role as UserRole | undefined;
    const [isProfileOpen, setIsProfileOpen] = useState(false);
    const buttonRef = useRef<HTMLButtonElement>(null);

    // Notification state
    const [unreadCount, setUnreadCount] = useState(0);
    const [notifications, setNotifications] = useState<Notification[]>([]);
    const [showDropdown, setShowDropdown] = useState(false);

    const fetchUnreadCount = useCallback(async () => {
        try {
            const res = await fetch("/api/proxy/v1/notifications/unread-count");
            if (res.ok) {
                const data = await res.json();
                setUnreadCount(typeof data === "number" ? data : data.count ?? 0);
            }
        } catch { /* ignore */ }
    }, []);

    const fetchRecentNotifications = useCallback(async () => {
        try {
            const res = await fetch("/api/proxy/v1/notifications?page=0&size=5");
            if (res.ok) {
                const data = await res.json();
                setNotifications(Array.isArray(data) ? data : data.content ?? []);
            }
        } catch { /* ignore */ }
    }, []);

    // Fetch unread count on mount + poll every 30s
    useEffect(() => {
        if (!session?.user) return;
        const initialFetch = window.setTimeout(() => {
            void fetchUnreadCount();
        }, 0);
        const interval = setInterval(fetchUnreadCount, 30000);
        return () => {
            window.clearTimeout(initialFetch);
            clearInterval(interval);
        };
    }, [session?.user, fetchUnreadCount]);

    const toggleDropdown = () => {
        const willOpen = !showDropdown;
        setShowDropdown(willOpen);
        if (willOpen) {
            void fetchRecentNotifications();
        }
    };

    const markAllRead = async () => {
        try {
            await fetch("/api/proxy/v1/notifications/read-all", { method: "PUT" });
            setUnreadCount(0);
            setNotifications((prev) => prev.map((n) => ({ ...n, isRead: true })));
        } catch { /* ignore */ }
    };

    const handleNotificationClick = async (n: Notification) => {
        // Mark as read
        if (!n.isRead) {
            try {
                await fetch(`/api/proxy/v1/notifications/${n.id}/read`, { method: "PUT" });
                setNotifications((prev) => prev.map((x) => x.id === n.id ? { ...x, isRead: true } : x));
                setUnreadCount((prev) => Math.max(0, prev - 1));
            } catch { /* ignore */ }
        }
        setShowDropdown(false);

        // Navigate based on referenceType
        if (n.referenceType && n.referenceId) {
            const routes: Record<string, string> = {
                TICKET: `/dashboard/tickets/${n.referenceId}`,
                LEASE: `/dashboard/leases/${n.referenceId}`,
                PAYMENT: `/dashboard/finance/cheques`,
            };
            const route = routes[n.referenceType];
            if (route) {
                router.push(`/${locale}${route}`);
            }
        }
    };

    return (
        <header className="h-[60px] px-7 flex items-center gap-4 border-b border-border bg-surface shrink-0 z-30">
            <div className="flex items-center relative w-full justify-between gap-4">
                <GlobalSearch role={userRole} locale={locale} />

                {/* Right Side */}
                <div className="flex items-center gap-3">{/* (locale, bell, profile) */}

                    {/* Locale Switcher */}
                    <div className="flex items-center bg-[var(--sand-100)] rounded-[var(--radius)] p-0.5 border border-border">
                        <Link
                            href={pathname.replace(new RegExp(`^/${locale}`), '') || '/'}
                            locale="en"
                            className={cn(
                                "px-3 py-1.5 rounded-md text-[11px] font-bold tracking-wider transition-all duration-200 cursor-pointer",
                                "focus:outline-none focus:ring-2 focus:ring-primary/30",
                                locale === 'en'
                                    ? 'bg-surface text-primary shadow-sm border border-border'
                                    : 'text-[var(--ink-500)] hover:text-foreground'
                            )}
                        >
                            EN
                        </Link>
                        <Link
                            href={pathname.replace(new RegExp(`^/${locale}`), '') || '/'}
                            locale="ar"
                            className={cn(
                                "px-3 py-1.5 rounded-md text-[11px] font-bold tracking-wider transition-all duration-200 cursor-pointer",
                                "focus:outline-none focus:ring-2 focus:ring-primary/30",
                                locale === 'ar'
                                    ? 'bg-surface text-primary shadow-sm border border-border'
                                    : 'text-[var(--ink-500)] hover:text-foreground'
                            )}
                        >
                            AR
                        </Link>
                    </div>

                    {/* Notification Bell */}
                    {session?.user && (
                        <div className="relative">
                            <button onClick={toggleDropdown} className="relative w-9 h-9 flex items-center justify-center border border-border rounded-[var(--radius)] bg-surface text-[var(--ink-600)] hover:text-foreground hover:bg-[var(--sand-100)] transition-colors cursor-pointer">
                                <Bell size={18} />
                                {unreadCount > 0 && (
                                    <span className="absolute -top-0.5 -right-0.5 w-4 h-4 bg-error text-white text-[9px] font-bold rounded-full flex items-center justify-center">
                                        {unreadCount > 9 ? "9+" : unreadCount}
                                    </span>
                                )}
                            </button>
                            {showDropdown && (
                                <>
                                    <div className="fixed inset-0 z-40" onClick={() => setShowDropdown(false)} />
                                    <div className="absolute right-0 top-full mt-2 w-80 bg-surface rounded-xl shadow-xl border border-border z-50 overflow-hidden">
                                        <div className="px-4 py-3 border-b border-border flex items-center justify-between">
                                            <h3 className="text-xs font-semibold text-foreground">Notifications</h3>
                                            {unreadCount > 0 && (
                                                <button onClick={markAllRead} className="text-[10px] text-primary font-semibold cursor-pointer">Mark all read</button>
                                            )}
                                        </div>
                                        <div className="max-h-80 overflow-y-auto divide-y divide-border">
                                            {notifications.map((n) => (
                                                <div key={n.id} onClick={() => handleNotificationClick(n)} className={cn("px-4 py-3 hover:bg-input/50 cursor-pointer transition-colors", !n.isRead && "bg-primary/5")}>
                                                    <p className="text-xs font-medium text-foreground">{n.title}</p>
                                                    <p className="text-[10px] text-muted mt-0.5 line-clamp-1">{n.message}</p>
                                                    <p className="text-[9px] text-muted mt-1">{timeAgo(n.createdAt)}</p>
                                                </div>
                                            ))}
                                            {notifications.length === 0 && (
                                                <div className="px-4 py-8 text-center text-muted">
                                                    <Bell size={20} className="mx-auto mb-2 opacity-40" />
                                                    <p className="text-xs">No notifications</p>
                                                </div>
                                            )}
                                        </div>
                                        <Link href="/dashboard/notifications" onClick={() => setShowDropdown(false)} className="block px-4 py-2.5 text-center text-xs font-semibold text-primary border-t border-border hover:bg-input/50 transition-colors">
                                            View All Notifications
                                        </Link>
                                    </div>
                                </>
                            )}
                        </div>
                    )}

                    {/* User Profile with Popover */}
                    {session?.user && (
                        <div className="relative pl-3 border-l border-border">
                            <button
                                ref={buttonRef}
                                onClick={() => setIsProfileOpen(!isProfileOpen)}
                                className="flex items-center gap-3 cursor-pointer hover:opacity-80 transition-opacity focus:outline-none focus:ring-2 focus:ring-primary/20 rounded-lg p-1 -m-1"
                            >
                                <div className="flex flex-col items-end">
                                    <span className="text-sm font-semibold text-foreground">{session.user.name || 'User'}</span>
                                    <span className="text-[10px] font-medium text-muted tracking-wider">
                                        {userRole ? roleLabel(userRole) : ''}
                                    </span>
                                </div>
                                <div className="w-9 h-9 rounded-full flex items-center justify-center font-bold text-sm border border-[var(--gold-400)]" style={{ background: 'var(--gold-500)', color: 'var(--ink-900)' }}>
                                    {session.user.name?.charAt(0) || 'U'}
                                </div>
                                <ChevronDown size={12} className={cn("text-muted transition-transform", isProfileOpen && "rotate-180")} />
                            </button>

                            {/* Popover Menu */}
                            {isProfileOpen && (
                                <>
                                    <div className="fixed inset-0 z-40" onClick={() => setIsProfileOpen(false)} />
                                    <div className="absolute right-0 top-full mt-2 w-56 bg-surface rounded-xl shadow-xl border border-border z-50 overflow-hidden">
                                        {/* User Info */}
                                        <div className="px-4 py-3 border-b border-border">
                                            <p className="text-sm font-semibold text-foreground">{session.user.name || 'User'}</p>
                                            <p className="text-xs text-muted truncate">{session.user.email || ''}</p>
                                        </div>

                                        <div className="p-1">
                                            {/* Update Profile */}
                                            <Link
                                                href="/dashboard/profile"
                                                onClick={() => setIsProfileOpen(false)}
                                                className="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-foreground hover:bg-input transition-colors cursor-pointer"
                                            >
                                                <User size={15} className="text-muted" />
                                                Update Profile
                                            </Link>

                                            {/* Divider */}
                                            <div className="h-px bg-border my-1 mx-2" />

                                            {/* Logout */}
                                            <button
                                                onClick={() => signOut()}
                                                className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-error hover:bg-error/5 transition-colors cursor-pointer"
                                            >
                                                <LogOut size={15} />
                                                Logout
                                            </button>
                                        </div>
                                    </div>
                                </>
                            )}
                        </div>
                    )}
                </div>
            </div>
        </header>
    );
}
