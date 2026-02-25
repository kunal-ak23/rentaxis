"use client";

import { motion } from "framer-motion";
import { TenantSwitcher } from './TenantSwitcher';
import {
    LayoutDashboard,
    Building2,
    Users,
    Settings,
    ChevronLeft,
    ChevronRight,
    LogOut,
    Menu,
    ShieldCheck
} from 'lucide-react';
import { Link } from "@/i18n/routing";
import { useTranslations, useLocale } from "next-intl";
import { usePathname } from "next/navigation";
import { useState } from "react";
import { cn } from "@/lib/utils";
import { signOut } from "next-auth/react";

export default function MvpSidebar() {
    const t = useTranslations("MasterData");
    const locale = useLocale();
    const pathname = usePathname();
    const [isCollapsed, setIsCollapsed] = useState(false);

    const menuItems = [
        { name: t("properties"), href: "/dashboard/properties", icon: LayoutDashboard },
        { name: "Tenants", href: "/superadmin/tenants", icon: ShieldCheck },
        { name: "Users", href: "/superadmin/users", icon: Users },
    ];

    return (
        <aside
            className={cn(
                "relative bg-white border-r min-h-screen flex flex-col transition-all duration-300 ease-in-out z-40",
                isCollapsed ? "w-16" : "w-64"
            )}
        >
            {/* Collapse Toggle - Subtle */}
            <button
                onClick={() => setIsCollapsed(!isCollapsed)}
                className="absolute -right-3 top-12 bg-white border border-gray-200 rounded-full p-1 shadow-sm hover:bg-gray-50 z-50 transition-transform active:scale-90"
            >
                {isCollapsed ? <ChevronRight size={10} /> : <div className="p-0.5"><Menu size={10} /></div>}
            </button>

            <div className={cn("mb-10 px-6 mt-10 flex items-center", isCollapsed && "px-0 justify-center")}>
                <Link href="/" className="flex items-center gap-2.5">
                    <div className="w-7 h-7 bg-primary rounded-lg flex items-center justify-center text-primary-foreground font-black text-[10px] shrink-0 shadow-sm shadow-primary/20">
                        R
                    </div>
                    {!isCollapsed && (
                        <span className="text-base font-black text-foreground tracking-tight">
                            RentAxis
                        </span>
                    )}
                </Link>
            </div>

            <nav className="flex-1 px-3 space-y-1">
                <div className={cn("px-3 mb-2 text-[9px] font-bold text-gray-400 uppercase tracking-[0.2em]", isCollapsed && "hidden")}>
                    Overview
                </div>
                {menuItems.map((item) => {
                    const isActive = pathname.includes(item.href);
                    const Icon = item.icon;
                    return (
                        <Link
                            key={item.href}
                            href={item.href}
                            className={cn(
                                "group flex items-center gap-3 px-3 py-2 rounded-lg transition-all duration-200 text-xs font-semibold relative",
                                isActive
                                    ? "bg-accent text-foreground"
                                    : "text-gray-400 hover:bg-gray-50 hover:text-foreground",
                                isCollapsed && "justify-center"
                            )}
                        >
                            <Icon size={16} className={cn("transition-transform", !isActive && "group-hover:scale-105")} />
                            {!isCollapsed && <span className="flex-1">{item.name}</span>}
                            {isActive && !isCollapsed && (
                                <motion.div
                                    layoutId="sidebar-indicator"
                                    className="absolute left-[-12px] w-1 h-4 bg-primary rounded-r-full"
                                />
                            )}
                        </Link>
                    );
                })}
            </nav>

            <div className="mt-auto px-3 pb-8 space-y-4">
                {!isCollapsed && (
                    <div className="px-3 text-[8px] text-gray-400 font-bold uppercase tracking-wider text-center">
                        Rel 0.4.2
                    </div>
                )}

                <button
                    onClick={() => signOut()}
                    className={cn(
                        "flex items-center gap-3 px-3 py-2.5 rounded-xl text-red-500 hover:bg-red-50 transition-all active:scale-95 border border-transparent hover:border-red-100",
                        isCollapsed ? "justify-center" : "w-full"
                    )}
                >
                    <LogOut size={16} />
                    {!isCollapsed && <span className="text-xs font-bold uppercase tracking-widest">Logout</span>}
                </button>
            </div>
        </aside>
    );
}
