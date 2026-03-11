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
    Loader2,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { Link } from "@/i18n/routing";

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
    PAYMENT_CLEARED: { icon: CheckCircle, color: "text-green-500" },
    PAYMENT_COLLECTED: { icon: ArrowRightCircle, color: "text-blue-500" },
    PAYMENT_DEPOSITED: { icon: TrendingUp, color: "text-amber-500" },
    PAYMENT_BOUNCED: { icon: AlertTriangle, color: "text-red-500" },
    LEASE_ACTIVATED: { icon: FileText, color: "text-primary" },
    LEASE_DRAFTED: { icon: FileText, color: "text-gray-400" },
    LEASE_TERMINATED: { icon: FileText, color: "text-red-400" },
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
            <div className="p-8 max-w-7xl mx-auto flex items-center justify-center min-h-[60vh]">
                <Loader2 size={24} className="animate-spin text-primary" />
            </div>
        );
    }

    if (!summary) {
        return (
            <div>
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <Activity size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 uppercase tracking-widest">
                        Unable to load dashboard data
                    </p>
                </div>
            </div>
        );
    }

    const occupancyPercent = Math.round(summary.occupancyRate);

    return (
        <div>
            {/* Header */}
            <div className="mb-10">
                <h1 className="text-xl font-black text-foreground tracking-tight mb-1">
                    {t("title")}
                </h1>
                <p className="text-xs text-gray-500 font-medium">
                    {t("description")}
                </p>
            </div>

            {/* KPI Cards Row */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5 mb-8">
                {/* Properties Card */}
                <div className="bg-white rounded-2xl p-5 border border-gray-100 shadow-sm hover:shadow-md transition-shadow">
                    <div className="flex items-start justify-between mb-4">
                        <div className="w-10 h-10 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                            <Building2 size={20} />
                        </div>
                        <span className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                            {t("properties")}
                        </span>
                    </div>
                    <p className="text-2xl font-black text-foreground mb-1">
                        {summary.totalProperties}
                    </p>
                    <p className="text-[11px] font-semibold text-gray-400">
                        {summary.totalUnits} {t("units")}, {summary.vacantUnits} {t("vacant")}
                    </p>
                </div>

                {/* Occupancy Card */}
                <div className="bg-white rounded-2xl p-5 border border-gray-100 shadow-sm hover:shadow-md transition-shadow">
                    <div className="flex items-start justify-between mb-4">
                        <div className="w-10 h-10 bg-blue-50 rounded-xl flex items-center justify-center text-blue-500 border border-blue-100">
                            <PieChart size={20} />
                        </div>
                        <span className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                            {t("occupancy")}
                        </span>
                    </div>
                    <p className="text-2xl font-black text-foreground mb-2">
                        {occupancyPercent}%
                    </p>
                    {/* Occupancy Bar */}
                    <div className="w-full bg-gray-100 rounded-full h-1.5">
                        <div
                            className={cn(
                                "h-1.5 rounded-full transition-all duration-500",
                                occupancyPercent >= 80 ? "bg-green-500" :
                                occupancyPercent >= 50 ? "bg-amber-500" : "bg-red-500"
                            )}
                            style={{ width: `${Math.min(occupancyPercent, 100)}%` }}
                        />
                    </div>
                </div>

                {/* Revenue Card */}
                <div className="bg-white rounded-2xl p-5 border border-gray-100 shadow-sm hover:shadow-md transition-shadow">
                    <div className="flex items-start justify-between mb-4">
                        <div className="w-10 h-10 bg-green-50 rounded-xl flex items-center justify-center text-green-600 border border-green-100">
                            <DollarSign size={20} />
                        </div>
                        <span className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                            {t("revenue")}
                        </span>
                    </div>
                    <p className="text-2xl font-black text-foreground mb-1">
                        AED {summary.totalRentRevenue.toLocaleString()}
                    </p>
                    <p className="text-[11px] font-semibold text-gray-400">
                        {summary.activeLeases} {t("activeLeases")}
                    </p>
                </div>

                {/* Overdue Card */}
                <div className={cn(
                    "bg-white rounded-2xl p-5 border shadow-sm hover:shadow-md transition-shadow",
                    summary.overdueAmount > 0
                        ? "border-red-200 bg-red-50/30"
                        : "border-gray-100"
                )}>
                    <div className="flex items-start justify-between mb-4">
                        <div className={cn(
                            "w-10 h-10 rounded-xl flex items-center justify-center border",
                            summary.overdueAmount > 0
                                ? "bg-red-100 text-red-600 border-red-200"
                                : "bg-gray-50 text-gray-400 border-gray-100"
                        )}>
                            <AlertTriangle size={20} />
                        </div>
                        <span className="text-[10px] font-bold text-gray-400 uppercase tracking-wider">
                            {t("overdue")}
                        </span>
                    </div>
                    <p className={cn(
                        "text-2xl font-black mb-1",
                        summary.overdueAmount > 0 ? "text-red-600" : "text-foreground"
                    )}>
                        AED {summary.overdueAmount.toLocaleString()}
                    </p>
                </div>
            </div>

            {/* Financial Summary Section */}
            <div className="mb-8">
                <h2 className="text-xs font-bold text-gray-400 uppercase tracking-[0.15em] mb-4 px-1">
                    {t("financialSummary")}
                </h2>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                    {/* Collected */}
                    <div className="bg-white rounded-2xl p-5 border border-green-100 shadow-sm">
                        <div className="flex items-center gap-3 mb-3">
                            <div className="w-8 h-8 bg-green-50 rounded-lg flex items-center justify-center">
                                <CheckCircle size={16} className="text-green-500" />
                            </div>
                            <span className="text-xs font-bold text-gray-500">{t("collected")}</span>
                        </div>
                        <p className="text-lg font-black text-green-600">
                            AED {summary.collectedAmount.toLocaleString()}
                        </p>
                    </div>

                    {/* Pending */}
                    <div className="bg-white rounded-2xl p-5 border border-amber-100 shadow-sm">
                        <div className="flex items-center gap-3 mb-3">
                            <div className="w-8 h-8 bg-amber-50 rounded-lg flex items-center justify-center">
                                <Clock size={16} className="text-amber-500" />
                            </div>
                            <span className="text-xs font-bold text-gray-500">{t("pending")}</span>
                        </div>
                        <p className="text-lg font-black text-amber-600">
                            AED {summary.pendingAmount.toLocaleString()}
                        </p>
                    </div>

                    {/* Overdue */}
                    <div className="bg-white rounded-2xl p-5 border border-red-100 shadow-sm">
                        <div className="flex items-center gap-3 mb-3">
                            <div className="w-8 h-8 bg-red-50 rounded-lg flex items-center justify-center">
                                <AlertCircle size={16} className="text-red-500" />
                            </div>
                            <span className="text-xs font-bold text-gray-500">{t("overdue")}</span>
                        </div>
                        <p className="text-lg font-black text-red-600">
                            AED {summary.overdueAmount.toLocaleString()}
                        </p>
                    </div>
                </div>
            </div>

            {/* Alerts and Recent Activity Side by Side */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-8">
                {/* Alerts Section */}
                <div className="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm">
                    <h2 className="text-xs font-bold text-gray-400 uppercase tracking-[0.15em] mb-5">
                        {t("alerts")}
                    </h2>
                    {(summary.expiringLeases > 0 || summary.overdueAmount > 0) ? (
                        <div className="space-y-3">
                            {summary.expiringLeases > 0 && (
                                <div className="flex items-start gap-3 p-3 bg-amber-50 rounded-xl border border-amber-100">
                                    <div className="w-8 h-8 bg-amber-100 rounded-lg flex items-center justify-center shrink-0 mt-0.5">
                                        <AlertTriangle size={14} className="text-amber-600" />
                                    </div>
                                    <div>
                                        <p className="text-xs font-bold text-amber-800">
                                            {summary.expiringLeases} {t("expiringLeases")}
                                        </p>
                                    </div>
                                </div>
                            )}
                            {summary.overdueAmount > 0 && (
                                <div className="flex items-start gap-3 p-3 bg-red-50 rounded-xl border border-red-100">
                                    <div className="w-8 h-8 bg-red-100 rounded-lg flex items-center justify-center shrink-0 mt-0.5">
                                        <AlertCircle size={14} className="text-red-600" />
                                    </div>
                                    <div>
                                        <p className="text-xs font-bold text-red-800">
                                            AED {summary.overdueAmount.toLocaleString()} {t("overduePayments")}
                                        </p>
                                    </div>
                                </div>
                            )}
                        </div>
                    ) : (
                        <div className="flex flex-col items-center justify-center py-8 text-gray-300">
                            <CheckCircle size={24} className="mb-2" />
                            <p className="text-xs font-semibold text-gray-400">{t("noAlerts")}</p>
                        </div>
                    )}
                </div>

                {/* Recent Activity Section */}
                <div className="bg-white rounded-2xl p-6 border border-gray-100 shadow-sm">
                    <h2 className="text-xs font-bold text-gray-400 uppercase tracking-[0.15em] mb-5">
                        {t("recentActivity")}
                    </h2>
                    {summary.recentActivity && summary.recentActivity.length > 0 ? (
                        <div className="space-y-3 max-h-64 overflow-y-auto">
                            {summary.recentActivity.map((activity, index) => {
                                const activityConfig = ACTIVITY_ICONS[activity.type] || {
                                    icon: Activity,
                                    color: "text-gray-400",
                                };
                                const Icon = activityConfig.icon;
                                return (
                                    <div
                                        key={index}
                                        className="flex items-start gap-3 p-3 bg-gray-50 rounded-xl border border-gray-100 hover:bg-gray-100/50 transition-colors"
                                    >
                                        <div className="w-8 h-8 bg-white rounded-lg flex items-center justify-center shrink-0 shadow-sm border border-gray-100">
                                            <Icon size={14} className={activityConfig.color} />
                                        </div>
                                        <div className="flex-1 min-w-0">
                                            <p className="text-xs font-semibold text-foreground leading-relaxed truncate">
                                                {activity.description}
                                            </p>
                                            <p className="text-[10px] font-medium text-gray-400 mt-0.5">
                                                {formatTimeAgo(activity.timestamp)}
                                            </p>
                                        </div>
                                    </div>
                                );
                            })}
                        </div>
                    ) : (
                        <div className="flex flex-col items-center justify-center py-8 text-gray-300">
                            <Activity size={24} className="mb-2" />
                            <p className="text-xs font-semibold text-gray-400">{t("noActivity")}</p>
                        </div>
                    )}
                </div>
            </div>

            {/* Quick Links Section */}
            <div className="mb-8">
                <h2 className="text-xs font-bold text-gray-400 uppercase tracking-[0.15em] mb-4 px-1">
                    {t("quickLinks")}
                </h2>
                <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
                    <Link
                        href="/dashboard/properties"
                        className="group bg-white rounded-2xl p-5 border border-gray-100 shadow-sm hover:shadow-md hover:border-primary/20 transition-all"
                    >
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-3">
                                <div className="w-9 h-9 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                                    <Building2 size={16} />
                                </div>
                                <span className="text-xs font-bold text-foreground">{t("viewProperties")}</span>
                            </div>
                            <ArrowRight size={14} className="text-gray-300 group-hover:text-primary group-hover:translate-x-1 transition-all" />
                        </div>
                    </Link>

                    <Link
                        href="/dashboard/leases"
                        className="group bg-white rounded-2xl p-5 border border-gray-100 shadow-sm hover:shadow-md hover:border-primary/20 transition-all"
                    >
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-3">
                                <div className="w-9 h-9 bg-purple-50 rounded-xl flex items-center justify-center text-purple-500 border border-purple-100">
                                    <FileText size={16} />
                                </div>
                                <span className="text-xs font-bold text-foreground">{t("manageLeases")}</span>
                            </div>
                            <ArrowRight size={14} className="text-gray-300 group-hover:text-primary group-hover:translate-x-1 transition-all" />
                        </div>
                    </Link>

                    <Link
                        href="/dashboard/finance/payments"
                        className="group bg-white rounded-2xl p-5 border border-gray-100 shadow-sm hover:shadow-md hover:border-primary/20 transition-all"
                    >
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-3">
                                <div className="w-9 h-9 bg-green-50 rounded-xl flex items-center justify-center text-green-600 border border-green-100">
                                    <CreditCard size={16} />
                                </div>
                                <span className="text-xs font-bold text-foreground">{t("paymentSchedule")}</span>
                            </div>
                            <ArrowRight size={14} className="text-gray-300 group-hover:text-primary group-hover:translate-x-1 transition-all" />
                        </div>
                    </Link>

                    <Link
                        href="/dashboard/finance/reports"
                        className="group bg-white rounded-2xl p-5 border border-gray-100 shadow-sm hover:shadow-md hover:border-primary/20 transition-all"
                    >
                        <div className="flex items-center justify-between">
                            <div className="flex items-center gap-3">
                                <div className="w-9 h-9 bg-blue-50 rounded-xl flex items-center justify-center text-blue-500 border border-blue-100">
                                    <BarChart3 size={16} />
                                </div>
                                <span className="text-xs font-bold text-foreground">{t("financialReports")}</span>
                            </div>
                            <ArrowRight size={14} className="text-gray-300 group-hover:text-primary group-hover:translate-x-1 transition-all" />
                        </div>
                    </Link>
                </div>
            </div>
        </div>
    );
}
