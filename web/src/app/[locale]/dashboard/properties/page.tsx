"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, MapPin, Building2, Hash, ArrowRight, X, Users, DollarSign, PieChart, Activity, List, LayoutGrid, Search, AlertCircle, RefreshCw, Upload, FileSpreadsheet, CheckCircle2, Loader2, Download } from "lucide-react";
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

    const [showImportForm, setShowImportForm] = useState(false);
    const [importFile, setImportFile] = useState<File | null>(null);
    const [importPreview, setImportPreview] = useState<string[][]>([]);
    const [importLoading, setImportLoading] = useState(false);
    const [importResult, setImportResult] = useState<any>(null);
    const [importFormData, setImportFormData] = useState({
        nameEn: "", nameAr: "", emirate: "DUBAI", address: "", type: "RESIDENTIAL", makaniNumber: ""
    });

    // Portfolio Import state
    const [showPortfolioImport, setShowPortfolioImport] = useState(false);
    const [portfolioFile, setPortfolioFile] = useState<File | null>(null);
    const [portfolioStep, setPortfolioStep] = useState<"upload" | "processing" | "result">("upload");
    const [portfolioJobId, setPortfolioJobId] = useState<string | null>(null);
    const [portfolioResult, setPortfolioResult] = useState<any>(null);
    const [portfolioUploading, setPortfolioUploading] = useState(false);

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

    const handleFileSelect = (file: File) => {
        setImportFile(file);
        const reader = new FileReader();
        reader.onload = (e) => {
            const text = e.target?.result as string;
            const rows = text.split("\n").filter(r => r.trim()).map(r => r.split(","));
            setImportPreview(rows.slice(0, 6)); // header + 5 rows
        };
        reader.readAsText(file);
    };

    const handlePortfolioUpload = async () => {
        if (!portfolioFile) return;
        setPortfolioUploading(true);
        try {
            const formData = new FormData();
            formData.append("file", portfolioFile);
            const res = await fetch("/api/proxy/v1/import/portfolio", { method: "POST", body: formData });
            const data = await res.json();
            if (res.ok && data.jobId) {
                setPortfolioJobId(data.jobId);
                setPortfolioStep("processing");
                pollPortfolioStatus(data.jobId);
            } else {
                setPortfolioResult({ status: "FAILED", errors: [{ sheet: "General", row: 0, field: "", message: data.error || "Upload failed" }] });
                setPortfolioStep("result");
            }
        } catch (err) {
            setPortfolioResult({ status: "FAILED", errors: [{ sheet: "General", row: 0, field: "", message: "Network error" }] });
            setPortfolioStep("result");
        } finally {
            setPortfolioUploading(false);
        }
    };

    const pollPortfolioStatus = (jobId: string) => {
        const interval = setInterval(async () => {
            try {
                const res = await fetch(`/api/proxy/v1/import/portfolio/${jobId}/status`);
                if (!res.ok) return;
                const data = await res.json();
                if (data.status === "COMPLETED" || data.status === "VALIDATION_FAILED" || data.status === "FAILED") {
                    clearInterval(interval);
                    setPortfolioResult(data);
                    setPortfolioStep("result");
                    if (data.status === "COMPLETED") fetchStats();
                }
            } catch { /* keep polling */ }
        }, 2000);
    };

    const downloadPortfolioTemplate = async () => {
        const res = await fetch("/api/proxy/v1/import/portfolio/template");
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = "portfolio-import-template.xlsx";
        a.click();
        URL.revokeObjectURL(url);
    };

    const resetPortfolioImport = () => {
        setShowPortfolioImport(false);
        setPortfolioFile(null);
        setPortfolioStep("upload");
        setPortfolioJobId(null);
        setPortfolioResult(null);
        setPortfolioUploading(false);
    };

    const downloadTemplate = async () => {
        const res = await fetch("/api/proxy/v1/properties/import/template");
        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = "property-import-template.csv";
        a.click();
        URL.revokeObjectURL(url);
    };

    const handleImportSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        if (!importFile) return;
        setImportLoading(true);
        try {
            const formData = new FormData();
            formData.append("file", importFile);
            formData.append("nameEn", importFormData.nameEn);
            formData.append("nameAr", importFormData.nameAr);
            formData.append("emirate", importFormData.emirate);
            formData.append("address", importFormData.address);
            formData.append("type", importFormData.type);
            formData.append("makaniNumber", importFormData.makaniNumber);

            const res = await fetch("/api/proxy/v1/properties/import", {
                method: "POST",
                body: formData,
            });
            const data = await res.json();
            if (res.ok) {
                setImportResult(data);
                fetchStats();
            } else {
                setImportResult(data);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setImportLoading(false);
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
                <div data-tour="properties-header">
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
                                data-tour="add-property-btn"
                                className="cursor-pointer flex items-center gap-2 bg-surface text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-background transition-all duration-200 active:scale-95 focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            >
                                <Plus size={14} />
                                {t("addProperty")}
                            </button>
                            <button
                                onClick={() => setShowImportForm(true)}
                                className="cursor-pointer flex items-center gap-2 bg-surface text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-background transition-all duration-200 active:scale-95 focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            >
                                <Upload size={14} />
                                Import Property
                            </button>
                            <button
                                onClick={() => setShowPortfolioImport(true)}
                                className="cursor-pointer flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all duration-200 active:scale-95 focus:ring-2 focus:ring-primary/30 focus:outline-none"
                            >
                                <FileSpreadsheet size={14} />
                                Import Portfolio
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

            {/* Portfolio Import Modal */}
            {showPortfolioImport && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-2xl w-full shadow-2xl border border-border relative max-h-[90vh] overflow-y-auto">
                        <button onClick={resetPortfolioImport} aria-label="Close" className="cursor-pointer absolute right-6 top-6 p-2 text-muted hover:text-foreground transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg">
                            <X size={18} />
                        </button>

                        {/* Step: Upload */}
                        {portfolioStep === "upload" && (
                            <div>
                                <div className="flex items-center gap-3 mb-1">
                                    <div className="w-10 h-10 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                                        <FileSpreadsheet size={20} />
                                    </div>
                                    <div>
                                        <h2 className="text-lg font-bold">Import Portfolio</h2>
                                        <p className="text-xs text-muted font-medium">Upload a multi-sheet Excel workbook to import properties, units, renters, and leases at once.</p>
                                    </div>
                                </div>

                                <div className="mt-6 mb-4 flex items-center justify-between">
                                    <span className="text-xs font-semibold text-muted uppercase tracking-[0.15em]">Excel File (.xlsx)</span>
                                    <button type="button" onClick={downloadPortfolioTemplate} className="cursor-pointer flex items-center gap-1.5 text-xs font-semibold text-primary hover:text-primary/80 transition-colors">
                                        <Download size={12} />
                                        Download Template
                                    </button>
                                </div>

                                <div
                                    className={cn(
                                        "border-2 border-dashed rounded-xl p-10 text-center transition-colors",
                                        portfolioFile ? "border-primary/40 bg-primary/5" : "border-border hover:border-primary/30"
                                    )}
                                    onDragOver={(e) => { e.preventDefault(); e.stopPropagation(); }}
                                    onDrop={(e) => {
                                        e.preventDefault();
                                        e.stopPropagation();
                                        const file = e.dataTransfer.files?.[0];
                                        if (file && file.name.toLowerCase().endsWith(".xlsx")) setPortfolioFile(file);
                                    }}
                                >
                                    {portfolioFile ? (
                                        <div className="flex items-center justify-center gap-3">
                                            <FileSpreadsheet size={18} className="text-primary" />
                                            <span className="text-sm font-semibold text-foreground">{portfolioFile.name}</span>
                                            <span className="text-xs text-muted">({(portfolioFile.size / 1024).toFixed(1)} KB)</span>
                                            <button type="button" onClick={() => setPortfolioFile(null)} className="cursor-pointer text-muted hover:text-error transition-colors ml-2">
                                                <X size={14} />
                                            </button>
                                        </div>
                                    ) : (
                                        <label className="cursor-pointer">
                                            <div className="flex flex-col items-center gap-3">
                                                <Upload size={28} className="text-muted" />
                                                <span className="text-xs text-muted font-medium">Drop .xlsx file here or click to browse</span>
                                                <span className="text-[10px] text-muted/60">Sheets: Properties, Units, Renters, Leases</span>
                                            </div>
                                            <input
                                                type="file"
                                                accept=".xlsx"
                                                className="hidden"
                                                onChange={(e) => {
                                                    const file = e.target.files?.[0];
                                                    if (file) setPortfolioFile(file);
                                                }}
                                            />
                                        </label>
                                    )}
                                </div>

                                <div className="flex justify-end gap-3 mt-6">
                                    <button type="button" onClick={resetPortfolioImport} className="cursor-pointer px-6 py-3 text-xs font-bold text-muted transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg">Cancel</button>
                                    <button
                                        type="button"
                                        onClick={handlePortfolioUpload}
                                        disabled={!portfolioFile || portfolioUploading}
                                        className={cn(
                                            "cursor-pointer px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none flex items-center gap-2",
                                            (!portfolioFile || portfolioUploading) && "opacity-50 cursor-not-allowed"
                                        )}
                                    >
                                        {portfolioUploading ? <><Loader2 size={14} className="animate-spin" /> Uploading...</> : <>Upload & Import</>}
                                    </button>
                                </div>
                            </div>
                        )}

                        {/* Step: Processing */}
                        {portfolioStep === "processing" && (
                            <div className="text-center py-8">
                                <Loader2 size={40} className="animate-spin text-primary mx-auto mb-6" />
                                <h2 className="text-lg font-bold mb-2">Processing Import</h2>
                                <p className="text-xs text-muted font-medium mb-1">Validating and importing your portfolio data...</p>
                                <p className="text-[10px] text-muted/60">This may take a few moments for large files.</p>
                            </div>
                        )}

                        {/* Step: Result */}
                        {portfolioStep === "result" && portfolioResult && (
                            <div>
                                {portfolioResult.status === "COMPLETED" ? (
                                    <>
                                        <div className="flex items-center gap-3 mb-6">
                                            <div className="w-10 h-10 bg-success/10 rounded-xl flex items-center justify-center text-success border border-success/20">
                                                <CheckCircle2 size={20} />
                                            </div>
                                            <div>
                                                <h2 className="text-lg font-bold text-success">Import Successful</h2>
                                                <p className="text-xs text-muted font-medium">Your portfolio has been imported successfully.</p>
                                            </div>
                                        </div>

                                        <div className="grid grid-cols-3 gap-3 mb-6">
                                            {[
                                                { label: "Properties", count: portfolioResult.propertiesCreated },
                                                { label: "Buildings", count: portfolioResult.buildingsCreated },
                                                { label: "Units", count: portfolioResult.unitsCreated },
                                                { label: "Renters", count: portfolioResult.rentersCreated },
                                                { label: "Leases", count: portfolioResult.leasesCreated },
                                                { label: "Payment Schedules", count: portfolioResult.paymentSchedulesCreated },
                                            ].map(item => (
                                                <div key={item.label} className="bg-input/50 rounded-lg p-3 border border-border text-center">
                                                    <p className="text-lg font-bold text-foreground tabular-nums">{item.count}</p>
                                                    <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">{item.label}</p>
                                                </div>
                                            ))}
                                        </div>
                                    </>
                                ) : (
                                    <>
                                        <div className="flex items-center gap-3 mb-6">
                                            <div className="w-10 h-10 bg-error/10 rounded-xl flex items-center justify-center text-error border border-error/20">
                                                <AlertCircle size={20} />
                                            </div>
                                            <div>
                                                <h2 className="text-lg font-bold text-error">
                                                    {portfolioResult.status === "VALIDATION_FAILED" ? "Validation Failed" : "Import Failed"}
                                                </h2>
                                                <p className="text-xs text-muted font-medium">
                                                    {portfolioResult.status === "VALIDATION_FAILED"
                                                        ? "Please fix the errors below and re-upload."
                                                        : "An unexpected error occurred during import."}
                                                </p>
                                            </div>
                                        </div>

                                        {portfolioResult.errors && portfolioResult.errors.length > 0 && (
                                            <div className="mb-6 max-h-64 overflow-y-auto">
                                                {/* Group errors by sheet */}
                                                {Object.entries(
                                                    (portfolioResult.errors as Array<{ sheet: string; row: number; field: string; message: string }>)
                                                        .reduce((acc: Record<string, Array<{ row: number; field: string; message: string }>>, err) => {
                                                            (acc[err.sheet] = acc[err.sheet] || []).push(err);
                                                            return acc;
                                                        }, {})
                                                ).map(([sheet, errors]) => (
                                                    <div key={sheet} className="mb-3">
                                                        <h4 className="text-xs font-semibold text-foreground mb-1.5 flex items-center gap-1.5">
                                                            <FileSpreadsheet size={12} className="text-muted" />
                                                            {sheet}
                                                            <span className="text-[10px] text-error font-medium">({(errors as Array<{ row: number; field: string; message: string }>).length} {(errors as Array<{ row: number; field: string; message: string }>).length === 1 ? 'error' : 'errors'})</span>
                                                        </h4>
                                                        <div className="space-y-1">
                                                            {(errors as Array<{ row: number; field: string; message: string }>).map((err, i) => (
                                                                <div key={i} className="flex items-start gap-2 text-xs bg-error/5 border border-error/10 rounded-lg px-3 py-2">
                                                                    {err.row > 0 && <span className="text-[10px] font-mono text-muted shrink-0">Row {err.row}</span>}
                                                                    {err.field && <span className="text-[10px] font-semibold text-error shrink-0">{err.field}:</span>}
                                                                    <span className="text-foreground">{err.message}</span>
                                                                </div>
                                                            ))}
                                                        </div>
                                                    </div>
                                                ))}
                                            </div>
                                        )}
                                    </>
                                )}

                                <div className="flex justify-end gap-3">
                                    {portfolioResult.status !== "COMPLETED" && (
                                        <button
                                            type="button"
                                            onClick={() => { setPortfolioStep("upload"); setPortfolioFile(null); setPortfolioResult(null); }}
                                            className="cursor-pointer px-6 py-3 text-xs font-bold text-primary transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg"
                                        >
                                            Try Again
                                        </button>
                                    )}
                                    <button
                                        type="button"
                                        onClick={resetPortfolioImport}
                                        className="cursor-pointer px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                    >
                                        Close
                                    </button>
                                </div>
                            </div>
                        )}
                    </div>
                </div>
            )}

            {/* Import Property Modal */}
            {showImportForm && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-2xl w-full shadow-2xl border border-border relative max-h-[90vh] overflow-y-auto">
                        <button onClick={() => { setShowImportForm(false); setImportFile(null); setImportPreview([]); setImportResult(null); }} aria-label="Close" className="cursor-pointer absolute right-6 top-6 p-2 text-muted hover:text-foreground transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg"><X size={18} /></button>
                        <h2 className="text-lg font-bold mb-1">Import Property</h2>
                        <p className="text-xs text-muted mb-8 font-medium">
                            Bulk import buildings and units from a CSV file into a new or existing project.
                        </p>

                        {/* Import Result */}
                        {importResult && (
                            <div className={cn(
                                "mb-6 rounded-xl px-5 py-4 border text-sm",
                                importResult.errors || importResult.error || importResult.message?.toLowerCase().includes("error")
                                    ? "bg-error/10 border-error/30 text-error"
                                    : "bg-success/10 border-success/30 text-success"
                            )}>
                                {importResult.errors ? (
                                    <div>
                                        <p className="font-semibold mb-2">Import failed:</p>
                                        <ul className="list-disc list-inside space-y-1 text-xs">
                                            {(Array.isArray(importResult.errors) ? importResult.errors : [importResult.errors]).map((err: string, i: number) => (
                                                <li key={i}>{err}</li>
                                            ))}
                                        </ul>
                                    </div>
                                ) : importResult.error ? (
                                    <p className="font-semibold">{importResult.error}</p>
                                ) : (
                                    <div>
                                        <p className="font-semibold mb-1">Import successful!</p>
                                        <p className="text-xs">
                                            {importResult.buildingsCreated !== undefined && `Buildings created: ${importResult.buildingsCreated}. `}
                                            {importResult.unitsCreated !== undefined && `Units created: ${importResult.unitsCreated}.`}
                                            {importResult.message && importResult.message}
                                        </p>
                                    </div>
                                )}
                            </div>
                        )}

                        <form onSubmit={handleImportSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("nameEn")} *</label>
                                <input required placeholder="Project Name (EN)" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={importFormData.nameEn} onChange={ev => setImportFormData({ ...importFormData, nameEn: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("nameAr")}</label>
                                <input placeholder="اسم المشروع (AR)" className="w-full bg-input border border-border p-3 rounded-xl text-xs text-right focus:ring-2 focus:ring-primary/30 focus:outline-none" value={importFormData.nameAr} onChange={ev => setImportFormData({ ...importFormData, nameAr: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("emirate")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={importFormData.emirate} onChange={ev => setImportFormData({ ...importFormData, emirate: ev.target.value })}>
                                    {["DUBAI", "ABU_DHABI", "SHARJAH", "AJMAN", "UMM_AL_QUWAIN", "RAS_AL_KHAIMAH", "FUJAIRAH"].map(opt => (
                                        <option key={opt} value={opt}>{e(opt)}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">Type</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={importFormData.type} onChange={ev => setImportFormData({ ...importFormData, type: ev.target.value })}>
                                    {["RESIDENTIAL", "COMMERCIAL", "MIXED", "INDUSTRIAL"].map(opt => (
                                        <option key={opt} value={opt}>{opt.charAt(0) + opt.slice(1).toLowerCase()}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-span-2">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">Address</label>
                                <input placeholder="Building name, street, area" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={importFormData.address} onChange={ev => setImportFormData({ ...importFormData, address: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">Makani Number</label>
                                <input placeholder="e.g. 12345-67890" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none" value={importFormData.makaniNumber} onChange={ev => setImportFormData({ ...importFormData, makaniNumber: ev.target.value })} />
                            </div>

                            {/* CSV Upload Section */}
                            <div className="col-span-2 mt-2">
                                <div className="flex items-center justify-between mb-2">
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] ml-1">CSV File *</label>
                                    <button type="button" onClick={downloadTemplate} className="cursor-pointer text-xs font-semibold text-primary hover:text-primary/80 transition-colors">
                                        Download Template
                                    </button>
                                </div>
                                <div
                                    className={cn(
                                        "border-2 border-dashed rounded-xl p-6 text-center transition-colors",
                                        importFile ? "border-primary/40 bg-primary/5" : "border-border hover:border-primary/30"
                                    )}
                                    onDragOver={(e) => { e.preventDefault(); e.stopPropagation(); }}
                                    onDrop={(e) => {
                                        e.preventDefault();
                                        e.stopPropagation();
                                        const file = e.dataTransfer.files?.[0];
                                        if (file && file.name.endsWith(".csv")) handleFileSelect(file);
                                    }}
                                >
                                    {importFile ? (
                                        <div className="flex items-center justify-center gap-2">
                                            <Upload size={14} className="text-primary" />
                                            <span className="text-xs font-semibold text-foreground">{importFile.name}</span>
                                            <button type="button" onClick={() => { setImportFile(null); setImportPreview([]); }} className="cursor-pointer text-muted hover:text-error transition-colors ml-2">
                                                <X size={14} />
                                            </button>
                                        </div>
                                    ) : (
                                        <label className="cursor-pointer">
                                            <div className="flex flex-col items-center gap-2">
                                                <Upload size={20} className="text-muted" />
                                                <span className="text-xs text-muted font-medium">Drop CSV file here or click to browse</span>
                                            </div>
                                            <input
                                                type="file"
                                                accept=".csv"
                                                className="hidden"
                                                onChange={(e) => {
                                                    const file = e.target.files?.[0];
                                                    if (file) handleFileSelect(file);
                                                }}
                                            />
                                        </label>
                                    )}
                                </div>
                            </div>

                            {/* CSV Preview */}
                            {importPreview.length > 0 && (
                                <div className="col-span-2 mt-1">
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-2 ml-1">Preview</label>
                                    <div className="overflow-x-auto rounded-lg border border-border">
                                        <table className="w-full text-xs">
                                            <thead>
                                                <tr className="bg-input/50">
                                                    {importPreview[0]?.map((header, i) => (
                                                        <th key={i} className="px-3 py-2 text-start text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap">{header.trim()}</th>
                                                    ))}
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {importPreview.slice(1).map((row, ri) => (
                                                    <tr key={ri} className="border-t border-border">
                                                        {row.map((cell, ci) => (
                                                            <td key={ci} className="px-3 py-1.5 text-foreground whitespace-nowrap">{cell.trim()}</td>
                                                        ))}
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                </div>
                            )}

                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => { setShowImportForm(false); setImportFile(null); setImportPreview([]); setImportResult(null); }} className="cursor-pointer px-6 py-3 text-xs font-bold text-muted transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg">{t("cancel")}</button>
                                <button
                                    type="submit"
                                    disabled={importLoading || !importFile || !importFormData.nameEn}
                                    className={cn(
                                        "cursor-pointer px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold transition-all duration-200 focus:ring-2 focus:ring-primary/30 focus:outline-none",
                                        (importLoading || !importFile || !importFormData.nameEn) && "opacity-50 cursor-not-allowed"
                                    )}
                                >
                                    {importLoading ? "Importing..." : "Import"}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {stats.length > 0 && (
                <>
                    {viewMode === "table" ? (
                        <div data-tour="properties-table" className="bg-surface rounded-xl border border-border overflow-hidden">
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
