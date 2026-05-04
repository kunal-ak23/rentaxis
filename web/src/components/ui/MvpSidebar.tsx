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
    AlertTriangle,
    Landmark,
    UserCog,
    Wrench,
    HelpCircle,
    Building2,
    CalendarDays,
} from 'lucide-react';
import Image from "next/image";
import { Link } from "@/i18n/routing";
import { useTranslations } from "next-intl";
import { usePathname } from "next/navigation";
import { useState, useEffect } from "react";
import { cn } from "@/lib/utils";
import { useSession } from "next-auth/react";
import { hasPermission, canConfigureGateway, canConfigureFines, type UserRole } from "@/lib/rbac";
import { useTenantFeatures } from "@/hooks/useTenantFeatures";
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
    const { isEnabled, tenantSlug } = useTenantFeatures();

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
                ...(isEnabled('LISTINGS') ? [{ name: "Listings", href: "/dashboard/listings", icon: Building2, tourId: 'sidebar-listings' }] : []),
                { name: "Tickets", href: "/dashboard/tickets", icon: Wrench, tourId: 'sidebar-tickets' },
                ...(isEnabled('MEETINGS') ? [{ name: "Meetings", href: "/dashboard/meetings", icon: CalendarDays, tourId: 'sidebar-meetings' }] : []),
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
        ...(canConfigureFines(userRole) ? [{ name: "Cheque-failure Fines", href: "/dashboard/settings/fines", icon: AlertTriangle }] : []),
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
        ...(isEnabled('LISTINGS') && tenantSlug ? [{ name: "Listings", href: `/marketplace/${tenantSlug}`, icon: Building2, tourId: 'sidebar-listings' }] : []),
        ...(isEnabled('MEETINGS') ? [{ name: "Meetings", href: "/dashboard/meetings", icon: CalendarDays, tourId: 'sidebar-meetings' }] : []),
    ] : [];

    const allItems = menuItems.length > 0 ? menuItems : renterItems.length > 0 ? renterItems : tenantUserItems;

    const renderSection = (
        items: { name: string; href: string; icon: React.ElementType; tourId?: string }[],
        label: string,
        layoutIdPrefix: string,
        sectionDataTour?: string
    ) => (
        <>
            <div className={cn(
                "px-2 mt-4 mb-1 text-[10.5px] font-semibold uppercase tracking-[0.08em]",
                "text-[--ink-500]",
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
                                "group flex items-center gap-2.5 px-2.5 py-2 rounded-[--radius-sm] transition-colors text-[13.5px] font-medium relative cursor-pointer",
                                "focus:outline-none focus:ring-2 focus:ring-[--gold-500]/30",
                                isActive
                                    ? "bg-[--sand-100] text-[--ink-900] font-semibold"
                                    : "text-[--ink-600] hover:bg-[--sand-100]",
                                isCollapsed && "justify-center"
                            )}
                        >
                            <Icon size={16} className={cn(
                                "shrink-0 transition-colors",
                                isActive ? "text-[--gold-500]" : "text-[--ink-500]"
                            )} />
                            {!isCollapsed && <span className="flex-1">{item.name}</span>}
                            {isActive && !isCollapsed && (
                                <motion.div
                                    layoutId={`sidebar-${layoutIdPrefix}-indicator`}
                                    className="absolute left-[-12px] top-1.5 bottom-1.5 w-0.5 bg-[--gold-500] rounded-r-sm"
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
                "relative h-screen flex flex-col border-r border-border bg-surface transition-all duration-200 z-40 sticky top-0",
                isCollapsed ? "w-[68px]" : "w-[244px]"
            )}
            style={{ flexShrink: 0 }}
        >
            {/* Collapse Toggle */}
            <button
                onClick={() => setIsCollapsed(!isCollapsed)}
                aria-label={isCollapsed ? "Expand sidebar" : "Collapse sidebar"}
                className="absolute -right-3 top-12 bg-surface border border-border rounded-full p-1.5 shadow-sm hover:bg-[--sand-100] z-50 transition-all duration-200 active:scale-90 cursor-pointer focus:outline-none focus:ring-2 focus:ring-[--gold-500]/30"
            >
                {isCollapsed
                    ? <ChevronRight size={10} className="text-[--ink-600]" />
                    : <Menu size={10} className="text-[--ink-600]" />
                }
            </button>

            {/* Logo */}
            <div className={cn("h-[60px] px-4 flex items-center border-b border-border", isCollapsed && "justify-center")}>
                <Link href="/" className="flex items-center gap-2 focus:outline-none focus:ring-2 focus:ring-[--gold-500]/30 rounded-lg">
                    <Image
                        src="/logo.png"
                        alt="RentAxis"
                        width={isCollapsed ? 32 : 140}
                        height={isCollapsed ? 32 : 40}
                        className={cn(
                            "object-contain transition-all duration-300",
                            isCollapsed ? "w-8 h-8" : "w-[140px] h-[40px]"
                        )}
                        priority
                    />
                </Link>
            </div>

            <nav data-tour="sidebar-nav" className="flex-1 px-3 py-3 space-y-0.5 overflow-y-auto thinscroll">
                {/* Dashboard Home */}
                <SidebarTooltip label={tDashboard("dashboard")} enabled={isCollapsed}>
                    <Link
                        href="/dashboard"
                        className={cn(
                            "group flex items-center gap-2.5 px-2.5 py-2 rounded-[--radius-sm] transition-colors text-[13.5px] font-medium relative mb-1 cursor-pointer",
                            "focus:outline-none focus:ring-2 focus:ring-[--gold-500]/30",
                            pathname.endsWith("/dashboard") || pathname.endsWith("/dashboard/")
                                ? "bg-[--sand-100] text-[--ink-900] font-semibold"
                                : "text-[--ink-600] hover:bg-[--sand-100]",
                            isCollapsed && "justify-center"
                        )}
                    >
                        <Home size={16} className={cn(
                            "shrink-0 transition-colors",
                            (pathname.endsWith("/dashboard") || pathname.endsWith("/dashboard/")) ? "text-[--gold-500]" : "text-[--ink-500]"
                        )} />
                        {!isCollapsed && <span className="flex-1">{tDashboard("dashboard")}</span>}
                        {(pathname.endsWith("/dashboard") || pathname.endsWith("/dashboard/")) && !isCollapsed && (
                            <motion.div
                                layoutId="sidebar-dashboard-indicator"
                                className="absolute left-[-12px] top-1.5 bottom-1.5 w-0.5 bg-[--gold-500] rounded-r-sm"
                            />
                        )}
                    </Link>
                </SidebarTooltip>

                {allItems.length > 0 && renderSection(allItems, "Workspace", "overview")}
                {financeItems.length > 0 && renderSection(financeItems, "Operations", "finance", "sidebar-finance")}
                {hrItems.length > 0 && renderSection(hrItems, "Operations", "hr")}
                {settingsItems.length > 0 && renderSection(settingsItems, "Operations", "settings")}

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
                            "group flex items-center gap-2.5 px-2.5 py-2 rounded-[--radius-sm] transition-colors text-[13.5px] font-medium relative cursor-pointer",
                            "focus:outline-none focus:ring-2 focus:ring-[--gold-500]/30",
                            pathname.includes("/dashboard/help")
                                ? "bg-[--sand-100] text-[--ink-900] font-semibold"
                                : "text-[--ink-600] hover:bg-[--sand-100]",
                            isCollapsed && "justify-center"
                        )}
                    >
                        <HelpCircle size={16} className={cn(
                            "shrink-0 transition-colors",
                            pathname.includes("/dashboard/help") ? "text-[--gold-500]" : "text-[--ink-500]"
                        )} />
                        {!isCollapsed && <span className="flex-1">Help &amp; Guides</span>}
                        {pathname.includes("/dashboard/help") && !isCollapsed && (
                            <motion.div
                                layoutId="sidebar-help-indicator"
                                className="absolute left-[-12px] top-1.5 bottom-1.5 w-0.5 bg-[--gold-500] rounded-r-sm"
                            />
                        )}
                    </Link>
                </SidebarTooltip>
            </nav>

            {/* Footer */}
            <div className="mt-auto border-t border-border p-3">
                {isCollapsed ? (
                    <div className="flex justify-center">
                        <div
                            className="w-8 h-8 rounded-full flex items-center justify-center text-[12px] font-semibold"
                            style={{ background: 'var(--ink-900)', color: 'var(--gold-500)' }}
                        >
                            RX
                        </div>
                    </div>
                ) : (
                    <div className="flex items-center justify-between gap-2 p-2 rounded-[--radius] border border-border bg-[--sand-100]">
                        <div className="flex items-center gap-2">
                            <div
                                className="w-8 h-8 rounded-full flex items-center justify-center text-[12px] font-semibold"
                                style={{ background: 'var(--ink-900)', color: 'var(--gold-500)' }}
                            >
                                RX
                            </div>
                            <div className="leading-tight">
                                <div className="text-[12px] font-semibold text-[--ink-900]">RentAxis</div>
                                <div className="text-[10px] text-[--ink-500]">{APP_VERSION}</div>
                            </div>
                        </div>
                        <ChevronRight size={14} className="text-[--ink-500]" />
                    </div>
                )}
            </div>
        </aside>
    );
}
