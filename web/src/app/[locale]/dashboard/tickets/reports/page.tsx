"use client";

import { useState, useEffect } from "react";
import { Link } from "@/i18n/routing";
import { Loader2, ArrowLeft, BarChart3, Clock, Star, AlertTriangle } from "lucide-react";
import { cn } from "@/lib/utils";

// ── Types ──────────────────────────────────────────────────────────────────

type TicketReport = {
    totalTickets: number;
    openCount: number;
    resolvedCount: number;
    closedCount: number;
    avgResolutionHours: number;
    avgSatisfaction: number;
    overdueCount: number;
    ticketsByCategory: Record<string, number>;
    ticketsByPriority: Record<string, number>;
};

// ── Badge Maps ─────────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
    OPEN: "bg-warning/10 text-warning",
    ASSIGNED: "bg-info/10 text-info",
    IN_PROGRESS: "bg-primary/10 text-primary",
    RESOLVED: "bg-success/10 text-success",
    CLOSED: "bg-input text-muted",
    REOPENED: "bg-error/10 text-error",
};

const PRIORITY_COLORS: Record<string, string> = {
    LOW: "bg-input text-muted",
    MEDIUM: "bg-info/10 text-info",
    HIGH: "bg-warning/10 text-warning",
    URGENT: "bg-error/10 text-error",
};

const CATEGORY_COLORS: Record<string, string> = {
    PLUMBING: "bg-info/10 text-info",
    ELECTRICAL: "bg-warning/10 text-warning",
    HVAC: "bg-primary/10 text-primary",
    APPLIANCE: "bg-input text-muted",
    STRUCTURAL: "bg-error/10 text-error",
    PEST_CONTROL: "bg-warning/10 text-warning",
    CLEANING: "bg-success/10 text-success",
    SECURITY: "bg-error/10 text-error",
    OTHER: "bg-input text-muted",
};

// ── Page Component ─────────────────────────────────────────────────────────

export default function TicketReportsPage() {
    const [report, setReport] = useState<TicketReport | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        (async () => {
            try {
                const res = await fetch("/api/proxy/v1/tickets/reports");
                if (res.ok) {
                    setReport(await res.json());
                } else {
                    setError("Failed to load report data");
                }
            } catch {
                setError("Failed to load report data");
            } finally {
                setLoading(false);
            }
        })();
    }, []);

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    if (error || !report) {
        return (
            <div className="text-center py-24">
                <p className="text-sm text-muted">{error || "No data available"}</p>
                <Link href="/dashboard/tickets" className="text-xs text-primary mt-2 inline-block hover:underline">
                    Back to Tickets
                </Link>
            </div>
        );
    }

    const statusBreakdown: Record<string, number> = {
        OPEN: report.openCount,
        RESOLVED: report.resolvedCount,
        CLOSED: report.closedCount,
    };

    return (
        <div>
            {/* Header */}
            <div className="flex items-center justify-between mb-6">
                <div>
                    <Link href="/dashboard/tickets" className="flex items-center gap-1 text-xs text-muted hover:text-foreground mb-2">
                        <ArrowLeft size={12} /> Back to Tickets
                    </Link>
                    <h1 className="text-lg font-bold text-foreground">Ticket Reports</h1>
                    <p className="text-xs text-muted mt-0.5">
                        Overview of maintenance ticket metrics and breakdowns
                    </p>
                </div>
            </div>

            {/* KPI Cards */}
            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 mb-6">
                <div className="bg-surface rounded-xl border border-border p-5">
                    <div className="flex items-center gap-3 mb-3">
                        <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center">
                            <BarChart3 size={16} className="text-primary" />
                        </div>
                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">Total Tickets</span>
                    </div>
                    <p className="text-2xl font-bold text-foreground">{report.totalTickets}</p>
                </div>

                <div className="bg-surface rounded-xl border border-border p-5">
                    <div className="flex items-center gap-3 mb-3">
                        <div className="w-8 h-8 rounded-lg bg-warning/10 flex items-center justify-center">
                            <AlertTriangle size={16} className="text-warning" />
                        </div>
                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">Open</span>
                    </div>
                    <p className="text-2xl font-bold text-foreground">{report.openCount}</p>
                    {report.overdueCount > 0 && (
                        <span className="inline-flex items-center gap-1 mt-2 px-2 py-0.5 rounded-md text-[9px] font-semibold bg-error/10 text-error">
                            {report.overdueCount} overdue
                        </span>
                    )}
                </div>

                <div className="bg-surface rounded-xl border border-border p-5">
                    <div className="flex items-center gap-3 mb-3">
                        <div className="w-8 h-8 rounded-lg bg-info/10 flex items-center justify-center">
                            <Clock size={16} className="text-info" />
                        </div>
                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">Avg Resolution</span>
                    </div>
                    <p className="text-2xl font-bold text-foreground">
                        {report.avgResolutionHours > 0 ? `${report.avgResolutionHours.toFixed(1)}h` : "--"}
                    </p>
                </div>

                <div className="bg-surface rounded-xl border border-border p-5">
                    <div className="flex items-center gap-3 mb-3">
                        <div className="w-8 h-8 rounded-lg bg-success/10 flex items-center justify-center">
                            <Star size={16} className="text-success" />
                        </div>
                        <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">Avg Satisfaction</span>
                    </div>
                    <p className="text-2xl font-bold text-foreground">
                        {report.avgSatisfaction > 0 ? `${report.avgSatisfaction.toFixed(1)} / 5` : "--"}
                    </p>
                </div>
            </div>

            {/* Breakdown Tables */}
            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
                {/* Status Breakdown */}
                <div className="bg-surface rounded-xl border border-border overflow-hidden">
                    <div className="px-4 py-3 border-b border-border">
                        <h3 className="text-xs font-bold text-foreground">By Status</h3>
                    </div>
                    <table className="w-full">
                        <thead>
                            <tr className="bg-input/50">
                                <th className="px-4 py-2 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Status</th>
                                <th className="px-4 py-2 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">Count</th>
                            </tr>
                        </thead>
                        <tbody>
                            {Object.entries(statusBreakdown).map(([status, count]) => (
                                <tr key={status} className="border-b border-border last:border-b-0">
                                    <td className="px-4 py-2.5">
                                        <span className={cn(
                                            "px-2 py-0.5 rounded-md text-[9px] font-semibold",
                                            STATUS_COLORS[status] || "bg-input text-muted",
                                        )}>
                                            {status.replace(/_/g, " ")}
                                        </span>
                                    </td>
                                    <td className="px-4 py-2.5 text-xs text-foreground font-semibold text-end tabular-nums">{count}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>

                {/* By Category */}
                <div className="bg-surface rounded-xl border border-border overflow-hidden">
                    <div className="px-4 py-3 border-b border-border">
                        <h3 className="text-xs font-bold text-foreground">By Category</h3>
                    </div>
                    <table className="w-full">
                        <thead>
                            <tr className="bg-input/50">
                                <th className="px-4 py-2 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Category</th>
                                <th className="px-4 py-2 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">Count</th>
                            </tr>
                        </thead>
                        <tbody>
                            {Object.entries(report.ticketsByCategory).map(([category, count]) => (
                                <tr key={category} className="border-b border-border last:border-b-0">
                                    <td className="px-4 py-2.5">
                                        <span className={cn(
                                            "px-2 py-0.5 rounded-md text-[9px] font-semibold",
                                            CATEGORY_COLORS[category] || "bg-input text-muted",
                                        )}>
                                            {category.replace(/_/g, " ")}
                                        </span>
                                    </td>
                                    <td className="px-4 py-2.5 text-xs text-foreground font-semibold text-end tabular-nums">{count}</td>
                                </tr>
                            ))}
                            {Object.keys(report.ticketsByCategory).length === 0 && (
                                <tr><td colSpan={2} className="px-4 py-4 text-xs text-muted text-center">No data</td></tr>
                            )}
                        </tbody>
                    </table>
                </div>

                {/* By Priority */}
                <div className="bg-surface rounded-xl border border-border overflow-hidden">
                    <div className="px-4 py-3 border-b border-border">
                        <h3 className="text-xs font-bold text-foreground">By Priority</h3>
                    </div>
                    <table className="w-full">
                        <thead>
                            <tr className="bg-input/50">
                                <th className="px-4 py-2 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">Priority</th>
                                <th className="px-4 py-2 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">Count</th>
                            </tr>
                        </thead>
                        <tbody>
                            {Object.entries(report.ticketsByPriority).map(([priority, count]) => (
                                <tr key={priority} className="border-b border-border last:border-b-0">
                                    <td className="px-4 py-2.5">
                                        <span className={cn(
                                            "px-2 py-0.5 rounded-md text-[9px] font-semibold",
                                            PRIORITY_COLORS[priority] || "bg-input text-muted",
                                        )}>
                                            {priority}
                                        </span>
                                    </td>
                                    <td className="px-4 py-2.5 text-xs text-foreground font-semibold text-end tabular-nums">{count}</td>
                                </tr>
                            ))}
                            {Object.keys(report.ticketsByPriority).length === 0 && (
                                <tr><td colSpan={2} className="px-4 py-4 text-xs text-muted text-center">No data</td></tr>
                            )}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
    );
}
