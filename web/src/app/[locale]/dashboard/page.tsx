"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import {
    Building2,
    PieChart,
    DollarSign,
    AlertTriangle,
    CheckCircle,
    Clock,
    ArrowRightCircle,
    AlertCircle,
    FileText,
    CreditCard,
    BarChart3,
    ArrowRight,
    Activity,
    TrendingUp,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Link } from "@/i18n/routing";
import { formatCurrencyCompact } from "@/lib/format";
import WelcomeBanner from '@/components/help/WelcomeBanner';

type DashboardSummary = {
    totalProperties: number;
    totalUnits: number;
    occupiedUnits: number;
    vacantUnits: number;
    occupancyRate: number;
    activeLeases: number;
    draftLeases: number;
    expiringLeases: number;
    totalRentRevenue: number;
    collectedAmount: number;
    pendingAmount: number;
    overdueAmount: number;
    recentActivity: {
        type: string;
        description: string;
        timestamp: string;
    }[];
};

const ACTIVITY_ICONS: Record<string, { icon: typeof CheckCircle; color: string }> = {
    PAYMENT_CLEARED: { icon: CheckCircle, color: "text-success" },
    PAYMENT_COLLECTED: { icon: ArrowRightCircle, color: "text-info" },
    PAYMENT_DEPOSITED: { icon: TrendingUp, color: "text-warning" },
    PAYMENT_BOUNCED: { icon: AlertTriangle, color: "text-error" },
    LEASE_ACTIVATED: { icon: FileText, color: "text-primary" },
    LEASE_DRAFTED: { icon: FileText, color: "text-muted" },
    LEASE_TERMINATED: { icon: FileText, color: "text-error" },
};

function formatTimeAgo(timestamp: string): string {
    const now = new Date();
    const date = new Date(timestamp);
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return "Just now";
    if (diffMins < 60) return `${diffMins}m ago`;
    if (diffHours < 24) return `${diffHours}h ago`;
    if (diffDays < 7) return `${diffDays}d ago`;
    return date.toLocaleDateString();
}

export default function DashboardPage() {
    const t = useTranslations("Dashboard");
    const { data: session } = useSession();
    const [summary, setSummary] = useState<DashboardSummary | null>(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        fetchSummary();
    }, []);

    const fetchSummary = async () => {
        try {
            const res = await fetch("/api/proxy/v1/dashboard/summary");
            if (res.ok) {
                const data = await res.json();
                setSummary(data);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    if (loading) {
        return (
            <div>
                <div className="mb-10">
                    <div className="h-7 w-48 bg-border/50 rounded-lg animate-pulse mb-2" />
                    <div className="h-4 w-64 bg-border/30 rounded-lg animate-pulse" />
                </div>
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5 mb-8">
                    {[...Array(4)].map((_, i) => (
                        <div key={i} className="bg-surface rounded-xl p-5 border border-border">
                            <div className="flex items-start justify-between mb-4">
                                <div className="w-10 h-10 bg-border/30 rounded-xl animate-pulse" />
                                <div className="h-3 w-16 bg-border/30 rounded animate-pulse" />
                            </div>
                            <div className="h-7 w-20 bg-border/50 rounded-lg animate-pulse mb-2" />
                            <div className="h-3 w-32 bg-border/30 rounded animate-pulse" />
                        </div>
                    ))}
                </div>
            </div>
        );
    }

    if (!summary) {
        return (
            <div className="text-center py-24 bg-surface border border-dashed border-border rounded-2xl flex flex-col items-center">
                <div className="w-16 h-16 bg-input rounded-2xl flex items-center justify-center text-muted mb-6">
                    <Activity size={32} />
                </div>
                <p className="text-sm font-semibold text-muted tracking-wide">
                    Unable to load dashboard data
                </p>
            </div>
        );
    }

    const occupancyPercent = Math.round(summary.occupancyRate);

    return (
        <div>
            {/* Header */}
            <div className="mb-10" data-tour="dashboard-header">
                <h1 className="mb-1">
                    {t("title")}
                </h1>
                <p className="text-sm text-muted">
                    {t("description")}
                </p>
            </div>

            <WelcomeBanner />

            {/* KPI Cards */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5 mb-8">
                {/* Properties */}
                <div className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 relative overflow-hidden">
                    <div className="absolute top-0 left-0 right-0 h-[2px] bg-accent" />
                    <div className="flex items-start justify-between mb-4">
                        <div className="w-10 h-10 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                            <Building2 size={20} />
                        </div>
                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                            {t("properties")}
                        </span>
                    </div>
                    <p className="text-2xl font-bold text-foreground mb-1 tabular-nums">
                        {summary.totalProperties}
                    </p>
                    <p className="text-[11px] text-muted">
                        {summary.totalUnits} {t("units")}, {summary.vacantUnits} {t("vacant")}
                    </p>
                </div>

                {/* Occupancy */}
                <div className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 relative overflow-hidden">
                    <div className="absolute top-0 left-0 right-0 h-[2px] bg-accent" />
                    <div className="flex items-start justify-between mb-4">
                        <div className="w-10 h-10 bg-info/10 rounded-xl flex items-center justify-center text-info border border-info/20">
                            <PieChart size={20} />
                        </div>
                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                            {t("occupancy")}
                        </span>
                    </div>
                    <p className="text-2xl font-bold text-foreground mb-2 tabular-nums">
                        {occupancyPercent}%
                    </p>
                    <div className="w-full bg-input rounded-full h-1.5">
                        <div
                            className={cn(
                                "h-1.5 rounded-full transition-all duration-500",
                                occupancyPercent >= 80 ? "bg-success" :
                                occupancyPercent >= 50 ? "bg-warning" : "bg-error"
                            )}
                            style={{ width: `${Math.min(occupancyPercent, 100)}%` }}
                        />
                    </div>
                </div>

                {/* Revenue */}
                <div className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 relative overflow-hidden">
                    <div className="absolute top-0 left-0 right-0 h-[2px] bg-accent" />
                    <div className="flex items-start justify-between mb-4">
                        <div className="w-10 h-10 bg-success/10 rounded-xl flex items-center justify-center text-success border border-success/20">
                            <DollarSign size={20} />
                        </div>
                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                            {t("revenue")}
                        </span>
                    </div>
                    <p className="text-2xl font-bold text-foreground mb-1 tabular-nums">
                        {formatCurrencyCompact(summary.totalRentRevenue)}
                    </p>
                    <p className="text-[11px] text-muted">
                        {summary.activeLeases} {t("activeLeases")}
                    </p>
                </div>

                {/* Overdue */}
                <div className={cn(
                    "bg-surface rounded-xl p-5 border hover:shadow-md transition-all duration-200 relative overflow-hidden",
                    summary.overdueAmount > 0 ? "border-error/30" : "border-border"
                )}>
                    <div className={cn(
                        "absolute top-0 left-0 right-0 h-[2px]",
                        summary.overdueAmount > 0 ? "bg-error" : "bg-accent"
                    )} />
                    <div className="flex items-start justify-between mb-4">
                        <div className={cn(
                            "w-10 h-10 rounded-xl flex items-center justify-center border",
                            summary.overdueAmount > 0
                                ? "bg-error/10 text-error border-error/20"
                                : "bg-input text-muted border-border"
                        )}>
                            <AlertTriangle size={20} />
                        </div>
                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                            {t("overdue")}
                        </span>
                    </div>
                    <p className={cn(
                        "text-2xl font-bold mb-1 tabular-nums",
                        summary.overdueAmount > 0 ? "text-error" : "text-foreground"
                    )}>
                        {formatCurrencyCompact(summary.overdueAmount)}
                    </p>
                </div>
            </div>

            {/* Financial Summary */}
            <div className="mb-8">
                <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4 px-1">
                    {t("financialSummary")}
                </h2>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                    <div className="bg-surface rounded-xl p-5 border border-success/20 hover:shadow-md transition-all duration-200">
                        <div className="flex items-center gap-3 mb-3">
                            <div className="w-8 h-8 bg-success/10 rounded-lg flex items-center justify-center">
                                <CheckCircle size={16} className="text-success" />
                            </div>
                            <span className="text-xs font-semibold text-muted">{t("collected")}</span>
                        </div>
                        <p className="text-lg font-bold text-success tabular-nums">
                            {formatCurrencyCompact(summary.collectedAmount)}
                        </p>
                    </div>

                    <div className="bg-surface rounded-xl p-5 border border-warning/20 hover:shadow-md transition-all duration-200">
                        <div className="flex items-center gap-3 mb-3">
                            <div className="w-8 h-8 bg-warning/10 rounded-lg flex items-center justify-center">
                                <Clock size={16} className="text-warning" />
                            </div>
                            <span className="text-xs font-semibold text-muted">{t("pending")}</span>
                        </div>
                        <p className="text-lg font-bold text-warning tabular-nums">
                            {formatCurrencyCompact(summary.pendingAmount)}
                        </p>
                    </div>

                    <div className="bg-surface rounded-xl p-5 border border-error/20 hover:shadow-md transition-all duration-200">
                        <div className="flex items-center gap-3 mb-3">
                            <div className="w-8 h-8 bg-error/10 rounded-lg flex items-center justify-center">
                                <AlertCircle size={16} className="text-error" />
                            </div>
                            <span className="text-xs font-semibold text-muted">{t("overdue")}</span>
                        </div>
                        <p className="text-lg font-bold text-error tabular-nums">
                            {formatCurrencyCompact(summary.overdueAmount)}
                        </p>
                    </div>
                </div>
            </div>

            {/* Alerts and Recent Activity */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-8">
                {/* Alerts */}
                <div className="bg-surface rounded-xl p-6 border border-border hover:shadow-md transition-all duration-200">
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-5">
                        {t("alerts")}
                    </h2>
                    {(summary.expiringLeases > 0 || summary.overdueAmount > 0) ? (
                        <div className="space-y-3">
                            {summary.expiringLeases > 0 && (
                                <div className="flex items-start gap-3 p-3 bg-warning/5 rounded-xl border border-warning/20">
                                    <div className="w-8 h-8 bg-warning/10 rounded-lg flex items-center justify-center shrink-0 mt-0.5">
                                        <AlertTriangle size={14} className="text-warning" />
                                    </div>
                                    <p className="text-xs font-semibold text-foreground">
                                        {summary.expiringLeases} {t("expiringLeases")}
                                    </p>
                                </div>
                            )}
                            {summary.overdueAmount > 0 && (
                                <div className="flex items-start gap-3 p-3 bg-error/5 rounded-xl border border-error/20">
                                    <div className="w-8 h-8 bg-error/10 rounded-lg flex items-center justify-center shrink-0 mt-0.5">
                                        <AlertCircle size={14} className="text-error" />
                                    </div>
                                    <p className="text-xs font-semibold text-foreground">
                                        {formatCurrencyCompact(summary.overdueAmount)} {t("overduePayments")}
                                    </p>
                                </div>
                            )}
                        </div>
                    ) : (
                        <div className="flex flex-col items-center justify-center py-8 text-muted/50">
                            <CheckCircle size={24} className="mb-2" />
                            <p className="text-xs font-medium text-muted">{t("noAlerts")}</p>
                        </div>
                    )}
                </div>

                {/* Recent Activity */}
                <div className="bg-surface rounded-xl p-6 border border-border hover:shadow-md transition-all duration-200">
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-5">
                        {t("recentActivity")}
                    </h2>
                    {summary.recentActivity && summary.recentActivity.length > 0 ? (
                        <div className="space-y-3 max-h-64 overflow-y-auto">
                            {summary.recentActivity.map((activity, index) => {
                                const activityConfig = ACTIVITY_ICONS[activity.type] || {
                                    icon: Activity,
                                    color: "text-muted",
                                };
                                const Icon = activityConfig.icon;
                                return (
                                    <div
                                        key={index}
                                        className="flex items-start gap-3 p-3 bg-input/50 rounded-xl border border-border hover:bg-input transition-all duration-200"
                                    >
                                        <div className="w-8 h-8 bg-surface rounded-lg flex items-center justify-center shrink-0 border border-border">
                                            <Icon size={14} className={activityConfig.color} />
                                        </div>
                                        <div className="flex-1 min-w-0">
                                            <p className="text-xs font-medium text-foreground leading-relaxed truncate">
                                                {activity.description}
                                            </p>
                                            <p className="text-[10px] text-muted mt-0.5">
                                                {formatTimeAgo(activity.timestamp)}
                                            </p>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    ) : (
                        <div className="flex flex-col items-center justify-center py-8 text-muted/50">
                            <Activity size={24} className="mb-2" />
                            <p className="text-xs font-medium text-muted">{t("noActivity")}</p>
                        </div>
                    )}
                </div>
            </div>

            {/* Quick Links */}
            <div className="mb-8">
                <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4 px-1">
                    {t("quickLinks")}
                </h2>
                <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                    {[
                        { href: "/dashboard/properties", label: t("viewProperties"), icon: Building2, iconBg: "bg-primary/10 text-primary border-primary/20" },
                        { href: "/dashboard/leases", label: t("manageLeases"), icon: FileText, iconBg: "bg-accent/10 text-accent border-accent/30" },
                        { href: "/dashboard/finance/payments", label: t("paymentSchedule"), icon: CreditCard, iconBg: "bg-success/10 text-success border-success/20" },
                        { href: "/dashboard/finance/reports", label: t("financialReports"), icon: BarChart3, iconBg: "bg-info/10 text-info border-info/20" },
                    ].map((link) => {
                        const Icon = link.icon;
                        return (
                            <Link
                                key={link.href}
                                href={link.href}
                                className="group cursor-pointer bg-surface rounded-xl p-5 border border-border hover:shadow-md hover:border-primary/30 transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            >
                                <div className="flex items-center justify-between">
                                    <div className="flex items-center gap-3">
                                        <div className={cn("w-9 h-9 rounded-xl flex items-center justify-center border", link.iconBg)}>
                                            <Icon size={16} />
                                        </div>
                                        <span className="text-xs font-semibold text-foreground">{link.label}</span>
                                    </div>
                                    <ArrowRight size={14} className="text-border group-hover:text-primary group-hover:translate-x-1 transition-all" />
                                </div>
                            </Link>
                        );
                    })}
                </div>
            </div>
        </div>
    );
}
