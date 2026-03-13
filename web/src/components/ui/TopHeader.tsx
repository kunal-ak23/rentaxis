"use client";

import { useSession } from "next-auth/react";
import { TenantSwitcher } from "./TenantSwitcher";
import { Link } from "@/i18n/routing";
import { usePathname } from "next/navigation";
import { useLocale } from "next-intl";
import { cn } from "@/lib/utils";
import { Moon, Sun } from "lucide-react";
import { getRoleLabel, type UserRole } from "@/lib/rbac";

export function TopHeader() {
    const { data: session } = useSession();
    const pathname = usePathname();
    const locale = useLocale();
    const userRole = session?.user?.role as UserRole | undefined;

    return (
        <header className="relative w-full h-16 bg-white/40 backdrop-blur-md border-b border-gray-200/50 z-30 flex items-center justify-between px-6">
            <div className="flex items-center relative w-full justify-between">
                {/* Left Side: Context Switcher */}
                <div className="flex items-center w-[300px]">
                    {session?.user && (
                        <div className="w-full pt-6">
                            <TenantSwitcher isCollapsed={false} />
                        </div>
                    )}
                </div>

                {/* Right Side: Environment & Settings */}
                <div className="flex items-center space-x-4">

                    {/* Theme Toggle Placeholder */}
                    <button
                        aria-label="Toggle theme"
                        className="p-2 text-gray-500 hover:text-primary hover:bg-primary/5 rounded-full transition-all duration-200 hidden md:flex cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
                    >
                        <Moon size={18} />
                    </button>

                    {/* Locale Switcher */}
                    <div className="flex items-center bg-gray-100 rounded-lg p-1 border border-gray-200 shadow-sm">
                        <Link
                            href={pathname.replace(new RegExp(`^/${locale}`), '') || '/'}
                            locale="en"
                            className={cn(
                                "px-3 py-1 rounded-md text-xs font-black tracking-widest transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30",
                                locale === 'en' ? 'bg-white text-primary shadow-sm' : 'text-gray-400 hover:text-gray-600'
                            )}
                        >
                            EN
                        </Link>
                        <Link
                            href={pathname.replace(new RegExp(`^/${locale}`), '') || '/'}
                            locale="ar"
                            className={cn(
                                "px-3 py-1 rounded-md text-xs font-black tracking-widest transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30",
                                locale === 'ar' ? 'bg-white text-primary shadow-sm' : 'text-gray-400 hover:text-gray-600'
                            )}
                        >
                            AR
                        </Link>
                    </div>

                    {/* User Profile Mini */}
                    {session?.user && (
                        <div className="flex items-center gap-2 pl-4 border-l border-gray-200">
                            <div className="flex flex-col items-end">
                                <span className="text-sm font-bold text-gray-800">{session.user.name || 'User'}</span>
                                <span className="text-[10px] font-medium text-gray-400 uppercase tracking-widest">
                                    {userRole ? getRoleLabel(userRole) : ''}
                                </span>
                            </div>
                            <div className="w-9 h-9 rounded-full bg-primary/10 text-primary flex items-center justify-center font-bold shadow-sm shadow-primary/20 border border-primary/20">
                                {session.user.name?.charAt(0) || 'U'}
                            </div>
                        </div>
                    )}
                </div>
            </div>
        </header>
    );
}
