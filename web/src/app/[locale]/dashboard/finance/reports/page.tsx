"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import {
    BarChart3, Building2, Globe, TrendingUp, TrendingDown,
    DollarSign, ArrowDown, ArrowUp, Wallet, ChevronDown, ChevronUp
} from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrency, formatCurrencyCompact, formatNumber } from "@/lib/format";

type Property = { id: string; nameEn: string };
type PropertyStats = { property: Property };

type ReportData = {
    reportType: string;
    reportName: string;
    dateRange: string;
    totalRentalIncome: number;
    totalOtherIncome: number;
    totalIncome: number;
    totalDirectExpenses: number;
    totalIndirectExpenses: number;
    totalExpenses: number;
    incomeBreakdown: Record<string, number>;
    directExpenseBreakdown: Record<string, number>;
    indirectExpenseBreakdown: Record<string, number>;
    netOperatingIncome: number;
    netProfit: number;
    totalAssets: number;
    totalLiabilities: number;
    totalEquity: number;
    outstandingRentReceivables: number;
    securityDepositsHeld: number;
    pdcReceivable: number;
    pdcPayable: number;
    advanceRentBalance: number;
};

type TrialBalanceLine = {
    accountCode: string;
    accountName: string;
    accountType: string;
    debit: number;
    credit: number;
    balance: number;
};

type TrialBalanceData = {
    lines: TrialBalanceLine[];
    totalDebit: number;
    totalCredit: number;
};

type AgingItem = {
    renterName: string;
    propertyName: string;
    unitNumber: string;
    amount: number;
    daysOverdue: number;
    dueDate: string;
};

type AgingBucket = {
    label: string;
    count: number;
    amount: number;
    details: AgingItem[];
};

type AgingData = {
    totalOutstanding: number;
    // Backend returns an ordered array of buckets, labelled "Current", "1-30 Days", ...
    buckets: AgingBucket[];
};

type VatLine = {
    description: string;
    taxableAmount: number;
    vatAmount: number;
};

type VatData = {
    totalOutputVat: number;
    totalInputVat: number;
    netVatPayable: number;
    totalTaxableSales: number;
    totalTaxablePurchases: number;
    salesLines: VatLine[];
    purchaseLines: VatLine[];
};

type TabType = "pnl" | "balanceSheet" | "trialBalance" | "aging" | "vatReturn" | "tickets";

type TicketReportData = {
    totalTickets: number; openCount: number; resolvedCount: number; closedCount: number;
    avgResolutionHours: number; avgSatisfaction: number; overdueCount: number;
    ticketsByCategory: Record<string, number>; ticketsByPriority: Record<string, number>;
};

const fmt = (n: number) => formatNumber(n);

const typeColor = (type: string) => {
    switch (type) {
        case 'ASSET': return 'bg-emerald-50 text-emerald-700';
        case 'LIABILITY': return 'bg-rose-50 text-rose-700';
        case 'INCOME': return 'bg-blue-50 text-blue-700';
        case 'EXPENSE': return 'bg-amber-50 text-amber-700';
        case 'EQUITY': return 'bg-violet-50 text-violet-700';
        default: return 'bg-input text-muted';
    }
};

export default function ReportsPage() {
    const t = useTranslations("Finance");
    const [activeTab, setActiveTab] = useState<TabType>("pnl");
    const [reportMode, setReportMode] = useState<"ORGANISATION" | "PROPERTY">("ORGANISATION");
    const [selectedPropertyId, setSelectedPropertyId] = useState("");
    const [startDate, setStartDate] = useState("");
    const [endDate, setEndDate] = useState("");
    const [properties, setProperties] = useState<PropertyStats[]>([]);
    const [report, setReport] = useState<ReportData | null>(null);
    const [trialBalance, setTrialBalance] = useState<TrialBalanceData | null>(null);
    const [agingData, setAgingData] = useState<AgingData | null>(null);
    const [vatData, setVatData] = useState<VatData | null>(null);
    const [ticketReport, setTicketReport] = useState<TicketReportData | null>(null);
    const [loading, setLoading] = useState(false);
    const [validationError, setValidationError] = useState("");
    const [expandedBuckets, setExpandedBuckets] = useState<Record<string, boolean>>({});

    useEffect(() => {
        fetchProperties();
    }, []);

    const fetchProperties = async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) setProperties(await res.json());
        } catch (err) {
            console.error(err);
        }
    };

    const toggleBucket = (key: string) => {
        setExpandedBuckets(prev => ({ ...prev, [key]: !prev[key] }));
    };

    const generateReport = async () => {
        setValidationError("");
        setLoading(true);
        try {
            const params = new URLSearchParams();
            if (startDate) params.set("startDate", startDate);
            if (endDate) params.set("endDate", endDate);

            if (activeTab === "pnl" || activeTab === "balanceSheet") {
                let url = "";
                if (reportMode === "ORGANISATION") {
                    url = `/api/proxy/v1/finance/reports/organisation?${params.toString()}`;
                } else {
                    if (!selectedPropertyId) {
                        setValidationError("Please select a property before generating a report.");
                        setLoading(false);
                        return;
                    }
                    url = `/api/proxy/v1/finance/reports/property/${selectedPropertyId}?${params.toString()}`;
                }
                const res = await fetch(url);
                if (res.ok) setReport(await res.json());
            } else if (activeTab === "trialBalance") {
                const res = await fetch(`/api/proxy/v1/finance/reports/trial-balance?${params.toString()}`);
                if (res.ok) setTrialBalance(await res.json());
            } else if (activeTab === "aging") {
                const agingParams = new URLSearchParams();
                if (selectedPropertyId) agingParams.set("propertyId", selectedPropertyId);
                const res = await fetch(`/api/proxy/v1/payments/aging-report?${agingParams.toString()}`);
                if (res.ok) setAgingData(await res.json());
            } else if (activeTab === "vatReturn") {
                if (!startDate || !endDate) {
                    setValidationError("Start date and end date are required for VAT Return.");
                    setLoading(false);
                    return;
                }
                const res = await fetch(`/api/proxy/v1/finance/reports/vat-return?${params.toString()}`);
                if (res.ok) setVatData(await res.json());
            } else if (activeTab === "tickets") {
                const ticketParams = new URLSearchParams();
                if (selectedPropertyId) ticketParams.set("propertyId", selectedPropertyId);
                if (startDate) ticketParams.set("startDate", startDate);
                if (endDate) ticketParams.set("endDate", endDate);
                const res = await fetch(`/api/proxy/v1/tickets/reports?${ticketParams.toString()}`);
                if (res.ok) setTicketReport(await res.json());
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const tabs: { key: TabType; label: string }[] = [
        { key: "pnl", label: t("profitAndLoss") },
        { key: "balanceSheet", label: t("balanceSheet") },
        { key: "trialBalance", label: t("trialBalance") },
        { key: "aging", label: t("agingReport") },
        { key: "vatReturn", label: t("vatReturn") },
        { key: "tickets", label: "Tickets" },
    ];

    // apiLabel must match the bucket labels built in PaymentScheduleService.getAgingReport
    const bucketConfigs = [
        { key: "current", apiLabel: "Current", label: t("current"), bg: "bg-emerald-50", border: "border-emerald-200", text: "text-emerald-700" },
        { key: "days1to30", apiLabel: "1-30 Days", label: t("days1to30"), bg: "bg-amber-50", border: "border-amber-200", text: "text-amber-700" },
        { key: "days31to60", apiLabel: "31-60 Days", label: t("days31to60"), bg: "bg-orange-50", border: "border-orange-200", text: "text-orange-700" },
        { key: "days61to90", apiLabel: "61-90 Days", label: t("days61to90"), bg: "bg-red-50", border: "border-red-200", text: "text-red-700" },
        { key: "days90plus", apiLabel: "90+ Days", label: t("days90plus"), bg: "bg-rose-100", border: "border-rose-300", text: "text-rose-700" },
    ];

    const findBucket = (cfg: (typeof bucketConfigs)[number], idx: number): AgingBucket | undefined =>
        agingData?.buckets?.find(b => b.label === cfg.apiLabel) ?? agingData?.buckets?.[idx];

    const showPropertyFilter = activeTab === "pnl" || activeTab === "balanceSheet" || activeTab === "aging" || activeTab === "tickets";
    const showDateFilters = activeTab !== "aging";

    return (
        <div>
            <div className="mb-10">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <BarChart3 size={20} className="text-primary" />
                    {t("reports")}
                </h1>
                <p className="text-xs text-muted font-medium">{t("reportsDesc")}</p>
            </div>

            {/* Tab Bar */}
            <div className="flex flex-wrap gap-2 mb-6">
                {tabs.map(tab => (
                    <button
                        key={tab.key}
                        onClick={() => setActiveTab(tab.key)}
                        className={`px-4 py-2 rounded-xl text-xs font-bold transition-all ${
                            activeTab === tab.key
                                ? 'bg-primary text-primary-foreground shadow-md shadow-primary/20'
                                : 'bg-surface text-muted hover:text-foreground border border-border'
                        }`}
                    >
                        {tab.label}
                    </button>
                ))}
            </div>

            {/* Filter Bar */}
            <div className="bg-surface border border-border rounded-xl p-5 shadow-sm mb-6">
                <div className="grid grid-cols-1 md:grid-cols-5 gap-4 items-end">
                    {(activeTab === "pnl" || activeTab === "balanceSheet") && (
                        <div>
                            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5">{t("reportType")}</label>
                            <div className="flex gap-2">
                                <button
                                    onClick={() => { setReportMode("ORGANISATION"); setValidationError(""); }}
                                    className={cn("flex-1 flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 border cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none",
                                        reportMode === "ORGANISATION" ? "bg-primary text-white border-primary shadow-sm" : "bg-surface text-muted border-border hover:bg-input/30"
                                    )}
                                >
                                    <Globe size={12} /> Org
                                </button>
                                <button
                                    onClick={() => { setReportMode("PROPERTY"); setValidationError(""); }}
                                    className={cn("flex-1 flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 border cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none",
                                        reportMode === "PROPERTY" ? "bg-primary text-white border-primary shadow-sm" : "bg-surface text-muted border-border hover:bg-input/30"
                                    )}
                                >
                                    <Building2 size={12} /> Property
                                </button>
                            </div>
                        </div>
                    )}

                    {showPropertyFilter && (activeTab === "aging" || reportMode === "PROPERTY") && (
                        <div>
                            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5">{t("selectProperty")}</label>
                            <select
                                className={cn(
                                    "w-full border rounded-lg bg-surface p-2.5 text-xs cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200",
                                    validationError && !selectedPropertyId && activeTab !== "aging" ? "border-red-300" : "border-border"
                                )}
                                value={selectedPropertyId}
                                onChange={ev => { setSelectedPropertyId(ev.target.value); setValidationError(""); }}
                            >
                                <option value="">Select...</option>
                                {properties.map(s => <option key={s.property.id} value={s.property.id}>{s.property.nameEn}</option>)}
                            </select>
                            {validationError && !selectedPropertyId && activeTab !== "aging" && (
                                <p className="text-[10px] text-red-500 font-medium mt-1">{validationError}</p>
                            )}
                        </div>
                    )}

                    {showDateFilters && (
                        <>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5">{t("startDate")}</label>
                                <input type="date" className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={startDate} onChange={ev => setStartDate(ev.target.value)} />
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5">{t("endDate")}</label>
                                <input type="date" className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={endDate} onChange={ev => setEndDate(ev.target.value)} />
                            </div>
                        </>
                    )}

                    <div>
                        <button
                            onClick={generateReport}
                            disabled={loading}
                            className="px-6 py-3 bg-primary text-primary-foreground rounded-lg text-xs font-bold hover:bg-primary/90 transition-all duration-200 shadow-lg shadow-primary/20 active:scale-95 disabled:opacity-50 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        >
                            {loading ? "Generating..." : t("generateReport")}
                        </button>
                    </div>
                </div>
                {validationError && activeTab === "vatReturn" && (
                    <p className="text-[10px] text-red-500 font-medium mt-2">{validationError}</p>
                )}
            </div>

            {/* Loading Skeleton */}
            {loading && (
                <div className="space-y-6 animate-pulse">
                    <div className="bg-input rounded-xl h-48" />
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                        <div className="bg-input rounded-xl h-64" />
                        <div className="bg-input rounded-xl h-64" />
                    </div>
                </div>
            )}

            {/* ==================== P&L Tab ==================== */}
            {activeTab === "pnl" && report && !loading && (
                <div className="space-y-6">
                    {/* Header */}
                    <div className="bg-surface rounded-xl border border-border p-6">
                        <div className="flex items-center justify-between mb-4">
                            <div>
                                <p className="text-xs font-semibold text-muted uppercase tracking-wider">{report.reportType} Report</p>
                                <h2 className="text-lg font-bold text-foreground mt-1">{report.reportName || "Financial Report"}</h2>
                            </div>
                            <div className="text-right">
                                <p className="text-xs font-semibold text-muted uppercase tracking-wider">Period</p>
                                <p className="text-sm font-bold text-foreground mt-1">{report.dateRange}</p>
                            </div>
                        </div>

                        {/* KPI Cards */}
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-4">
                            <div className="bg-input/50 rounded-xl p-4 border border-border">
                                <p className="text-[11px] font-semibold text-muted uppercase tracking-wider flex items-center gap-1"><TrendingUp size={10} /> {t("totalIncome")}</p>
                                <p className="text-lg font-bold text-success mt-1 tabular-nums">{fmt(report.totalIncome)}</p>
                            </div>
                            <div className="bg-input/50 rounded-xl p-4 border border-border">
                                <p className="text-[11px] font-semibold text-muted uppercase tracking-wider flex items-center gap-1"><TrendingDown size={10} /> {t("totalExpenses")}</p>
                                <p className="text-lg font-bold text-error mt-1 tabular-nums">{fmt(report.totalExpenses)}</p>
                            </div>
                            <div className="bg-input/50 rounded-xl p-4 border border-border">
                                <p className="text-[11px] font-semibold text-muted uppercase tracking-wider flex items-center gap-1"><DollarSign size={10} /> {t("noi")}</p>
                                <p className={cn("text-lg font-bold mt-1 tabular-nums", report.netOperatingIncome >= 0 ? "text-success" : "text-error")}>{fmt(report.netOperatingIncome)}</p>
                            </div>
                            <div className="bg-input/50 rounded-xl p-4 border border-border">
                                <p className="text-[11px] font-semibold text-muted uppercase tracking-wider flex items-center gap-1"><Wallet size={10} /> {t("netProfit")}</p>
                                <p className={cn("text-lg font-bold mt-1 tabular-nums", report.netProfit >= 0 ? "text-success" : "text-error")}>{fmt(report.netProfit)}</p>
                            </div>
                        </div>
                    </div>

                    {/* Income & Expense Breakdown */}
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                        {/* Income */}
                        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-emerald-50 to-green-50 border-b border-emerald-100/50">
                                <h3 className="text-sm font-bold text-emerald-700 flex items-center gap-2">
                                    <ArrowDown size={14} /> {t("incomeBreakdown")}
                                </h3>
                            </div>
                            <div className="divide-y divide-border">
                                <div className="flex justify-between px-5 py-3">
                                    <span className="text-xs font-bold text-muted">{t("rentalIncome")}</span>
                                    <span className="text-xs font-bold text-emerald-600">{fmt(report.totalRentalIncome)}</span>
                                </div>
                                <div className="flex justify-between px-5 py-3">
                                    <span className="text-xs font-bold text-muted">{t("otherIncome")}</span>
                                    <span className="text-xs font-bold text-emerald-600">{fmt(report.totalOtherIncome)}</span>
                                </div>
                                {report.incomeBreakdown && Object.entries(report.incomeBreakdown).map(([key, val]) => (
                                    <div key={key} className="flex justify-between px-5 py-2.5 bg-input/30">
                                        <span className="text-[10px] text-muted pl-4">{key}</span>
                                        <span className="text-[10px] font-bold text-muted">{fmt(val)}</span>
                                    </div>
                                ))}
                                <div className="flex justify-between px-5 py-3 bg-emerald-50">
                                    <span className="text-xs font-bold text-emerald-700 uppercase">{t("totalIncome")}</span>
                                    <span className="text-xs font-bold text-emerald-700">{fmt(report.totalIncome)}</span>
                                </div>
                            </div>
                        </div>

                        {/* Expenses */}
                        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-rose-50 to-red-50 border-b border-rose-100/50">
                                <h3 className="text-sm font-bold text-rose-700 flex items-center gap-2">
                                    <ArrowUp size={14} /> {t("expenseBreakdown")}
                                </h3>
                            </div>
                            <div className="divide-y divide-border">
                                <div className="flex justify-between px-5 py-3">
                                    <span className="text-xs font-bold text-muted">{t("directExpenses")}</span>
                                    <span className="text-xs font-bold text-rose-600">{fmt(report.totalDirectExpenses)}</span>
                                </div>
                                {report.directExpenseBreakdown && Object.entries(report.directExpenseBreakdown).map(([key, val]) => (
                                    <div key={key} className="flex justify-between px-5 py-2.5 bg-input/30">
                                        <span className="text-[10px] text-muted pl-4">{key}</span>
                                        <span className="text-[10px] font-bold text-muted">{fmt(val)}</span>
                                    </div>
                                ))}
                                <div className="flex justify-between px-5 py-3">
                                    <span className="text-xs font-bold text-muted">{t("indirectExpenses")}</span>
                                    <span className="text-xs font-bold text-rose-600">{fmt(report.totalIndirectExpenses)}</span>
                                </div>
                                {report.indirectExpenseBreakdown && Object.entries(report.indirectExpenseBreakdown).map(([key, val]) => (
                                    <div key={key} className="flex justify-between px-5 py-2.5 bg-input/30">
                                        <span className="text-[10px] text-muted pl-4">{key}</span>
                                        <span className="text-[10px] font-bold text-muted">{fmt(val)}</span>
                                    </div>
                                ))}
                                <div className="flex justify-between px-5 py-3 bg-rose-50">
                                    <span className="text-xs font-bold text-rose-700 uppercase">{t("totalExpenses")}</span>
                                    <span className="text-xs font-bold text-rose-700">{fmt(report.totalExpenses)}</span>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* ==================== Balance Sheet Tab ==================== */}
            {activeTab === "balanceSheet" && report && !loading && (
                <div className="space-y-6">
                    {report.reportType === "ORGANISATION" && (
                        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-violet-50 to-indigo-50 border-b border-violet-100/50">
                                <h3 className="text-sm font-bold text-violet-700 flex items-center gap-2">
                                    <Wallet size={14} /> {t("balanceSheet")}
                                </h3>
                            </div>
                            <div className="grid grid-cols-3 divide-x divide-border">
                                <div className="p-5 text-center">
                                    <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-2">{t("totalAssets")}</p>
                                    <p className="text-lg font-bold text-emerald-600">{fmt(report.totalAssets)}</p>
                                </div>
                                <div className="p-5 text-center">
                                    <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-2">{t("totalLiabilities")}</p>
                                    <p className="text-lg font-bold text-rose-600">{fmt(report.totalLiabilities)}</p>
                                </div>
                                <div className="p-5 text-center">
                                    <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-2">{t("equity")}</p>
                                    <p className="text-lg font-bold text-violet-600">{fmt(report.totalEquity)}</p>
                                </div>
                            </div>
                        </div>
                    )}

                    {report.reportType === "PROPERTY" && (
                        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-blue-50 to-cyan-50 border-b border-blue-100/50">
                                <h3 className="text-sm font-bold text-blue-700 flex items-center gap-2">
                                    <Building2 size={14} /> {t("propertyDetails")}
                                </h3>
                            </div>
                            <div className="grid grid-cols-2 md:grid-cols-5 gap-px bg-border">
                                {[
                                    { label: t("outstandingReceivables"), value: report.outstandingRentReceivables, color: "text-amber-600" },
                                    { label: t("securityDeposits"), value: report.securityDepositsHeld, color: "text-blue-600" },
                                    { label: t("pdcReceivable"), value: report.pdcReceivable, color: "text-emerald-600" },
                                    { label: t("pdcPayable"), value: report.pdcPayable, color: "text-rose-600" },
                                    { label: t("advanceRent"), value: report.advanceRentBalance, color: "text-violet-600" },
                                ].map((item, i) => (
                                    <div key={i} className="bg-surface p-4 text-center">
                                        <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-2">{item.label}</p>
                                        <p className={cn("text-sm font-bold", item.color)}>{fmt(item.value)}</p>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}
                </div>
            )}

            {/* ==================== Trial Balance Tab ==================== */}
            {activeTab === "trialBalance" && trialBalance && !loading && (
                <div className="space-y-6">
                    {trialBalance.lines.length === 0 ? (
                        <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                            <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                                <BarChart3 size={32} />
                            </div>
                            <p className="text-sm font-bold text-muted mb-2 uppercase tracking-widest">{t("noDataForPeriod")}</p>
                        </div>
                    ) : (
                        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                            <div className="overflow-x-auto">
                                <table className="w-full">
                                    <thead>
                                        <tr className="bg-input/70">
                                            <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("accountCode")}</th>
                                            <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("accountName")}</th>
                                            <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("accountType")}</th>
                                            <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("debit")}</th>
                                            <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("credit")}</th>
                                            <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("accountBalance")}</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-border">
                                        {trialBalance.lines.map((line, i) => (
                                            <tr key={i} className="hover:bg-input/30">
                                                <td className="px-5 py-3 text-xs font-mono text-muted">{line.accountCode}</td>
                                                <td className="px-5 py-3 text-xs font-medium">{line.accountName}</td>
                                                <td className="px-5 py-3"><span className={`text-[9px] px-2 py-0.5 rounded-full font-bold ${typeColor(line.accountType)}`}>{line.accountType}</span></td>
                                                <td className="px-5 py-3 text-xs text-right font-medium tabular-nums">{fmt(line.debit)}</td>
                                                <td className="px-5 py-3 text-xs text-right font-medium tabular-nums">{fmt(line.credit)}</td>
                                                <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(line.balance)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                    <tfoot>
                                        <tr className="bg-input/70 border-t-2 border-border">
                                            <td colSpan={3} className="px-5 py-3 text-xs font-bold uppercase">Total</td>
                                            <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(trialBalance.totalDebit)}</td>
                                            <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(trialBalance.totalCredit)}</td>
                                            <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(trialBalance.totalDebit - trialBalance.totalCredit)}</td>
                                        </tr>
                                    </tfoot>
                                </table>
                            </div>
                        </div>
                    )}
                </div>
            )}

            {/* ==================== Aging Report Tab ==================== */}
            {activeTab === "aging" && agingData && !loading && (
                <div className="space-y-6">
                    {/* Total Outstanding KPI */}
                    <div className="bg-surface border border-border rounded-xl p-5 shadow-sm">
                        <p className="text-[10px] font-bold text-muted uppercase">{t("totalOutstanding")}</p>
                        <p className="text-xl font-bold text-foreground">{fmt(agingData.totalOutstanding)}</p>
                    </div>

                    {/* Bucket Summary Cards */}
                    <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
                        {bucketConfigs.map((cfg, idx) => {
                            const bucket = findBucket(cfg, idx);
                            if (!bucket) return null;
                            return (
                                <div key={cfg.key} className={`${cfg.bg} border ${cfg.border} rounded-xl p-5 shadow-sm`}>
                                    <p className={`text-[10px] font-bold uppercase ${cfg.text}`}>{cfg.label}</p>
                                    <p className={`text-xl font-bold ${cfg.text}`}>{fmt(bucket.amount)}</p>
                                    <p className="text-[10px] text-muted mt-1">{bucket.count} items</p>
                                </div>
                            );
                        })}
                    </div>

                    {/* Expandable Detail Tables per Bucket */}
                    {bucketConfigs.map((cfg, idx) => {
                        const bucket = findBucket(cfg, idx);
                        if (!bucket?.details || bucket.details.length === 0) return null;
                        const isExpanded = expandedBuckets[cfg.key];
                        return (
                            <div key={cfg.key} className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                                <button
                                    onClick={() => toggleBucket(cfg.key)}
                                    className="w-full flex items-center justify-between px-5 py-4 hover:bg-input/30 transition-colors"
                                >
                                    <span className={`text-sm font-bold ${cfg.text}`}>{cfg.label} ({bucket.count})</span>
                                    {isExpanded ? <ChevronUp size={16} className="text-muted" /> : <ChevronDown size={16} className="text-muted" />}
                                </button>
                                {isExpanded && (
                                    <div className="overflow-x-auto border-t border-border">
                                        <table className="w-full">
                                            <thead>
                                                <tr className="bg-input/70">
                                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("renterName")}</th>
                                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("propertyName")}</th>
                                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("unitNumber")}</th>
                                                    <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("accountBalance")}</th>
                                                    <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("daysOverdue")}</th>
                                                    <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("date")}</th>
                                                </tr>
                                            </thead>
                                            <tbody className="divide-y divide-border">
                                                {bucket.details.map((item, i) => (
                                                    <tr key={i} className="hover:bg-input/30">
                                                        <td className="px-5 py-3 text-xs font-medium">{item.renterName}</td>
                                                        <td className="px-5 py-3 text-xs text-muted">{item.propertyName}</td>
                                                        <td className="px-5 py-3 text-xs text-muted">{item.unitNumber}</td>
                                                        <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(item.amount)}</td>
                                                        <td className="px-5 py-3 text-xs text-right font-medium tabular-nums">{item.daysOverdue}</td>
                                                        <td className="px-5 py-3 text-xs text-right text-muted">{item.dueDate}</td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                )}
                            </div>
                        );
                    })}
                </div>
            )}

            {/* ==================== VAT Return Tab ==================== */}
            {activeTab === "vatReturn" && vatData && !loading && (
                <div className="space-y-6">
                    {/* KPI Summary Cards */}
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        <div className="bg-surface border border-border rounded-xl p-5 shadow-sm">
                            <p className="text-[10px] font-bold text-muted uppercase">{t("outputVat")}</p>
                            <p className="text-xl font-bold text-emerald-600">{fmt(vatData.totalOutputVat)}</p>
                            <p className="text-[10px] text-muted mt-1">{t("taxableSales")}: {fmt(vatData.totalTaxableSales)}</p>
                        </div>
                        <div className="bg-surface border border-border rounded-xl p-5 shadow-sm">
                            <p className="text-[10px] font-bold text-muted uppercase">{t("inputVat")}</p>
                            <p className="text-xl font-bold text-amber-600">{fmt(vatData.totalInputVat)}</p>
                            <p className="text-[10px] text-muted mt-1">{t("taxablePurchases")}: {fmt(vatData.totalTaxablePurchases)}</p>
                        </div>
                        <div className="bg-surface border border-border rounded-xl p-5 shadow-sm">
                            <p className="text-[10px] font-bold text-muted uppercase">{t("netVatPayable")}</p>
                            <p className={cn("text-xl font-bold", vatData.netVatPayable >= 0 ? "text-blue-600" : "text-rose-600")}>{fmt(vatData.netVatPayable)}</p>
                        </div>
                    </div>

                    {/* Sales Lines Table */}
                    {vatData.salesLines && vatData.salesLines.length > 0 && (
                        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-emerald-50 to-green-50 border-b border-emerald-100/50">
                                <h3 className="text-sm font-bold text-emerald-700">{t("outputVat")}</h3>
                            </div>
                            <div className="overflow-x-auto">
                                <table className="w-full">
                                    <thead>
                                        <tr className="bg-input/70">
                                            <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("description")}</th>
                                            <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("taxableSales")}</th>
                                            <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("vatAmount")}</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-border">
                                        {vatData.salesLines.map((line, i) => (
                                            <tr key={i} className="hover:bg-input/30">
                                                <td className="px-5 py-3 text-xs font-medium">{line.description}</td>
                                                <td className="px-5 py-3 text-xs text-right font-medium tabular-nums">{fmt(line.taxableAmount)}</td>
                                                <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(line.vatAmount)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                    <tfoot>
                                        <tr className="bg-emerald-50 border-t-2 border-emerald-200">
                                            <td className="px-5 py-3 text-xs font-bold uppercase">Total</td>
                                            <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(vatData.totalTaxableSales)}</td>
                                            <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(vatData.totalOutputVat)}</td>
                                        </tr>
                                    </tfoot>
                                </table>
                            </div>
                        </div>
                    )}

                    {/* Purchase Lines Table */}
                    {vatData.purchaseLines && vatData.purchaseLines.length > 0 && (
                        <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-amber-50 to-yellow-50 border-b border-amber-100/50">
                                <h3 className="text-sm font-bold text-amber-700">{t("inputVat")}</h3>
                            </div>
                            <div className="overflow-x-auto">
                                <table className="w-full">
                                    <thead>
                                        <tr className="bg-input/70">
                                            <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("description")}</th>
                                            <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("taxablePurchases")}</th>
                                            <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("vatAmount")}</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-border">
                                        {vatData.purchaseLines.map((line, i) => (
                                            <tr key={i} className="hover:bg-input/30">
                                                <td className="px-5 py-3 text-xs font-medium">{line.description}</td>
                                                <td className="px-5 py-3 text-xs text-right font-medium tabular-nums">{fmt(line.taxableAmount)}</td>
                                                <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(line.vatAmount)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                    <tfoot>
                                        <tr className="bg-amber-50 border-t-2 border-amber-200">
                                            <td className="px-5 py-3 text-xs font-bold uppercase">Total</td>
                                            <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(vatData.totalTaxablePurchases)}</td>
                                            <td className="px-5 py-3 text-xs text-right font-bold tabular-nums">{fmt(vatData.totalInputVat)}</td>
                                        </tr>
                                    </tfoot>
                                </table>
                            </div>
                        </div>
                    )}
                </div>
            )}

            {/* Tickets Report */}
            {activeTab === "tickets" && ticketReport && !loading && (
                <div className="space-y-6">
                    {/* KPI Cards */}
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                        <div className="bg-surface rounded-xl p-5 border border-border">
                            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Total Tickets</p>
                            <p className="text-2xl font-bold text-foreground tabular-nums">{ticketReport.totalTickets}</p>
                        </div>
                        <div className="bg-surface rounded-xl p-5 border border-border">
                            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Open</p>
                            <p className="text-2xl font-bold text-warning tabular-nums">{ticketReport.openCount}
                                {ticketReport.overdueCount > 0 && <span className="text-xs text-error ml-2">({ticketReport.overdueCount} overdue)</span>}
                            </p>
                        </div>
                        <div className="bg-surface rounded-xl p-5 border border-border">
                            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Avg Resolution</p>
                            <p className="text-2xl font-bold text-foreground tabular-nums">{ticketReport.avgResolutionHours?.toFixed(1) || "—"} <span className="text-xs text-muted font-normal">hrs</span></p>
                        </div>
                        <div className="bg-surface rounded-xl p-5 border border-border">
                            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">Avg Satisfaction</p>
                            <p className="text-2xl font-bold text-accent tabular-nums">{ticketReport.avgSatisfaction?.toFixed(1) || "—"} <span className="text-xs text-muted font-normal">/ 5</span></p>
                        </div>
                    </div>

                    {/* Breakdown Tables */}
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                        {/* By Status */}
                        <div className="bg-surface rounded-xl border border-border overflow-hidden">
                            <div className="px-5 py-3 bg-input/50"><h3 className="text-[11px] font-semibold text-muted uppercase tracking-wider">By Status</h3></div>
                            <div className="divide-y divide-border">
                                {[
                                    { label: "Open", count: ticketReport.openCount, color: "text-warning" },
                                    { label: "Resolved", count: ticketReport.resolvedCount, color: "text-success" },
                                    { label: "Closed", count: ticketReport.closedCount, color: "text-muted" },
                                ].map(r => (
                                    <div key={r.label} className="flex items-center justify-between px-5 py-2.5">
                                        <span className="text-xs text-foreground">{r.label}</span>
                                        <span className={cn("text-xs font-bold tabular-nums", r.color)}>{r.count}</span>
                                    </div>
                                ))}
                            </div>
                        </div>

                        {/* By Category */}
                        <div className="bg-surface rounded-xl border border-border overflow-hidden">
                            <div className="px-5 py-3 bg-input/50"><h3 className="text-[11px] font-semibold text-muted uppercase tracking-wider">By Category</h3></div>
                            <div className="divide-y divide-border">
                                {ticketReport.ticketsByCategory && Object.entries(ticketReport.ticketsByCategory).map(([cat, count]) => (
                                    <div key={cat} className="flex items-center justify-between px-5 py-2.5">
                                        <span className="text-xs text-foreground">{cat.replace(/_/g, " ")}</span>
                                        <span className="text-xs font-bold text-foreground tabular-nums">{count}</span>
                                    </div>
                                ))}
                            </div>
                        </div>

                        {/* By Priority */}
                        <div className="bg-surface rounded-xl border border-border overflow-hidden">
                            <div className="px-5 py-3 bg-input/50"><h3 className="text-[11px] font-semibold text-muted uppercase tracking-wider">By Priority</h3></div>
                            <div className="divide-y divide-border">
                                {ticketReport.ticketsByPriority && Object.entries(ticketReport.ticketsByPriority).map(([pri, count]) => (
                                    <div key={pri} className="flex items-center justify-between px-5 py-2.5">
                                        <span className="text-xs text-foreground">{pri}</span>
                                        <span className="text-xs font-bold text-foreground tabular-nums">{count}</span>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* No data state */}
            {!loading && (
                (activeTab === "pnl" && !report) ||
                (activeTab === "balanceSheet" && !report) ||
                (activeTab === "trialBalance" && !trialBalance) ||
                (activeTab === "aging" && !agingData) ||
                (activeTab === "vatReturn" && !vatData) ||
                (activeTab === "tickets" && !ticketReport)
            ) && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted shadow-sm mb-6">
                        <BarChart3 size={32} />
                    </div>
                    <p className="text-sm font-bold text-muted mb-2 uppercase tracking-widest">{t("noReport")}</p>
                    <p className="text-xs text-muted">Select a report type and click Generate to view financial data.</p>
                </div>
            )}
        </div>
    );
}
