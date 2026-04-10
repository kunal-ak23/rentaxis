"use client";

import React, { useState, useEffect, useRef } from "react";
import { useTranslations } from "next-intl";
import {
    Receipt, Plus, X, Filter, Calendar, Building2, Home, ChevronDown, Loader2, LayoutList, BookOpen, ChevronLeft, ChevronRight
} from "lucide-react";
import { cn } from "@/lib/utils";
import { formatCurrency, formatCurrencyCompact, formatNumber } from "@/lib/format";

type Account = {
    id: string;
    code: string;
    name: string;
    accountType: string;
};

type Property = {
    id: string;
    nameEn: string;
};

type PropertyStats = {
    property: Property;
};

type UnitData = {
    id: string;
    unitNumber: string;
    currentTenantName?: string;
};

type Transaction = {
    id: string;
    date: string;
    description: string;
    account: Account | null;
    accountCode: string;
    accountType: string;
    debit: number;
    credit: number;
    property: Property | null;
    unit: UnitData | null;
    vatApplicable: boolean;
    vatAmount: number;
    notes: string;
    splitParent: boolean;
    splitChildren?: Transaction[];
    parentTransaction?: { id: string } | null;
};

const TYPE_COLORS: Record<string, string> = {
    ASSET: "bg-success/10 text-success",
    LIABILITY: "bg-error/10 text-error",
    INCOME: "bg-info/10 text-info",
    EXPENSE: "bg-warning/10 text-warning",
    EQUITY: "bg-info/10 text-info",
};

export default function TransactionsPage() {
    const t = useTranslations("Finance");
    const [transactions, setTransactions] = useState<Transaction[]>([]);
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [properties, setProperties] = useState<PropertyStats[]>([]);
    const [units, setUnits] = useState<UnitData[]>([]);
    const [loading, setLoading] = useState(true);
    const [showForm, setShowForm] = useState(false);
    const [showFilters, setShowFilters] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [viewMode, setViewMode] = useState<"simple" | "accounting">("simple");
    const [currentPage, setCurrentPage] = useState(1);
    const [expandedSplits, setExpandedSplits] = useState<Set<string>>(new Set());

    const toggleSplitExpand = async (txnId: string) => {
        const next = new Set(expandedSplits);
        if (next.has(txnId)) {
            next.delete(txnId);
            setExpandedSplits(next);
            return;
        }
        // Check if children already loaded
        const txn = transactions.find(t => t.id === txnId);
        if (txn && (!txn.splitChildren || txn.splitChildren.length === 0)) {
            try {
                const res = await fetch(`/api/proxy/v1/finance/transactions/${txnId}`);
                if (res.ok) {
                    const data = await res.json();
                    setTransactions(prev => prev.map(t =>
                        t.id === txnId ? { ...t, splitChildren: data.splitChildren || [] } : t
                    ));
                }
            } catch (err) {
                console.error(err);
            }
        }
        next.add(txnId);
        setExpandedSplits(next);
    };
    const pageSize = 25;

    const [filters, setFilters] = useState({
        propertyId: "",
        accountType: "",
        startDate: "",
        endDate: ""
    });

    const [formData, setFormData] = useState({
        date: new Date().toISOString().split("T")[0],
        description: "",
        accountId: "",
        debit: 0,
        credit: 0,
        propertyId: "",
        unitId: "",
        vatApplicable: false,
        vatAmount: 0,
        vatRate: 5,
        grossAmount: 0,
        netAmount: 0,
        notes: ""
    });

    const [splitMode, setSplitMode] = useState(false);
    const [splits, setSplits] = useState<Array<{
        id: number;
        propertyId: string;
        unitId: string;
        amount: number;
        units: UnitData[];
    }>>([
        { id: 1, propertyId: "", unitId: "", amount: 0, units: [] },
        { id: 2, propertyId: "", unitId: "", amount: 0, units: [] },
    ]);
    const splitIdRef = useRef(3);

    useEffect(() => {
        fetchTransactions();
        fetchAccounts();
        fetchProperties();
    }, []);

    const fetchTransactions = async (customFilters?: typeof filters) => {
        try {
            const f = customFilters || filters;
            const params = new URLSearchParams();
            if (f.propertyId) params.set("propertyId", f.propertyId);
            if (f.accountType) params.set("accountType", f.accountType);
            if (f.startDate) params.set("startDate", f.startDate);
            if (f.endDate) params.set("endDate", f.endDate);

            const res = await fetch(`/api/proxy/v1/finance/transactions?${params.toString()}`);
            if (res.ok) {
                const data = await res.json();
                setTransactions(data);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const fetchAccounts = async () => {
        try {
            const res = await fetch("/api/proxy/v1/finance/accounts");
            if (res.ok) setAccounts(await res.json());
        } catch (err) {
            console.error(err);
        }
    };

    const fetchProperties = async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) setProperties(await res.json());
        } catch (err) {
            console.error(err);
        }
    };

    const fetchUnitsForProperty = async (propertyId: string) => {
        if (!propertyId) {
            setUnits([]);
            return;
        }
        try {
            const res = await fetch(`/api/proxy/v1/units/property/${propertyId}`);
            if (res.ok) setUnits(await res.json());
        } catch (err) {
            console.error(err);
        }
    };

    const resetForm = () => {
        setFormData({
            date: new Date().toISOString().split("T")[0],
            description: "",
            accountId: "",
            debit: 0,
            credit: 0,
            propertyId: "",
            unitId: "",
            vatApplicable: false,
            vatAmount: 0,
            vatRate: 5,
            grossAmount: 0,
            netAmount: 0,
            notes: ""
        });
        setUnits([]);
        setSplitMode(false);
        splitIdRef.current = 3;
        setSplits([
            { id: 1, propertyId: "", unitId: "", amount: 0, units: [] },
            { id: 2, propertyId: "", unitId: "", amount: 0, units: [] },
        ]);
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        setSubmitting(true);
        try {
            if (splitMode) {
                const body = {
                    date: formData.date,
                    description: formData.description,
                    accountId: formData.accountId,
                    debit: formData.debit || 0,
                    credit: formData.credit || 0,
                    vatApplicable: formData.vatApplicable,
                    vatRate: formData.vatApplicable ? formData.vatRate : 0,
                    notes: formData.notes,
                    splits: splits.map(s => ({
                        propertyId: s.propertyId || null,
                        unitId: s.unitId || null,
                        amount: s.amount,
                    })),
                };
                const res = await fetch("/api/proxy/v1/finance/transactions/split", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(body),
                });
                if (res.ok) {
                    setShowForm(false);
                    fetchTransactions();
                    resetForm();
                }
            } else {
                const body: Record<string, unknown> = {
                    date: formData.date,
                    description: formData.description,
                    account: { id: formData.accountId },
                    debit: formData.debit,
                    credit: formData.credit,
                    vatApplicable: formData.vatApplicable,
                    vatAmount: formData.vatAmount,
                    vatRate: formData.vatApplicable ? formData.vatRate : 0,
                    netAmount: formData.vatApplicable ? formData.netAmount : 0,
                    grossAmount: formData.vatApplicable ? formData.grossAmount : 0,
                    notes: formData.notes
                };
                if (formData.propertyId) body.property = { id: formData.propertyId };
                if (formData.unitId) body.unit = { id: formData.unitId };
                const res = await fetch("/api/proxy/v1/finance/transactions", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(body)
                });
                if (res.ok) {
                    setShowForm(false);
                    fetchTransactions();
                    resetForm();
                }
            }
        } catch (err) {
            console.error(err);
        } finally {
            setSubmitting(false);
        }
    };

    const applyFilters = () => {
        fetchTransactions(filters);
        setShowFilters(false);
        setCurrentPage(1);
    };

    const clearFilters = () => {
        const empty = { propertyId: "", accountType: "", startDate: "", endDate: "" };
        setFilters(empty);
        fetchTransactions(empty);
        setCurrentPage(1);
    };

    const transactionAmount = formData.debit || formData.credit || 0;
    const splitTotal = splits.reduce((sum, s) => sum + (s.amount || 0), 0);
    const splitBalanced = Math.abs(splitTotal - transactionAmount) < 0.01 && transactionAmount > 0;

    const handleSplitToggle = (enabled: boolean) => {
        setSplitMode(enabled);
        if (enabled && transactionAmount > 0) {
            const equalShare = Math.round((transactionAmount / 2) * 100) / 100;
            splitIdRef.current = 3;
            setSplits([
                { id: 1, propertyId: "", unitId: "", amount: equalShare, units: [] },
                { id: 2, propertyId: "", unitId: "", amount: transactionAmount - equalShare, units: [] },
            ]);
        }
    };

    const addSplitRow = () => {
        setSplits(prev => [...prev, { id: splitIdRef.current++, propertyId: "", unitId: "", amount: 0, units: [] }]);
    };

    const removeSplitRow = (index: number) => {
        setSplits(prev => {
            if (prev.length <= 2) return prev;
            return prev.filter((_, i) => i !== index);
        });
    };

    const updateSplitProperty = async (index: number, propertyId: string) => {
        setSplits(prev => {
            const updated = [...prev];
            updated[index] = { ...updated[index], propertyId, unitId: "", units: [] };
            return updated;
        });
        if (!propertyId) return;
        try {
            const res = await fetch(`/api/proxy/v1/units/property/${propertyId}`);
            if (res.ok) {
                const fetchedUnits = await res.json();
                setSplits(prev => {
                    const updated = [...prev];
                    updated[index] = { ...updated[index], units: fetchedUnits };
                    return updated;
                });
            }
        } catch (err) {
            console.error(err);
        }
    };

    const updateSplitUnit = (index: number, unitId: string) => {
        setSplits(prev => {
            const updated = [...prev];
            updated[index] = { ...updated[index], unitId };
            return updated;
        });
    };

    const updateSplitAmount = (index: number, amount: number) => {
        setSplits(prev => {
            const updated = [...prev];
            updated[index] = { ...updated[index], amount };
            return updated;
        });
    };

    const distributeSplitsEqually = () => {
        if (transactionAmount <= 0) return;
        setSplits(prev => {
            if (prev.length === 0) return prev;
            const equalShare = Math.round((transactionAmount / prev.length) * 100) / 100;
            const remainder = Math.round((transactionAmount - equalShare * prev.length) * 100) / 100;
            return prev.map((s, i) => ({
                ...s,
                amount: i === 0 ? equalShare + remainder : equalShare,
            }));
        });
    };

    const totalDebit = transactions.reduce((s, t) => s + (t.debit || 0), 0);
    const totalCredit = transactions.reduce((s, t) => s + (t.credit || 0), 0);

    // Simple ledger: group paired double-entry rows into single "money in / money out" rows
    const simpleLedger = (() => {
        // Only show INCOME (money in) and EXPENSE (money out) entries, skip ASSET/LIABILITY pairs
        const rows = transactions
            .filter(t => t.accountType === "INCOME" || t.accountType === "EXPENSE")
            .map(t => {
                const isIncome = t.accountType === "INCOME";
                return {
                    id: t.id,
                    date: t.date,
                    description: t.description,
                    property: t.property,
                    unit: t.unit,
                    moneyIn: isIncome ? (t.credit || t.debit || 0) : 0,
                    moneyOut: !isIncome ? (t.debit || t.credit || 0) : 0,
                    notes: t.notes,
                    splitParent: t.splitParent || false,
                    splitChildren: t.splitChildren,
                };
            })
            .sort((a, b) => a.date.localeCompare(b.date));

        // Compute running balance
        let balance = 0;
        return rows.map(r => {
            balance += r.moneyIn - r.moneyOut;
            return { ...r, balance };
        });
    })();

    const simpleTotalIn = simpleLedger.reduce((s, r) => s + r.moneyIn, 0);
    const simpleTotalOut = simpleLedger.reduce((s, r) => s + r.moneyOut, 0);

    // Pagination
    const accountingTotal = transactions.length;
    const simpleTotal = simpleLedger.length;
    const activeTotal = viewMode === "simple" ? simpleTotal : accountingTotal;
    const totalPages = Math.max(1, Math.ceil(activeTotal / pageSize));
    const safePage = Math.min(currentPage, totalPages);

    const paginatedTransactions = transactions.slice((safePage - 1) * pageSize, safePage * pageSize);
    const paginatedSimple = simpleLedger.slice((safePage - 1) * pageSize, safePage * pageSize);

    return (
        <div>
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Receipt size={20} className="text-primary" />
                        {t("transactions")}
                    </h1>
                    <p className="text-xs text-muted font-medium">
                        {t("transactionsDesc")}
                    </p>
                </div>
                <div className="flex gap-3">
                    {/* View Mode Toggle */}
                    <div className="flex bg-input rounded-full p-0.5">
                        <button
                            onClick={() => { setViewMode("simple"); setCurrentPage(1); }}
                            className={cn(
                                "flex items-center gap-1.5 px-3.5 py-2 rounded-full text-[10px] font-bold uppercase tracking-wider transition-all duration-200 cursor-pointer",
                                viewMode === "simple"
                                    ? "bg-surface text-foreground shadow-sm"
                                    : "text-muted hover:text-foreground"
                            )}
                        >
                            <LayoutList size={12} />
                            Simple
                        </button>
                        <button
                            onClick={() => { setViewMode("accounting"); setCurrentPage(1); }}
                            className={cn(
                                "flex items-center gap-1.5 px-3.5 py-2 rounded-full text-[10px] font-bold uppercase tracking-wider transition-all duration-200 cursor-pointer",
                                viewMode === "accounting"
                                    ? "bg-surface text-foreground shadow-sm"
                                    : "text-muted hover:text-foreground"
                            )}
                        >
                            <BookOpen size={12} />
                            Accounting
                        </button>
                    </div>
                    <button
                        onClick={() => setShowFilters(!showFilters)}
                        className={cn(
                            "flex items-center gap-2 bg-surface text-foreground border border-border px-4 py-2.5 rounded-full text-xs font-bold transition-all duration-200 shadow-sm active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none",
                            showFilters && "bg-primary/5 border-primary/30"
                        )}
                    >
                        <Filter size={14} />
                        {t("filter")}
                    </button>
                    <button
                        onClick={() => setShowForm(true)}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:bg-primary/90 transition-all duration-200 shadow-lg shadow-primary/10 active:scale-95 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        <Plus size={14} />
                        {t("addTransaction")}
                    </button>
                </div>
            </div>

            {/* Filter Bar */}
            {showFilters && (
                <div className="mb-8 bg-surface border border-border rounded-xl p-5 shadow-sm">
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                        <div>
                            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5">{t("property")}</label>
                            <select className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={filters.propertyId} onChange={ev => setFilters({ ...filters, propertyId: ev.target.value })}>
                                <option value="">All Properties</option>
                                {properties.map(s => <option key={s.property.id} value={s.property.id}>{s.property.nameEn}</option>)}
                            </select>
                        </div>
                        <div>
                            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5">{t("accountType")}</label>
                            <select className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={filters.accountType} onChange={ev => setFilters({ ...filters, accountType: ev.target.value })}>
                                <option value="">All Types</option>
                                {["ASSET", "LIABILITY", "INCOME", "EXPENSE", "EQUITY"].map(type => <option key={type} value={type}>{type}</option>)}
                            </select>
                        </div>
                        <div>
                            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5">{t("startDate")}</label>
                            <input type="date" className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={filters.startDate} onChange={ev => setFilters({ ...filters, startDate: ev.target.value })} />
                        </div>
                        <div>
                            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5">{t("endDate")}</label>
                            <input type="date" className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs cursor-pointer focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={filters.endDate} onChange={ev => setFilters({ ...filters, endDate: ev.target.value })} />
                        </div>
                    </div>
                    <div className="flex justify-end gap-3 mt-4">
                        <button onClick={clearFilters} className="text-xs font-bold text-muted hover:text-foreground cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg px-2 py-1">{t("clearFilters")}</button>
                        <button onClick={applyFilters} className="px-6 py-2 bg-primary text-primary-foreground rounded-lg text-xs font-bold cursor-pointer transition-all duration-200 hover:bg-primary/90 focus:ring-2 focus:ring-primary/20 focus:outline-none">{t("applyFilters")}</button>
                    </div>
                </div>
            )}

            {/* Add Transaction Modal */}
            {showForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-2xl w-full shadow-2xl border border-border relative max-h-[90vh] overflow-y-auto">
                        <button onClick={() => setShowForm(false)} aria-label="Close modal" className="absolute right-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-lg"><X size={18} /></button>
                        <h2 className="text-lg font-bold mb-1">{t("addTransaction")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">Record a new financial transaction.</p>
                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("date")}</label>
                                <input required type="date" className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={formData.date} onChange={ev => setFormData({ ...formData, date: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("account")}</label>
                                <select required className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={formData.accountId} onChange={ev => setFormData({ ...formData, accountId: ev.target.value })}>
                                    <option value="">Select Account</option>
                                    {accounts.map(a => <option key={a.id} value={a.id}>{a.code} - {a.name}</option>)}
                                </select>
                            </div>
                            <div className="col-span-2">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("description")}</label>
                                <input required placeholder="e.g. Rental income - Belle Vue tenant January 2025" className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={formData.description} onChange={ev => setFormData({ ...formData, description: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("debit")}</label>
                                <input type="number" step="0.01" placeholder="0.00" className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={formData.debit || ""} onChange={ev => {
                                    const debit = Number(ev.target.value);
                                    const amount = debit || formData.credit;
                                    const vatAmt = formData.vatApplicable ? Math.round(amount * formData.vatRate) / 100 : 0;
                                    setFormData({ ...formData, debit, netAmount: amount, vatAmount: vatAmt, grossAmount: amount + vatAmt });
                                }} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("credit")}</label>
                                <input type="number" step="0.01" placeholder="0.00" className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={formData.credit || ""} onChange={ev => {
                                    const credit = Number(ev.target.value);
                                    const amount = formData.debit || credit;
                                    const vatAmt = formData.vatApplicable ? Math.round(amount * formData.vatRate) / 100 : 0;
                                    setFormData({ ...formData, credit, netAmount: amount, vatAmount: vatAmt, grossAmount: amount + vatAmt });
                                }} />
                            </div>
                            {/* Split toggle */}
                            <div className="col-span-2 flex items-center gap-3">
                                <label className="flex items-center gap-2 cursor-pointer">
                                    <input
                                        type="checkbox"
                                        className="rounded border-border"
                                        checked={splitMode}
                                        onChange={ev => handleSplitToggle(ev.target.checked)}
                                    />
                                    <span className="text-xs font-bold text-muted">Split across multiple properties/units</span>
                                </label>
                            </div>

                            {!splitMode ? (
                                <>
                                    <div className="col-span-1">
                                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("property")} (Project)</label>
                                        <select
                                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                            value={formData.propertyId}
                                            onChange={ev => {
                                                setFormData({ ...formData, propertyId: ev.target.value, unitId: "" });
                                                fetchUnitsForProperty(ev.target.value);
                                            }}
                                        >
                                            <option value="">Organisation Level</option>
                                            {properties.map(s => <option key={s.property.id} value={s.property.id}>{s.property.nameEn}</option>)}
                                        </select>
                                    </div>
                                    <div className="col-span-1">
                                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("unit")} (Property)</label>
                                        <select
                                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                            value={formData.unitId}
                                            onChange={ev => setFormData({ ...formData, unitId: ev.target.value })}
                                            disabled={!formData.propertyId}
                                        >
                                            <option value="">Property Level (No Unit)</option>
                                            {units.map(u => <option key={u.id} value={u.id}>{u.unitNumber}{u.currentTenantName ? ` — ${u.currentTenantName}` : ""}</option>)}
                                        </select>
                                    </div>
                                </>
                            ) : (
                                <div className="col-span-2">
                                    <div className="border border-border rounded-xl overflow-hidden">
                                        {/* Header */}
                                        <div className="flex items-center justify-between bg-input/50 px-4 py-2.5">
                                            <span className="text-[10px] font-bold text-muted uppercase tracking-wider">Cost Centre Allocation</span>
                                            <button type="button" onClick={distributeSplitsEqually} className="text-[10px] font-bold text-primary hover:underline cursor-pointer">
                                                Distribute Equally
                                            </button>
                                        </div>
                                        {/* Split rows */}
                                        <div className="divide-y divide-border">
                                            {splits.map((split, index) => (
                                                <div key={split.id} className="flex items-center gap-3 px-4 py-3">
                                                    <div className="flex-1">
                                                        <select
                                                            className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                                            value={split.propertyId}
                                                            onChange={ev => updateSplitProperty(index, ev.target.value)}
                                                        >
                                                            <option value="">Select Property</option>
                                                            {properties.map(s => <option key={s.property.id} value={s.property.id}>{s.property.nameEn}</option>)}
                                                        </select>
                                                    </div>
                                                    <div className="flex-1">
                                                        <select
                                                            className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                                            value={split.unitId}
                                                            onChange={ev => updateSplitUnit(index, ev.target.value)}
                                                            disabled={!split.propertyId}
                                                        >
                                                            <option value="">Property Level</option>
                                                            {split.units.map(u => <option key={u.id} value={u.id}>{u.unitNumber}{u.currentTenantName ? ` — ${u.currentTenantName}` : ""}</option>)}
                                                        </select>
                                                    </div>
                                                    <div className="w-32">
                                                        <input
                                                            type="number"
                                                            step="0.01"
                                                            placeholder="0.00"
                                                            className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs text-right focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                                            value={split.amount || ""}
                                                            onChange={ev => updateSplitAmount(index, Number(ev.target.value))}
                                                        />
                                                    </div>
                                                    <button
                                                        type="button"
                                                        onClick={() => removeSplitRow(index)}
                                                        disabled={splits.length <= 2}
                                                        className="p-1.5 text-muted hover:text-red-500 disabled:opacity-30 disabled:cursor-not-allowed cursor-pointer transition-all"
                                                    >
                                                        <X size={14} />
                                                    </button>
                                                </div>
                                            ))}
                                        </div>
                                        {/* Footer: add row + total */}
                                        <div className="flex items-center justify-between bg-input/30 px-4 py-2.5 border-t border-border">
                                            <button type="button" onClick={addSplitRow} className="text-xs font-bold text-primary hover:underline cursor-pointer flex items-center gap-1">
                                                <Plus size={12} /> Add Row
                                            </button>
                                            <div className="flex items-center gap-2">
                                                <span className="text-[10px] font-bold text-muted uppercase tracking-wider">Total:</span>
                                                <span className={cn(
                                                    "text-xs font-bold tabular-nums",
                                                    splitBalanced ? "text-emerald-600" : "text-red-500"
                                                )}>
                                                    {formatNumber(splitTotal)} / {formatNumber(transactionAmount)}
                                                </span>
                                                {splitBalanced && <span className="text-emerald-600 text-xs">✓</span>}
                                            </div>
                                        </div>
                                    </div>
                                </div>
                            )}
                            <div className="col-span-1 flex items-center gap-3 pt-5">
                                <label className="flex items-center gap-2 cursor-pointer">
                                    <input type="checkbox" className="rounded border-border" checked={formData.vatApplicable} onChange={ev => {
                                        const checked = ev.target.checked;
                                        const amount = formData.debit || formData.credit;
                                        const vatAmt = checked ? Math.round(amount * formData.vatRate) / 100 : 0;
                                        setFormData({ ...formData, vatApplicable: checked, netAmount: amount, vatAmount: vatAmt, grossAmount: checked ? amount + vatAmt : 0 });
                                    }} />
                                    <span className="text-xs font-bold text-muted">{t("vatApplicable")}</span>
                                </label>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">VAT Rate (%)</label>
                                <input type="number" step="0.01" placeholder="5" className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200" value={formData.vatRate} onChange={ev => {
                                    const vatRate = Number(ev.target.value);
                                    const amount = formData.debit || formData.credit;
                                    const vatAmt = formData.vatApplicable ? Math.round(amount * vatRate) / 100 : 0;
                                    setFormData({ ...formData, vatRate, vatAmount: vatAmt, grossAmount: amount + vatAmt });
                                }} disabled={!formData.vatApplicable} />
                            </div>
                            {formData.vatApplicable && (formData.debit > 0 || formData.credit > 0) && (
                                <div className="col-span-2 bg-info/10 border border-info/20 rounded-xl p-4">
                                    <div className="grid grid-cols-3 gap-4 text-center">
                                        <div>
                                            <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Net Amount</p>
                                            <p className="text-sm font-bold text-foreground">{formatNumber(formData.netAmount)}</p>
                                        </div>
                                        <div>
                                            <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("vatAmount")} ({formData.vatRate}%)</p>
                                            <p className="text-sm font-bold text-blue-600">{formatNumber(formData.vatAmount)}</p>
                                        </div>
                                        <div>
                                            <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Gross Amount</p>
                                            <p className="text-sm font-bold text-emerald-600">{formatNumber(formData.grossAmount)}</p>
                                        </div>
                                    </div>
                                </div>
                            )}
                            <div className="col-span-2">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("notes")}</label>
                                <textarea placeholder="Optional notes" className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200 h-16 resize-none" value={formData.notes} onChange={ev => setFormData({ ...formData, notes: ev.target.value })} />
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-2">
                                <button type="button" onClick={() => setShowForm(false)} className="px-6 py-3 text-xs font-bold text-muted cursor-pointer transition-all duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none rounded-xl">{t("cancel")}</button>
                                <button type="submit" disabled={submitting || (splitMode && !splitBalanced)} className="px-8 py-3 bg-primary text-primary-foreground rounded-lg text-xs font-bold cursor-pointer transition-all duration-200 hover:bg-primary/90 focus:ring-2 focus:ring-primary/20 focus:outline-none disabled:opacity-50 flex items-center gap-2">
                                    {submitting && <Loader2 size={14} className="animate-spin" />}
                                    {t("create")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {/* Table Skeleton */}
            {loading && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    <div className="animate-pulse">
                        <div className="h-12 bg-input/70 border-b border-border" />
                        {[1, 2, 3, 4, 5].map((i) => (
                            <div key={i} className="flex gap-4 px-5 py-4 border-b border-border">
                                <div className="h-3 w-20 bg-input rounded" />
                                <div className="h-3 w-40 bg-input rounded" />
                                <div className="h-3 w-20 bg-input rounded" />
                                <div className="h-3 w-28 bg-input rounded" />
                                <div className="h-3 w-16 bg-input rounded" />
                                <div className="h-3 w-16 bg-input rounded" />
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {/* ── Simple Ledger View ── */}
            {!loading && viewMode === "simple" && simpleLedger.length > 0 && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="bg-input/70">
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("date")}</th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("description")}</th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("property")}</th>
                                    <th className="text-right px-5 py-3.5 text-[10px] font-bold text-emerald-500 uppercase tracking-wider">Money In</th>
                                    <th className="text-right px-5 py-3.5 text-[10px] font-bold text-red-400 uppercase tracking-wider">Money Out</th>
                                    <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">Balance</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {paginatedSimple.map(row => (
                                    <React.Fragment key={row.id}>
                                        <tr className="hover:bg-input/30 transition-colors">
                                            <td className="px-5 py-3 text-xs text-foreground font-medium">
                                                <div className="flex items-center gap-1.5">
                                                    {row.splitParent && (
                                                        <button
                                                            type="button"
                                                            onClick={() => toggleSplitExpand(row.id)}
                                                            className="cursor-pointer text-muted hover:text-foreground transition-all"
                                                            aria-label={expandedSplits.has(row.id) ? "Collapse splits" : "Expand splits"}
                                                        >
                                                            <ChevronDown size={14} className={cn("transition-transform duration-200", expandedSplits.has(row.id) && "rotate-180")} />
                                                        </button>
                                                    )}
                                                    {row.date}
                                                </div>
                                            </td>
                                            <td className="px-5 py-3">
                                                <div className="flex items-center gap-2">
                                                    <p className="text-xs font-bold text-foreground">{row.description}</p>
                                                    {row.splitParent && (
                                                        <span className="text-[9px] font-bold uppercase tracking-wider bg-primary/10 text-primary px-1.5 py-0.5 rounded">Split</span>
                                                    )}
                                                </div>
                                                {row.notes && <p className="text-[10px] text-muted mt-0.5">{row.notes}</p>}
                                            </td>
                                            <td className="px-5 py-3">
                                                <div className="flex items-center gap-1.5">
                                                    {row.property ? (
                                                        <>
                                                            <Building2 size={12} className="text-muted" />
                                                            <span className="text-xs text-foreground font-medium">{row.property.nameEn}</span>
                                                        </>
                                                    ) : (
                                                        <span className="text-[10px] text-muted font-bold uppercase">Org</span>
                                                    )}
                                                    {row.unit && (
                                                        <span className="text-[9px] text-primary bg-primary/5 px-1.5 py-0.5 rounded font-bold ml-1">
                                                            Unit {row.unit.unitNumber}
                                                        </span>
                                                    )}
                                                </div>
                                            </td>
                                            <td className="px-5 py-3 text-right text-xs font-bold tabular-nums">
                                                {row.moneyIn > 0 ? (
                                                    <span className="text-emerald-600">+{formatNumber(row.moneyIn)}</span>
                                                ) : "—"}
                                            </td>
                                            <td className="px-5 py-3 text-right text-xs font-bold tabular-nums">
                                                {row.moneyOut > 0 ? (
                                                    <span className="text-red-500">-{formatNumber(row.moneyOut)}</span>
                                                ) : "—"}
                                            </td>
                                            <td className="px-5 py-3 text-right text-xs font-bold tabular-nums text-foreground">
                                                {formatNumber(row.balance)}
                                            </td>
                                        </tr>
                                        {row.splitParent && expandedSplits.has(row.id) && row.splitChildren && row.splitChildren.map((child, idx) => (
                                            <tr key={child.id} className="bg-input/10 border-b border-border/50">
                                                <td className="px-5 py-2 text-xs text-muted pl-10">{/* indent */}</td>
                                                <td className="px-5 py-2">
                                                    <div className="flex items-center gap-1.5 pl-4">
                                                        <span className="text-muted text-xs select-none">{idx < (row.splitChildren?.length ?? 0) - 1 ? "├" : "└"}</span>
                                                        <div className="flex items-center gap-1.5">
                                                            {child.property ? (
                                                                <>
                                                                    <Building2 size={11} className="text-muted" />
                                                                    <span className="text-xs text-foreground font-medium">{child.property.nameEn}</span>
                                                                </>
                                                            ) : (
                                                                <span className="text-[10px] text-muted">Org</span>
                                                            )}
                                                            {child.unit && (
                                                                <span className="text-[9px] text-primary bg-primary/5 px-1.5 py-0.5 rounded font-bold ml-1">
                                                                    Unit {child.unit.unitNumber}
                                                                </span>
                                                            )}
                                                        </div>
                                                    </div>
                                                </td>
                                                <td className="px-5 py-2"></td>
                                                <td className="px-5 py-2 text-right text-xs font-medium tabular-nums text-muted">
                                                    {child.credit > 0 ? `+${formatNumber(child.credit)}` : "—"}
                                                </td>
                                                <td className="px-5 py-2 text-right text-xs font-medium tabular-nums text-muted">
                                                    {child.debit > 0 ? `-${formatNumber(child.debit)}` : "—"}
                                                </td>
                                                <td className="px-5 py-2"></td>
                                            </tr>
                                        ))}
                                    </React.Fragment>
                                ))}
                            </tbody>
                            <tfoot>
                                <tr className="bg-input/70 border-t-2 border-border">
                                    <td colSpan={3} className="px-5 py-3 text-xs font-bold text-foreground uppercase tracking-wider">Totals</td>
                                    <td className="px-5 py-3 text-right text-xs font-bold tabular-nums text-emerald-600">
                                        +{formatNumber(simpleTotalIn)}
                                    </td>
                                    <td className="px-5 py-3 text-right text-xs font-bold tabular-nums text-red-500">
                                        {simpleTotalOut > 0 ? `-${formatNumber(simpleTotalOut)}` : "—"}
                                    </td>
                                    <td className="px-5 py-3 text-right text-xs font-bold tabular-nums text-foreground">
                                        {formatNumber(simpleTotalIn - simpleTotalOut)}
                                    </td>
                                </tr>
                            </tfoot>
                        </table>
                    </div>
                </div>
            )}

            {!loading && viewMode === "simple" && simpleLedger.length === 0 && transactions.length > 0 && (
                <div className="text-center py-16 text-xs text-muted font-medium">
                    No income or expense transactions found. Switch to Accounting view to see all entries.
                </div>
            )}

            {/* ── Accounting View (Double-Entry) ── */}
            {!loading && viewMode === "accounting" && transactions.length > 0 && (
                <div className="bg-surface border border-border rounded-xl overflow-hidden shadow-sm">
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="bg-input/70">
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("date")}</th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("description")}</th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("account")}</th>
                                    <th className="text-left px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("property")}</th>
                                    <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("debit")}</th>
                                    <th className="text-right px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">{t("credit")}</th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-border">
                                {paginatedTransactions.map(txn => (
                                    <React.Fragment key={txn.id}>
                                        <tr className="hover:bg-input/30 transition-colors">
                                            <td className="px-5 py-3 text-xs text-foreground font-medium">
                                                <div className="flex items-center gap-1.5">
                                                    {txn.splitParent && (
                                                        <button
                                                            type="button"
                                                            onClick={() => toggleSplitExpand(txn.id)}
                                                            className="cursor-pointer text-muted hover:text-foreground transition-all"
                                                            aria-label={expandedSplits.has(txn.id) ? "Collapse splits" : "Expand splits"}
                                                        >
                                                            <ChevronDown size={14} className={cn("transition-transform duration-200", expandedSplits.has(txn.id) && "rotate-180")} />
                                                        </button>
                                                    )}
                                                    {txn.date}
                                                </div>
                                            </td>
                                            <td className="px-5 py-3">
                                                <div className="flex items-center gap-2">
                                                    <p className="text-xs font-bold text-foreground">{txn.description}</p>
                                                    {txn.splitParent && (
                                                        <span className="text-[9px] font-bold uppercase tracking-wider bg-primary/10 text-primary px-1.5 py-0.5 rounded">Split</span>
                                                    )}
                                                </div>
                                                {txn.notes && <p className="text-[10px] text-muted mt-0.5">{txn.notes}</p>}
                                            </td>
                                            <td className="px-5 py-3">
                                                <span className={cn("text-[9px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg", TYPE_COLORS[txn.accountType] || "bg-input text-muted")}>
                                                    {txn.accountCode}
                                                </span>
                                                <span className="text-[10px] text-muted ml-2">{txn.account?.name}</span>
                                            </td>
                                            <td className="px-5 py-3">
                                                <div className="flex items-center gap-1.5">
                                                    {txn.property ? (
                                                        <>
                                                            <Building2 size={12} className="text-muted" />
                                                            <span className="text-xs text-foreground font-medium">{txn.property.nameEn}</span>
                                                        </>
                                                    ) : (
                                                        <span className="text-[10px] text-muted font-bold uppercase">Org</span>
                                                    )}
                                                    {txn.unit && (
                                                        <span className="text-[9px] text-primary bg-primary/5 px-1.5 py-0.5 rounded font-bold ml-1">
                                                            Unit {txn.unit.unitNumber}
                                                        </span>
                                                    )}
                                                </div>
                                            </td>
                                            <td className="px-5 py-3 text-right text-xs font-bold tabular-nums text-foreground">
                                                {txn.debit > 0 ? formatNumber(txn.debit) : "—"}
                                            </td>
                                            <td className="px-5 py-3 text-right text-xs font-bold tabular-nums text-foreground">
                                                {txn.credit > 0 ? formatNumber(txn.credit) : "—"}
                                            </td>
                                        </tr>
                                        {txn.splitParent && expandedSplits.has(txn.id) && txn.splitChildren && txn.splitChildren.map((child, idx) => (
                                            <tr key={child.id} className="bg-input/10 border-b border-border/50">
                                                <td className="px-5 py-2 text-xs text-muted"></td>
                                                <td className="px-5 py-2">
                                                    <div className="flex items-center gap-1.5 pl-4">
                                                        <span className="text-muted text-xs select-none">{idx < (txn.splitChildren?.length ?? 0) - 1 ? "├" : "└"}</span>
                                                        <div className="flex items-center gap-1.5">
                                                            {child.property ? (
                                                                <>
                                                                    <Building2 size={11} className="text-muted" />
                                                                    <span className="text-xs text-foreground font-medium">{child.property.nameEn}</span>
                                                                </>
                                                            ) : (
                                                                <span className="text-[10px] text-muted">Org</span>
                                                            )}
                                                            {child.unit && (
                                                                <span className="text-[9px] text-primary bg-primary/5 px-1.5 py-0.5 rounded font-bold ml-1">
                                                                    Unit {child.unit.unitNumber}
                                                                </span>
                                                            )}
                                                        </div>
                                                    </div>
                                                </td>
                                                <td className="px-5 py-2">
                                                    <span className={cn("text-[9px] font-bold uppercase tracking-wider px-2 py-1 rounded-lg", TYPE_COLORS[child.accountType] || "bg-input text-muted")}>
                                                        {child.accountCode}
                                                    </span>
                                                </td>
                                                <td className="px-5 py-2"></td>
                                                <td className="px-5 py-2 text-right text-xs font-medium tabular-nums text-muted">
                                                    {child.debit > 0 ? formatNumber(child.debit) : "—"}
                                                </td>
                                                <td className="px-5 py-2 text-right text-xs font-medium tabular-nums text-muted">
                                                    {child.credit > 0 ? formatNumber(child.credit) : "—"}
                                                </td>
                                            </tr>
                                        ))}
                                    </React.Fragment>
                                ))}
                            </tbody>
                            <tfoot>
                                <tr className="bg-input/70 border-t-2 border-border">
                                    <td colSpan={4} className="px-5 py-3 text-xs font-bold text-foreground uppercase tracking-wider">Totals</td>
                                    <td className="px-5 py-3 text-right text-xs font-bold tabular-nums text-foreground">{formatNumber(totalDebit)}</td>
                                    <td className="px-5 py-3 text-right text-xs font-bold tabular-nums text-foreground">{formatNumber(totalCredit)}</td>
                                </tr>
                            </tfoot>
                        </table>
                    </div>
                </div>
            )}

            {/* Pagination */}
            {!loading && activeTotal > pageSize && (
                <div className="flex items-center justify-between mt-6 px-1">
                    <p className="text-[11px] font-semibold text-muted uppercase tracking-wider">
                        {(safePage - 1) * pageSize + 1}–{Math.min(safePage * pageSize, activeTotal)} of {activeTotal}
                    </p>
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => setCurrentPage(p => Math.max(1, p - 1))}
                            disabled={safePage <= 1}
                            className="p-2 rounded-lg border border-border text-muted hover:text-foreground hover:bg-input/30 disabled:opacity-30 disabled:cursor-not-allowed transition-all cursor-pointer"
                        >
                            <ChevronLeft size={14} />
                        </button>
                        <span className="text-xs font-bold text-foreground px-3">
                            {safePage} / {totalPages}
                        </span>
                        <button
                            onClick={() => setCurrentPage(p => Math.min(totalPages, p + 1))}
                            disabled={safePage >= totalPages}
                            className="p-2 rounded-lg border border-border text-muted hover:text-foreground hover:bg-input/30 disabled:opacity-30 disabled:cursor-not-allowed transition-all cursor-pointer"
                        >
                            <ChevronRight size={14} />
                        </button>
                    </div>
                </div>
            )}

            {transactions.length === 0 && !loading && (
                <div className="text-center py-16 text-xs text-muted font-medium">
                    No transactions found. Add your first transaction to get started.
                </div>
            )}
        </div>
    );
}
