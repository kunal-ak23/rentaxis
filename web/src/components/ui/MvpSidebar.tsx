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
    ReceiptText,
    Layers,
    GitCompare,
    Home,
    FileText,
    Contact,
    CreditCard,
    Sliders,
    AlertTriangle,
    Landmark,
    UserCog,
    Wrench,
    HelpCircle,
    Building2,
    CalendarDays,
    ScanLine,
    CalendarCheck,
    Megaphone,
    BookUser,
    Scale,
    NotebookText,
    LayoutTemplate,
    CalendarClock,
    Banknote,
    RefreshCcw,
    PieChart,
    FileSpreadsheet,
    Send,
    ScrollText,
    Tags,
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
import { TenantSwitcher } from "./TenantSwitcher";

const APP_VERSION = process.env.NEXT_PUBLIC_APP_VERSION || '0.6.0.dev';

/**
 * The nav item to highlight for a pathname: the longest href that equals the
 * path or is a whole-segment prefix of it. A substring test lit "My Leases"
 * (/dashboard/renter-portal) on every renter-portal sub-page, alongside the
 * payments or penalties item the renter was actually on.
 */
export function activeNavHref(pathname: string, hrefs: string[]): string | null {
    const path = pathname.replace(/^\/(en|ar)(?=\/|$)/, "") || "/";
    let best: string | null = null;
    for (const href of hrefs) {
        if ((path === href || path.startsWith(`${href}/`)) && (best === null || href.length > best.length)) {
            best = href;
        }
    }
    return best;
}

export default function MvpSidebar() {
    const t = useTranslations("MasterData");
    const tCheques = useTranslations("Cheques");
    const tOnlinePayments = useTranslations("OnlinePayments");
    const tDashboard = useTranslations("Dashboard");
    const tVendors = useTranslations("Vendors");
    const tBankAccounts = useTranslations("BankAccounts");
    const tBankRec = useTranslations("BankRec");
    const tStaff = useTranslations("Staff");
    const tGatePass = useTranslations("GatePass");
    const tBookings = useTranslations("Bookings");
    const tPromotions = useTranslations("Promotions");
    const tLedger = useTranslations("Ledger");
    const tRecognition = useTranslations("Recognition");
    const tVouchers = useTranslations("Vouchers");
    const tCutover = useTranslations("Cutover");
    // Nav labels that were previously plain English literals. They render on
    // every dashboard page for every role, so in Arabic the whole primary
    // navigation stayed English inside an RTL layout.
    const tNav = useTranslations("Navigation");
    const tReports = useTranslations("PropertyReports");
    const tPayables = useTranslations("Payables");
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
        // Leases sits on its own gate rather than inside canViewProperties.
        // An ACCOUNTANT is the role that posts, amends and extends a contract,
        // and the properties gate does not admit them — so the one role that
        // owns posting had no link to the screen it posts from.
        ...(hasPermission(userRole, 'canViewLeases') && !hasPermission(userRole, 'canViewProperties')
            ? [{ name: t("leases"), href: "/dashboard/leases", icon: FileText, tourId: 'sidebar-leases' }]
            : []),
        ...(hasPermission(userRole, 'canViewProperties')
            ? [
                { name: t("properties"), href: "/dashboard/properties", icon: LayoutDashboard, tourId: 'sidebar-properties' },
                { name: t("renters"), href: "/dashboard/renters", icon: Contact, tourId: 'sidebar-renters' },
                { name: t("leases"), href: "/dashboard/leases", icon: FileText, tourId: 'sidebar-leases' },
                ...(isEnabled('LISTINGS') ? [{ name: tNav("listings"), href: "/dashboard/listings", icon: Building2, tourId: 'sidebar-listings' }] : []),
                { name: tNav("tickets"), href: "/dashboard/tickets", icon: Wrench, tourId: 'sidebar-tickets' },
                ...(isEnabled('MEETINGS') ? [{ name: tNav("meetings"), href: "/dashboard/meetings", icon: CalendarDays, tourId: 'sidebar-meetings' }] : []),
                // Still not wrapped in isEnabled('GATEPASS'), and the reason has changed
                // again: a toggle now exists — the superadmin tenants page has a features
                // drawer wired to PUT /api/admin/tenants/{id}/features/{feature}, so the
                // old "setEnabled() has no caller" premise no longer holds. What does
                // still hold: GATEPASS defaults to false and no existing tenant has been
                // opted in, so wrapping this today would hide the page for every tenant
                // until a superadmin flips each one — a rollout decision covering all
                // five flags, not a gate-pass one. Until that rollout happens, role stays
                // the boundary; the report's @PreAuthorize is what actually gates the data.
                ...(hasPermission(userRole, 'canViewGatePassReport')
                    ? [{ name: tGatePass("navLabel"), href: "/dashboard/gatepass", icon: ScanLine, tourId: 'sidebar-gatepass' }]
                    : []),
                ...(hasPermission(userRole, 'canManageFacilities')
                    ? [{ name: tBookings("navLabel"), href: "/dashboard/bookings", icon: CalendarCheck, tourId: 'sidebar-bookings' }]
                    : []),
                ...(hasPermission(userRole, 'canManagePromotions')
                    ? [{ name: tPromotions("navLabel"), href: "/dashboard/promotions", icon: Megaphone, tourId: 'sidebar-promotions' }]
                    : []),
            ]
            : []),
        ...(hasPermission(userRole, 'canManageTenants')
            ? [{ name: tNav("tenants"), href: "/superadmin/tenants", icon: ShieldCheck, tourId: 'sidebar-tenants' }]
            : []),
        ...(hasPermission(userRole, 'canManageUsers')
            ? [{ name: tNav("users"), href: "/superadmin/users", icon: Users, tourId: 'sidebar-users' }]
            : []),
    ];

    // The accounting-v2 ledger replaced the old transactions and reports pages:
    // journal vouchers are where entries are read and posted, and the three
    // ledger reports are what the old reports page only gestured at.
    //
    // Three gates, not one: the ledger pages admit ACCOUNTANT; the cheque
    // register's own controller (ChequeController's STAFF group) admits
    // ACCOUNTANT *and* PROPERTY_MANAGER, unlike the old Payments link, which sat
    // behind canAccessFinanceOps (SA/TA only) because PaymentScheduleController
    // refused an accountant. Vendors now has its own gate (canManageVendors,
    // finance-ops audit S1) because VendorController admits ACCOUNTANT; Bank
    // Accounts stays behind canAccessFinanceOps — its controller still stops at
    // TENANT_ADMIN.
    const financeItems = [
        ...(hasPermission(userRole, 'canAccessFinance') ? [
            { name: t("chartOfAccounts"), href: "/dashboard/finance/accounts", icon: BookOpen, tourId: 'sidebar-accounts' },
            { name: tLedger("journals"), href: "/dashboard/finance/journals", icon: Receipt, tourId: 'sidebar-journals' },
            { name: tLedger("generalLedger"), href: "/dashboard/finance/general-ledger", icon: NotebookText, tourId: 'sidebar-general-ledger' },
            { name: tLedger("tenantLedger"), href: "/dashboard/finance/tenant-ledger", icon: BookUser, tourId: 'sidebar-tenant-ledger' },
            { name: tLedger("trialBalance"), href: "/dashboard/finance/trial-balance", icon: Scale, tourId: 'sidebar-trial-balance' },
        ] : []),
        // Vouchers sit in this branch, not in canAccessFinanceOps, because
        // VoucherController's single class-level @PreAuthorize is
        // hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','ACCOUNTANT') — the same set
        // canAccessFinance carries, and NOT the SA/TA-only set the operational
        // pages below use. Its own gate all the same: canManageVouchers mirrors
        // that one annotation, so a future widening of the ledger's roles is not
        // silently a widening of the voucher screens'.
        ...(hasPermission(userRole, 'canManageVouchers') ? [
            { name: tVouchers("vouchers"), href: "/dashboard/finance/vouchers", icon: ReceiptText, tourId: 'sidebar-vouchers' },
        ] : []),
        // The cut-over. ImportBatchController is SA/TA/ACCOUNTANT — the same set
        // as canAccessFinance, so it belongs in this branch and not in the
        // SA/TA-only one below; its own key all the same, mirroring that one
        // annotation. The cut-over template download ON the page is the same set
        // (PortfolioImportController.CUTOVER_ROLES admits ACCOUNTANT) and is
        // gated there, not here.
        ...(hasPermission(userRole, 'canManageImportBatches') ? [
            { name: tCutover("importBatches"), href: "/dashboard/finance/import-batches", icon: Layers, tourId: 'sidebar-import-batches' },
        ] : []),
        // OpeningBalanceController is SA/TA/ACCOUNTANT too, on its own annotation
        // (OpeningBalanceController.java:57) — hence its own key rather than a
        // reuse of the batches one. Both pages sit behind it: the reconciliation
        // report is the same controller.
        ...(hasPermission(userRole, 'canManageOpeningBalances') ? [
            { name: tCutover("openingBalances"), href: "/dashboard/finance/opening-balances", icon: Scale, tourId: 'sidebar-opening-balances' },
            { name: tCutover("reconciliation"), href: "/dashboard/finance/reconciliation", icon: GitCompare, tourId: 'sidebar-reconciliation' },
        ] : []),
        // The month-end close. Its own gate rather than canAccessFinance because
        // the two endpoints behind the page (RecognitionController's
        // /finance/recognition/pending and /run) are SA/TA/ACCOUNTANT — the same
        // set today, but the rule the link mirrors is that one, not the ledger's.
        ...(hasPermission(userRole, 'canRunRecognition') ? [
            { name: tRecognition("title"), href: "/dashboard/finance/recognition", icon: CalendarCheck, tourId: 'sidebar-recognition' },
        ] : []),
        ...(hasPermission(userRole, 'canManageCheques') ? [
            { name: tCheques("register"), href: "/dashboard/finance/cheques", icon: CreditCard, tourId: 'sidebar-cheques-register' },
            { name: tCheques("collection"), href: "/dashboard/finance/cheques/collection", icon: Banknote, tourId: 'sidebar-cheques-collection' },
            { name: tCheques("returnReplace"), href: "/dashboard/finance/cheques/return-replace", icon: RefreshCcw, tourId: 'sidebar-cheques-return-replace' },
            { name: tCheques("postDated"), href: "/dashboard/finance/cheques/post-dated", icon: CalendarClock, tourId: 'sidebar-cheques-post-dated' },
        ] : []),
        // PenaltyAssessmentController#list/#propose — same role set as canManageCheques
        // minus nothing: SA/TA/ACCOUNTANT/PM all see the queue, deciding is gated
        // inside it (canApprovePenalties).
        ...(hasPermission(userRole, 'canProposePenalties') ? [
            { name: tCheques("penalties"), href: "/dashboard/finance/penalties", icon: AlertTriangle, tourId: 'sidebar-penalties' },
        ] : []),
        ...(hasPermission(userRole, 'canManageVendors') ? [
            { name: tVendors("title"), href: "/dashboard/finance/vendors", icon: Users },
        ] : []),
        ...(hasPermission(userRole, 'canAccessFinanceOps') ? [
            { name: tBankAccounts("title"), href: "/dashboard/finance/bank-accounts", icon: Landmark },
        ] : []),
    ];

    // Finance → Reports (finance-ops spec §1). PropertyReportController admits
    // PROPERTY_MANAGER read-only, narrowed to assigned properties server-side,
    // so this is its own group rather than part of the canAccessFinance ledger set.
    const reportItems = [
        ...(hasPermission(userRole, 'canViewPropertyReports') ? [
            { name: tReports("propertyPl"), href: "/dashboard/finance/reports/property-pl", icon: PieChart, tourId: 'sidebar-property-pl' },
            { name: tReports("propertyStatement"), href: "/dashboard/finance/reports/property-statement", icon: FileSpreadsheet, tourId: 'sidebar-property-statement' },
            { name: tReports("balanceSheet"), href: "/dashboard/finance/reports/balance-sheet", icon: Scale, tourId: 'sidebar-balance-sheet' },
        ] : []),
        // F14-10 / #55: tenant-wide reports, finance roles only.
        ...(hasPermission(userRole, 'canViewCompanyReports') ? [
            { name: tReports("companyPl"), href: "/dashboard/finance/reports/company-pl", icon: Building2, tourId: 'sidebar-company-pl' },
        ] : []),
        ...(hasPermission(userRole, 'canViewVatReturn') ? [
            { name: tReports("vatReturn"), href: "/dashboard/finance/reports/vat-return", icon: Receipt, tourId: 'sidebar-vat-return' },
        ] : []),
        // Finance → Payables (finance-ops spec §2). Aging admits a property
        // manager (property-filtered, server-side); opening items do not.
        ...(hasPermission(userRole, 'canViewPayablesAging') ? [
            { name: tPayables("aging"), href: "/dashboard/finance/payables/aging", icon: PieChart, tourId: 'sidebar-payables-aging' },
        ] : []),
        ...(hasPermission(userRole, 'canManagePayables') ? [
            { name: tPayables("openingItems"), href: "/dashboard/finance/payables/opening-items", icon: FileSpreadsheet, tourId: 'sidebar-payables-opening' },
            // PR 3b: PaymentRunController and IssuedChequeController, finance roles only.
            { name: tPayables("paymentRuns"), href: "/dashboard/finance/payables/payment-runs", icon: Send, tourId: 'sidebar-payables-runs' },
            { name: tPayables("issuedCheques"), href: "/dashboard/finance/payables/issued-cheques", icon: ScrollText, tourId: 'sidebar-payables-issued-cheques' },
        ] : []),
        // Finance → Bank reconciliation (finance-ops spec §3): BankReconciliationController,
        // finance roles only. A property manager keeps the cheque actions on the register.
        ...(hasPermission(userRole, 'canReconcileBank') ? [
            { name: tBankRec("sidebar"), href: "/dashboard/finance/bank-reconciliation", icon: Landmark, tourId: 'sidebar-bank-reconciliation' },
        ] : []),
    ];

    // StaffController is hasAnyRole('SUPER_ADMIN','TENANT_ADMIN') — same gate.
    const hrItems = hasPermission(userRole, 'canAccessFinanceOps') ? [
        { name: tStaff("title"), href: "/dashboard/staff", icon: UserCog },
    ] : [];

    // The accounting setup pages are gated by canManageAccountSetup, which admits
    // ACCOUNTANT — a role canConfigureGateway deliberately excludes. Keeping the
    // whole section behind the gateway check would have hidden the two pages from
    // exactly the role that owns them, so each group now carries its own gate.
    // Account mappings moved into the property's own Accounts tab and the
    // tenant-wide template, so the standalone page is gone.
    const settingsItems = [
        ...(hasPermission(userRole, 'canManageAccountSetup') ? [
            { name: tLedger("accountTemplate"), href: "/dashboard/settings/account-template", icon: LayoutTemplate },
            { name: tLedger("fiscal"), href: "/dashboard/settings/fiscal", icon: CalendarClock },
            { name: tLedger("chargeTypes"), href: "/dashboard/settings/charge-types", icon: Tags },
        ] : []),
        ...(userRole && canConfigureGateway(userRole) ? [
            { name: tOnlinePayments("gatewayConfig"), href: "/dashboard/settings/gateway", icon: CreditCard },
            { name: tOnlinePayments("rentSettings"), href: "/dashboard/settings/rent-settings", icon: Sliders },
        ] : []),
        ...(userRole && canConfigureFines(userRole) ? [{ name: tNav("chequeFailureFines"), href: "/dashboard/settings/fines", icon: AlertTriangle }] : []),
    ];

    // Tenant user minimal items
    const tenantUserItems = userRole === 'TENANT_USER' ? [
        { name: tNav("myUnit"), href: "/dashboard/my-unit", icon: Home },
    ] : [];

    // Renter portal items
    const renterItems = hasPermission(userRole, 'canViewRenterPortal') ? [
        { name: tNav("myLeases"), href: "/dashboard/renter-portal", icon: FileText, tourId: 'sidebar-my-leases' },
        { name: tOnlinePayments("myPayments"), href: "/dashboard/renter-portal/payments", icon: CreditCard, tourId: 'sidebar-my-payments' },
        // The penalties page explains an approved fine; without a link a renter
        // with one had no way to find it (gap #39).
        { name: tNav("myPenalties"), href: "/dashboard/renter-portal/penalties", icon: AlertTriangle, tourId: 'sidebar-my-penalties' },
        { name: tNav("myTickets"), href: "/dashboard/tickets", icon: Wrench, tourId: 'sidebar-my-tickets' },
        ...(isEnabled('LISTINGS') && tenantSlug ? [{ name: tNav("listings"), href: `/marketplace/${tenantSlug}`, icon: Building2, tourId: 'sidebar-listings' }] : []),
        ...(isEnabled('MEETINGS') ? [{ name: tNav("meetings"), href: "/dashboard/meetings", icon: CalendarDays, tourId: 'sidebar-meetings' }] : []),
    ] : [];

    const allItems = menuItems.length > 0 ? menuItems : renterItems.length > 0 ? renterItems : tenantUserItems;

    // Every item the sidebar renders, so the longest match wins across sections.
    const navHrefs = () => [...allItems, ...financeItems, ...reportItems, ...hrItems, ...settingsItems].map((i) => i.href);

    const renderSection = (
        items: { name: string; href: string; icon: React.ElementType; tourId?: string }[],
        label: string,
        layoutIdPrefix: string,
        sectionDataTour?: string
    ) => (
        <>
            <div className={cn(
                "px-2 mt-4 mb-1 text-[10.5px] font-semibold uppercase tracking-[0.08em]",
                "text-[var(--ink-500)]",
                isCollapsed && "hidden"
            )} {...(sectionDataTour ? { 'data-tour': sectionDataTour } : {})}>
                {label}
            </div>
            {items.map((item) => {
                const isActive = activeNavHref(pathname, navHrefs()) === item.href;
                const Icon = item.icon;
                return (
                    <SidebarTooltip key={item.href} label={item.name} enabled={isCollapsed}>
                        <Link
                            href={item.href}
                            data-tour={item.tourId}
                            aria-current={isActive ? "page" : undefined}
                            className={cn(
                                "group flex items-center gap-2.5 px-2.5 py-2 rounded-[var(--radius-sm)] transition-colors text-[13.5px] font-medium relative cursor-pointer",
                                "focus:outline-none focus:ring-2 focus:ring-[var(--gold-500)]/30",
                                isActive
                                    ? "bg-[var(--sand-100)] text-[var(--ink-900)] font-semibold"
                                    : "text-[var(--ink-600)] hover:bg-[var(--sand-100)]",
                                isCollapsed && "justify-center"
                            )}
                        >
                            <Icon size={16} className={cn(
                                "shrink-0 transition-colors",
                                isActive ? "text-[var(--gold-500)]" : "text-[var(--ink-500)]"
                            )} />
                            {!isCollapsed && <span className="flex-1">{item.name}</span>}
                            {isActive && !isCollapsed && (
                                <motion.div
                                    layoutId={`sidebar-${layoutIdPrefix}-indicator`}
                                    className="absolute left-[-12px] top-1.5 bottom-1.5 w-0.5 bg-[var(--gold-500)] rounded-r-sm"
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
                className="absolute -right-3 top-12 bg-surface border border-border rounded-full p-1.5 shadow-sm hover:bg-[var(--sand-100)] z-50 transition-all duration-200 active:scale-90 cursor-pointer focus:outline-none focus:ring-2 focus:ring-[var(--gold-500)]/30"
            >
                {isCollapsed
                    ? <ChevronRight size={10} className="text-[var(--ink-600)]" />
                    : <Menu size={10} className="text-[var(--ink-600)]" />
                }
            </button>

            {/* Logo */}
            <div className={cn("h-[60px] px-4 flex items-center border-b border-border", isCollapsed && "justify-center")}>
                <Link href="/" className="flex items-center gap-2 focus:outline-none focus:ring-2 focus:ring-[var(--gold-500)]/30 rounded-lg">
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
                            "group flex items-center gap-2.5 px-2.5 py-2 rounded-[var(--radius-sm)] transition-colors text-[13.5px] font-medium relative mb-1 cursor-pointer",
                            "focus:outline-none focus:ring-2 focus:ring-[var(--gold-500)]/30",
                            pathname.endsWith("/dashboard") || pathname.endsWith("/dashboard/")
                                ? "bg-[var(--sand-100)] text-[var(--ink-900)] font-semibold"
                                : "text-[var(--ink-600)] hover:bg-[var(--sand-100)]",
                            isCollapsed && "justify-center"
                        )}
                    >
                        <Home size={16} className={cn(
                            "shrink-0 transition-colors",
                            (pathname.endsWith("/dashboard") || pathname.endsWith("/dashboard/")) ? "text-[var(--gold-500)]" : "text-[var(--ink-500)]"
                        )} />
                        {!isCollapsed && <span className="flex-1">{tDashboard("dashboard")}</span>}
                        {(pathname.endsWith("/dashboard") || pathname.endsWith("/dashboard/")) && !isCollapsed && (
                            <motion.div
                                layoutId="sidebar-dashboard-indicator"
                                className="absolute left-[-12px] top-1.5 bottom-1.5 w-0.5 bg-[var(--gold-500)] rounded-r-sm"
                            />
                        )}
                    </Link>
                </SidebarTooltip>

                {allItems.length > 0 && renderSection(allItems, tNav("sectionWorkspace"), "overview")}
                {financeItems.length > 0 && renderSection(financeItems, tNav("sectionOperations"), "finance", "sidebar-finance")}
                {reportItems.length > 0 && renderSection(reportItems, tNav("sectionReports"), "reports", "sidebar-reports")}
                {hrItems.length > 0 && renderSection(hrItems, tNav("sectionOperations"), "hr")}
                {settingsItems.length > 0 && renderSection(settingsItems, tNav("sectionOperations"), "settings")}

                {/* Support Section */}
                <div className={cn(
                    "px-4 mt-7 mb-2.5 text-[9px] font-semibold uppercase tracking-[0.2em]",
                    "text-sidebar-muted",
                    isCollapsed && "hidden"
                )}>
                    {tNav("sectionSupport")}
                </div>
                <SidebarTooltip label={tNav("helpAndGuides")} enabled={isCollapsed}>
                    <Link
                        href="/dashboard/help"
                        data-tour="sidebar-help"
                        className={cn(
                            "group flex items-center gap-2.5 px-2.5 py-2 rounded-[var(--radius-sm)] transition-colors text-[13.5px] font-medium relative cursor-pointer",
                            "focus:outline-none focus:ring-2 focus:ring-[var(--gold-500)]/30",
                            pathname.includes("/dashboard/help")
                                ? "bg-[var(--sand-100)] text-[var(--ink-900)] font-semibold"
                                : "text-[var(--ink-600)] hover:bg-[var(--sand-100)]",
                            isCollapsed && "justify-center"
                        )}
                    >
                        <HelpCircle size={16} className={cn(
                            "shrink-0 transition-colors",
                            pathname.includes("/dashboard/help") ? "text-[var(--gold-500)]" : "text-[var(--ink-500)]"
                        )} />
                        {!isCollapsed && <span className="flex-1">{tNav("helpAndGuides")}</span>}
                        {pathname.includes("/dashboard/help") && !isCollapsed && (
                            <motion.div
                                layoutId="sidebar-help-indicator"
                                className="absolute left-[-12px] top-1.5 bottom-1.5 w-0.5 bg-[var(--gold-500)] rounded-r-sm"
                            />
                        )}
                    </Link>
                </SidebarTooltip>
            </nav>

            {/* Footer — current org / tenant switcher */}
            <div className="mt-auto border-t border-border p-3">
                <TenantSwitcher isCollapsed={isCollapsed} />
                {!isCollapsed && (
                    <div className="text-[10px] text-[var(--ink-500)] mt-2 px-1 font-mono">
                        v{APP_VERSION}
                    </div>
                )}
            </div>
        </aside>
    );
}
