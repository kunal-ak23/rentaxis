"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import {
    Settings,
    CheckCircle,
    XCircle,
    Loader2,
    ShieldCheck,
    Building2,
    CalendarDays,
    Clock,
    AlertTriangle,
    CreditCard,
    Save,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { canConfigureRentSettings } from "@/lib/rbac";
import type { UserRole } from "@/lib/rbac";

type Property = {
    id: string;
    nameEn: string;
    nameAr?: string;
};

type RentSettings = {
    id?: string;
    propertyId: string;
    dueDayOfMonth: number;
    gracePeriodDays: number;
    penaltyType: "NONE" | "FIXED_PER_DAY" | "PERCENTAGE";
    penaltyAmount: number;
    onlinePaymentEnabled: boolean;
};

const DEFAULT_SETTINGS: Omit<RentSettings, "propertyId"> = {
    dueDayOfMonth: 1,
    gracePeriodDays: 5,
    penaltyType: "NONE",
    penaltyAmount: 0,
    onlinePaymentEnabled: false,
};

export default function RentSettingsPage() {
    const t = useTranslations("OnlinePayments");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    const [properties, setProperties] = useState<Property[]>([]);
    const [selectedPropertyId, setSelectedPropertyId] = useState("");
    const [settings, setSettings] = useState<RentSettings | null>(null);
    const [initialLoading, setInitialLoading] = useState(true);
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [saveSuccess, setSaveSuccess] = useState(false);
    const [error, setError] = useState("");

    useEffect(() => {
        fetchProperties().finally(() => setInitialLoading(false));
    }, []);

    useEffect(() => {
        if (selectedPropertyId) {
            fetchSettings(selectedPropertyId);
        } else {
            setSettings(null);
        }
    }, [selectedPropertyId]);

    const fetchProperties = async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) {
                const data = await res.json();
                // API returns PropertyStats[] with nested property object
                const props = data.map((s: { property: Property }) => s.property);
                setProperties(props);
            }
        } catch (err) {
            console.error("Failed to fetch properties", err);
        }
    };

    const fetchSettings = async (propertyId: string) => {
        setLoading(true);
        setError("");
        try {
            const res = await fetch(`/api/proxy/v1/rent-settings/${propertyId}`);
            if (res.ok) {
                setSettings(await res.json());
            } else if (res.status === 404) {
                // No settings yet — use defaults
                setSettings({
                    ...DEFAULT_SETTINGS,
                    propertyId,
                });
            } else {
                setError("Failed to load rent settings.");
            }
        } catch (err) {
            // Treat network errors as "no settings"
            setSettings({
                ...DEFAULT_SETTINGS,
                propertyId,
            });
        } finally {
            setLoading(false);
        }
    };

    const handleSave = async () => {
        if (!settings || !selectedPropertyId) return;
        setSaving(true);
        setError("");
        setSaveSuccess(false);
        try {
            const res = await fetch(`/api/proxy/v1/rent-settings/${selectedPropertyId}`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    dueDayOfMonth: settings.dueDayOfMonth,
                    gracePeriodDays: settings.gracePeriodDays,
                    penaltyType: settings.penaltyType,
                    penaltyAmount: settings.penaltyAmount,
                    onlinePaymentEnabled: settings.onlinePaymentEnabled,
                }),
            });
            if (res.ok) {
                setSaveSuccess(true);
                const saved = await res.json();
                setSettings(saved);
                setTimeout(() => setSaveSuccess(false), 4000);
            } else {
                const data = await res.json().catch(() => ({}));
                setError(data.message || "Failed to save settings.");
            }
        } catch (err) {
            setError("Network error. Please try again.");
        } finally {
            setSaving(false);
        }
    };

    const updateField = <K extends keyof RentSettings>(field: K, value: RentSettings[K]) => {
        if (!settings) return;
        setSettings({ ...settings, [field]: value });
    };

    // Access check
    if (userRole && !canConfigureRentSettings(userRole)) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">Access Denied</h2>
                    <p className="text-sm text-muted">You do not have permission to configure rent settings.</p>
                </div>
            </div>
        );
    }

    if (initialLoading) {
        return (
            <div className="max-w-4xl">
                <div className="mb-8">
                    <div className="h-8 w-64 bg-input rounded-lg animate-pulse mb-2" />
                    <div className="h-4 w-96 bg-background rounded-lg animate-pulse" />
                </div>
                <div className="bg-surface rounded-xl p-6 shadow-sm border border-border mb-6">
                    <div className="h-4 w-40 bg-input rounded animate-pulse mb-4" />
                    <div className="h-12 w-full bg-background rounded-xl animate-pulse" />
                </div>
                <div className="bg-surface rounded-xl p-6 shadow-sm border border-border">
                    <div className="h-4 w-32 bg-input rounded animate-pulse mb-6" />
                    <div className="space-y-6">
                        <div className="h-12 w-32 bg-background rounded-xl animate-pulse" />
                        <div className="h-12 w-32 bg-background rounded-xl animate-pulse" />
                        <div className="space-y-2">
                            <div className="h-12 w-full bg-background rounded-xl animate-pulse" />
                            <div className="h-12 w-full bg-background rounded-xl animate-pulse" />
                        </div>
                    </div>
                </div>
            </div>
        );
    }

    return (
        <div className="max-w-4xl">
            {/* Page Header */}
            <div className="mb-8">
                <h1 className="text-2xl font-bold text-foreground tracking-tight flex items-center gap-3">
                    <Settings size={24} className="text-primary" />
                    {t("rentSettings")}
                </h1>
                <p className="text-sm text-muted mt-1">
                    Configure rent due dates, grace periods, and late penalties per property.
                </p>
            </div>

            {/* Success Banner */}
            {saveSuccess && (
                <div className="mb-6 flex items-center gap-3 bg-green-50 border border-green-200 text-green-700 px-5 py-3 rounded-xl text-sm font-semibold">
                    <CheckCircle size={18} />
                    {t("saved")}
                </div>
            )}

            {/* Error Banner */}
            {error && (
                <div className="mb-6 flex items-center gap-3 bg-red-50 border border-red-200 text-red-700 px-5 py-3 rounded-xl text-sm font-semibold">
                    <XCircle size={18} />
                    {error}
                </div>
            )}

            {/* Property Selector */}
            <div className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 mb-6">
                <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4 flex items-center gap-2">
                    <Building2 size={14} className="text-primary/60" />
                    Select Property
                </h2>
                <select
                    value={selectedPropertyId}
                    onChange={e => setSelectedPropertyId(e.target.value)}
                    className="w-full border border-border rounded-lg bg-surface text-foreground p-3 text-sm font-medium cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                >
                    <option value="">-- Select a property --</option>
                    {properties.map(p => (
                        <option key={p.id} value={p.id}>
                            {p.nameEn}
                        </option>
                    ))}
                </select>
            </div>

            {/* Loading */}
            {loading && (
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <Loader2 size={24} className="mx-auto animate-spin text-primary mb-3" />
                    <p className="text-sm text-muted font-medium">Loading settings...</p>
                </div>
            )}

            {/* Settings Form */}
            {settings && !loading && (
                <div className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 mb-6">
                    <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-6 flex items-center gap-2">
                        <Settings size={14} className="text-primary/60" />
                        {t("settings")}
                    </h2>

                    <div className="space-y-6">
                        {/* Due Day of Month */}
                        <div>
                            <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5 flex items-center gap-1.5">
                                <CalendarDays size={12} />
                                {t("dueDayOfMonth")}
                            </label>
                            <input
                                type="number"
                                min={1}
                                max={28}
                                value={settings.dueDayOfMonth}
                                onChange={e => updateField("dueDayOfMonth", Math.min(28, Math.max(1, Number(e.target.value))))}
                                className="w-32 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                            />
                            <p className="text-[10px] text-muted mt-1">Day of month when rent is due (1-28)</p>
                        </div>

                        {/* Grace Period */}
                        <div>
                            <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5 flex items-center gap-1.5">
                                <Clock size={12} />
                                {t("gracePeriodDays")}
                            </label>
                            <input
                                type="number"
                                min={0}
                                max={30}
                                value={settings.gracePeriodDays}
                                onChange={e => updateField("gracePeriodDays", Math.min(30, Math.max(0, Number(e.target.value))))}
                                className="w-32 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                            />
                            <p className="text-[10px] text-muted mt-1">Days after due date before penalty applies (0-30)</p>
                        </div>

                        {/* Penalty Type */}
                        <div>
                            <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-3 flex items-center gap-1.5">
                                <AlertTriangle size={12} />
                                {t("penaltyType")}
                            </label>
                            <div className="space-y-2">
                                {[
                                    { value: "NONE", label: t("none") },
                                    { value: "FIXED_PER_DAY", label: t("fixedPerDay") },
                                    { value: "PERCENTAGE", label: t("percentageOfRent") },
                                ].map(option => (
                                    <label
                                        key={option.value}
                                        className={cn(
                                            "flex items-center gap-3 p-3 rounded-xl border cursor-pointer transition-all duration-200",
                                            settings.penaltyType === option.value
                                                ? "border-primary/30 bg-primary/5"
                                                : "border-border hover:border-border/80"
                                        )}
                                    >
                                        <input
                                            type="radio"
                                            name="penaltyType"
                                            value={option.value}
                                            checked={settings.penaltyType === option.value}
                                            onChange={e => updateField("penaltyType", e.target.value as RentSettings["penaltyType"])}
                                            className="accent-primary focus:ring-2 focus:ring-primary/20"
                                        />
                                        <span className="text-sm font-semibold text-foreground">{option.label}</span>
                                    </label>
                                ))}
                            </div>
                        </div>

                        {/* Penalty Amount (conditional) */}
                        {settings.penaltyType !== "NONE" && (
                            <div>
                                <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5">
                                    {t("penaltyAmount")}
                                    {settings.penaltyType === "FIXED_PER_DAY"
                                        ? " \u2014 Amount per day (AED)"
                                        : " \u2014 Percentage per day (%)"}
                                </label>
                                <input
                                    type="number"
                                    min={0}
                                    step={settings.penaltyType === "PERCENTAGE" ? 0.1 : 1}
                                    value={settings.penaltyAmount}
                                    onChange={e => updateField("penaltyAmount", Number(e.target.value))}
                                    className="w-40 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                                />
                            </div>
                        )}

                        {/* Online Payment Toggle */}
                        <div className="flex items-center justify-between bg-input rounded-xl p-4 border border-border">
                            <div>
                                <p className="text-sm font-bold text-foreground flex items-center gap-2">
                                    <CreditCard size={14} className="text-primary/60" />
                                    {t("onlinePaymentEnabled")}
                                </p>
                                <p className="text-[11px] text-muted mt-0.5">
                                    {settings.onlinePaymentEnabled
                                        ? "Renters can pay rent online through the portal."
                                        : "Online payments are disabled for this property."}
                                </p>
                            </div>
                            <button
                                type="button"
                                role="switch"
                                aria-checked={settings.onlinePaymentEnabled}
                                aria-label="Toggle online payment"
                                onClick={() => updateField("onlinePaymentEnabled", !settings.onlinePaymentEnabled)}
                                className={cn(
                                    "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none",
                                    settings.onlinePaymentEnabled ? "bg-primary" : "bg-muted/40"
                                )}
                            >
                                <span
                                    className={cn(
                                        "pointer-events-none inline-block h-5 w-5 rounded-full bg-white shadow ring-0 transition-transform duration-200",
                                        settings.onlinePaymentEnabled ? "translate-x-5" : "translate-x-0"
                                    )}
                                />
                            </button>
                        </div>
                    </div>

                    {/* Save Button */}
                    <div className="mt-8 pt-6 border-t border-border">
                        <button
                            onClick={handleSave}
                            disabled={saving}
                            className="flex items-center gap-2 px-6 py-2.5 rounded-xl text-xs font-bold bg-primary text-primary-foreground hover:bg-primary/90 transition-all duration-200 shadow-sm disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        >
                            {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
                            {t("saveConfig")}
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}
