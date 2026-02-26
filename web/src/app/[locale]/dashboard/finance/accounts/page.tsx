"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import {
    BookOpen, Plus, X, ChevronDown, ChevronRight,
    Landmark, TrendingDown, TrendingUp, Coins, Scale, Sparkles
} from "lucide-react";
import { cn } from "@/lib/utils";

type Account = {
    id: string;
    code: string;
    name: string;
    accountType: "ASSET" | "LIABILITY" | "INCOME" | "EXPENSE" | "EQUITY";
    parentCode: string | null;
    description: string | null;
    system: boolean;
};

const TYPE_CONFIG: Record<string, { label: string; icon: React.ElementType; gradient: string; border: string; text: string; badge: string }> = {
    ASSET: { label: "Assets", icon: Landmark, gradient: "from-emerald-500/10 to-emerald-600/5", border: "border-emerald-200/60", text: "text-emerald-700", badge: "bg-emerald-100 text-emerald-700" },
    LIABILITY: { label: "Liabilities", icon: TrendingDown, gradient: "from-rose-500/10 to-rose-600/5", border: "border-rose-200/60", text: "text-rose-700", badge: "bg-rose-100 text-rose-700" },
    INCOME: { label: "Income", icon: TrendingUp, gradient: "from-blue-500/10 to-blue-600/5", border: "border-blue-200/60", text: "text-blue-700", badge: "bg-blue-100 text-blue-700" },
    EXPENSE: { label: "Expenses", icon: Coins, gradient: "from-amber-500/10 to-amber-600/5", border: "border-amber-200/60", text: "text-amber-700", badge: "bg-amber-100 text-amber-700" },
    EQUITY: { label: "Equity", icon: Scale, gradient: "from-violet-500/10 to-violet-600/5", border: "border-violet-200/60", text: "text-violet-700", badge: "bg-violet-100 text-violet-700" },
};

export default function AccountsPage() {
    const t = useTranslations("Finance");
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [showForm, setShowForm] = useState(false);
    const [expandedTypes, setExpandedTypes] = useState<Set<string>>(new Set(["ASSET", "LIABILITY", "INCOME", "EXPENSE", "EQUITY"]));
    const [loading, setLoading] = useState(true);

    const [formData, setFormData] = useState({
        code: "",
        name: "",
        accountType: "ASSET",
        parentCode: "",
        description: ""
    });

    useEffect(() => {
        fetchAccounts();
    }, []);

    const fetchAccounts = async () => {
        try {
            const res = await fetch("/api/proxy/v1/finance/accounts");
            if (res.ok) {
                const data = await res.json();
                setAccounts(data);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const handleSeedDefaults = async () => {
        try {
            const res = await fetch("/api/proxy/v1/finance/accounts/seed", { method: "POST" });
            if (res.ok) {
                fetchAccounts();
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        try {
            const res = await fetch("/api/proxy/v1/finance/accounts", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData)
            });
            if (res.ok) {
                setShowForm(false);
                fetchAccounts();
                setFormData({ code: "", name: "", accountType: "ASSET", parentCode: "", description: "" });
            }
        } catch (err) {
            console.error(err);
        }
    };

    const toggleType = (type: string) => {
        setExpandedTypes(prev => {
            const next = new Set(prev);
            if (next.has(type)) next.delete(type);
            else next.add(type);
            return next;
        });
    };

    const groupedAccounts: Record<string, Account[]> = {};
    for (const a of accounts) {
        if (!groupedAccounts[a.accountType]) groupedAccounts[a.accountType] = [];
        groupedAccounts[a.accountType].push(a);
    }

    const typeOrder = ["ASSET", "LIABILITY", "INCOME", "EXPENSE", "EQUITY"];

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <BookOpen size={20} className="text-primary" />
                        {t("chartOfAccounts")}
                    </h1>
                    <p className="text-xs text-gray-500 font-medium">
                        {t("chartOfAccountsDesc")}
                    </p>
                </div>
                <div className="flex gap-3">
                    {accounts.length === 0 && (
                        <button
                            onClick={handleSeedDefaults}
                            className="flex items-center gap-2 bg-gradient-to-r from-primary to-blue-500 text-white px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all shadow-lg shadow-primary/20 active:scale-95"
                        >
                            <Sparkles size={14} />
                            {t("seedDefaults")}
                        </button>
                    )}
                    <button
                        onClick={() => setShowForm(true)}
                        className="flex items-center gap-2 bg-white text-foreground border border-border px-5 py-2.5 rounded-full text-xs font-bold hover:bg-gray-50 transition-all shadow-sm active:scale-95"
                    >
                        <Plus size={14} />
                        {t("addAccount")}
                    </button>
                </div>
            </div>

            {/* Add Account Modal */}
            {showForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-xl w-full shadow-2xl border border-gray-100 relative">
                        <button onClick={() => setShowForm(false)} className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600"><X size={18} /></button>
                        <h2 className="text-lg font-black mb-1">{t("addAccount")}</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">Create a new account in the chart of accounts.</p>
                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("accountCode")}</label>
                                <input required placeholder="e.g. D-01-16" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.code} onChange={ev => setFormData({ ...formData, code: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("accountName")}</label>
                                <input required placeholder="e.g. Garden Maintenance" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.name} onChange={ev => setFormData({ ...formData, name: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("accountType")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.accountType} onChange={ev => setFormData({ ...formData, accountType: ev.target.value })}>
                                    {typeOrder.map(t => <option key={t} value={t}>{TYPE_CONFIG[t].label}</option>)}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("parentCode")}</label>
                                <input placeholder="e.g. D-01 (optional)" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.parentCode} onChange={ev => setFormData({ ...formData, parentCode: ev.target.value })} />
                            </div>
                            <div className="col-span-2">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("description")}</label>
                                <textarea placeholder="Optional description" className="w-full bg-input border border-border p-3 rounded-xl text-xs h-20 resize-none" value={formData.description} onChange={ev => setFormData({ ...formData, description: ev.target.value })} />
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-2">
                                <button type="button" onClick={() => setShowForm(false)} className="px-6 py-3 text-xs font-bold text-gray-500">{t("cancel")}</button>
                                <button type="submit" className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold">{t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {/* Account Groups */}
            <div className="space-y-6">
                {typeOrder.map(type => {
                    const items = groupedAccounts[type] || [];
                    const config = TYPE_CONFIG[type];
                    const Icon = config.icon;
                    const isExpanded = expandedTypes.has(type);

                    return (
                        <div key={type} className={cn("rounded-2xl border overflow-hidden transition-all", config.border)}>
                            <button
                                onClick={() => toggleType(type)}
                                className={cn(
                                    "w-full flex items-center justify-between p-5 bg-gradient-to-r transition-all hover:opacity-90",
                                    config.gradient
                                )}
                            >
                                <div className="flex items-center gap-3">
                                    <div className={cn("w-10 h-10 rounded-xl flex items-center justify-center bg-white/80 shadow-sm", config.text)}>
                                        <Icon size={20} />
                                    </div>
                                    <div className="text-left">
                                        <h3 className="text-sm font-black text-foreground">{config.label}</h3>
                                        <p className="text-[10px] text-gray-500 font-medium">{items.length} accounts</p>
                                    </div>
                                </div>
                                {isExpanded ? <ChevronDown size={16} className="text-gray-400" /> : <ChevronRight size={16} className="text-gray-400" />}
                            </button>
                            {isExpanded && items.length > 0 && (
                                <div className="bg-white divide-y divide-gray-50">
                                    {items.map(account => (
                                        <div key={account.id} className="flex items-center justify-between px-6 py-3.5 hover:bg-gray-50/50 transition-colors">
                                            <div className="flex items-center gap-4">
                                                <span className={cn("text-[10px] font-black uppercase tracking-widest px-2.5 py-1 rounded-lg", config.badge)}>
                                                    {account.code}
                                                </span>
                                                <div>
                                                    <p className="text-xs font-bold text-foreground">{account.name}</p>
                                                    {account.description && (
                                                        <p className="text-[10px] text-gray-400 mt-0.5">{account.description}</p>
                                                    )}
                                                </div>
                                            </div>
                                            <div className="flex items-center gap-2">
                                                {account.parentCode && (
                                                    <span className="text-[9px] font-bold text-gray-300 uppercase">
                                                        ↳ {account.parentCode}
                                                    </span>
                                                )}
                                                {account.system && (
                                                    <span className="text-[8px] font-bold text-gray-300 bg-gray-50 px-2 py-0.5 rounded-full uppercase tracking-wider">System</span>
                                                )}
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            )}
                            {isExpanded && items.length === 0 && (
                                <div className="bg-white px-6 py-8 text-center text-xs text-gray-400">
                                    No {config.label.toLowerCase()} accounts yet
                                </div>
                            )}
                        </div>
                    );
                })}
            </div>

            {/* Empty State */}
            {accounts.length === 0 && !loading && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center mt-8">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <BookOpen size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-2 uppercase tracking-widest">No Accounts</p>
                    <p className="text-xs text-gray-400 mb-6">Seed the default chart of accounts to get started.</p>
                    <button
                        onClick={handleSeedDefaults}
                        className="text-xs font-black text-primary border-b-2 border-primary pb-0.5 hover:opacity-70 transition-all"
                    >
                        {t("seedDefaults")}
                    </button>
                </div>
            )}
        </div>
    );
}
