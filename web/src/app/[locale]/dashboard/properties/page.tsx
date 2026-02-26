"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, MapPin, Building2, Hash, ArrowRight, X, Users, DollarSign, PieChart, Activity } from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import CardFlip from "@/components/ui/card-flip";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";

type Property = {
    id: string;
    nameEn: string;
    nameAr: string;
    emirate: string;
    address: string;
    makaniNumber: string;
    type: string;
    fixedExpenses?: number;
};

type PropertyManager = {
    id: string;
    name: string;
    phoneNumber?: string;
};

type PropertyStats = {
    property: Property;
    propertyCount: number;
    revenueAtCapacity: number;
    actualRevenue: number;
    vacancies: number;
    assignedManagers: PropertyManager[];
};

export default function PropertiesPage() {
    const t = useTranslations("MasterData");
    const e = useTranslations("Emirates");
    const locale = useLocale();
    const [stats, setStats] = useState<PropertyStats[]>([]);
    const [showProjectForm, setShowProjectForm] = useState(false);
    const [showPropertyForm, setShowPropertyForm] = useState(false);
    const { data: session } = useSession();
    const userRole = (session?.user as any)?.role as UserRole | undefined;
    const canCreate = hasPermission(userRole, 'canCreateProperties');

    const [projectFormData, setProjectFormData] = useState({
        nameEn: "",
        nameAr: "",
        emirate: "DUBAI",
        address: "",
        makaniNumber: "",
        type: "RESIDENTIAL",
        fixedExpenses: 0
    });

    const [propertyFormData, setPropertyFormData] = useState({
        propertyId: "",
        unitNumber: "",
        type: "BHK1",
        sizeSqft: 0,
        expectedRent: 0,
        actualRent: 0,
        status: "VACANT",
        currentTenantName: ""
    });

    useEffect(() => {
        fetchStats();
    }, []);

    const fetchStats = async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) {
                const data = await res.json();
                setStats(data);
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleProjectSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        try {
            const res = await fetch("/api/proxy/v1/properties", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(projectFormData)
            });
            if (res.ok) {
                setShowProjectForm(false);
                fetchStats();
                setProjectFormData({
                    nameEn: "",
                    nameAr: "",
                    emirate: "DUBAI",
                    address: "",
                    makaniNumber: "",
                    type: "RESIDENTIAL",
                    fixedExpenses: 0
                });
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handlePropertySubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        try {
            const res = await fetch("/api/proxy/v1/units", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    property: { id: propertyFormData.propertyId },
                    unitNumber: propertyFormData.unitNumber,
                    type: propertyFormData.type,
                    sizeSqft: propertyFormData.sizeSqft,
                    expectedRent: propertyFormData.expectedRent,
                    actualRent: propertyFormData.actualRent,
                    status: propertyFormData.status,
                    currentTenantName: propertyFormData.currentTenantName
                })
            });
            if (res.ok) {
                setShowPropertyForm(false);
                fetchStats();
                setPropertyFormData({
                    propertyId: "",
                    unitNumber: "",
                    type: "BHK1",
                    sizeSqft: 0,
                    expectedRent: 0,
                    actualRent: 0,
                    status: "VACANT",
                    currentTenantName: ""
                });
            }
        } catch (err) {
            console.error(err);
        }
    };

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1">
                        {t("projects")}
                    </h1>
                    <p className="text-xs text-gray-500 font-medium">
                        Manage your real estate projects and their individual properties.
                    </p>
                </div>
                {canCreate && (
                    <div className="flex gap-3">
                        <button
                            onClick={() => setShowProjectForm(true)}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all shadow-lg shadow-primary/10 active:scale-95"
                        >
                            <Plus size={14} />
                            {t("addProject")}
                        </button>
                        <button
                            onClick={() => {
                                if (stats.length === 0) {
                                    alert("Please add a project first.");
                                    return;
                                }
                                setPropertyFormData(prev => ({ ...prev, propertyId: stats[0].property.id }));
                                setShowPropertyForm(true);
                            }}
                            className="flex items-center gap-2 bg-white text-foreground border border-border px-5 py-2.5 rounded-full text-xs font-bold hover:bg-gray-50 transition-all shadow-sm active:scale-95"
                        >
                            <Plus size={14} />
                            {t("addProperty")}
                        </button>
                    </div>
                )}
            </div>

            {/* Project Form Modal */}
            {showProjectForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-xl w-full shadow-2xl border border-gray-100 relative">
                        <button onClick={() => setShowProjectForm(false)} className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600"><X size={18} /></button>
                        <h2 className="text-lg font-black mb-1">{t("addProject")}</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">Create a new Project (Portfolio Group).</p>
                        <form onSubmit={handleProjectSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("nameEn")}</label>
                                <input placeholder="Project Name (EN)" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={projectFormData.nameEn} onChange={ev => setProjectFormData({ ...projectFormData, nameEn: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("nameAr")}</label>
                                <input placeholder="اسم المشروع (AR)" className="w-full bg-input border border-border p-3 rounded-xl text-xs text-right" value={projectFormData.nameAr} onChange={ev => setProjectFormData({ ...projectFormData, nameAr: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("emirate")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={projectFormData.emirate} onChange={ev => setProjectFormData({ ...projectFormData, emirate: ev.target.value })}>
                                    {["DUBAI", "ABU_DHABI", "SHARJAH", "AJMAN", "UMM_AL_QUWAIN", "RAS_AL_KHAIMAH", "FUJAIRAH"].map(opt => (
                                        <option key={opt} value={opt}>{e(opt)}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowProjectForm(false)} className="px-6 py-3 text-xs font-bold text-gray-500">{t("cancel")}</button>
                                <button type="submit" className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold">{t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {/* Property Form Modal */}
            {showPropertyForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-xl w-full shadow-2xl border border-gray-100 relative">
                        <button onClick={() => setShowPropertyForm(false)} className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600"><X size={18} /></button>
                        <h2 className="text-lg font-black mb-1">{t("addProperty")}</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">Add a new Property (Unit) to a Project.</p>
                        <form onSubmit={handlePropertySubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-2">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">Select Project</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={propertyFormData.propertyId} onChange={ev => setPropertyFormData({ ...propertyFormData, propertyId: ev.target.value })}>
                                    {stats.map(s => (
                                        <option key={s.property.id} value={s.property.id}>{locale === 'ar' ? s.property.nameAr : s.property.nameEn}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("unitNumber")}</label>
                                <input required placeholder="e.g. 101" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={propertyFormData.unitNumber} onChange={ev => setPropertyFormData({ ...propertyFormData, unitNumber: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("expectedRent")}</label>
                                <input type="number" placeholder="50000" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={propertyFormData.expectedRent} onChange={ev => setPropertyFormData({ ...propertyFormData, expectedRent: Number(ev.target.value) })} />
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowPropertyForm(false)} className="px-6 py-3 text-xs font-bold text-gray-500">{t("cancel")}</button>
                                <button type="submit" className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold">{t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
                {stats.map(s => (
                    <CardFlip
                        key={s.property.id}
                        front={
                            <div className="h-full flex flex-col justify-between">
                                <div>
                                    <div className="flex justify-between items-start mb-6">
                                        <div className="w-12 h-12 bg-primary/10 rounded-2xl flex items-center justify-center text-primary border border-primary/20">
                                            <Building2 size={24} />
                                        </div>
                                        <span className="text-[10px] font-black uppercase tracking-widest px-3 py-1 bg-gray-50 text-gray-400 rounded-lg border border-gray-100">
                                            {s.property.type}
                                        </span>
                                    </div>
                                    <h3 className="text-lg font-black text-foreground tracking-tight mb-2">
                                        {locale === 'ar' && s.property.nameAr ? s.property.nameAr : s.property.nameEn}
                                    </h3>
                                    <p className="text-xs text-gray-400 font-medium mb-4 flex items-center gap-1.5">
                                        <MapPin size={12} className="text-primary/40" />
                                        {s.property.address || e(s.property.emirate)}
                                    </p>
                                </div>
                                <div className="grid grid-cols-2 gap-4 mt-auto">
                                    <div className="bg-gray-50 rounded-2xl p-3 border border-gray-100/50">
                                        <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-1">Properties</p>
                                        <p className="text-sm font-black text-foreground">{s.propertyCount}</p>
                                    </div>
                                    <div className="bg-gray-50 rounded-2xl p-3 border border-gray-100/50">
                                        <p className="text-[9px] font-bold text-gray-400 uppercase tracking-wider mb-1">Vacancies</p>
                                        <p className="text-sm font-black text-primary">{s.vacancies}</p>
                                    </div>
                                </div>
                            </div>
                        }
                        back={
                            <div className="h-full flex flex-col">
                                <h4 className="text-sm font-black text-primary uppercase tracking-widest mb-6 border-b border-primary/10 pb-2">Financial Snapshot</h4>
                                <div className="space-y-4 flex-1">
                                    <div className="flex justify-between items-center group/item transition-all hover:translate-x-1">
                                        <div className="flex items-center gap-2">
                                            <PieChart size={14} className="text-primary/40" />
                                            <span className="text-[11px] font-bold text-gray-500 uppercase tracking-wider">{t("revenueAtCapacity")}</span>
                                        </div>
                                        <span className="text-xs font-black text-foreground">AED {s.revenueAtCapacity.toLocaleString()}</span>
                                    </div>
                                    <div className="flex justify-between items-center group/item transition-all hover:translate-x-1">
                                        <div className="flex items-center gap-2">
                                            <DollarSign size={14} className="text-primary/40" />
                                            <span className="text-[11px] font-bold text-gray-500 uppercase tracking-wider">{t("actualRent")}</span>
                                        </div>
                                        <span className="text-xs font-black text-foreground">AED {s.actualRevenue.toLocaleString()}</span>
                                    </div>
                                    <div className="flex justify-between items-start group/item transition-all hover:translate-x-1">
                                        <div className="flex items-center gap-2">
                                            <Users size={14} className="text-primary/40" />
                                            <span className="text-[11px] font-bold text-gray-500 uppercase tracking-wider">{t("propertyManager")}</span>
                                        </div>
                                        <div className="flex flex-col items-end gap-1">
                                            {s.assignedManagers && s.assignedManagers.length > 0 ? s.assignedManagers.map(m => (
                                                <div key={m.id} className="text-right">
                                                    <p className="text-xs font-black text-foreground leading-tight">{m.name}</p>
                                                    {m.phoneNumber && <p className="text-[9px] font-bold text-gray-400 font-mono">{m.phoneNumber}</p>}
                                                </div>
                                            )) : (
                                                <span className="text-xs font-black text-foreground opacity-30 italic">Unassigned</span>
                                            )}
                                        </div>
                                    </div>
                                    <div className="flex justify-between items-center group/item transition-all hover:translate-x-1">
                                        <div className="flex items-center gap-2">
                                            <Activity size={14} className="text-primary/40" />
                                            <span className="text-[11px] font-bold text-gray-500 uppercase tracking-wider">{t("fixedExpenses")}</span>
                                        </div>
                                        <span className="text-xs font-black text-foreground text-red-500">AED {s.property.fixedExpenses?.toLocaleString()}</span>
                                    </div>
                                </div>
                                <Link
                                    href={`/dashboard/properties/${s.property.id}`}
                                    className="mt-6 flex items-center justify-center gap-2 bg-white text-xs font-black text-primary py-3 rounded-2xl border border-primary/20 hover:bg-primary hover:text-white transition-all group/link shadow-sm"
                                >
                                    Manage Property
                                    <ArrowRight size={14} className="group-hover/link:translate-x-1 transition-transform" />
                                </Link>
                            </div>
                        }
                    />
                ))}
            </div>

            {stats.length === 0 && !showProjectForm && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <Building2 size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-6 uppercase tracking-widest">
                        {canCreate ? 'No Projects Found' : 'No Properties Assigned'}
                    </p>
                    {canCreate && (
                        <button onClick={() => setShowProjectForm(true)} className="text-xs font-black text-foreground border-b-2 border-primary pb-0.5 hover:text-primary transition-all">
                            {t("addProject")}
                        </button>
                    )}
                </div>
            )}
        </div>
    );
}
