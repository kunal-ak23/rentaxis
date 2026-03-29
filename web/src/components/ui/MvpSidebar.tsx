"use client";

import { motion } from "framer-motion";
import {
    LayoutDashboard,
    Users,
    ChevronRight,
    Menu,
    ShieldCheck,
    BookOpen,
    Receipt,
    BarChart3,
    Home,
    FileText,
    Contact,
    CreditCard,
    Sliders,
    GitBranch,
    Landmark,
    UserCog,
    Wrench,
    HelpCircle,
} from 'lucide-react';
import Image from "next/image";
import { Link } from "@/i18n/routing";
import { useTranslations } from "next-intl";
import { usePathname } from "next/navigation";
import { useState, useEffect } from "react";
import { cn } from "@/lib/utils";
import { useSession } from "next-auth/react";
import { hasPermission, canConfigureGateway, type UserRole } from "@/lib/rbac";
import { SidebarTooltip } from "./SidebarTooltip";

const APP_VERSION = process.env.NEXT_PUBLIC_APP_VERSION || '0.6.0.dev';

export default function MvpSidebar() {
    const t = useTranslations("MasterData");
    const tPayments = useTranslations("Payments");
    const tOnlinePayments = useTranslations("OnlinePayments");
    const tDashboard = useTranslations("Dashboard");
    const tVendors = useTranslations("Vendors");
    const tBankAccounts = useTranslations("BankAccounts");
    const tStaff = useTranslations("Staff");
    const pathname = usePathname();
    const [isCollapsed, setIsCollapsed] = useState(() => {
        if (typeof window !== 'undefined') {
            return localStorage.getItem('sidebar_collapsed') === 'true';
        }
        return false;
    });
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    useEffect(() => {
        localStorage.setItem('sidebar_collapsed', String(isCollapsed));
    }, [isCollapsed]);

    // Build menu items based on role permissions
    const menuItems = [
        ...(hasPermission(userRole, 'canViewProperties')
            ? [
                { name: t("properties"), href: "/dashboard/properties", icon: LayoutDashboard, tourId: 'sidebar-properties' },
                { name: t("renters"), href: "/dashboard/renters", icon: Contact, tourId: 'sidebar-renters' },
                { name: t("leases"), href: "/dashboard/leases", icon: FileText, tourId: 'sidebar-leases' },
                { name: "Tickets", href: "/dashboard/tickets", icon: Wrench, tourId: 'sidebar-tickets' }
            ]
            : []),
        ...(hasPermission(userRole, 'canManageTenants')
            ? [{ name: "Tenants", href: "/superadmin/tenants", icon: ShieldCheck, tourId: 'sidebar-tenants' }]
            : []),
        ...(hasPermission(userRole, 'canManageUsers')
            ? [{ name: "Users", href: "/superadmin/users", icon: Users, tourId: 'sidebar-users' }]
            : []),
    ];

    const financeItems = hasPermission(userRole, 'canAccessFinance') ? [
        { name: t("chartOfAccounts"), href: "/dashboard/finance/accounts", icon: BookOpen, tourId: 'sidebar-accounts' },
        { name: t("transactions"), href: "/dashboard/finance/transactions", icon: Receipt, tourId: 'sidebar-transactions' },
        { name: t("reports"), href: "/dashboard/finance/reports", icon: BarChart3, tourId: 'sidebar-reports' },
        { name: tPayments("payments"), href: "/dashboard/finance/payments", icon: CreditCard, tourId: 'sidebar-payments' },
        { name: tVendors("title"), href: "/dashboard/finance/vendors", icon: Users },
        { name: tBankAccounts("title"), href: "/dashboard/finance/bank-accounts", icon: Landmark },
    ] : [];

    const hrItems = hasPermission(userRole, 'canAccessFinance') ? [
        { name: tStaff("title"), href: "/dashboard/staff", icon: UserCog },
    ] : [];

    const settingsItems = (userRole && canConfigureGateway(userRole)) ? [
        { name: "Account Mappings", href: "/dashboard/settings/account-mappings", icon: GitBranch },
        { name: tOnlinePayments("gatewayConfig"), href: "/dashboard/settings/gateway", icon: CreditCard },
        { name: tOnlinePayments("rentSettings"), href: "/dashboard/settings/rent-settings", icon: Sliders },
    ] : [];

    // Tenant user minimal items
    const tenantUserItems = userRole === 'TENANT_USER' ? [
        { name: "My Unit", href: "/dashboard/my-unit", icon: Home },
    ] : [];

    // Renter portal items
    const renterItems = hasPermission(userRole, 'canViewRenterPortal') ? [
        { name: "My Leases", href: "/dashboard/renter-portal", icon: FileText, tourId: 'sidebar-my-leases' },
        { name: tOnlinePayments("myPayments"), href: "/dashboard/renter-portal/payments", icon: CreditCard, tourId: 'sidebar-my-payments' },
        { name: "My Tickets", href: "/dashboard/tickets", icon: Wrench, tourId: 'sidebar-my-tickets' },
    ] : [];

    const allItems = menuItems.length > 0 ? menuItems : renterItems.length > 0 ? renterItems : tenantUserItems;

    const renderSection = (
        items: typeof menuItems,
        label: string,
        layoutIdPrefix: string,
        sectionDataTour?: string
    ) => (
        <>
            <div className={cn(
                "px-4 mt-7 mb-2.5 text-[9px] font-semibold uppercase tracking-[0.2em]",
                "text-sidebar-muted",
                isCollapsed && "hidden"
            )} {...(sectionDataTour ? { 'data-tour': sectionDataTour } : {})}>
                {label}
            </div>
            {items.map((item) => {
                const isActive = pathname.includes(item.href);
                const Icon = item.icon;
                return (
                    <SidebarTooltip key={item.href} label={item.name} enabled={isCollapsed}>
                        <Link
                            href={item.href}
                            data-tour={item.tourId}
                            className={cn(
                                "group flex items-center gap-3 px-3 py-2.5 rounded-lg transition-all duration-200 text-[13px] font-medium relative cursor-pointer",
                                "focus:outline-none focus:ring-2 focus:ring-accent/30",
                                isActive
                                    ? "bg-white/10 text-white"
                                    : "text-sidebar-muted hover:bg-white/5 hover:text-white",
                                isCollapsed && "justify-center"
                            )}
                        >
                            <Icon size={16} className={cn(
                                "shrink-0 transition-colors",
                                isActive ? "text-accent" : "group-hover:text-white/80"
                            )} />
                            {!isCollapsed && <span className="flex-1">{item.name}</span>}
                            {isActive && !isCollapsed && (
                                <motion.div
                                    layoutId={`sidebar-${layoutIdPrefix}-indicator`}
                                    className="absolute left-0 w-[3px] h-5 bg-accent rounded-r-full"
                                />
                            )}
                        </Link>
                    </SidebarTooltip>
                );
            })}
        </>
    );

    return (
        <aside
            className={cn(
                "relative bg-sidebar h-screen flex flex-col transition-all duration-300 ease-in-out z-40 sticky top-0",
                isCollapsed ? "w-[72px]" : "w-[260px]"
            )}
        >
            {/* Collapse Toggle */}
            <button
                onClick={() => setIsCollapsed(!isCollapsed)}
                aria-label={isCollapsed ? "Expand sidebar" : "Collapse sidebar"}
                className="absolute -right-3 top-12 bg-sidebar border border-white/10 rounded-full p-1.5 shadow-lg hover:bg-white/10 z-50 transition-all duration-200 active:scale-90 cursor-pointer focus:outline-none focus:ring-2 focus:ring-accent/30"
            >
                {isCollapsed
                    ? <ChevronRight size={10} className="text-white/70" />
                    : <Menu size={10} className="text-white/70" />
                }
            </button>

            {/* Logo */}
            <div className={cn("mb-2 px-5 mt-2 flex items-center", isCollapsed && "px-0 justify-center")}>
                <Link href="/" className="flex items-center gap-2 focus:outline-none focus:ring-2 focus:ring-accent/30 rounded-lg">
                    <Image
                        src="/logo.png"
                        alt="RentAxis"
                        width={isCollapsed ? 32 : 140}
                        height={isCollapsed ? 32 : 40}
                        className={cn(
                            "object-contain brightness-0 invert transition-all duration-300",
                            isCollapsed ? "w-8 h-8" : "w-[140px] h-[40px]"
                        )}
                        priority
                    />
                </Link>
            </div>

            <nav data-tour="sidebar-nav" className="flex-1 py-1 px-3 space-y-0.5 overflow-y-auto">
                {/* Dashboard Home */}
                <SidebarTooltip label={tDashboard("dashboard")} enabled={isCollapsed}>
                    <Link
                        href="/dashboard"
                        className={cn(
                            "group flex items-center gap-3 px-3 py-2.5 rounded-lg transition-all duration-200 text-[13px] font-medium relative mb-1 cursor-pointer",
                            "focus:outline-none focus:ring-2 focus:ring-accent/30",
                            pathname.endsWith("/dashboard") || pathname.endsWith("/dashboard/")
                                ? "bg-white/10 text-white"
                                : "text-sidebar-muted hover:bg-white/5 hover:text-white",
                            isCollapsed && "justify-center"
                        )}
                    >
                        <Home size={16} className={cn(
                            "shrink-0 transition-colors",
                            (pathname.endsWith("/dashboard") || pathname.endsWith("/dashboard/")) ? "text-accent" : "group-hover:text-white/80"
                        )} />
                        {!isCollapsed && <span className="flex-1">{tDashboard("dashboard")}</span>}
                        {(pathname.endsWith("/dashboard") || pathname.endsWith("/dashboard/")) && !isCollapsed && (
                            <motion.div
                                layoutId="sidebar-dashboard-indicator"
                                className="absolute left-0 w-[3px] h-5 bg-accent rounded-r-full"
                            />
                        )}
                    </Link>
                </SidebarTooltip>

                {allItems.length > 0 && renderSection(allItems, "Overview", "overview")}
                {financeItems.length > 0 && renderSection(financeItems, "Finance", "finance", "sidebar-finance")}
                {hrItems.length > 0 && renderSection(hrItems, "HR", "hr")}
                {settingsItems.length > 0 && renderSection(settingsItems, "Settings", "settings")}

                {/* Support Section */}
                <div className={cn(
                    "px-4 mt-7 mb-2.5 text-[9px] font-semibold uppercase tracking-[0.2em]",
                    "text-sidebar-muted",
                    isCollapsed && "hidden"
                )}>
                    Support
                </div>
                <SidebarTooltip label="Help & Guides" enabled={isCollapsed}>
                    <Link
                        href="/dashboard/help"
                        data-tour="sidebar-help"
                        className={cn(
                            "group flex items-center gap-3 px-3 py-2.5 rounded-lg transition-all duration-200 text-[13px] font-medium relative cursor-pointer",
                            "focus:outline-none focus:ring-2 focus:ring-accent/30",
                            pathname.includes("/dashboard/help")
                                ? "bg-white/10 text-white"
                                : "text-sidebar-muted hover:bg-white/5 hover:text-white",
                            isCollapsed && "justify-center"
                        )}
                    >
                        <HelpCircle size={16} className={cn(
                            "shrink-0 transition-colors",
                            pathname.includes("/dashboard/help") ? "text-accent" : "group-hover:text-white/80"
                        )} />
                        {!isCollapsed && <span className="flex-1">Help &amp; Guides</span>}
                        {pathname.includes("/dashboard/help") && !isCollapsed && (
                            <motion.div
                                layoutId="sidebar-help-indicator"
                                className="absolute left-0 w-[3px] h-5 bg-accent rounded-r-full"
                            />
                        )}
                    </Link>
                </SidebarTooltip>
            </nav>

            {/* Footer */}
            <div className="mt-auto px-3 pb-6">
                {!isCollapsed && (
                    <div className="px-3 text-[9px] text-white/20 font-medium tracking-wider text-center">
                        {APP_VERSION}
                    </div>
                )}
            </div>
        </aside>
    );
}
