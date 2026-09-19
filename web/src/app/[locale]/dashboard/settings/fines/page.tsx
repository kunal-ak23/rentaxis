"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import {
    AlertTriangle,
    CheckCircle,
    XCircle,
    Loader2,
    ShieldCheck,
    Save,
    DollarSign,
    Clock,
    Bell,
} from "lucide-react";
import { canConfigureFines } from "@/lib/rbac";
import type { UserRole } from "@/lib/rbac";
import { NumberInput } from "@/components/ui/NumberInput";

type FineConfig = {
    bounceAmount: number;
    signatureMismatchAmount: number;
    accountClosedAmount: number;
    graceDays: number;
    perDayRate: number;
    /**
     * The penalty module's own three fields (`FineConfigDTO`, spec §7.3).
     * Optional on the wire — the PUT treats a missing one as "leave it
     * alone" — but this screen always has a value to send once it has
     * loaded, either the server's own or {@link DEFAULT_FINE_CONFIG}'s.
     */
    bouncesBeforePenalty: number;
    autoProposeChequeReturn: boolean;
    autoProposeLatePayment: boolean;
};

// Mirrors FineSettingsInitializer's own DEFAULT_* constants, so a tenant
// whose settings row does not exist yet (a fresh GET creates one server-side,
// but the client's own 404 fallback should still agree with it).
const DEFAULT_FINE_CONFIG: FineConfig = {
    bounceAmount: 500,
    signatureMismatchAmount: 500,
    accountClosedAmount: 1000,
    graceDays: 7,
    perDayRate: 25,
    bouncesBeforePenalty: 2,
    autoProposeChequeReturn: true,
    autoProposeLatePayment: false,
};

export default function FinesSettingsPage() {
    const t = useTranslations("Fines");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    const [config, setConfig] = useState<FineConfig>(DEFAULT_FINE_CONFIG);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [saveSuccess, setSaveSuccess] = useState(false);
    const [error, setError] = useState("");

    useEffect(() => {
        fetchConfig();
    }, []);

    const fetchConfig = async () => {
        setLoading(true);
        setError("");
        try {
            const res = await fetch("/api/proxy/v1/settings/fines");
            if (res.ok) {
                const data = await res.json();
                // The penalty fields are nullable on the DTO in principle (the PUT
                // treats a missing one as "leave it alone"), but the GET always
                // returns a fully-seeded row — this merge only guards a partial
                // response, it does not paper over a real gap.
                setConfig({ ...DEFAULT_FINE_CONFIG, ...data });
            } else if (res.status === 404) {
                setConfig(DEFAULT_FINE_CONFIG);
            } else {
                setError(t("errorLoad"));
            }
        } catch {
            setConfig(DEFAULT_FINE_CONFIG);
        } finally {
            setLoading(false);
        }
    };

    const handleSave = async () => {
        setSaving(true);
        setError("");
        setSaveSuccess(false);
        try {
            const res = await fetch("/api/proxy/v1/settings/fines", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(config),
            });
            if (res.ok) {
                const saved = await res.json();
                setConfig(saved);
                setSaveSuccess(true);
                setTimeout(() => setSaveSuccess(false), 4000);
            } else {
                const data = await res.json().catch(() => ({}));
                setError((data as { message?: string }).message || t("errorSave"));
            }
        } catch {
            setError(t("errorNetwork"));
        } finally {
            setSaving(false);
        }
    };

    const updateField = <K extends keyof FineConfig>(field: K, value: FineConfig[K]) => {
        setConfig(prev => ({ ...prev, [field]: value }));
    };

    // Access check
    if (userRole && !canConfigureFines(userRole)) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">Access Denied</h2>
                    <p className="text-sm text-muted">{t("accessDenied")}</p>
                </div>
            </div>
        );
    }

    if (loading) {
        return (
            <div className="max-w-4xl">
                <div className="mb-8">
                    <div className="h-8 w-80 bg-input rounded-lg animate-pulse mb-2" />
                    <div className="h-4 w-[36rem] bg-background rounded-lg animate-pulse" />
                </div>
                <div className="bg-surface rounded-xl p-6 shadow-sm border border-border mb-6">
                    <div className="h-4 w-40 bg-input rounded animate-pulse mb-6" />
                    <div className="space-y-6">
                        {[1, 2, 3].map(i => (
                            <div key={i} className="h-14 w-full bg-background rounded-xl animate-pulse" />
                        ))}
                    </div>
                </div>
                <div className="bg-surface rounded-xl p-6 shadow-sm border border-border">
                    <div className="h-4 w-48 bg-input rounded animate-pulse mb-6" />
                    <div className="space-y-6">
                        {[1, 2].map(i => (
                            <div key={i} className="h-14 w-48 bg-background rounded-xl animate-pulse" />
                        ))}
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
                    <AlertTriangle size={24} className="text-primary" />
                    {t("pageTitle")}
                </h1>
                <p className="text-sm text-muted mt-1 max-w-2xl">
                    {t("pageSubtitle")}
                </p>
            </div>

            {/* Success Banner */}
            {saveSuccess && (
                <div
                    role="status"
                    aria-live="polite"
                    className="mb-6 flex items-center gap-3 bg-success/10 border border-success/20 text-success px-5 py-3 rounded-xl text-sm font-semibold"
                >
                    <CheckCircle size={18} />
                    {t("saved")}
                </div>
            )}

            {/* Error Banner */}
            {error && (
                <div
                    role="alert"
                    aria-live="assertive"
                    className="mb-6 flex items-center gap-3 bg-error/10 border border-error/20 text-error px-5 py-3 rounded-xl text-sm font-semibold"
                >
                    <XCircle size={18} />
                    {error}
                </div>
            )}

            {/* Fine Amounts Section */}
            <div className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 mb-6">
                <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-6 flex items-center gap-2">
                    <DollarSign size={14} className="text-primary/60" />
                    {t("sectionAmounts")}
                </h2>

                <div className="space-y-6">
                    {/* Bounce Fine */}
                    <div>
                        <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5">
                            {t("bounceAmount")}
                        </label>
                        <div className="flex items-center gap-2">
                            <NumberInput showZero
                                min={0}
                                step={1}
                                value={config.bounceAmount}
                                onChange={(v) => updateField("bounceAmount", v)}
                                className="w-40 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                            />
                            <span className="text-xs font-semibold text-muted">AED</span>
                        </div>
                    </div>

                    {/* Signature Mismatch Fine */}
                    <div>
                        <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5">
                            {t("signatureMismatchAmount")}
                        </label>
                        <div className="flex items-center gap-2">
                            <NumberInput showZero
                                min={0}
                                step={1}
                                value={config.signatureMismatchAmount}
                                onChange={(v) => updateField("signatureMismatchAmount", v)}
                                className="w-40 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                            />
                            <span className="text-xs font-semibold text-muted">AED</span>
                        </div>
                    </div>

                    {/* Account Closed Fine */}
                    <div>
                        <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5">
                            {t("accountClosedAmount")}
                        </label>
                        <div className="flex items-center gap-2">
                            <NumberInput showZero
                                min={0}
                                step={1}
                                value={config.accountClosedAmount}
                                onChange={(v) => updateField("accountClosedAmount", v)}
                                className="w-40 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                            />
                            <span className="text-xs font-semibold text-muted">AED</span>
                        </div>
                    </div>
                </div>
            </div>

            {/* Accrual Section */}
            <div className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 mb-6">
                <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-6 flex items-center gap-2">
                    <Clock size={14} className="text-primary/60" />
                    {t("sectionAccrual")}
                </h2>

                <div className="space-y-6">
                    {/* Grace Days */}
                    <div>
                        <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5">
                            {t("graceDays")}
                        </label>
                        <NumberInput showZero
                            min={0}
                            max={90}
                            step={1}
                            value={config.graceDays}
                            onChange={(v) => updateField("graceDays", Math.min(90, Math.max(0, v)))}
                            className="w-32 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                        />
                        <p className="text-[10px] text-muted mt-1">{t("graceDaysHint")}</p>
                    </div>

                    {/* Per-day Rate */}
                    <div>
                        <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5">
                            {t("perDayRate")}
                        </label>
                        <div className="flex items-center gap-2">
                            <NumberInput showZero
                                min={0}
                                step={0.5}
                                value={config.perDayRate}
                                onChange={(v) => updateField("perDayRate", v)}
                                className="w-40 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                            />
                            <span className="text-xs font-semibold text-muted">AED/day</span>
                        </div>
                        <p className="text-[10px] text-muted mt-1">{t("perDayRateHint")}</p>
                    </div>
                </div>
            </div>

            {/* Penalty proposal Section — FineConfigDTO's own three fields (spec §7.3) */}
            <div className="bg-surface rounded-xl p-5 border border-border hover:shadow-md transition-all duration-200 mb-6">
                <h2 className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-6 flex items-center gap-2">
                    <Bell size={14} className="text-primary/60" />
                    {t("sectionPenaltyModule")}
                </h2>

                <div className="space-y-6">
                    <div>
                        <label className="block text-[10px] font-bold text-muted uppercase tracking-widest mb-1.5">
                            {t("bouncesBeforePenalty")}
                        </label>
                        <NumberInput showZero
                            min={1}
                            step={1}
                            value={config.bouncesBeforePenalty}
                            onChange={(v) => updateField("bouncesBeforePenalty", Math.max(1, v))}
                            className="w-32 border border-border rounded-lg bg-surface p-3 text-sm font-bold text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200"
                        />
                        <p className="text-[10px] text-muted mt-1">{t("bouncesBeforePenaltyHint")}</p>
                    </div>

                    <label className="flex items-center gap-3 cursor-pointer">
                        <input
                            type="checkbox"
                            checked={config.autoProposeChequeReturn}
                            onChange={(e) => updateField("autoProposeChequeReturn", e.target.checked)}
                            className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary/20"
                        />
                        <span className="text-xs font-semibold text-foreground">{t("autoProposeChequeReturn")}</span>
                    </label>

                    <label className="flex items-center gap-3 cursor-pointer">
                        <input
                            type="checkbox"
                            checked={config.autoProposeLatePayment}
                            onChange={(e) => updateField("autoProposeLatePayment", e.target.checked)}
                            className="h-4 w-4 rounded border-border text-primary focus:ring-2 focus:ring-primary/20"
                        />
                        <span className="text-xs font-semibold text-foreground">{t("autoProposeLatePayment")}</span>
                    </label>
                </div>

                {/* Save Button */}
                <div className="mt-8 pt-6 border-t border-border">
                    <button
                        onClick={handleSave}
                        disabled={saving}
                        className="flex items-center gap-2 px-6 py-2.5 rounded-xl text-xs font-bold bg-primary text-primary-foreground hover:bg-primary/90 transition-all duration-200 shadow-sm disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    >
                        {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />}
                        {t("save")}
                    </button>
                </div>
            </div>
        </div>
    );
}
