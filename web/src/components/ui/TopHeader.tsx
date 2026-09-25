"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import { useSession, signOut } from "next-auth/react";
import { Link } from "@/i18n/routing";
import { usePathname, useRouter } from "next/navigation";
import { useLocale } from "next-intl";
import { cn } from "@/lib/utils";
import { notificationText, timeAgo } from "@/lib/notificationText";
import { LogOut, User, ChevronDown, Bell, HelpCircle, Menu as MenuIcon } from "lucide-react";
import { getRoleLabel, getRoleLabelKey, type UserRole } from "@/lib/rbac";
import GlobalSearch from "./GlobalSearch";
import { useNavShell } from "@/components/nav/NavShellContext";
import { activeNav, buildNav } from "@/lib/nav/navModel";
import { useLabel } from "@/lib/nav/useLabel";
import { useTenantFeatures } from "@/hooks/useTenantFeatures";

type Notification = {
    id: string;
    title: string;
    message: string;
    messageKey?: string | null;
    params?: Record<string, string> | null;
    type: string;
    referenceType: string | null;
    referenceId: string | null;
    isRead: boolean;
    createdAt: string;
};

export function TopHeader() {
    const tRoles = useTranslations("Roles");
    const tNotifications = useTranslations("Notifications");
    const tNav = useTranslations("Navigation");
    const tMeetings = useTranslations("Meetings");
    // t.has guards a role the catalogue does not know; getRoleLabel is the
    // English fallback rather than letting next-intl throw.
    const roleLabel = (role: string) =>
        tRoles.has(getRoleLabelKey(role)) ? tRoles(getRoleLabelKey(role)) : getRoleLabel(role);
    const { data: session } = useSession();
    const pathname = usePathname();
    const locale = useLocale();
    const router = useRouter();
    const userRole = session?.user?.role as UserRole | undefined;
    // Phone drawer + breadcrumb ("Leasing › Tenancy Contracts") from the same
    // nav model the rail renders, so the two can never disagree.
    const { setDrawerOpen } = useNavShell();
    const label = useLabel();
    const { isEnabled, tenantSlug } = useTenantFeatures();
    const rail = buildNav({ role: userRole, isEnabled, tenantSlug, booksLive: true });
    const here = activeNav(pathname, rail);
    const hereSection = rail.find(s => s.id === here.section);
    const hereItem = hereSection?.groups.flatMap(g => g.items).find(i => i.id === here.item);
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
        <header className="h-[60px] px-4 md:px-7 flex items-center gap-4 border-b border-border bg-surface shrink-0 z-30">
            <div className="flex items-center relative w-full justify-between gap-4">
                <div className="flex items-center gap-3 min-w-0">
                    <button type="button" onClick={() => setDrawerOpen(true)} aria-label={tNav("openMenu")} data-testid="header-menu"
                        className="md:hidden w-9 h-9 shrink-0 flex items-center justify-center border border-border rounded-[var(--radius)] bg-surface cursor-pointer">
                        <MenuIcon size={18} />
                    </button>
                    {hereSection && (
                        <nav aria-label={tNav("breadcrumb")} data-testid="header-breadcrumb" className="hidden lg:flex items-center gap-1.5 text-[13px] text-[var(--ink-500)] whitespace-nowrap">
                            <span>{label(hereSection.label)}</span>
                            {hereItem && <><span aria-hidden className="rtl:-scale-x-100">›</span><span className="font-semibold text-foreground">{label(hereItem.label)}</span></>}
                        </nav>
                    )}
                    <GlobalSearch role={userRole} locale={locale} />
                </div>

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

                    {/* Help (moved here from the sidebar) */}
                    <Link href="/dashboard/help" aria-label={tNav("helpAndGuides")} data-tour="header-help" data-testid="header-help"
                        className="w-9 h-9 shrink-0 flex items-center justify-center border border-border rounded-[var(--radius)] bg-surface text-[var(--ink-600)] hover:text-foreground hover:bg-[var(--sand-100)] transition-colors">
                        <HelpCircle size={18} />
                    </Link>

                    {/* Notification Bell */}
                    {session?.user && (
                        <div className="relative">
                            <button onClick={toggleDropdown} data-testid="header-notifications" className="relative w-9 h-9 flex items-center justify-center border border-border rounded-[var(--radius)] bg-surface text-[var(--ink-600)] hover:text-foreground hover:bg-[var(--sand-100)] transition-colors cursor-pointer">
                                <Bell size={18} />
                                {unreadCount > 0 && (
                                    <span className="absolute -top-0.5 -end-0.5 w-4 h-4 bg-error text-white text-[9px] font-bold rounded-full flex items-center justify-center">
                                        {unreadCount > 9 ? "9+" : unreadCount}
                                    </span>
                                )}
                            </button>
                            {showDropdown && (
                                <>
                                    <div className="fixed inset-0 z-40" onClick={() => setShowDropdown(false)} />
                                    <div className="absolute end-0 top-full mt-2 w-80 bg-surface rounded-xl shadow-xl border border-border z-50 overflow-hidden">
                                        <div className="px-4 py-3 border-b border-border flex items-center justify-between">
                                            <h3 className="text-xs font-semibold text-foreground">{tNotifications("title")}</h3>
                                            {unreadCount > 0 && (
                                                <button onClick={markAllRead} className="text-[10px] text-primary font-semibold cursor-pointer">{tNotifications("markAllRead")}</button>
                                            )}
                                        </div>
                                        <div className="max-h-80 overflow-y-auto divide-y divide-border">
                                            {notifications.map((n) => {
                                                const text = notificationText(n, tNotifications, locale, tMeetings);
                                                return (
                                                <div key={n.id} onClick={() => handleNotificationClick(n)} className={cn("px-4 py-3 hover:bg-input/50 cursor-pointer transition-colors", !n.isRead && "bg-primary/5")}>
                                                    <p className="text-xs font-medium text-foreground">{text.title}</p>
                                                    <p className="text-[10px] text-muted mt-0.5 line-clamp-1">{text.body}</p>
                                                    <p className="text-[9px] text-muted mt-1">{timeAgo(n.createdAt, tNotifications, locale)}</p>
                                                </div>
                                                );
                                            })}
                                            {notifications.length === 0 && (
                                                <div className="px-4 py-8 text-center text-muted">
                                                    <Bell size={20} className="mx-auto mb-2 opacity-40" />
                                                    <p className="text-xs">{tNotifications("empty")}</p>
                                                </div>
                                            )}
                                        </div>
                                        <Link href="/dashboard/notifications" onClick={() => setShowDropdown(false)} className="block px-4 py-2.5 text-center text-xs font-semibold text-primary border-t border-border hover:bg-input/50 transition-colors">
                                            {tNotifications("viewAll")}
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
                                data-testid="profile-menu"
                                aria-haspopup="menu"
                                aria-expanded={isProfileOpen}
                                onClick={() => setIsProfileOpen(!isProfileOpen)}
                                className="flex items-center gap-3 cursor-pointer hover:opacity-80 transition-opacity focus:outline-none focus:ring-2 focus:ring-primary/20 rounded-lg p-1 -m-1"
                            >
                                <div className="flex flex-col items-end">
                                    <span className="text-sm font-semibold text-foreground">{session.user.name || tNav("userFallback")}</span>
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
                                    <div className="absolute end-0 top-full mt-2 w-56 bg-surface rounded-xl shadow-xl border border-border z-50 overflow-hidden">
                                        {/* User Info */}
                                        <div className="px-4 py-3 border-b border-border">
                                            <p className="text-sm font-semibold text-foreground">{session.user.name || tNav("userFallback")}</p>
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
                                                {tNav("updateProfile")}
                                            </Link>

                                            {/* Divider */}
                                            <div className="h-px bg-border my-1 mx-2" />

                                            {/* Logout */}
                                            <button
                                                data-testid="logout"
                                                onClick={() => signOut()}
                                                className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-error hover:bg-error/5 transition-colors cursor-pointer"
                                            >
                                                <LogOut size={15} className="rtl:rotate-180" />
                                                {tNav("logout")}
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
