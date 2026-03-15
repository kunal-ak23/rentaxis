"use client";

import { useState, useRef } from "react";
import { useSession, signOut } from "next-auth/react";
import { TenantSwitcher } from "./TenantSwitcher";
import { Link } from "@/i18n/routing";
import { usePathname } from "next/navigation";
import { useLocale } from "next-intl";
import { cn } from "@/lib/utils";
import { LogOut, User, ChevronDown } from "lucide-react";
import { getRoleLabel, type UserRole } from "@/lib/rbac";

export function TopHeader() {
    const { data: session } = useSession();
    const pathname = usePathname();
    const locale = useLocale();
    const userRole = session?.user?.role as UserRole | undefined;
    const [isProfileOpen, setIsProfileOpen] = useState(false);
    const buttonRef = useRef<HTMLButtonElement>(null);

    return (
        <header className="shrink-0 z-30 w-full h-14 bg-surface/80 backdrop-blur-md border-b border-border flex items-center justify-between px-6">
            <div className="flex items-center relative w-full justify-between">
                {/* Left Side: Context Switcher */}
                <div className="flex items-center w-[300px]">
                    {session?.user && (
                        <div className="w-full">
                            <TenantSwitcher isCollapsed={false} />
                        </div>
                    )}
                </div>

                {/* Right Side */}
                <div className="flex items-center gap-3">
                    {/* Locale Switcher */}
                    <div className="flex items-center bg-input rounded-lg p-0.5 border border-border">
                        <Link
                            href={pathname.replace(new RegExp(`^/${locale}`), '') || '/'}
                            locale="en"
                            className={cn(
                                "px-3 py-1.5 rounded-md text-[11px] font-bold tracking-wider transition-all duration-200 cursor-pointer",
                                "focus:outline-none focus:ring-2 focus:ring-primary/30",
                                locale === 'en'
                                    ? 'bg-surface text-primary shadow-sm border border-border'
                                    : 'text-muted hover:text-foreground'
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
                                    : 'text-muted hover:text-foreground'
                            )}
                        >
                            AR
                        </Link>
                    </div>

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
                                        {userRole ? getRoleLabel(userRole) : ''}
                                    </span>
                                </div>
                                <div className="w-9 h-9 rounded-full bg-primary/10 text-primary flex items-center justify-center font-bold text-sm border border-primary/20">
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
