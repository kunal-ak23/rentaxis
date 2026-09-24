"use client";

import { useState, useEffect, use } from "react";
import { useTranslations } from "next-intl";
import { Plus, X, Building, Info, LayoutList, Ruler, Hash, Users, CreditCard, ArrowLeft, Activity } from "lucide-react";
import { cn } from "@/lib/utils";
import CardFlip from "@/components/ui/card-flip";
import { Link } from "@/i18n/routing";
import { formatCurrency, formatCurrencyCompact, formatDate } from "@/lib/format";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { NumberInput } from "@/components/ui/NumberInput";

type UnitOccupancy = "OCCUPIED" | "RESERVED" | "VACANT" | "MAINTENANCE";

type Unit = {
    id: string;
    unitNumber: string;
    type: string;
    sizeSqft: number;
    status: string;
    /** Derived by date (occupied = a posted lease covers today); falls back to `status` on an older API response. */
    occupancy?: UnitOccupancy;
    nextLeaseStart?: string | null;
    nextTenantName?: string | null;
    expectedRent: number;
    actualRent: number;
    currentTenantName?: string;
};

/** The badge/back-panel text for a unit's occupancy, translated. */
function occupancyLabel(u: Unit, t: (key: string, values?: Record<string, string>) => string): string {
    const occ = u.occupancy ?? u.status;
    switch (occ) {
        case "RESERVED":
            if (!u.nextLeaseStart) return t("reservedStatus");
            return u.nextTenantName
                ? t("reservedFromBadge", { date: formatDate(u.nextLeaseStart), tenant: u.nextTenantName })
                : t("reservedFromBadgeNoTenant", { date: formatDate(u.nextLeaseStart) });
        case "OCCUPIED":
            return t("occupied");
        case "MAINTENANCE":
            return t("maintenanceStatus");
        case "VACANT":
            return t("vacant");
        default:
            return occ;
    }
}

export default function UnitsPage({ params }: { params: Promise<{ id: string }> }) {
    const { id: propertyId } = use(params);
    const t = useTranslations("MasterData");
    const tCommon = useTranslations("Common");
    const [units, setUnits] = useState<Unit[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [showForm, setShowForm] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);
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
            } else {
                // A non-2xx used to leave the state at its initial empty
                // value, so a failed request rendered as "nothing here".
                setLoadError(tCommon("loadFailedUnits"));
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
        setFormError(null);
        try {
            const res = await fetch("/api/proxy/v1/units", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData)
            });
            await throwIfNotOk(res);
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
        } catch (err) {
            console.error(err);
            setFormError(err instanceof ApiError ? err.message : "Failed to create property. Please try again.");
        } finally {
            setSubmitting(false);
        }
    };

    const reload = () => {
        setLoadError(null);
        fetchUnits();
    };

    return (
        <div className="p-8 max-w-7xl mx-auto">
            {loadError && <LoadErrorBanner message={loadError} onRetry={reload} />}
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <Link href="/dashboard/properties" className="flex items-center gap-1.5 text-[10px] font-bold text-primary uppercase tracking-widest mb-4 hover:-translate-x-1 transition-transform cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded">
                        <ArrowLeft size={12} />
                        Back to Projects
                    </Link>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t("properties")}</h1>
                    <p className="text-xs text-muted font-medium tracking-tight">Manage individual properties within this project.</p>
                </div>
                <button
                    onClick={() => { setFormError(null); setShowForm(true); }}
                    className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 active:scale-95 self-start cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                >
                    <Plus size={14} />
                    {t("addProperty")}
                </button>
            </div>

            {showForm && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-md w-full shadow-2xl border border-border relative">
                        <button onClick={() => setShowForm(false)} aria-label="Close" className="absolute right-6 top-6 p-2 text-muted hover:text-foreground transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg"><X size={18} /></button>
                        <h2 className="text-lg font-bold mb-1 text-foreground leading-tight">{t("addProperty")}</h2>
                        <p className="text-xs text-muted mb-8 font-medium">Add a new inventory unit to this project.</p>

                        <form onSubmit={handleSubmit} className="space-y-5">
                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("unitNumber")}</label>
                                    <input required placeholder="e.g. 101" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.unitNumber} onChange={ev => setFormData({ ...formData, unitNumber: ev.target.value })} />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("status")}</label>
                                    <select className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.status} onChange={ev => setFormData({ ...formData, status: ev.target.value })}>
                                        <option value="VACANT">Vacant</option>
                                        <option value="OCCUPIED">Occupied</option>
                                        <option value="MAINTENANCE">Maintenance</option>
                                    </select>
                                </div>
                            </div>
                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("expectedRent")}</label>
                                    <NumberInput placeholder="AED" className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.expectedRent} onChange={(v) => setFormData({ ...formData, expectedRent: v })} />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("sizeSqft")}</label>
                                    <NumberInput placeholder="Sq. Ft." className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.sizeSqft} onChange={(v) => setFormData({ ...formData, sizeSqft: v })} />
                                </div>
                            </div>
                            {formError && (
                                <div className="bg-error/10 border border-error/30 rounded-lg px-4 py-3 text-xs text-error">
                                    {formError}
                                </div>
                            )}
                            <div className="flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowForm(false)} className="px-6 py-3 rounded-xl text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200">{t("cancel")}</button>
                                <button type="submit" disabled={submitting} className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed">{submitting ? "Creating..." : t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
                {loading ? (
                    Array.from({ length: 4 }).map((_, i) => (
                        <div key={i} className="h-[280px] bg-surface rounded-xl border border-border p-6 animate-pulse">
                            <div className="flex justify-between items-start mb-8">
                                <div className="w-10 h-10 bg-background rounded-xl" />
                                <div className="w-16 h-5 bg-background rounded-lg" />
                            </div>
                            <div className="mb-6">
                                <div className="h-3 w-20 bg-background rounded mb-2" />
                                <div className="h-6 w-28 bg-input rounded" />
                            </div>
                            <div className="border-t border-border pt-4 flex gap-4">
                                <div className="h-3 w-16 bg-background rounded" />
                                <div className="h-3 w-20 bg-background rounded" />
                            </div>
                        </div>
                    ))
                ) : units.map(u => {
                    const occ = u.occupancy ?? u.status;
                    return (
                    <CardFlip
                        key={u.id}
                        className="h-[280px] transition-all duration-200"
                        front={
                            <div className="h-full flex flex-col justify-between">
                                <div className="flex justify-between items-start gap-2">
                                    <div className="w-10 h-10 bg-background rounded-xl flex items-center justify-center text-muted border border-border shrink-0">
                                        <Building size={20} />
                                    </div>
                                    <span
                                        data-testid={`unit-status-badge-${u.id}`}
                                        className={cn(
                                        "px-2.5 py-1 rounded-lg text-[9px] font-bold border text-end",
                                        occ === 'RESERVED' ? 'normal-case tracking-normal' : 'uppercase tracking-widest',
                                        occ === 'VACANT' ? 'bg-success/10 text-success border-success/20' :
                                            occ === 'OCCUPIED' ? 'bg-primary/5 text-primary border-primary/10' :
                                                occ === 'RESERVED' ? 'bg-[var(--gold-500)]/10 text-[var(--gold-700)] border-[var(--gold-500)]/20' :
                                                    'bg-warning/10 text-warning border-warning/20'
                                    )}>
                                        {occupancyLabel(u, t)}
                                    </span>
                                </div>
                                <div>
                                    <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Unit Number</p>
                                    <h3 className="text-xl font-bold text-foreground tracking-tight">#{u.unitNumber}</h3>
                                </div>
                                <div className="flex items-center gap-4 border-t border-border pt-4 mt-4">
                                    <div className="flex items-center gap-1.5">
                                        <LayoutList size={12} className="text-muted" />
                                        <span className="text-[10px] font-bold text-muted uppercase">{u.type}</span>
                                    </div>
                                    <div className="flex items-center gap-1.5">
                                        <Ruler size={12} className="text-muted" />
                                        <span className="text-[10px] font-bold text-muted uppercase">{u.sizeSqft} SQFT</span>
                                    </div>
                                </div>
                            </div>
                        }
                        back={
                            <div className="h-full flex flex-col">
                                <h4 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4 flex items-center gap-2">
                                    <Info size={12} />
                                    Property Details
                                </h4>
                                <div className="space-y-4 flex-1">
                                    <div className="bg-surface/50 rounded-xl p-3 border border-border">
                                        <div className="flex items-center gap-2 mb-1">
                                            <Users size={12} className="text-primary/40" />
                                            <span className="text-[9px] font-bold text-muted uppercase">{occ === 'OCCUPIED' ? t('currentTenant') : t('status')}</span>
                                        </div>
                                        <p className="text-xs font-bold text-foreground">
                                            {occ === 'OCCUPIED'
                                                ? (u.currentTenantName || '—')
                                                : occ === 'RESERVED'
                                                    ? t('reservedStatus')
                                                    : occ === 'MAINTENANCE'
                                                        ? t('underMaintenance')
                                                        : t('readyToLease')}
                                        </p>
                                        {occ === 'OCCUPIED' && u.nextLeaseStart && (
                                            <p className="text-[10px] text-muted mt-1" data-testid={`unit-next-lease-${u.id}`}>
                                                {t('nextTenantFrom', { tenant: u.nextTenantName || '—', date: formatDate(u.nextLeaseStart) })}
                                            </p>
                                        )}
                                    </div>
                                    <div className="grid grid-cols-2 gap-3">
                                        <div className="bg-surface/50 rounded-xl p-3 border border-border relative overflow-hidden">
                                            <div className="absolute top-0 left-0 right-0 h-[2px] bg-accent" />
                                            <div className="flex items-center gap-2 mb-1">
                                                <CreditCard size={12} className="text-primary/40" />
                                                <span className="text-[9px] font-bold text-muted uppercase">Target Rent</span>
                                            </div>
                                            <p className="text-xs font-bold text-foreground">{formatCurrencyCompact(u.expectedRent)}</p>
                                        </div>
                                        <div className="bg-surface/50 rounded-xl p-3 border border-border relative overflow-hidden">
                                            <div className="absolute top-0 left-0 right-0 h-[2px] bg-accent" />
                                            <div className="flex items-center gap-2 mb-1">
                                                <Activity size={12} className="text-primary/40" />
                                                <span className="text-[9px] font-bold text-muted uppercase">Actual Rent</span>
                                            </div>
                                            <p className="text-xs font-bold text-foreground">{formatCurrencyCompact(u.actualRent)}</p>
                                        </div>
                                    </div>
                                </div>
                                <button className="mt-4 w-full py-2.5 rounded-xl bg-primary text-white text-[10px] font-bold uppercase tracking-widest hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none">
                                    Edit Details
                                </button>
                            </div>
                        }
                    />
                    );
                })}
            </div>

            {!loading && units.length === 0 && (
                <div className="p-20 text-center flex flex-col items-center bg-background border border-dashed border-border rounded-xl">
                    <div className="w-16 h-16 bg-surface rounded-xl flex items-center justify-center text-muted mb-6">
                        <Building size={32} />
                    </div>
                    <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-6">
                        No properties in this project
                    </p>
                    <button onClick={() => setShowForm(true)} className="text-xs font-bold text-primary border-b-2 border-primary pb-0.5 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded transition-all duration-200">
                        {t("addProperty")}
                    </button>
                </div>
            )}
        </div>
    );
}
