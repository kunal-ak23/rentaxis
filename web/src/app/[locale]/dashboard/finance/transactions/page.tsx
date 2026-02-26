"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import {
    Receipt, Plus, X, Filter, Calendar, Building2, Home, ChevronDown
} from "lucide-react";
import { cn } from "@/lib/utils";

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
};

const TYPE_COLORS: Record<string, string> = {
    ASSET: "bg-emerald-100 text-emerald-700",
    LIABILITY: "bg-rose-100 text-rose-700",
    INCOME: "bg-blue-100 text-blue-700",
    EXPENSE: "bg-amber-100 text-amber-700",
    EQUITY: "bg-violet-100 text-violet-700",
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
        notes: ""
    });

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

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        try {
            const body: Record<string, unknown> = {
                date: formData.date,
                description: formData.description,
                account: { id: formData.accountId },
                debit: formData.debit,
                credit: formData.credit,
                vatApplicable: formData.vatApplicable,
                vatAmount: formData.vatAmount,
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
                    notes: ""
                });
                setUnits([]);
            }
        } catch (err) {
            console.error(err);
        }
    };

    const applyFilters = () => {
        fetchTransactions(filters);
        setShowFilters(false);
    };

    const clearFilters = () => {
        const empty = { propertyId: "", accountType: "", startDate: "", endDate: "" };
        setFilters(empty);
        fetchTransactions(empty);
    };

    const totalDebit = transactions.reduce((s, t) => s + (t.debit || 0), 0);
    const totalCredit = transactions.reduce((s, t) => s + (t.credit || 0), 0);

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <Receipt size={20} className="text-primary" />
                        {t("transactions")}
                    </h1>
                    <p className="text-xs text-gray-500 font-medium">
                        {t("transactionsDesc")}
                    </p>
                </div>
                <div className="flex gap-3">
                    <button
                        onClick={() => setShowFilters(!showFilters)}
                        className={cn(
                            "flex items-center gap-2 bg-white text-foreground border border-border px-4 py-2.5 rounded-full text-xs font-bold transition-all shadow-sm active:scale-95",
                            showFilters && "bg-primary/5 border-primary/30"
                        )}
                    >
                        <Filter size={14} />
                        {t("filter")}
                    </button>
                    <button
                        onClick={() => setShowForm(true)}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all shadow-lg shadow-primary/10 active:scale-95"
                    >
                        <Plus size={14} />
                        {t("addTransaction")}
                    </button>
                </div>
            </div>

            {/* Filter Bar */}
            {showFilters && (
                <div className="mb-8 bg-white border border-border rounded-2xl p-5 shadow-sm">
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
                        <div>
                            <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5">{t("property")}</label>
                            <select className="w-full bg-input border border-border p-2.5 rounded-xl text-xs" value={filters.propertyId} onChange={ev => setFilters({ ...filters, propertyId: ev.target.value })}>
                                <option value="">All Properties</option>
                                {properties.map(s => <option key={s.property.id} value={s.property.id}>{s.property.nameEn}</option>)}
                            </select>
                        </div>
                        <div>
                            <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5">{t("accountType")}</label>
                            <select className="w-full bg-input border border-border p-2.5 rounded-xl text-xs" value={filters.accountType} onChange={ev => setFilters({ ...filters, accountType: ev.target.value })}>
                                <option value="">All Types</option>
                                {["ASSET", "LIABILITY", "INCOME", "EXPENSE", "EQUITY"].map(t => <option key={t} value={t}>{t}</option>)}
                            </select>
                        </div>
                        <div>
                            <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5">{t("startDate")}</label>
                            <input type="date" className="w-full bg-input border border-border p-2.5 rounded-xl text-xs" value={filters.startDate} onChange={ev => setFilters({ ...filters, startDate: ev.target.value })} />
                        </div>
                        <div>
                            <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5">{t("endDate")}</label>
                            <input type="date" className="w-full bg-input border border-border p-2.5 rounded-xl text-xs" value={filters.endDate} onChange={ev => setFilters({ ...filters, endDate: ev.target.value })} />
                        </div>
                    </div>
                    <div className="flex justify-end gap-3 mt-4">
                        <button onClick={clearFilters} className="text-xs font-bold text-gray-400 hover:text-gray-600">{t("clearFilters")}</button>
                        <button onClick={applyFilters} className="px-6 py-2 bg-primary text-primary-foreground rounded-xl text-xs font-bold">{t("applyFilters")}</button>
                    </div>
                </div>
            )}

            {/* Add Transaction Modal */}
            {showForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-2xl w-full shadow-2xl border border-gray-100 relative max-h-[90vh] overflow-y-auto">
                        <button onClick={() => setShowForm(false)} className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600"><X size={18} /></button>
                        <h2 className="text-lg font-black mb-1">{t("addTransaction")}</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">Record a new financial transaction.</p>
                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("date")}</label>
                                <input required type="date" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.date} onChange={ev => setFormData({ ...formData, date: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("account")}</label>
                                <select required className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.accountId} onChange={ev => setFormData({ ...formData, accountId: ev.target.value })}>
                                    <option value="">Select Account</option>
                                    {accounts.map(a => <option key={a.id} value={a.id}>{a.code} - {a.name}</option>)}
                                </select>
                            </div>
                            <div className="col-span-2">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("description")}</label>
                                <input required placeholder="e.g. Rental income - Belle Vue tenant January 2025" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.description} onChange={ev => setFormData({ ...formData, description: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("debit")}</label>
                                <input type="number" step="0.01" placeholder="0.00" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.debit || ""} onChange={ev => setFormData({ ...formData, debit: Number(ev.target.value) })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("credit")}</label>
                                <input type="number" step="0.01" placeholder="0.00" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.credit || ""} onChange={ev => setFormData({ ...formData, credit: Number(ev.target.value) })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("property")} (Project)</label>
                                <select
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs"
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
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("unit")} (Property)</label>
                                <select
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs"
                                    value={formData.unitId}
                                    onChange={ev => setFormData({ ...formData, unitId: ev.target.value })}
                                    disabled={!formData.propertyId}
                                >
                                    <option value="">Property Level (No Unit)</option>
                                    {units.map(u => <option key={u.id} value={u.id}>{u.unitNumber}{u.currentTenantName ? ` — ${u.currentTenantName}` : ""}</option>)}
                                </select>
                            </div>
                            <div className="col-span-1 flex items-center gap-3 pt-5">
                                <label className="flex items-center gap-2 cursor-pointer">
                                    <input type="checkbox" className="rounded border-gray-300" checked={formData.vatApplicable} onChange={ev => setFormData({ ...formData, vatApplicable: ev.target.checked })} />
                                    <span className="text-xs font-bold text-gray-500">{t("vatApplicable")}</span>
                                </label>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("vatAmount")}</label>
                                <input type="number" step="0.01" placeholder="0.00" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.vatAmount || ""} onChange={ev => setFormData({ ...formData, vatAmount: Number(ev.target.value) })} disabled={!formData.vatApplicable} />
                            </div>
                            <div className="col-span-2">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("notes")}</label>
                                <textarea placeholder="Optional notes" className="w-full bg-input border border-border p-3 rounded-xl text-xs h-16 resize-none" value={formData.notes} onChange={ev => setFormData({ ...formData, notes: ev.target.value })} />
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-2">
                                <button type="button" onClick={() => setShowForm(false)} className="px-6 py-3 text-xs font-bold text-gray-500">{t("cancel")}</button>
                                <button type="submit" className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold">{t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {/* Transactions Table */}
            <div className="bg-white border border-border rounded-2xl overflow-hidden shadow-sm">
                <div className="overflow-x-auto">
                    <table className="w-full">
                        <thead>
                            <tr className="border-b border-gray-100">
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">{t("date")}</th>
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">{t("description")}</th>
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">{t("account")}</th>
                                <th className="text-left px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">{t("property")}</th>
                                <th className="text-right px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">{t("debit")}</th>
                                <th className="text-right px-5 py-3.5 text-[10px] font-bold text-gray-400 uppercase tracking-wider">{t("credit")}</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-gray-50">
                            {transactions.map(txn => (
                                <tr key={txn.id} className="hover:bg-gray-50/50 transition-colors">
                                    <td className="px-5 py-3 text-xs text-foreground font-medium">{txn.date}</td>
                                    <td className="px-5 py-3">
                                        <p className="text-xs font-bold text-foreground">{txn.description}</p>
                                        {txn.notes && <p className="text-[10px] text-gray-400 mt-0.5">{txn.notes}</p>}
                                    </td>
                                    <td className="px-5 py-3">
                                        <span className={cn("text-[9px] font-black uppercase tracking-wider px-2 py-1 rounded-lg", TYPE_COLORS[txn.accountType] || "bg-gray-100 text-gray-500")}>
                                            {txn.accountCode}
                                        </span>
                                        <span className="text-[10px] text-gray-500 ml-2">{txn.account?.name}</span>
                                    </td>
                                    <td className="px-5 py-3">
                                        <div className="flex items-center gap-1.5">
                                            {txn.property ? (
                                                <>
                                                    <Building2 size={12} className="text-gray-300" />
                                                    <span className="text-xs text-foreground font-medium">{txn.property.nameEn}</span>
                                                </>
                                            ) : (
                                                <span className="text-[10px] text-gray-400 font-bold uppercase">Org</span>
                                            )}
                                            {txn.unit && (
                                                <span className="text-[9px] text-primary bg-primary/5 px-1.5 py-0.5 rounded font-bold ml-1">
                                                    Unit {txn.unit.unitNumber}
                                                </span>
                                            )}
                                        </div>
                                    </td>
                                    <td className="px-5 py-3 text-right text-xs font-bold text-foreground">
                                        {txn.debit > 0 ? txn.debit.toLocaleString(undefined, { minimumFractionDigits: 2 }) : "—"}
                                    </td>
                                    <td className="px-5 py-3 text-right text-xs font-bold text-foreground">
                                        {txn.credit > 0 ? txn.credit.toLocaleString(undefined, { minimumFractionDigits: 2 }) : "—"}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                        {transactions.length > 0 && (
                            <tfoot>
                                <tr className="bg-gray-50 border-t-2 border-gray-200">
                                    <td colSpan={4} className="px-5 py-3 text-xs font-black text-foreground uppercase tracking-wider">Totals</td>
                                    <td className="px-5 py-3 text-right text-xs font-black text-foreground">{totalDebit.toLocaleString(undefined, { minimumFractionDigits: 2 })}</td>
                                    <td className="px-5 py-3 text-right text-xs font-black text-foreground">{totalCredit.toLocaleString(undefined, { minimumFractionDigits: 2 })}</td>
                                </tr>
                            </tfoot>
                        )}
                    </table>
                </div>
            </div>

            {transactions.length === 0 && !loading && (
                <div className="text-center py-16 text-xs text-gray-400 font-medium">
                    No transactions found. Add your first transaction to get started.
                </div>
            )}
        </div>
    );
}
