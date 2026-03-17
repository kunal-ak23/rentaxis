"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, MapPin, Building2, Hash, ArrowRight, X, Users, DollarSign, PieChart, Activity, List, LayoutGrid, Search, AlertCircle, RefreshCw } from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { formatCurrency, formatCurrencyCompact } from "@/lib/format";
import { Pagination } from "@/components/ui/Pagination";

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

function getOccupancy(s: PropertyStats) {
    const totalUnits = s.propertyCount;
    const occupiedUnits = totalUnits - s.vacancies;
    const occupancyPct = totalUnits > 0 ? Math.round((occupiedUnits / totalUnits) * 100) : 0;
    return occupancyPct;
}

function getOccupancyColor(pct: number) {
    if (pct >= 80) return "bg-success";
    if (pct >= 50) return "bg-warning";
    return "bg-error";
}

function getOccupancyTextColor(pct: number) {
    if (pct >= 80) return "text-success";
    if (pct >= 50) return "text-warning";
    return "text-error";
}

export default function PropertiesPage() {
    const t = useTranslations("MasterData");
    const e = useTranslations("Emirates");
    const locale = useLocale();
    const [stats, setStats] = useState<PropertyStats[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showProjectForm, setShowProjectForm] = useState(false);
    const [showPropertyForm, setShowPropertyForm] = useState(false);
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canCreate = hasPermission(userRole, 'canCreateProperties');

    const [viewMode, setViewMode] = useState<"table" | "cards">("table");
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);
    const [searchQuery, setSearchQuery] = useState("");
    const [confirmDialog, setConfirmDialog] = useState<{
        title: string;
        description: string;
        confirmText: string;
        isDestructive: boolean;
        onConfirm: () => void;
    } | null>(null);

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
        const onVisibilityChange = () => {
            if (document.visibilityState === "visible") fetchStats();
        };
        document.addEventListener("visibilitychange", onVisibilityChange);
        return () => document.removeEventListener("visibilitychange", onVisibilityChange);
    }, []);

    useEffect(() => {
        setCurrentPage(1);
    }, [stats]);

    const fetchStats = async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) {
                const data = await res.json();
                setStats(data);
                setError(null);
            } else {
                setError("Failed to load properties");
            }
        } catch (err) {
            console.error(err);
            setError("Failed to load properties");
        } finally {
            setLoading(false);
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

    const filteredStats = stats.filter(s => {
        if (!searchQuery) return true;
        const q = searchQuery.toLowerCase();
        return (
            s.property.nameEn?.toLowerCase().includes(q) ||
            s.property.nameAr?.toLowerCase().includes(q) ||
            s.property.address?.toLowerCase().includes(q) ||
            s.property.emirate?.toLowerCase().includes(q) ||
            s.property.type?.toLowerCase().includes(q)
        );
    });
    const totalItems = filteredStats.length;
    const paginatedItems = filteredStats.slice((currentPage - 1) * itemsPerPage, currentPage * itemsPerPage);

    if (loading) {
        return (
            <div>
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                    <div>
                        <div className="h-6 w-40 bg-input rounded-lg animate-pulse mb-2" />
                        <div className="h-4 w-72 bg-background rounded-lg animate-pulse" />
                    </div>
                    <div className="flex gap-3">
                        <div className="h-10 w-32 bg-input rounded-lg animate-pulse" />
                        <div className="h-10 w-32 bg-background rounded-lg animate-pulse" />
                    </div>
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
                    {[...Array(6)].map((_, i) => (
                        <div key={i} className="bg-surface rounded-xl p-6 border border-border">
                            <div className="flex justify-between items-start mb-6">
                                <div className="w-12 h-12 bg-background rounded-xl animate-pulse" />
                                <div className="h-5 w-20 bg-background rounded-lg animate-pulse" />
                            </div>
                            <div className="h-5 w-36 bg-input rounded-lg animate-pulse mb-2" />
                            <div className="h-3 w-28 bg-background rounded animate-pulse mb-4" />
                            <div className="grid grid-cols-2 gap-4 mt-4">
                                <div className="bg-background rounded-xl p-3 border border-border">
                                    <div className="h-2 w-16 bg-background rounded animate-pulse mb-2" />
                                    <div className="h-4 w-8 bg-input rounded animate-pulse" />
                                </div>
                                <div className="bg-background rounded-xl p-3 border border-border">
                                    <div className="h-2 w-16 bg-background rounded animate-pulse mb-2" />
                                    <div className="h-4 w-8 bg-input rounded animate-pulse" />
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        );
    }

    return (
        <div>
            {/* Error Banner */}
            {error && (
                <div className="mb-6 flex items-center justify-between gap-3 bg-error/10 border border-error/30 text-error rounded-xl px-5 py-3">
                    <div className="flex items-center gap-2">
                        <AlertCircle size={16} />
                        <span className="text-sm font-medium">{error}</span>
                    </div>
                    <button
                        onClick={() => { setError(null); setLoading(true); fetchStats(); }}
                        className="cursor-pointer flex items-center gap-1.5 text-xs font-semibold bg-error/10 hover:bg-error/20 px-3 py-1.5 rounded-lg transition-colors"
                    >
                        <RefreshCw size={12} />
                        {/* TODO: t("retry") */}
                        Retry
                    </button>
                </div>
            )}

            <div className="flex flex-col gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">
                        {t("projects")}
                    </h1>
                    <p className="text-xs text-muted font-medium">
                        {/* TODO: t("projectsDescription") */}
                        Manage your real estate projects and their individual properties.
                    </p>
                </div>
                <div className="flex flex-col md:flex-row md:items-center justify-between gap-3">
                    <div className="relative">
                        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
                        <input
                            type="text"
                            placeholder={/* TODO: t("search") */ "Search..."}
                            value={searchQuery}
                            onChange={(e) => { setSearchQuery(e.target.value); setCurrentPage(1); }}
                            className="pl-9 pr-4 py-2 bg-surface border border-border rounded-lg text-sm text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none w-64 transition-all"
                        />
                    </div>
                    <div className="flex items-center gap-3">
                    <div className="flex items-center bg-input rounded-lg p-0.5 border border-border">
                        <button
                            onClick={() => setViewMode("table")}
                            className={cn(
                                "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                viewMode === "table" ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                            )}
                        >
                            {/* TODO: t("table") */}
                            <List size={13} /> Table
                        </button>
                        <button
                            onClick={() => setViewMode("cards")}
                            className={cn(
                                "px-3 py-1.5 rounded-md text-xs font-medium transition-all cursor-pointer flex items-center gap-1.5",
                                viewMode === "cards" ? "bg-surface text-foreground shadow-sm border border-border" : "text-muted hover:text-foreground"
                            )}
                        >
                            {/* TODO: t("cards") */}
                            <LayoutGrid size={13} /> Cards
                        </button>
                    </div>
                    {canCreate && (
                        <>
                            <button
                                onClick={() => setShowProjectForm(true)}
                                className="cursor-pointer flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:opacity-90 transition-all duration-200 active:scale-95 focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            >
                                <Plus size={14} />
                                {t("addProject")}
                            </button>
                            <button
                                onClick={() => {
                                    if (stats.length === 0) {
                                        setConfirmDialog({
                                            title: /* TODO: t("noProjects") */ "No Projects",
                                            description: /* TODO: t("addProjectFirst") */ "Please add a project first before adding a property.",
                                            confirmText: /* TODO: t("ok") */ "OK",
                                            isDestructive: false,
                                            onConfirm: () => { setConfirmDialog(null); },
                                        });
                                        return;
                                    }
                                    setPropertyFormData(prev => ({ ...prev, propertyId: stats[0].property.id }));
                                    setShowPropertyForm(true);
                                }}
                                className="cursor-pointer flex items-center gap-2 bg-surface text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-background transition-all duration-200 active:scale-95 focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            >
                                <Plus size={14} />
                                {t("addProperty")}
                            </button>
                        </>
                    )}
                    </div>
                </div>
            </div>

            {/* Project Form Modal */}
            {showProjectForm && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-xl w-full shadow-2xl border border-border relative">
                        <button onClick={() => setShowProjectForm(false)} aria-label="Close" className="cursor-pointer absolute right-6 top-6 p-2 text-muted hover:text-foreground transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg"><X size={18} /></button>
                        <h2 className="text-lg font-bold mb-1">{t("addProject")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">
                            {/* TODO: t("addProjectDescription") */}
                            Create a new Project (Portfolio Group).
                        </p>
                        <form onSubmit={handleProjectSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("nameEn")}</label>
                                <input placeholder="Project Name (EN)" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={projectFormData.nameEn} onChange={ev => setProjectFormData({ ...projectFormData, nameEn: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("nameAr")}</label>
                                <input placeholder="اسم المشروع (AR)" className="w-full bg-input border border-border p-3 rounded-xl text-xs text-right focus:ring-2 focus:ring-primary/30 focus:outline-none" value={projectFormData.nameAr} onChange={ev => setProjectFormData({ ...projectFormData, nameAr: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("emirate")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={projectFormData.emirate} onChange={ev => setProjectFormData({ ...projectFormData, emirate: ev.target.value })}>
                                    {["DUBAI", "ABU_DHABI", "SHARJAH", "AJMAN", "UMM_AL_QUWAIN", "RAS_AL_KHAIMAH", "FUJAIRAH"].map(opt => (
                                        <option key={opt} value={opt}>{e(opt)}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {/* TODO: t("type") */}
                                    Type
                                </label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={projectFormData.type} onChange={ev => setProjectFormData({ ...projectFormData, type: ev.target.value })}>
                                    {["RESIDENTIAL", "COMMERCIAL", "MIXED", "INDUSTRIAL"].map(opt => (
                                        <option key={opt} value={opt}>{opt.charAt(0) + opt.slice(1).toLowerCase()}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-span-2">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {/* TODO: t("address") */}
                                    Address
                                </label>
                                <input placeholder="Building name, street, area" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={projectFormData.address} onChange={ev => setProjectFormData({ ...projectFormData, address: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {/* TODO: t("makaniNumber") */}
                                    Makani Number
                                </label>
                                <input placeholder="e.g. 12345-67890" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={projectFormData.makaniNumber} onChange={ev => setProjectFormData({ ...projectFormData, makaniNumber: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {t("fixedExpenses")}
                                </label>
                                <input type="number" placeholder="0" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={projectFormData.fixedExpenses || ""} onChange={ev => setProjectFormData({ ...projectFormData, fixedExpenses: Number(ev.target.value) })} />
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowProjectForm(false)} className="cursor-pointer px-6 py-3 text-xs font-bold text-muted transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg">{t("cancel")}</button>
                                <button type="submit" className="cursor-pointer px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none">{t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {/* Property Form Modal */}
            {showPropertyForm && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-xl w-full shadow-2xl border border-border relative">
                        <button onClick={() => setShowPropertyForm(false)} aria-label="Close" className="cursor-pointer absolute right-6 top-6 p-2 text-muted hover:text-foreground transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg"><X size={18} /></button>
                        <h2 className="text-lg font-bold mb-1">{t("addProperty")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">
                            {/* TODO: t("addPropertyDescription") */}
                            Add a new Property (Unit) to a Project.
                        </p>
                        <form onSubmit={handlePropertySubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-2">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {/* TODO: t("selectProject") */}
                                    Select Project
                                </label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={propertyFormData.propertyId} onChange={ev => setPropertyFormData({ ...propertyFormData, propertyId: ev.target.value })}>
                                    {stats.map(s => (
                                        <option key={s.property.id} value={s.property.id}>{locale === 'ar' ? s.property.nameAr : s.property.nameEn}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("unitNumber")}</label>
                                <input required placeholder="e.g. 101" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={propertyFormData.unitNumber} onChange={ev => setPropertyFormData({ ...propertyFormData, unitNumber: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {/* TODO: t("type") */}
                                    Type
                                </label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={propertyFormData.type} onChange={ev => setPropertyFormData({ ...propertyFormData, type: ev.target.value })}>
                                    {["STUDIO", "BHK1", "BHK2", "BHK3", "BHK4", "PENTHOUSE", "SHOP", "OFFICE", "WAREHOUSE"].map(opt => (
                                        <option key={opt} value={opt}>{opt.replace("BHK", "BHK ")}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {/* TODO: t("sizeSqft") */}
                                    Size (sqft)
                                </label>
                                <input type="number" placeholder="0" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={propertyFormData.sizeSqft || ""} onChange={ev => setPropertyFormData({ ...propertyFormData, sizeSqft: Number(ev.target.value) })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {/* TODO: t("status") */}
                                    Status
                                </label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={propertyFormData.status} onChange={ev => setPropertyFormData({ ...propertyFormData, status: ev.target.value })}>
                                    <option value="VACANT">Vacant</option>
                                    <option value="OCCUPIED">Occupied</option>
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("expectedRent")}</label>
                                <input type="number" placeholder="50000" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={propertyFormData.expectedRent || ""} onChange={ev => setPropertyFormData({ ...propertyFormData, expectedRent: Number(ev.target.value) })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">
                                    {/* TODO: t("actualRent") */}
                                    Actual Rent
                                </label>
                                <input type="number" placeholder="0" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={propertyFormData.actualRent || ""} onChange={ev => setPropertyFormData({ ...propertyFormData, actualRent: Number(ev.target.value) })} />
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowPropertyForm(false)} className="cursor-pointer px-6 py-3 text-xs font-bold text-muted transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg">{t("cancel")}</button>
                                <button type="submit" className="cursor-pointer px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none">{t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {stats.length > 0 && (
                <>
                    {viewMode === "table" ? (
                        <div className="bg-surface rounded-xl border border-border overflow-hidden">
                            <div className="overflow-x-auto">
                                <table className="w-full">
                                    <thead>
                                        <tr className="bg-input/50">
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">
                                                {/* TODO: t("name") */}
                                                Name
                                            </th>
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">
                                                {/* TODO: t("location") */}
                                                Location
                                            </th>
                                            <th className="px-5 py-3 text-start text-[11px] font-semibold text-muted uppercase tracking-wider">
                                                {/* TODO: t("type") */}
                                                Type
                                            </th>
                                            <th className="px-5 py-3 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">
                                                {/* TODO: t("units") */}
                                                Units
                                            </th>
                                            <th className="px-5 py-3 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">
                                                {/* TODO: t("vacant") */}
                                                Vacant
                                            </th>
                                            <th className="px-5 py-3 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">
                                                {/* TODO: t("occupancy") */}
                                                Occupancy
                                            </th>
                                            <th className="px-5 py-3 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">Revenue at Capacity</th>
                                            <th className="px-5 py-3 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">Actual Revenue</th>
                                            <th className="px-5 py-3 text-end text-[11px] font-semibold text-muted uppercase tracking-wider">
                                                {/* TODO: t("actions") */}
                                                Actions
                                            </th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {paginatedItems.map(s => {
                                            const occupancyPct = getOccupancy(s);
                                            return (
                                                <tr key={s.property.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                                    <td className="px-5 py-3.5 text-sm text-foreground font-medium">
                                                        {locale === 'ar' && s.property.nameAr ? s.property.nameAr : s.property.nameEn}
                                                    </td>
                                                    <td className="px-5 py-3.5 text-sm text-foreground">
                                                        {s.property.address || e(s.property.emirate)}
                                                    </td>
                                                    <td className="px-5 py-3.5">
                                                        <span className="text-[10px] font-semibold uppercase tracking-widest px-2.5 py-1 bg-input text-muted rounded-md border border-border">
                                                            {s.property.type}
                                                        </span>
                                                    </td>
                                                    <td className="px-5 py-3.5 text-sm text-foreground text-end tabular-nums">{s.propertyCount}</td>
                                                    <td className="px-5 py-3.5 text-sm text-end tabular-nums">
                                                        <span className={cn(s.vacancies > 0 ? "text-warning" : "text-success", "font-medium")}>{s.vacancies}</span>
                                                    </td>
                                                    <td className="px-5 py-3.5 text-end">
                                                        <span className={cn(
                                                            "text-xs font-semibold px-2 py-0.5 rounded-md",
                                                            occupancyPct >= 80 ? "bg-success/10 text-success" :
                                                            occupancyPct >= 50 ? "bg-warning/10 text-warning" :
                                                            "bg-error/10 text-error"
                                                        )}>
                                                            {occupancyPct}%
                                                        </span>
                                                    </td>
                                                    <td className="px-5 py-3.5 text-sm text-foreground text-end tabular-nums">{formatCurrency(s.revenueAtCapacity)}</td>
                                                    <td className="px-5 py-3.5 text-sm text-foreground text-end tabular-nums">{formatCurrency(s.actualRevenue)}</td>
                                                    <td className="px-5 py-3.5 text-end">
                                                        <Link
                                                            href={`/dashboard/properties/${s.property.id}`}
                                                            className="text-xs font-semibold text-primary hover:text-primary/80 transition-colors"
                                                        >
                                                            {/* TODO: t("manage") */}
                                                            Manage
                                                        </Link>
                                                    </td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    ) : (
                        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                            {paginatedItems.map(s => {
                                const occupancyPct = getOccupancy(s);
                                return (
                                    <Link
                                        key={s.property.id}
                                        href={`/dashboard/properties/${s.property.id}`}
                                        className="group bg-surface rounded-xl border border-border hover:shadow-lg hover:border-primary/30 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none overflow-hidden"
                                    >
                                        {/* Header */}
                                        <div className="p-5 pb-4">
                                            <div className="flex justify-between items-start mb-4">
                                                <div className="w-11 h-11 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                                                    <Building2 size={20} />
                                                </div>
                                                <span className="text-[10px] font-semibold uppercase tracking-widest px-2.5 py-1 bg-input text-muted rounded-md border border-border">
                                                    {s.property.type}
                                                </span>
                                            </div>
                                            <h3 className="text-base font-bold text-foreground tracking-tight mb-1">
                                                {locale === 'ar' && s.property.nameAr ? s.property.nameAr : s.property.nameEn}
                                            </h3>
                                            <p className="text-xs text-muted font-medium flex items-center gap-1.5">
                                                <MapPin size={11} className="text-muted/50" />
                                                {s.property.address || e(s.property.emirate)}
                                            </p>
                                        </div>

                                        {/* Stats Row */}
                                        <div className="grid grid-cols-3 border-t border-border">
                                            <div className="px-5 py-3 border-r border-border">
                                                <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-0.5">
                                                    {/* TODO: t("units") */}
                                                    Units
                                                </p>
                                                <p className="text-sm font-bold text-foreground tabular-nums">{s.propertyCount}</p>
                                            </div>
                                            <div className="px-5 py-3 border-r border-border">
                                                <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-0.5">
                                                    {/* TODO: t("vacant") */}
                                                    Vacant
                                                </p>
                                                <p className={cn("text-sm font-bold tabular-nums", s.vacancies > 0 ? "text-warning" : "text-success")}>{s.vacancies}</p>
                                            </div>
                                            <div className="px-5 py-3">
                                                <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-0.5">
                                                    {/* TODO: t("revenue") */}
                                                    Revenue
                                                </p>
                                                <p className="text-sm font-bold text-foreground tabular-nums">{formatCurrencyCompact(s.actualRevenue)}</p>
                                            </div>
                                        </div>

                                        {/* Occupancy Progress Bar */}
                                        <div className="px-5 py-3 border-t border-border">
                                            <div className="flex justify-between items-center mb-1.5">
                                                <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                                                    {/* TODO: t("occupancy") */}
                                                    Occupancy
                                                </span>
                                                <span className={cn("text-xs font-bold tabular-nums", getOccupancyTextColor(occupancyPct))}>
                                                    {occupancyPct}%
                                                </span>
                                            </div>
                                            <div className="w-full h-1.5 bg-input rounded-full overflow-hidden">
                                                <div
                                                    className={cn("h-full rounded-full transition-all", getOccupancyColor(occupancyPct))}
                                                    style={{ width: `${occupancyPct}%` }}
                                                />
                                            </div>
                                        </div>

                                        {/* Financial Details */}
                                        <div className="px-5 py-3 bg-input/50 border-t border-border space-y-2">
                                            <div className="flex justify-between items-center">
                                                <span className="text-[11px] text-muted font-medium">{t("revenueAtCapacity")}</span>
                                                <span className="text-[11px] font-semibold text-foreground tabular-nums">{formatCurrencyCompact(s.revenueAtCapacity)}</span>
                                            </div>
                                            {(s.property.fixedExpenses ?? 0) > 0 && (
                                                <div className="flex justify-between items-center">
                                                    <span className="text-[11px] text-muted font-medium">{t("fixedExpenses")}</span>
                                                    <span className="text-[11px] font-semibold text-error tabular-nums">{formatCurrencyCompact(s.property.fixedExpenses)}</span>
                                                </div>
                                            )}
                                            {s.assignedManagers && s.assignedManagers.length > 0 && (
                                                <div className="flex justify-between items-center">
                                                    <span className="text-[11px] text-muted font-medium">{t("propertyManager")}</span>
                                                    <span className="text-[11px] font-semibold text-foreground">{s.assignedManagers.map(m => m.name).join(', ')}</span>
                                                </div>
                                            )}
                                        </div>

                                        {/* Footer CTA */}
                                        <div className="px-5 py-3 border-t border-border flex items-center justify-between">
                                            <span className="text-xs font-semibold text-primary">
                                                {/* TODO: t("manageProperty") */}
                                                Manage Property
                                            </span>
                                            <ArrowRight size={14} className="text-primary group-hover:translate-x-1 transition-transform" />
                                        </div>
                                    </Link>
                                );
                            })}
                        </div>
                    )}

                    <Pagination
                        currentPage={currentPage}
                        totalItems={totalItems}
                        itemsPerPage={itemsPerPage}
                        onPageChange={setCurrentPage}
                        onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                    />
                </>
            )}

            {stats.length === 0 && !showProjectForm && (
                <div className="text-center py-24 bg-background border border-dashed border-border rounded-xl flex flex-col items-center">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted mb-6">
                        <Building2 size={32} />
                    </div>
                    <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-6">
                        {/* TODO: t("noProjectsFound") / t("noPropertiesAssigned") */}
                        {canCreate ? 'No Projects Found' : 'No Properties Assigned'}
                    </p>
                    {canCreate && (
                        <button onClick={() => setShowProjectForm(true)} className="cursor-pointer text-xs font-bold text-foreground border-b-2 border-primary pb-0.5 hover:text-primary transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none">
                            {t("addProject")}
                        </button>
                    )}
                </div>
            )}

            <ConfirmDialog
                isOpen={confirmDialog !== null}
                onClose={() => setConfirmDialog(null)}
                onConfirm={confirmDialog?.onConfirm || (() => {})}
                title={confirmDialog?.title || ""}
                description={confirmDialog?.description}
                confirmText={confirmDialog?.confirmText || "Confirm"}
                isDestructive={confirmDialog?.isDestructive || false}
            />
        </div>
    );
}
