"use client";

import { useState, useEffect } from "react";
import { useLocale, useTranslations } from "next-intl";
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
    ChevronDown,
    ChevronRight,
    RotateCcw,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { canConfigureRentSettings } from "@/lib/rbac";
import type { UserRole } from "@/lib/rbac";
import { NumberInput } from "@/components/ui/NumberInput";

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
    // nullable fine override fields — null means inherit org default
    fineBounceAmount: number | null;
    fineSignatureMismatchAmount: number | null;
    fineAccountClosedAmount: number | null;
    fineGraceDays: number | null;
    finePerDayRate: number | null;
};

type FineConfig = {
    bounceAmount: number;
    signatureMismatchAmount: number;
    accountClosedAmount: number;
    graceDays: number;
    perDayRate: number;
};

const DEFAULT_SETTINGS: Omit<RentSettings, "propertyId"> = {
    dueDayOfMonth: 1,
    gracePeriodDays: 5,
    penaltyType: "NONE",
    penaltyAmount: 0,
    onlinePaymentEnabled: false,
    fineBounceAmount: null,
    fineSignatureMismatchAmount: null,
    fineAccountClosedAmount: null,
    fineGraceDays: null,
    finePerDayRate: null,
};

const ORG_FINE_DEFAULTS: FineConfig = {
    bounceAmount: 500,
    signatureMismatchAmount: 500,
    accountClosedAmount: 1000,
    graceDays: 7,
    perDayRate: 25,
};

export default function RentSettingsPage() {
    const t = useTranslations("OnlinePayments");
    const tFines = useTranslations("Fines");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    const [properties, setProperties] = useState<Property[]>([]);
    const [selectedPropertyId, setSelectedPropertyId] = useState("");
    const [settings, setSettings] = useState<RentSettings | null>(null);
    const [orgFines, setOrgFines] = useState<FineConfig>(ORG_FINE_DEFAULTS);
    const [initialLoading, setInitialLoading] = useState(true);
    const [loading, setLoading] = useState(false);
    const [saving, setSaving] = useState(false);
    const [saveSuccess, setSaveSuccess] = useState(false);
    const [error, setError] = useState("");
    const [finesExpanded, setFinesExpanded] = useState(false);

    useEffect(() => {
        Promise.all([
            fetchProperties(),
            fetchOrgFines(),
        ]).finally(() => setInitialLoading(false));
    }, []);

    useEffect(() => {
        if (selectedPropertyId) {
            fetchSettings(selectedPropertyId);
        } else {
            setSettings(null);
        }
    }, [selectedPropertyId]);

    const fetchOrgFines = async () => {
        try {
            const res = await fetch("/api/proxy/v1/settings/fines");
            if (res.ok) {
                const data = await res.json();
                setOrgFines(data);
            }
        } catch {
            // use defaults
        }
    };

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
            if (res.status === 204) {
                // No settings yet — use defaults
                setSettings({
                    ...DEFAULT_SETTINGS,
                    propertyId,
                });
            } else if (res.ok) {
                const data = await res.json();
                setSettings({
                    ...DEFAULT_SETTINGS,
                    ...data,
                });
            } else {
                setSettings(null);
                setError(t("loadSettingsFailed"));
            }
        } catch {
            setSettings(null);
            setError(t("loadSettingsFailed"));
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
                    fineBounceAmount: settings.fineBounceAmount,
                    fineSignatureMismatchAmount: settings.fineSignatureMismatchAmount,
                    fineAccountClosedAmount: settings.fineAccountClosedAmount,
                    fineGraceDays: settings.fineGraceDays,
                    finePerDayRate: settings.finePerDayRate,
                }),
            });
            if (res.ok) {
                setSaveSuccess(true);
                const saved = await res.json();
                setSettings({ ...DEFAULT_SETTINGS, ...saved });
                setTimeout(() => setSaveSuccess(false), 4000);
            } else {
                const data = await res.json().catch(() => ({}));
                setError((data as { message?: string }).message || t("saveSettingsFailed"));
            }
        } catch {
            setError(t("networkError"));
        } finally {
            setSaving(false);
        }
    };

    const updateField = <K extends keyof RentSettings>(field: K, value: RentSettings[K]) => {
        if (!settings) return;
        setSettings({ ...settings, [field]: value });
    };

    // Fine override helpers
    const setFineOverride = (field: keyof RentSettings, value: string) => {
        if (!settings) return;
        const parsed = value === "" ? null : Number(value);
        setSettings({ ...settings, [field]: parsed });
    };

    const resetFineOverride = (field: keyof RentSettings) => {
        if (!settings) return;
        setSettings({ ...settings, [field]: null });
    };

    const fineOverrideValue = (field: keyof RentSettings): string => {
        if (!settings) return "";
        const val = settings[field];
        return val === null || val === undefined ? "" : String(val);
    };

    const isOverridden = (field: keyof RentSettings): boolean => {
        if (!settings) return false;
        return settings[field] !== null && settings[field] !== undefined;
    };

    // Access check
    if (userRole && !canConfigureRentSettings(userRole)) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{t("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("rentSettingsAccessDenied")}</p>
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
                    {t("rentSettingsDesc")}
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
                    {t("selectPropertyHeading")}
                </h2>
                <select
                    value={selectedPropertyId}
                    onChange={e => setSelectedPropertyId(e.target.value)}
                    className="w-full border border-border rounded-lg bg-surface text-foreground p-3 text-sm font-medium cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                >
                    <option value="">{t("selectPropertyPlaceholder")}</option>
                    {properties.map(p => (
                        <option key={p.id} value={p.id}>
                            {locale === "ar" && p.nameAr ? p.nameAr : p.nameEn}
                        </option>
                    ))}
                </select>
            </div>

            {/* Loading */}
            {loading && (
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <Loader2 size={24} className="mx-auto animate-spin text-primary mb-3" />
                    <p className="text-sm text-muted font-medium">{t("loadingSettings")}</p>
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
                            <NumberInput showZero
                                min={1}
                                max={28}
                                value={settings.dueDayOfMonth}
                                onChange={(v) => updateField("dueDayOfMonth", Math.min(28, Math.max(1, v)))}
                                className="w-32 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                            />
                            <p className="text-[10px] text-muted mt-1">{t("dueDayHint")}</p>
                        </div>

                        {/* Grace Period */}
                        <div>
                            <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5 flex items-center gap-1.5">
                                <Clock size={12} />
                                {t("gracePeriodDays")}
                            </label>
                            <NumberInput showZero
                                min={0}
                                max={30}
                                value={settings.gracePeriodDays}
                                onChange={(v) => updateField("gracePeriodDays", Math.min(30, Math.max(0, v)))}
                                className="w-32 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                            />
                            <p className="text-[10px] text-muted mt-1">{t("gracePeriodHint")}</p>
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
                                    {" — "}
                                    {settings.penaltyType === "FIXED_PER_DAY"
                                        ? t("penaltyAmountPerDay")
                                        : t("penaltyPercentPerDay")}
                                </label>
                                <NumberInput showZero
                                    min={0}
                                    step={settings.penaltyType === "PERCENTAGE" ? 0.1 : 1}
                                    value={settings.penaltyAmount}
                                    onChange={(v) => updateField("penaltyAmount", v)}
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
                                        ? t("onlineEnabledHint")
                                        : t("onlineDisabledHint")}
                                </p>
                            </div>
                            <button
                                type="button"
                                role="switch"
                                aria-checked={settings.onlinePaymentEnabled}
                                aria-label={t("toggleOnlinePayment")}
                                onClick={() => updateField("onlinePaymentEnabled", !settings.onlinePaymentEnabled)}
                                className={cn(
                                    "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200 focus:ring-2 focus:ring-primary/20 focus:outline-none",
                                    settings.onlinePaymentEnabled ? "bg-primary" : "bg-muted/40"
                                )}
                            >
                                <span
                                    className={cn(
                                        "pointer-events-none inline-block h-5 w-5 rounded-full bg-white shadow ring-0 transition-transform duration-200",
                                        settings.onlinePaymentEnabled ? "translate-x-5 rtl:-translate-x-5" : "translate-x-0"
                                    )}
                                />
                            </button>
                        </div>

                        {/* ── Cheque-failure Fine Overrides (collapsible) ── */}
                        <div className="border border-border rounded-xl overflow-hidden">
                            <button
                                type="button"
                                onClick={() => setFinesExpanded(v => !v)}
                                className="w-full flex items-center justify-between p-4 bg-input hover:bg-input/80 transition-colors duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/20"
                            >
                                <span className="flex items-center gap-2 text-xs font-bold text-muted uppercase tracking-[0.15em]">
                                    <AlertTriangle size={13} className="text-amber-500/70" />
                                    {tFines("overrideSection")}
                                    {/* Show count of active overrides as a pill */}
                                    {(() => {
                                        const count = [
                                            settings.fineBounceAmount,
                                            settings.fineSignatureMismatchAmount,
                                            settings.fineAccountClosedAmount,
                                        ].filter(v => v !== null && v !== undefined).length;
                                        return count > 0 ? (
                                            <span className="ms-1 text-[9px] font-bold bg-amber-100 text-amber-700 px-2 py-0.5 rounded-full">
                                                {t("overridesActive", { count })}
                                            </span>
                                        ) : null;
                                    })()}
                                </span>
                                {finesExpanded
                                    ? <ChevronDown size={14} className="text-muted" />
                                    : <ChevronRight size={14} className="text-muted rtl:rotate-180" />
                                }
                            </button>

                            {finesExpanded && (
                                <div className="p-5 border-t border-border space-y-5">
                                    <p className="text-[11px] text-muted font-medium">
                                        {tFines("overrideSectionHint")}
                                    </p>
                                    {/* F14-29: no grace-days/per-day-rate override here — this
                                        property's late fee comes from Penalty type/amount above,
                                        combined with the lease's own grace period. */}
                                    <p className="text-[11px] text-muted font-medium" data-testid="fine-override-late-fee-hint">
                                        {tFines("lateFeeRuleHint")}
                                    </p>

                                    {/* Fine Override Field helper */}
                                    {([
                                        { field: "fineBounceAmount" as const, label: tFines("bounceAmount"), orgVal: orgFines.bounceAmount, unit: t("unitAed") },
                                        { field: "fineSignatureMismatchAmount" as const, label: tFines("signatureMismatchAmount"), orgVal: orgFines.signatureMismatchAmount, unit: t("unitAed") },
                                        { field: "fineAccountClosedAmount" as const, label: tFines("accountClosedAmount"), orgVal: orgFines.accountClosedAmount, unit: t("unitAed") },
                                    ] as const).map(({ field, label, orgVal, unit }) => {
                                        const overridden = isOverridden(field);
                                        return (
                                            <div key={field}>
                                                <div className="flex items-center gap-2 mb-1.5">
                                                    <label className="text-[10px] font-bold text-muted uppercase tracking-widest">
                                                        {label}
                                                    </label>
                                                    {overridden && (
                                                        <span className="text-[9px] font-bold bg-amber-100 text-amber-700 px-2 py-0.5 rounded-full">
                                                            {tFines("overrideBadge")}
                                                        </span>
                                                    )}
                                                </div>
                                                <div className="flex items-center gap-2">
                                                    <input
                                                        type="number"
                                                        min={0}
                                                        step={1}
                                                        value={fineOverrideValue(field)}
                                                        placeholder={String(orgVal)}
                                                        onChange={e => setFineOverride(field, e.target.value)}
                                                        className={cn(
                                                            "w-40 border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200",
                                                            overridden ? "border-amber-300 bg-amber-50/30" : "border-border"
                                                        )}
                                                    />
                                                    <span className="text-xs font-semibold text-muted">{unit}</span>
                                                    {overridden && (
                                                        <button
                                                            type="button"
                                                            onClick={() => resetFineOverride(field)}
                                                            className="flex items-center gap-1 text-[10px] font-bold text-muted hover:text-foreground border border-border rounded-lg px-2 py-1.5 transition-colors duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/20"
                                                            title={tFines("resetToDefault")}
                                                        >
                                                            <RotateCcw size={10} />
                                                            {tFines("resetToDefault")}
                                                        </button>
                                                    )}
                                                </div>
                                                {!overridden && (
                                                    <p className="text-[10px] text-muted mt-1">
                                                        {tFines("orgDefault", { value: `${orgVal} ${unit}` })}
                                                    </p>
                                                )}
                                            </div>
                                        );
                                    })}
                                </div>
                            )}
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
