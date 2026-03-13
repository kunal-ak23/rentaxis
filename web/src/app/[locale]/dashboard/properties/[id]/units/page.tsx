"use client";

import { useState, useEffect, use } from "react";
import { useTranslations } from "next-intl";
import { Plus, X, Building, Info, LayoutList, Ruler, Hash, Users, CreditCard, ArrowLeft, Activity } from "lucide-react";
import { cn } from "@/lib/utils";
import CardFlip from "@/components/ui/card-flip";
import { Link } from "@/i18n/routing";

type Unit = {
    id: string;
    unitNumber: string;
    type: string;
    sizeSqft: number;
    status: string;
    expectedRent: number;
    actualRent: number;
    currentTenantName?: string;
};

export default function UnitsPage({ params }: { params: Promise<{ id: string }> }) {
    const { id: propertyId } = use(params);
    const t = useTranslations("MasterData");
    const [units, setUnits] = useState<Unit[]>([]);
    const [loading, setLoading] = useState(true);
    const [showForm, setShowForm] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [formData, setFormData] = useState({
        unitNumber: "",
        type: "BHK1",
        sizeSqft: 0,
        expectedRent: 0,
        actualRent: 0,
        status: "VACANT",
        currentTenantName: "",
        property: { id: propertyId }
    });

    useEffect(() => {
        fetchUnits();
    }, [propertyId]);

    const fetchUnits = async () => {
        try {
            const res = await fetch(`/api/proxy/v1/units/property/${propertyId}`);
            if (res.ok) {
                const data = await res.json();
                setUnits(data);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        setSubmitting(true);
        try {
            const res = await fetch("/api/proxy/v1/units", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData)
            });
            if (res.ok) {
                setShowForm(false);
                fetchUnits();
                setFormData({
                    unitNumber: "",
                    type: "BHK1",
                    sizeSqft: 0,
                    expectedRent: 0,
                    actualRent: 0,
                    status: "VACANT",
                    currentTenantName: "",
                    property: { id: propertyId }
                });
            }
        } catch (err) {
            console.error(err);
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <Link href="/dashboard/properties" className="flex items-center gap-1.5 text-[10px] font-black text-primary uppercase tracking-widest mb-4 hover:-translate-x-1 transition-transform cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded">
                        <ArrowLeft size={12} />
                        Back to Projects
                    </Link>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1">{t("properties")}</h1>
                    <p className="text-xs text-gray-400 font-medium tracking-tight">Manage individual properties within this project.</p>
                </div>
                <button
                    onClick={() => setShowForm(true)}
                    className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 shadow-lg shadow-primary/10 active:scale-95 self-start cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                >
                    <Plus size={14} />
                    {t("addProperty")}
                </button>
            </div>

            {showForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-md w-full shadow-2xl border border-gray-100 relative">
                        <button onClick={() => setShowForm(false)} aria-label="Close" className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg"><X size={18} /></button>
                        <h2 className="text-lg font-black mb-1 text-gray-900 leading-tight">{t("addProperty")}</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">Add a new inventory unit to this project.</p>

                        <form onSubmit={handleSubmit} className="space-y-5">
                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("unitNumber")}</label>
                                    <input required placeholder="e.g. 101" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.unitNumber} onChange={ev => setFormData({ ...formData, unitNumber: ev.target.value })} />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("status")}</label>
                                    <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.status} onChange={ev => setFormData({ ...formData, status: ev.target.value })}>
                                        <option value="VACANT">Vacant</option>
                                        <option value="OCCUPIED">Occupied</option>
                                        <option value="MAINTENANCE">Maintenance</option>
                                    </select>
                                </div>
                            </div>
                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("expectedRent")}</label>
                                    <input type="number" placeholder="AED" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.expectedRent || ""} onChange={ev => setFormData({ ...formData, expectedRent: Number(ev.target.value) })} />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("sizeSqft")}</label>
                                    <input type="number" placeholder="Sq. Ft." className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.sizeSqft || ""} onChange={ev => setFormData({ ...formData, sizeSqft: Number(ev.target.value) })} />
                                </div>
                            </div>
                            <div className="flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowForm(false)} className="px-6 py-3 rounded-xl text-xs font-bold text-gray-500 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200">{t("cancel")}</button>
                                <button type="submit" disabled={submitting} className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed">{submitting ? "Creating..." : t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                {loading ? (
                    Array.from({ length: 4 }).map((_, i) => (
                        <div key={i} className="h-[280px] bg-white rounded-3xl border border-gray-100 p-6 animate-pulse">
                            <div className="flex justify-between items-start mb-8">
                                <div className="w-10 h-10 bg-gray-100 rounded-xl" />
                                <div className="w-16 h-5 bg-gray-100 rounded-lg" />
                            </div>
                            <div className="mb-6">
                                <div className="h-3 w-20 bg-gray-100 rounded mb-2" />
                                <div className="h-6 w-28 bg-gray-200 rounded" />
                            </div>
                            <div className="border-t border-gray-50 pt-4 flex gap-4">
                                <div className="h-3 w-16 bg-gray-100 rounded" />
                                <div className="h-3 w-20 bg-gray-100 rounded" />
                            </div>
                        </div>
                    ))
                ) : units.map(u => (
                    <CardFlip
                        key={u.id}
                        className="h-[280px] transition-all duration-200"
                        front={
                            <div className="h-full flex flex-col justify-between">
                                <div className="flex justify-between items-start">
                                    <div className="w-10 h-10 bg-gray-50 rounded-xl flex items-center justify-center text-gray-400 border border-gray-100">
                                        <Building size={20} />
                                    </div>
                                    <span className={cn(
                                        "px-2.5 py-1 rounded-lg text-[9px] font-black uppercase tracking-widest border",
                                        u.status === 'VACANT' ? 'bg-emerald-50 text-emerald-600 border-emerald-100' :
                                            u.status === 'OCCUPIED' ? 'bg-primary/5 text-primary border-primary/10' :
                                                'bg-amber-50 text-amber-600 border-amber-100'
                                    )}>
                                        {u.status}
                                    </span>
                                </div>
                                <div>
                                    <p className="text-[10px] font-bold text-gray-400 uppercase tracking-[0.2em] mb-1">Unit Number</p>
                                    <h3 className="text-xl font-black text-foreground tracking-tight">#{u.unitNumber}</h3>
                                </div>
                                <div className="flex items-center gap-4 border-t border-gray-50 pt-4 mt-4">
                                    <div className="flex items-center gap-1.5">
                                        <LayoutList size={12} className="text-gray-300" />
                                        <span className="text-[10px] font-bold text-gray-500 uppercase">{u.type}</span>
                                    </div>
                                    <div className="flex items-center gap-1.5">
                                        <Ruler size={12} className="text-gray-300" />
                                        <span className="text-[10px] font-bold text-gray-500 uppercase">{u.sizeSqft} SQFT</span>
                                    </div>
                                </div>
                            </div>
                        }
                        back={
                            <div className="h-full flex flex-col">
                                <h4 className="text-[10px] font-black text-primary uppercase tracking-widest mb-4 flex items-center gap-2">
                                    <Info size={12} />
                                    Property Details
                                </h4>
                                <div className="space-y-4 flex-1">
                                    <div className="bg-white/50 rounded-2xl p-3 border border-primary/5">
                                        <div className="flex items-center gap-2 mb-1">
                                            <Users size={12} className="text-primary/40" />
                                            <span className="text-[9px] font-bold text-gray-500 uppercase">{u.status === 'OCCUPIED' ? 'Current Tenant' : 'Lease Status'}</span>
                                        </div>
                                        <p className="text-xs font-black text-foreground">{u.currentTenantName || (u.status === 'VACANT' ? 'Ready to Lease' : 'Under Maintenance')}</p>
                                    </div>
                                    <div className="grid grid-cols-2 gap-3">
                                        <div className="bg-white/50 rounded-2xl p-3 border border-primary/5">
                                            <div className="flex items-center gap-2 mb-1">
                                                <CreditCard size={12} className="text-primary/40" />
                                                <span className="text-[9px] font-bold text-gray-500 uppercase">Target Rent</span>
                                            </div>
                                            <p className="text-xs font-black text-foreground">AED {u.expectedRent?.toLocaleString()}</p>
                                        </div>
                                        <div className="bg-white/50 rounded-2xl p-3 border border-primary/5">
                                            <div className="flex items-center gap-2 mb-1">
                                                <Activity size={12} className="text-primary/40" />
                                                <span className="text-[9px] font-bold text-gray-500 uppercase">Actual Rent</span>
                                            </div>
                                            <p className="text-xs font-black text-foreground">AED {u.actualRent?.toLocaleString()}</p>
                                        </div>
                                    </div>
                                </div>
                                <button className="mt-4 w-full py-2.5 rounded-xl bg-primary text-white text-[10px] font-black uppercase tracking-widest hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
                                    Edit Details
                                </button>
                            </div>
                        }
                    />
                ))}
            </div>

            {!loading && units.length === 0 && (
                <div className="p-20 text-center flex flex-col items-center bg-gray-50/50 border border-dashed border-gray-200 rounded-[3rem]">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <Building size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-6 uppercase tracking-widest">
                        No properties in this project
                    </p>
                    <button onClick={() => setShowForm(true)} className="text-xs font-black text-primary border-b-2 border-primary pb-0.5 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded transition-all duration-200">
                        {t("addProperty")}
                    </button>
                </div>
            )}
        </div>
    );
}
