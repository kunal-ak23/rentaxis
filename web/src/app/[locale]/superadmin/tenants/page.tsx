"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { Plus, X, Building2, Globe, FileText, Settings2, ShieldCheck, Mail, Hash, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

type Tenant = { id: string; name: string; status: string; createdAt: string };

export default function SuperAdminTenantsPage() {
    const t = useTranslations("Index");
    const [tenants, setTenants] = useState<Tenant[]>([]);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [newTenantName, setNewTenantName] = useState("");
    const [showForm, setShowForm] = useState(false);

    useEffect(() => {
        fetchTenants();
    }, []);

    const fetchTenants = async () => {
        setLoading(true);
        try {
            const res = await fetch("/api/proxy/admin/tenants");
            if (res.ok) {
                const data = await res.json();
                setTenants(data);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    const createTenant = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!newTenantName) return;
        setSubmitting(true);
        try {
            const res = await fetch("/api/proxy/admin/tenants", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name: newTenantName }),
            });
            if (res.ok) {
                setNewTenantName("");
                setShowForm(false);
                fetchTenants();
            }
        } catch (e) {
            console.error(e);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <div className="flex items-center gap-2 mb-1">
                        <div className="w-5 h-5 bg-purple-50 text-purple-600 rounded flex items-center justify-center border border-purple-100">
                            <ShieldCheck size={12} />
                        </div>
                        <h1 className="text-xl font-black text-foreground tracking-tight">Super Admin: Manage Tenants</h1>
                    </div>
                    <p className="text-xs text-gray-400 font-medium">
                        System-wide infrastructure and data isolation control.
                    </p>
                </div>
                <button
                    onClick={() => setShowForm(true)}
                    className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 shadow-lg shadow-primary/10 active:scale-95 self-start cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                >
                    <Plus size={14} />
                    Provision New Organization
                </button>
            </div>

            {showForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-md w-full shadow-2xl border border-gray-100 relative">
                        <button
                            onClick={() => setShowForm(false)}
                            aria-label="Close"
                            className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-full"
                        >
                            <X size={18} />
                        </button>

                        <h2 className="text-lg font-black mb-1 text-foreground leading-tight">Provision New Organization</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">Register a new client entity onto the platform.</p>

                        <form onSubmit={createTenant} className="space-y-5">
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">Organization Name</label>
                                <input
                                    required
                                    placeholder="e.g. Al Futtaim Properties"
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/30 focus:border-primary/30 transition-all duration-200"
                                    value={newTenantName}
                                    onChange={(e) => setNewTenantName(e.target.value)}
                                />
                            </div>
                            <div className="flex justify-end gap-3 mt-4">
                                <button
                                    type="button"
                                    onClick={() => setShowForm(false)}
                                    className="px-6 py-3 rounded-xl text-xs font-bold text-gray-500 hover:bg-gray-50 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold hover:opacity-90 shadow-lg shadow-primary/10 active:scale-95 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2"
                                >
                                    {submitting && <Loader2 size={14} className="animate-spin" />}
                                    Create Organization
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {loading ? (
                <div className="bg-white rounded-[2rem] border border-gray-100 shadow-[0_20px_50px_rgba(0,0,0,0.02)] overflow-hidden p-6 space-y-4">
                    {[...Array(4)].map((_, i) => (
                        <div key={i} className="flex gap-6 animate-pulse">
                            <div className="h-4 bg-gray-100 rounded-lg w-1/4" />
                            <div className="h-4 bg-gray-100 rounded-lg w-1/3" />
                            <div className="h-4 bg-gray-100 rounded-lg w-1/6" />
                        </div>
                    ))}
                </div>
            ) : (
            <div className="bg-white rounded-[2rem] border border-gray-100 shadow-[0_20px_50px_rgba(0,0,0,0.02)] overflow-hidden">
                <table className="w-full text-left">
                    <thead>
                        <tr className="bg-gray-50/50 border-b border-gray-100">
                            <th className="p-5 text-[10px] font-black uppercase tracking-widest text-gray-400">
                                <div className="flex items-center gap-2">
                                    <Hash size={12} />
                                    ID
                                </div>
                            </th>
                            <th className="p-5 text-[10px] font-black uppercase tracking-widest text-gray-400">
                                <div className="flex items-center gap-2">
                                    <Building2 size={12} />
                                    Organization Name
                                </div>
                            </th>
                            <th className="p-5 text-[10px] font-black uppercase tracking-widest text-gray-400">
                                <div className="flex items-center gap-2">
                                    <Settings2 size={12} />
                                    System Status
                                </div>
                            </th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-50">
                        {tenants.map(t => (
                            <tr key={t.id} className="group hover:bg-gray-50/50 transition-all duration-200">
                                <td className="p-5">
                                    <span className="font-mono text-[10px] text-gray-400">
                                        {t.id}
                                    </span>
                                </td>
                                <td className="p-5">
                                    <span className="text-sm font-black text-foreground group-hover:text-primary transition-colors">
                                        {t.name}
                                    </span>
                                </td>
                                <td className="p-5">
                                    <span className={cn(
                                        "px-2.5 py-1 rounded-full text-[10px] font-black uppercase tracking-widest",
                                        t.status === 'ACTIVE' ? 'bg-emerald-50 text-emerald-600' : 'bg-green-50 text-green-600'
                                    )}>
                                        {t.status || 'ACTIVE'}
                                    </span>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {tenants.length === 0 && (
                    <div className="p-24 text-center flex flex-col items-center">
                        <div className="w-16 h-16 bg-gray-50 rounded-2xl flex items-center justify-center text-gray-200 mb-6 border border-dashed border-gray-200">
                            <ShieldCheck size={32} />
                        </div>
                        <p className="text-[10px] font-bold text-gray-400 uppercase tracking-[0.2em]">
                            System core is ready. No organizations provisioned yet.
                        </p>
                    </div>
                )}
            </div>
            )}
        </div>
    );
}
