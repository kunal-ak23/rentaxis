"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { useTranslations, useLocale } from "next-intl";
import { Link } from "@/i18n/routing";
import { Building2, Home, FileText, ArrowLeft, Plus, MapPin, Upload, Calendar, DollarSign, Settings } from "lucide-react";
import { cn } from "@/lib/utils";
import { useSession } from "next-auth/react";
import { hasPermission, canConfigureRentSettings, type UserRole } from "@/lib/rbac";

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

    if (!property) return <div className="p-8">Loading...</div>;

    const displayName = locale === "ar" && property.nameAr ? property.nameAr : property.nameEn;

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <button
                onClick={() => router.back()}
                className="flex items-center gap-2 text-xs font-bold text-gray-400 hover:text-foreground mb-6 transition-colors"
            >
                <ArrowLeft size={14} /> Back to Projects
            </button>

            <div className="bg-white rounded-3xl p-8 shadow-sm border border-gray-100 mb-8">
                <div className="flex items-start justify-between">
                    <div>
                        <div className="flex items-center gap-3 mb-2">
                            <h1 className="text-2xl font-black text-foreground tracking-tight">{displayName}</h1>
                            <span className="text-[10px] font-black uppercase tracking-widest px-3 py-1 bg-primary/10 text-primary rounded-lg border border-primary/20">
                                {property.type}
                            </span>
                        </div>
                        <p className="text-sm font-medium text-gray-500 flex items-center gap-2">
                            <MapPin size={14} className="text-gray-400" />
                            {property.address ? `${property.address}, ` : ""}{e(property.emirate)}
                            {property.makaniNumber && ` • Makani: ${property.makaniNumber}`}
                        </p>
                    </div>
                    {canManageRentSettings && (
                        <Link
                            href="/dashboard/settings/rent-settings"
                            className="flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold border border-gray-200 text-gray-500 hover:bg-gray-50 hover:text-foreground transition-all"
                        >
                            <Settings size={14} />
                            {tOnlinePayments("rentSettings")}
                        </Link>
                    )}
                </div>
            </div>

            {/* Tabs */}
            <div className="flex items-center gap-6 mb-8 border-b border-gray-100">
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
                                "flex items-center gap-2 pb-4 text-sm font-bold transition-all relative",
                                isActive ? "text-primary" : "text-gray-400 hover:text-gray-600"
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
                    <div className="bg-gray-50 rounded-2xl p-6 border border-gray-100 col-span-1 md:col-span-2">
                        <p className="text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-4 flex items-center gap-2">
                            <Building2 size={12} className="text-primary/40" />
                            {t("propertyManager")}
                        </p>
                        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                            {managers.length > 0 ? managers.map(m => (
                                <div key={m.id} className="bg-white p-4 rounded-xl border border-border/50 shadow-sm flex flex-col gap-1">
                                    <p className="text-sm font-black text-foreground">{m.name}</p>
                                    <p className="text-[11px] font-bold text-gray-500">{m.email}</p>
                                    {m.phoneNumber && (
                                        <p className="text-[11px] font-black text-primary/70 font-mono mt-1">{m.phoneNumber}</p>
                                    )}
                                </div>
                            )) : (
                                <p className="text-xs font-medium text-gray-400 italic">No managers assigned.</p>
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
    const [formData, setFormData] = useState({ nameEn: "", nameAr: "", floors: 1 });

    const handleSubmit = async (e: any) => {
        e.preventDefault();
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
    };

    return (
        <div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-lg font-black">Buildings</h2>
                {canCreate && (
                    <button
                        onClick={() => setShowForm(true)}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold hover:opacity-90 transition-all shadow-sm"
                    >
                        <Plus size={14} /> Add Building
                    </button>
                )}
            </div>

            {showForm && (
                <form onSubmit={handleSubmit} className="bg-white p-6 rounded-2xl border border-gray-100 shadow-sm mb-6 grid grid-cols-3 gap-4">
                    <div>
                        <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1">Name (EN)</label>
                        <input required className="w-full border rounded-lg p-2 text-xs" value={formData.nameEn} onChange={e => setFormData({ ...formData, nameEn: e.target.value })} />
                    </div>
                    <div>
                        <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1 text-right">Name (AR)</label>
                        <input className="w-full border rounded-lg p-2 text-xs text-right" value={formData.nameAr} onChange={e => setFormData({ ...formData, nameAr: e.target.value })} dir="rtl" />
                    </div>
                    <div>
                        <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1">Floors</label>
                        <input type="number" required className="w-full border rounded-lg p-2 text-xs" value={formData.floors} onChange={e => setFormData({ ...formData, floors: Number(e.target.value) })} />
                    </div>
                    <div className="col-span-3 flex justify-end gap-2 mt-2">
                        <button type="button" onClick={() => setShowForm(false)} className="px-4 py-2 text-xs font-bold text-gray-500">Cancel</button>
                        <button type="submit" className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold">Save</button>
                    </div>
                </form>
            )}

            <div className="grid grid-cols-3 gap-4">
                {buildings.map((b: any) => (
                    <div key={b.id} className="bg-gray-50 p-4 rounded-2xl border border-gray-100 flex justify-between items-center">
                        <div>
                            <p className="font-bold text-sm text-foreground">{b.nameEn}</p>
                            {b.nameAr && <p className="text-xs text-gray-400 font-medium" dir="rtl">{b.nameAr}</p>}
                        </div>
                        <div className="text-xs font-bold text-gray-400 bg-white px-2 py-1 rounded-md border border-gray-100">
                            {b.floors} Floors
                        </div>
                    </div>
                ))}
                {buildings.length === 0 && !showForm && (
                    <div className="col-span-3 text-center py-12 text-gray-400 font-medium text-xs">No buildings added yet.</div>
                )}
            </div>
        </div>
    );
}

// ------ UNITS TAB SUB-COMPONENT ------

function UnitsTab({ units, buildings, propertyId, canCreate, onUpdate }: any) {
    const t = useTranslations("MasterData");
    const [showBulkUpload, setShowBulkUpload] = useState(false);
    const [file, setFile] = useState<File | null>(null);
    const [uploadBuildingId, setUploadBuildingId] = useState<string>("");

    const handleBulkUpload = async (e: any) => {
        e.preventDefault();
        if (!file) return;

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
    };

    return (
        <div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-lg font-black">Units</h2>
                {canCreate && (
                    <button
                        onClick={() => setShowBulkUpload(!showBulkUpload)}
                        className="flex items-center gap-2 bg-gray-100 text-foreground px-4 py-2 rounded-full text-xs font-bold hover:bg-gray-200 transition-all shadow-sm"
                    >
                        <Upload size={14} /> Bulk Upload CSV
                    </button>
                )}
            </div>

            {showBulkUpload && (
                <form onSubmit={handleBulkUpload} className="bg-white p-6 rounded-2xl border border-gray-100 shadow-sm mb-6 flex items-end gap-4">
                    <div className="flex-1">
                        <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1">CSV File</label>
                        <input type="file" accept=".csv" required onChange={e => setFile(e.target.files?.[0] || null)} className="w-full text-xs" />
                        <p className="text-[10px] text-gray-400 mt-1">Format: unitNumber, type, sizeSqft, expectedRent, status</p>
                    </div>
                    <div className="flex-1">
                        <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1">Assign to Building (Optional)</label>
                        <select className="w-full border rounded-lg p-2 text-xs" value={uploadBuildingId} onChange={e => setUploadBuildingId(e.target.value)}>
                            <option value="">No Building (Direct to Property)</option>
                            {buildings.map((b: any) => <option key={b.id} value={b.id}>{b.nameEn}</option>)}
                        </select>
                    </div>
                    <button type="submit" className="px-6 py-2 bg-primary text-white rounded-lg text-xs font-bold mb-[2px]">Upload</button>
                </form>
            )}

            <div className="bg-white border rounded-2xl overflow-hidden shadow-sm">
                <table className="w-full text-left text-sm">
                    <thead className="bg-gray-50 text-gray-400 text-[10px] uppercase font-bold tracking-wider">
                        <tr>
                            <th className="px-6 py-4">Unit #</th>
                            <th className="px-6 py-4">Building</th>
                            <th className="px-6 py-4">Type</th>
                            <th className="px-6 py-4">Status</th>
                            <th className="px-6 py-4">Size (Sqft)</th>
                            <th className="px-6 py-4 text-right">Rent</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                        {units.map((u: any) => (
                            <tr key={u.id} className="hover:bg-gray-50/50 transition-colors">
                                <td className="px-6 py-4 font-bold text-foreground">{u.unitNumber}</td>
                                <td className="px-6 py-4 text-gray-500 font-medium">
                                    {buildings.find((b: any) => b.id === u.building?.id)?.nameEn || "N/A"}
                                </td>
                                <td className="px-6 py-4">
                                    <span className="px-2 py-1 text-[10px] font-bold uppercase tracking-widest bg-gray-100 rounded-md">
                                        {u.type}
                                    </span>
                                </td>
                                <td className="px-6 py-4">
                                    <span className={cn(
                                        "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                        u.status === "VACANT" ? "bg-green-100 text-green-700" : "bg-blue-100 text-blue-700"
                                    )}>
                                        {u.status}
                                    </span>
                                </td>
                                <td className="px-6 py-4 text-gray-500">{u.sizeSqft}</td>
                                <td className="px-6 py-4 text-right font-black">AED {u.expectedRent}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {units.length === 0 && (
                    <div className="text-center py-12 text-gray-400 font-medium text-xs">No units found.</div>
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
            case 'ACTIVE': return 'bg-green-100 text-green-700';
            case 'DRAFT': return 'bg-gray-100 text-gray-700';
            case 'PENDING_SIGNATURE': return 'bg-yellow-100 text-yellow-700';
            case 'TERMINATED': return 'bg-red-100 text-red-700';
            case 'EXPIRED': return 'bg-orange-100 text-orange-700';
            default: return 'bg-blue-100 text-blue-700';
        }
    };

    return (
        <div>
            <h2 className="text-lg font-black mb-6">Leases</h2>
            <div className="bg-white border rounded-2xl overflow-hidden shadow-sm">
                <table className="w-full text-left text-sm">
                    <thead className="bg-gray-50 text-gray-400 text-[10px] uppercase font-bold tracking-wider">
                        <tr>
                            <th className="px-6 py-4">Unit</th>
                            <th className="px-6 py-4">Renter</th>
                            <th className="px-6 py-4">Status</th>
                            <th className="px-6 py-4">Period</th>
                            <th className="px-6 py-4 text-right">Rent (AED)</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-100">
                        {leases.map((l: any) => (
                            <tr key={l.id} className="hover:bg-gray-50/50 transition-colors">
                                <td className="px-6 py-4 font-bold text-foreground flex items-center gap-2">
                                    <FileText size={14} className="text-gray-400" />
                                    {t("unit")} {l.unitIdentifier}
                                </td>
                                <td className="px-6 py-4 text-gray-600 font-medium">{l.renterName}</td>
                                <td className="px-6 py-4">
                                    <span className={cn("px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md", getStatusColor(l.status))}>
                                        {l.status.replace('_', ' ')}
                                    </span>
                                </td>
                                <td className="px-6 py-4 text-gray-500 text-xs flex items-center gap-1">
                                    <Calendar size={12} className="text-gray-400" />
                                    {new Date(l.startDate).toLocaleDateString()} - {new Date(l.endDate).toLocaleDateString()}
                                </td>
                                <td className="px-6 py-4 text-right font-black flex items-center justify-end gap-1">
                                    <DollarSign size={12} className="text-gray-400" />
                                    {l.rentAmount?.toLocaleString()}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {leases.length === 0 && (
                    <div className="text-center py-12 text-gray-400 font-medium text-xs">No leases found for this property.</div>
                )}
            </div>
        </div>
    );
}
