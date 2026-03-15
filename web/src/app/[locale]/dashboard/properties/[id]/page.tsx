"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { useTranslations, useLocale } from "next-intl";
import { Link } from "@/i18n/routing";
import { Building2, Home, FileText, ArrowLeft, Plus, MapPin, Upload, Calendar, DollarSign, Settings } from "lucide-react";
import { cn } from "@/lib/utils";
import { useSession } from "next-auth/react";
import { hasPermission, canConfigureRentSettings, type UserRole } from "@/lib/rbac";
import { formatCurrency, formatCurrencyCompact } from "@/lib/format";

export default function PropertyDetailPage() {
    const params = useParams();
    const router = useRouter();
    const t = useTranslations("MasterData");
    const e = useTranslations("Emirates");
    const tOnlinePayments = useTranslations("OnlinePayments");
    const locale = useLocale();
    const propertyId = params.id as string;

    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canCreate = hasPermission(userRole, 'canCreateProperties');
    const canManageRentSettings = userRole ? canConfigureRentSettings(userRole) : false;

    const [activeTab, setActiveTab] = useState<"overview" | "buildings" | "units" | "leases">("overview");
    const [property, setProperty] = useState<any>(null);
    const [buildings, setBuildings] = useState<any[]>([]);
    const [units, setUnits] = useState<any[]>([]);
    const [managers, setManagers] = useState<any[]>([]);

    useEffect(() => {
        fetchProperty();
        fetchBuildings();
        fetchUnits();
        fetchManagers();
    }, [propertyId]);

    const fetchProperty = async () => {
        const res = await fetch(`/api/proxy/v1/properties/${propertyId}`);
        if (res.ok) setProperty(await res.json());
    };

    const fetchBuildings = async () => {
        const res = await fetch(`/api/proxy/v1/buildings/property/${propertyId}`);
        if (res.ok) setBuildings(await res.json());
    };

    const fetchUnits = async () => {
        const res = await fetch(`/api/proxy/v1/units/property/${propertyId}`);
        if (res.ok) setUnits(await res.json());
    };

    const fetchManagers = async () => {
        const res = await fetch(`/api/proxy/v1/properties/${propertyId}/managers`);
        if (res.ok) setManagers(await res.json());
    };

    if (!property) return (
        <div className="p-8 max-w-7xl mx-auto space-y-6">
            <div className="h-4 w-32 bg-input rounded animate-pulse" />
            <div className="bg-surface rounded-xl p-8 border border-border">
                <div className="h-6 w-64 bg-input rounded animate-pulse mb-3" />
                <div className="h-4 w-48 bg-background rounded animate-pulse" />
            </div>
            <div className="flex gap-6 border-b border-border pb-4">
                {[1, 2, 3, 4].map(i => (
                    <div key={i} className="h-4 w-20 bg-input rounded animate-pulse" />
                ))}
            </div>
            <div className="grid grid-cols-3 gap-4">
                {[1, 2, 3].map(i => (
                    <div key={i} className="h-24 bg-background rounded-xl animate-pulse" />
                ))}
            </div>
        </div>
    );

    const displayName = locale === "ar" && property.nameAr ? property.nameAr : property.nameEn;

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <button
                onClick={() => router.back()}
                className="flex items-center gap-2 text-xs font-bold text-muted hover:text-foreground mb-6 transition-colors cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded"
            >
                <ArrowLeft size={14} /> Back to Projects
            </button>

            <div className="bg-surface rounded-xl p-8 border border-border mb-8">
                <div className="flex items-start justify-between">
                    <div>
                        <div className="flex items-center gap-3 mb-2">
                            <h1 className="text-2xl font-bold text-foreground tracking-tight">{displayName}</h1>
                            <span className="text-[10px] font-bold uppercase tracking-widest px-3 py-1 bg-primary/10 text-primary rounded-lg border border-primary/20">
                                {property.type}
                            </span>
                        </div>
                        <p className="text-sm font-medium text-muted flex items-center gap-2">
                            <MapPin size={14} className="text-muted" />
                            {property.address ? `${property.address}, ` : ""}{e(property.emirate)}
                            {property.makaniNumber && ` • Makani: ${property.makaniNumber}`}
                        </p>
                    </div>
                    {canManageRentSettings && (
                        <Link
                            href="/dashboard/settings/rent-settings"
                            className="flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold border border-border text-muted hover:bg-background hover:text-foreground transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Settings size={14} />
                            {tOnlinePayments("rentSettings")}
                        </Link>
                    )}
                </div>
            </div>

            {/* Tabs */}
            <div className="flex items-center gap-6 mb-8 border-b border-border">
                {[
                    { id: "overview", label: "Overview", icon: Home },
                    { id: "buildings", label: "Buildings", icon: Building2 },
                    { id: "units", label: "Units", icon: Home },
                    { id: "leases", label: "Leases", icon: FileText }
                ].map(tab => {
                    const Icon = tab.icon;
                    const isActive = activeTab === tab.id;
                    return (
                        <button
                            key={tab.id}
                            onClick={() => setActiveTab(tab.id as any)}
                            className={cn(
                                "flex items-center gap-2 pb-4 text-sm font-bold transition-all duration-200 relative cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded",
                                isActive ? "text-primary" : "text-muted hover:text-foreground"
                            )}
                        >
                            <Icon size={16} />
                            {tab.label}
                            {isActive && (
                                <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-primary rounded-t-full" />
                            )}
                        </button>
                    );
                })}
            </div>

            {/* Content areas */}
            {activeTab === "overview" && (
                <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                    <div className="bg-background rounded-xl p-6 border border-border col-span-1 md:col-span-2">
                        <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4 flex items-center gap-2">
                            <Building2 size={12} className="text-primary/40" />
                            {t("propertyManager")}
                        </p>
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                            {managers.length > 0 ? managers.map(m => (
                                <div key={m.id} className="bg-surface p-4 rounded-xl border border-border hover:shadow-md transition-all duration-200 flex flex-col gap-1">
                                    <p className="text-sm font-bold text-foreground">{m.name}</p>
                                    <p className="text-[11px] font-bold text-muted">{m.email}</p>
                                    {m.phoneNumber && (
                                        <p className="text-[11px] font-bold text-primary/70 font-mono mt-1">{m.phoneNumber}</p>
                                    )}
                                </div>
                            )) : (
                                <p className="text-xs font-medium text-muted italic">No managers assigned.</p>
                            )}
                        </div>
                    </div>
                </div>
            )}

            {activeTab === "buildings" && (
                <BuildingsTab buildings={buildings} propertyId={propertyId} canCreate={canCreate} onUpdate={fetchBuildings} />
            )}

            {activeTab === "units" && (
                <UnitsTab units={units} buildings={buildings} propertyId={propertyId} canCreate={canCreate} onUpdate={fetchUnits} />
            )}

            {activeTab === "leases" && (
                <LeasesTab propertyId={propertyId} />
            )}
        </div>
    );
}

// ------ BUILDINGS TAB SUB-COMPONENT ------

function BuildingsTab({ buildings, propertyId, canCreate, onUpdate }: any) {
    const [showForm, setShowForm] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [formData, setFormData] = useState({ nameEn: "", nameAr: "", floors: 1 });

    const handleSubmit = async (e: any) => {
        e.preventDefault();
        setSubmitting(true);
        try {
            const res = await fetch("/api/proxy/v1/buildings", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ ...formData, property: { id: propertyId } })
            });
            if (res.ok) {
                setShowForm(false);
                setFormData({ nameEn: "", nameAr: "", floors: 1 });
                onUpdate();
            }
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-lg font-bold">Buildings</h2>
                {canCreate && (
                    <button
                        onClick={() => setShowForm(true)}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                    >
                        <Plus size={14} /> Add Building
                    </button>
                )}
            </div>

            {showForm && (
                <form onSubmit={handleSubmit} className="bg-surface p-6 rounded-xl border border-border mb-6 grid grid-cols-3 gap-4">
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Name (EN)</label>
                        <input required className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.nameEn} onChange={e => setFormData({ ...formData, nameEn: e.target.value })} />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1 text-right">Name (AR)</label>
                        <input className="w-full bg-input border border-border rounded-lg p-2 text-xs text-right focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.nameAr} onChange={e => setFormData({ ...formData, nameAr: e.target.value })} dir="rtl" />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Floors</label>
                        <input type="number" required className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.floors} onChange={e => setFormData({ ...formData, floors: Number(e.target.value) })} />
                    </div>
                    <div className="col-span-3 flex justify-end gap-2 mt-2">
                        <button type="button" onClick={() => setShowForm(false)} className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200">Cancel</button>
                        <button type="submit" disabled={submitting} className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed">{submitting ? "Saving..." : "Save"}</button>
                    </div>
                </form>
            )}

            <div className="grid grid-cols-3 gap-4">
                {buildings.map((b: any) => (
                    <div key={b.id} className="bg-surface rounded-xl p-4 border border-border flex justify-between items-center hover:shadow-md transition-all duration-200">
                        <div>
                            <p className="font-bold text-sm text-foreground">{b.nameEn}</p>
                            {b.nameAr && <p className="text-xs text-muted font-medium" dir="rtl">{b.nameAr}</p>}
                        </div>
                        <div className="text-xs font-bold text-muted bg-background px-2 py-1 rounded-md border border-border">
                            {b.floors} Floors
                        </div>
                    </div>
                ))}
                {buildings.length === 0 && !showForm && (
                    <div className="col-span-3 text-center py-12 text-muted font-medium text-xs">No buildings added yet.</div>
                )}
            </div>
        </div>
    );
}

// ------ UNITS TAB SUB-COMPONENT ------

function UnitsTab({ units, buildings, propertyId, canCreate, onUpdate }: any) {
    const t = useTranslations("MasterData");
    const [showForm, setShowForm] = useState(false);
    const [showBulkUpload, setShowBulkUpload] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [file, setFile] = useState<File | null>(null);
    const [uploadBuildingId, setUploadBuildingId] = useState<string>("");
    const [unitForm, setUnitForm] = useState({
        unitNumber: "", type: "STUDIO", sizeSqft: "", expectedRent: "", buildingId: ""
    });

    const unitTypes = ["STUDIO", "BHK1", "BHK2", "BHK3", "PENTHOUSE", "RETAIL", "OFFICE"];

    const handleAddUnit = async (e: any) => {
        e.preventDefault();
        setSubmitting(true);
        try {
            const body: any = {
                unitNumber: unitForm.unitNumber,
                type: unitForm.type,
                sizeSqft: unitForm.sizeSqft ? Number(unitForm.sizeSqft) : null,
                expectedRent: unitForm.expectedRent ? Number(unitForm.expectedRent) : null,
                status: "VACANT",
                property: { id: propertyId },
            };
            if (unitForm.buildingId) {
                body.building = { id: unitForm.buildingId };
            }
            const res = await fetch("/api/proxy/v1/units", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (res.ok) {
                setShowForm(false);
                setUnitForm({ unitNumber: "", type: "STUDIO", sizeSqft: "", expectedRent: "", buildingId: "" });
                onUpdate();
            }
        } finally {
            setSubmitting(false);
        }
    };

    const handleBulkUpload = async (e: any) => {
        e.preventDefault();
        if (!file) return;
        setSubmitting(true);
        try {
            const formData = new FormData();
            formData.append("file", file);
            formData.append("propertyId", propertyId);
            if (uploadBuildingId) formData.append("buildingId", uploadBuildingId);

            const res = await fetch("/api/proxy/v1/units/bulk", {
                method: "POST",
                body: formData,
            });

            if (res.ok) {
                setShowBulkUpload(false);
                setFile(null);
                onUpdate();
            }
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-lg font-bold">Units</h2>
                {canCreate && (
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => { setShowForm(true); setShowBulkUpload(false); }}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Plus size={14} /> Add Unit
                        </button>
                        <button
                            onClick={() => { setShowBulkUpload(!showBulkUpload); setShowForm(false); }}
                            className="flex items-center gap-2 bg-background text-foreground px-4 py-2 rounded-full text-xs font-bold hover:bg-input transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Upload size={14} /> Bulk Upload CSV
                        </button>
                    </div>
                )}
            </div>

            {showForm && (
                <form onSubmit={handleAddUnit} className="bg-surface p-6 rounded-xl border border-border mb-6 grid grid-cols-2 md:grid-cols-5 gap-4">
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Unit Number</label>
                        <input required className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" placeholder="e.g. 101" value={unitForm.unitNumber} onChange={e => setUnitForm({ ...unitForm, unitNumber: e.target.value })} />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Type</label>
                        <select className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={unitForm.type} onChange={e => setUnitForm({ ...unitForm, type: e.target.value })}>
                            {unitTypes.map(ut => <option key={ut} value={ut}>{ut.replace("BHK", " BHK ")}</option>)}
                        </select>
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Size (Sqft)</label>
                        <input type="number" className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" placeholder="e.g. 850" value={unitForm.sizeSqft} onChange={e => setUnitForm({ ...unitForm, sizeSqft: e.target.value })} />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Expected Rent</label>
                        <input type="number" className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" placeholder="e.g. 5000" value={unitForm.expectedRent} onChange={e => setUnitForm({ ...unitForm, expectedRent: e.target.value })} />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Building</label>
                        <select className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={unitForm.buildingId} onChange={e => setUnitForm({ ...unitForm, buildingId: e.target.value })}>
                            <option value="">No Building</option>
                            {buildings.map((b: any) => <option key={b.id} value={b.id}>{b.nameEn}</option>)}
                        </select>
                    </div>
                    <div className="col-span-2 md:col-span-5 flex justify-end gap-2 mt-2">
                        <button type="button" onClick={() => setShowForm(false)} className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200">Cancel</button>
                        <button type="submit" disabled={submitting} className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed">{submitting ? "Saving..." : "Save Unit"}</button>
                    </div>
                </form>
            )}

            {showBulkUpload && (
                <form onSubmit={handleBulkUpload} className="bg-surface p-6 rounded-xl border border-border mb-6 flex items-end gap-4">
                    <div className="flex-1">
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">CSV File</label>
                        <input type="file" accept=".csv" required onChange={e => setFile(e.target.files?.[0] || null)} className="w-full text-xs" />
                        <p className="text-[10px] text-muted mt-1">Format: unitNumber, type, sizeSqft, expectedRent, status</p>
                    </div>
                    <div className="flex-1">
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Assign to Building (Optional)</label>
                        <select className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={uploadBuildingId} onChange={e => setUploadBuildingId(e.target.value)}>
                            <option value="">No Building (Direct to Property)</option>
                            {buildings.map((b: any) => <option key={b.id} value={b.id}>{b.nameEn}</option>)}
                        </select>
                    </div>
                    <button type="submit" disabled={submitting} className="px-6 py-2 bg-primary text-white rounded-lg text-xs font-bold mb-[2px] cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed">{submitting ? "Uploading..." : "Upload"}</button>
                </form>
            )}

            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <table className="w-full text-left text-sm">
                    <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                        <tr>
                            <th className="px-6 py-4">Unit #</th>
                            <th className="px-6 py-4">Building</th>
                            <th className="px-6 py-4">Type</th>
                            <th className="px-6 py-4">Status</th>
                            <th className="px-6 py-4">Size (Sqft)</th>
                            <th className="px-6 py-4 text-right">Rent</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                        {units.map((u: any) => (
                            <tr key={u.id} className="hover:bg-background/50 transition-all duration-200">
                                <td className="px-6 py-4 font-bold text-foreground">{u.unitNumber}</td>
                                <td className="px-6 py-4 text-muted font-medium">
                                    {buildings.find((b: any) => b.id === u.building?.id)?.nameEn || "N/A"}
                                </td>
                                <td className="px-6 py-4">
                                    <span className="px-2 py-1 text-[10px] font-bold uppercase tracking-widest bg-background rounded-md">
                                        {u.type}
                                    </span>
                                </td>
                                <td className="px-6 py-4">
                                    <span className={cn(
                                        "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                        u.status === "VACANT" ? "bg-success/10 text-success" : "bg-info/10 text-info"
                                    )}>
                                        {u.status}
                                    </span>
                                </td>
                                <td className="px-6 py-4 text-muted">{u.sizeSqft}</td>
                                <td className="px-6 py-4 text-right font-bold">{formatCurrencyCompact(u.expectedRent)}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {units.length === 0 && (
                    <div className="text-center py-12 text-muted font-medium text-xs">No units found.</div>
                )}
            </div>
        </div>
    );
}

// ------ LEASES TAB SUB-COMPONENT ------

function LeasesTab({ propertyId }: { propertyId: string }) {
    const t = useTranslations("MasterData");
    const [leases, setLeases] = useState<any[]>([]);

    useEffect(() => {
        fetchLeases();
    }, [propertyId]);

    const fetchLeases = async () => {
        try {
            const res = await fetch(`/api/proxy/v1/leases/property/${propertyId}`);
            if (res.ok) setLeases(await res.json());
        } catch (err) {
            console.error(err);
        }
    };

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'ACTIVE': return 'bg-success/10 text-success';
            case 'DRAFT': return 'bg-background text-muted';
            case 'PENDING_SIGNATURE': return 'bg-warning/10 text-warning';
            case 'TERMINATED': return 'bg-error/10 text-error';
            case 'EXPIRED': return 'bg-warning/10 text-warning';
            default: return 'bg-info/10 text-info';
        }
    };

    return (
        <div>
            <h2 className="text-lg font-bold mb-6">Leases</h2>
            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <table className="w-full text-left text-sm">
                    <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                        <tr>
                            <th className="px-6 py-4">Unit</th>
                            <th className="px-6 py-4">Renter</th>
                            <th className="px-6 py-4">Status</th>
                            <th className="px-6 py-4">Period</th>
                            <th className="px-6 py-4 text-right">Rent (AED)</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                        {leases.map((l: any) => (
                            <tr key={l.id} className="hover:bg-background/50 transition-all duration-200">
                                <td className="px-6 py-4 font-bold text-foreground flex items-center gap-2">
                                    <FileText size={14} className="text-muted" />
                                    {t("unit")} {l.unitIdentifier}
                                </td>
                                <td className="px-6 py-4 text-muted font-medium">{l.renterName}</td>
                                <td className="px-6 py-4">
                                    <span className={cn("px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md", getStatusColor(l.status))}>
                                        {l.status.replace('_', ' ')}
                                    </span>
                                </td>
                                <td className="px-6 py-4 text-muted text-xs flex items-center gap-1">
                                    <Calendar size={12} className="text-muted" />
                                    {new Date(l.startDate).toLocaleDateString()} - {new Date(l.endDate).toLocaleDateString()}
                                </td>
                                <td className="px-6 py-4 text-right font-bold flex items-center justify-end gap-1">
                                    <DollarSign size={12} className="text-muted" />
                                    {formatCurrencyCompact(l.rentAmount)}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {leases.length === 0 && (
                    <div className="text-center py-12 text-muted font-medium text-xs">No leases found for this property.</div>
                )}
            </div>
        </div>
    );
}
