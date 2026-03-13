"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import {
    BarChart3, Building2, Globe, Calendar, TrendingUp, TrendingDown,
    DollarSign, ArrowDown, ArrowUp, Wallet
} from "lucide-react";
import { cn } from "@/lib/utils";

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

export default function ReportsPage() {
    const t = useTranslations("Finance");
    const [reportMode, setReportMode] = useState<"ORGANISATION" | "PROPERTY">("ORGANISATION");
    const [selectedPropertyId, setSelectedPropertyId] = useState("");
    const [startDate, setStartDate] = useState("");
    const [endDate, setEndDate] = useState("");
    const [properties, setProperties] = useState<PropertyStats[]>([]);
    const [report, setReport] = useState<ReportData | null>(null);
    const [loading, setLoading] = useState(false);
    const [validationError, setValidationError] = useState("");

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

    const generateReport = async () => {
        setValidationError("");
        setLoading(true);
        try {
            const params = new URLSearchParams();
            if (startDate) params.set("startDate", startDate);
            if (endDate) params.set("endDate", endDate);

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
            if (res.ok) {
                setReport(await res.json());
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const formatAmount = (amount: number) =>
        amount.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

    return (
        <div>
            <div className="mb-10">
                <h1 className="text-xl font-black text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <BarChart3 size={20} className="text-primary" />
                    {t("reports")}
                </h1>
                <p className="text-xs text-gray-500 font-medium">{t("reportsDesc")}</p>
            </div>

            {/* Report Configuration */}
            <div className="bg-white border border-border rounded-2xl p-6 shadow-sm mb-8">
                <div className="grid grid-cols-1 md:grid-cols-5 gap-4 items-end">
                    <div>
                        <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5">{t("reportType")}</label>
                        <div className="flex gap-2">
                            <button
                                onClick={() => { setReportMode("ORGANISATION"); setValidationError(""); }}
                                className={cn("flex-1 flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 border cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none",
                                    reportMode === "ORGANISATION" ? "bg-primary text-white border-primary shadow-sm" : "bg-white text-gray-500 border-border hover:bg-gray-50"
                                )}
                            >
                                <Globe size={12} /> Org
                            </button>
                            <button
                                onClick={() => { setReportMode("PROPERTY"); setValidationError(""); }}
                                className={cn("flex-1 flex items-center justify-center gap-1.5 px-3 py-2.5 rounded-xl text-xs font-bold transition-all duration-200 border cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none",
                                    reportMode === "PROPERTY" ? "bg-primary text-white border-primary shadow-sm" : "bg-white text-gray-500 border-border hover:bg-gray-50"
                                )}
                            >
                                <Building2 size={12} /> Property
                            </button>
                        </div>
                    </div>

                    {reportMode === "PROPERTY" && (
                        <div>
                            <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5">{t("selectProperty")}</label>
                            <select
                                className={cn(
                                    "w-full bg-input border p-2.5 rounded-xl text-xs cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200",
                                    validationError && !selectedPropertyId ? "border-red-300" : "border-border"
                                )}
                                value={selectedPropertyId}
                                onChange={ev => { setSelectedPropertyId(ev.target.value); setValidationError(""); }}
                            >
                                <option value="">Select...</option>
                                {properties.map(s => <option key={s.property.id} value={s.property.id}>{s.property.nameEn}</option>)}
                            </select>
                            {validationError && !selectedPropertyId && (
                                <p className="text-[10px] text-red-500 font-medium mt-1">{validationError}</p>
                            )}
                        </div>
                    )}

                    <div>
                        <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5">{t("startDate")}</label>
                        <input type="date" className="w-full bg-input border border-border p-2.5 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={startDate} onChange={ev => setStartDate(ev.target.value)} />
                    </div>
                    <div>
                        <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5">{t("endDate")}</label>
                        <input type="date" className="w-full bg-input border border-border p-2.5 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={endDate} onChange={ev => setEndDate(ev.target.value)} />
                    </div>
                    <div>
                        <button
                            onClick={generateReport}
                            disabled={loading}
                            className="w-full flex items-center justify-center gap-2 bg-gradient-to-r from-primary to-blue-500 text-white px-5 py-2.5 rounded-xl text-xs font-bold hover:opacity-90 transition-all duration-200 shadow-lg shadow-primary/20 active:scale-95 disabled:opacity-50 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            {loading ? "Generating..." : t("generateReport")}
                        </button>
                    </div>
                </div>
            </div>

            {/* Loading Skeleton */}
            {loading && (
                <div className="space-y-6 animate-pulse">
                    <div className="bg-gray-200 rounded-2xl h-48" />
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                        <div className="bg-gray-200 rounded-2xl h-64" />
                        <div className="bg-gray-200 rounded-2xl h-64" />
                    </div>
                </div>
            )}

            {/* Report Display */}
            {report && !loading && (
                <div className="space-y-6">
                    {/* Header */}
                    <div className="bg-gradient-to-r from-gray-900 to-gray-800 rounded-2xl p-6 text-white">
                        <div className="flex items-center justify-between mb-4">
                            <div>
                                <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">{report.reportType} Report</p>
                                <h2 className="text-lg font-black mt-1">{report.reportName || "Financial Report"}</h2>
                            </div>
                            <div className="text-right">
                                <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest">Period</p>
                                <p className="text-sm font-bold mt-1">{report.dateRange}</p>
                            </div>
                        </div>

                        {/* KPI Cards */}
                        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-4">
                            <div className="bg-white/10 backdrop-blur-sm rounded-xl p-4 border border-white/10">
                                <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider flex items-center gap-1"><TrendingUp size={10} /> {t("totalIncome")}</p>
                                <p className="text-lg font-black text-emerald-400 mt-1">{formatAmount(report.totalIncome)}</p>
                            </div>
                            <div className="bg-white/10 backdrop-blur-sm rounded-xl p-4 border border-white/10">
                                <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider flex items-center gap-1"><TrendingDown size={10} /> {t("totalExpenses")}</p>
                                <p className="text-lg font-black text-rose-400 mt-1">{formatAmount(report.totalExpenses)}</p>
                            </div>
                            <div className="bg-white/10 backdrop-blur-sm rounded-xl p-4 border border-white/10">
                                <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider flex items-center gap-1"><DollarSign size={10} /> {t("noi")}</p>
                                <p className={cn("text-lg font-black mt-1", report.netOperatingIncome >= 0 ? "text-emerald-400" : "text-rose-400")}>{formatAmount(report.netOperatingIncome)}</p>
                            </div>
                            <div className="bg-white/10 backdrop-blur-sm rounded-xl p-4 border border-white/10">
                                <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider flex items-center gap-1"><Wallet size={10} /> {t("netProfit")}</p>
                                <p className={cn("text-lg font-black mt-1", report.netProfit >= 0 ? "text-emerald-400" : "text-rose-400")}>{formatAmount(report.netProfit)}</p>
                            </div>
                        </div>
                    </div>

                    {/* Income & Expense Breakdown */}
                    <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
                        {/* Income */}
                        <div className="bg-white border border-border rounded-2xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-emerald-50 to-green-50 border-b border-emerald-100/50">
                                <h3 className="text-sm font-black text-emerald-700 flex items-center gap-2">
                                    <ArrowDown size={14} /> {t("incomeBreakdown")}
                                </h3>
                            </div>
                            <div className="divide-y divide-gray-50">
                                <div className="flex justify-between px-5 py-3">
                                    <span className="text-xs font-bold text-gray-500">{t("rentalIncome")}</span>
                                    <span className="text-xs font-black text-emerald-600">{formatAmount(report.totalRentalIncome)}</span>
                                </div>
                                <div className="flex justify-between px-5 py-3">
                                    <span className="text-xs font-bold text-gray-500">{t("otherIncome")}</span>
                                    <span className="text-xs font-black text-emerald-600">{formatAmount(report.totalOtherIncome)}</span>
                                </div>
                                {report.incomeBreakdown && Object.entries(report.incomeBreakdown).map(([key, val]) => (
                                    <div key={key} className="flex justify-between px-5 py-2.5 bg-gray-50/50">
                                        <span className="text-[10px] text-gray-400 pl-4">{key}</span>
                                        <span className="text-[10px] font-bold text-gray-500">{formatAmount(val)}</span>
                                    </div>
                                ))}
                                <div className="flex justify-between px-5 py-3 bg-emerald-50">
                                    <span className="text-xs font-black text-emerald-700 uppercase">{t("totalIncome")}</span>
                                    <span className="text-xs font-black text-emerald-700">{formatAmount(report.totalIncome)}</span>
                                </div>
                            </div>
                        </div>

                        {/* Expenses */}
                        <div className="bg-white border border-border rounded-2xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-rose-50 to-red-50 border-b border-rose-100/50">
                                <h3 className="text-sm font-black text-rose-700 flex items-center gap-2">
                                    <ArrowUp size={14} /> {t("expenseBreakdown")}
                                </h3>
                            </div>
                            <div className="divide-y divide-gray-50">
                                <div className="flex justify-between px-5 py-3">
                                    <span className="text-xs font-bold text-gray-500">{t("directExpenses")}</span>
                                    <span className="text-xs font-black text-rose-600">{formatAmount(report.totalDirectExpenses)}</span>
                                </div>
                                {report.directExpenseBreakdown && Object.entries(report.directExpenseBreakdown).map(([key, val]) => (
                                    <div key={key} className="flex justify-between px-5 py-2.5 bg-gray-50/50">
                                        <span className="text-[10px] text-gray-400 pl-4">{key}</span>
                                        <span className="text-[10px] font-bold text-gray-500">{formatAmount(val)}</span>
                                    </div>
                                ))}
                                <div className="flex justify-between px-5 py-3">
                                    <span className="text-xs font-bold text-gray-500">{t("indirectExpenses")}</span>
                                    <span className="text-xs font-black text-rose-600">{formatAmount(report.totalIndirectExpenses)}</span>
                                </div>
                                {report.indirectExpenseBreakdown && Object.entries(report.indirectExpenseBreakdown).map(([key, val]) => (
                                    <div key={key} className="flex justify-between px-5 py-2.5 bg-gray-50/50">
                                        <span className="text-[10px] text-gray-400 pl-4">{key}</span>
                                        <span className="text-[10px] font-bold text-gray-500">{formatAmount(val)}</span>
                                    </div>
                                ))}
                                <div className="flex justify-between px-5 py-3 bg-rose-50">
                                    <span className="text-xs font-black text-rose-700 uppercase">{t("totalExpenses")}</span>
                                    <span className="text-xs font-black text-rose-700">{formatAmount(report.totalExpenses)}</span>
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Balance Sheet (Organisation only) */}
                    {report.reportType === "ORGANISATION" && (
                        <div className="bg-white border border-border rounded-2xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-violet-50 to-indigo-50 border-b border-violet-100/50">
                                <h3 className="text-sm font-black text-violet-700 flex items-center gap-2">
                                    <Wallet size={14} /> {t("balanceSheet")}
                                </h3>
                            </div>
                            <div className="grid grid-cols-3 divide-x divide-gray-100">
                                <div className="p-5 text-center">
                                    <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-2">{t("totalAssets")}</p>
                                    <p className="text-lg font-black text-emerald-600">{formatAmount(report.totalAssets)}</p>
                                </div>
                                <div className="p-5 text-center">
                                    <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-2">{t("totalLiabilities")}</p>
                                    <p className="text-lg font-black text-rose-600">{formatAmount(report.totalLiabilities)}</p>
                                </div>
                                <div className="p-5 text-center">
                                    <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-2">{t("equity")}</p>
                                    <p className="text-lg font-black text-violet-600">{formatAmount(report.totalEquity)}</p>
                                </div>
                            </div>
                        </div>
                    )}

                    {/* Property-specific line items */}
                    {report.reportType === "PROPERTY" && (
                        <div className="bg-white border border-border rounded-2xl overflow-hidden shadow-sm">
                            <div className="px-5 py-4 bg-gradient-to-r from-blue-50 to-cyan-50 border-b border-blue-100/50">
                                <h3 className="text-sm font-black text-blue-700 flex items-center gap-2">
                                    <Building2 size={14} /> {t("propertyDetails")}
                                </h3>
                            </div>
                            <div className="grid grid-cols-2 md:grid-cols-5 gap-px bg-gray-100">
                                {[
                                    { label: t("outstandingReceivables"), value: report.outstandingRentReceivables, color: "text-amber-600" },
                                    { label: t("securityDeposits"), value: report.securityDepositsHeld, color: "text-blue-600" },
                                    { label: t("pdcReceivable"), value: report.pdcReceivable, color: "text-emerald-600" },
                                    { label: t("pdcPayable"), value: report.pdcPayable, color: "text-rose-600" },
                                    { label: t("advanceRent"), value: report.advanceRentBalance, color: "text-violet-600" },
                                ].map((item, i) => (
                                    <div key={i} className="bg-white p-4 text-center">
                                        <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-2">{item.label}</p>
                                        <p className={cn("text-sm font-black", item.color)}>{formatAmount(item.value)}</p>
                                    </div>
                                ))}
                            </div>
                        </div>
                    )}
                </div>
            )}

            {/* No report state */}
            {!report && !loading && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <BarChart3 size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-2 uppercase tracking-widest">{t("noReport")}</p>
                    <p className="text-xs text-gray-400">Select a report type and click Generate to view financial data.</p>
                </div>
            )}
        </div>
    );
}
