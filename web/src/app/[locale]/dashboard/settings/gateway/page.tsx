"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import {
    CreditCard,
    Eye,
    EyeOff,
    CheckCircle,
    XCircle,
    Loader2,
    ShieldCheck,
    Wifi,
    Save,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { canConfigureGateway } from "@/lib/rbac";
import type { UserRole } from "@/lib/rbac";

type Gateway = {
    id: string;
    code: string;
    name: string;
    description: string;
    isActive: boolean;
    sdkJsUrl: string;
    supportedCurrencies: string[];
};

type GatewayConfig = {
    id: string;
    gatewayId: string;
    gatewayCode: string;
    gatewayName: string;
    isActive: boolean;
    isTestMode: boolean;
    apiKeyMasked: string;
    hasWebhookSecret: boolean;
};

export default function GatewayConfigPage() {
    const t = useTranslations("OnlinePayments");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    const [gateways, setGateways] = useState<Gateway[]>([]);
    const [existingConfig, setExistingConfig] = useState<GatewayConfig | null>(null);

    const [selectedGatewayId, setSelectedGatewayId] = useState("");
    const [apiKey, setApiKey] = useState("");
    const [apiSecret, setApiSecret] = useState("");
    const [webhookSecret, setWebhookSecret] = useState("");
    const [isTestMode, setIsTestMode] = useState(true);

    const [showApiSecret, setShowApiSecret] = useState(false);
    const [showWebhookSecret, setShowWebhookSecret] = useState(false);

    const [saving, setSaving] = useState(false);
    const [testing, setTesting] = useState(false);
    const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null);
    const [saveSuccess, setSaveSuccess] = useState(false);
    const [error, setError] = useState("");

    useEffect(() => {
        fetchGateways();
        fetchExistingConfig();
    }, []);

    const fetchGateways = async () => {
        try {
            const res = await fetch("/api/proxy/v1/gateway-config/gateways");
            if (res.ok) setGateways(await res.json());
        } catch (err) {
            console.error("Failed to fetch gateways", err);
        }
    };

    const fetchExistingConfig = async () => {
        try {
            const res = await fetch("/api/proxy/v1/gateway-config");
            if (res.ok) {
                const config: GatewayConfig = await res.json();
                setExistingConfig(config);
                setSelectedGatewayId(config.gatewayId);
                setApiKey(config.apiKeyMasked || "");
                setIsTestMode(config.isTestMode);
            }
        } catch (err) {
            // No existing config — that's fine
        }
    };

    const handleTestConnection = async () => {
        setTesting(true);
        setTestResult(null);
        try {
            const res = await fetch("/api/proxy/v1/gateway-config/test", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
            });
            const result = await res.json();
            setTestResult(result);
        } catch (err) {
            setTestResult({ success: false, message: "Network error" });
        } finally {
            setTesting(false);
        }
    };

    const handleSave = async () => {
        if (!selectedGatewayId) {
            setError("Please select a payment gateway.");
            return;
        }
        setSaving(true);
        setError("");
        setSaveSuccess(false);
        try {
            const res = await fetch("/api/proxy/v1/gateway-config", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    gatewayId: selectedGatewayId,
                    apiKey,
                    apiSecret,
                    webhookSecret,
                    isTestMode,
                }),
            });
            if (res.ok) {
                setSaveSuccess(true);
                fetchExistingConfig();
                setTimeout(() => setSaveSuccess(false), 4000);
            } else {
                const data = await res.json().catch(() => ({}));
                setError(data.message || "Failed to save configuration.");
            }
        } catch (err) {
            setError("Network error. Please try again.");
        } finally {
            setSaving(false);
        }
    };

    // Access check
    if (userRole && !canConfigureGateway(userRole)) {
        return (
            <div className="max-w-4xl">
                <div className="bg-white rounded-2xl p-12 shadow-sm border border-gray-100 text-center">
                    <ShieldCheck size={48} className="mx-auto text-gray-300 mb-4" />
                    <h2 className="text-lg font-black text-foreground mb-2">Access Denied</h2>
                    <p className="text-sm text-gray-500">You do not have permission to configure payment gateways.</p>
                </div>
            </div>
        );
    }

    const selectedGateway = gateways.find(g => g.id === selectedGatewayId);

    return (
        <div className="max-w-4xl">
            {/* Page Header */}
            <div className="mb-8">
                <h1 className="text-2xl font-black text-foreground tracking-tight flex items-center gap-3">
                    <CreditCard size={24} className="text-primary" />
                    {t("gatewayConfig")}
                </h1>
                <p className="text-sm text-gray-500 mt-1">
                    Configure your payment gateway credentials for online rent collection.
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

            {/* Card 1: Select Gateway */}
            <div className="bg-white rounded-2xl p-6 shadow-sm border border-gray-100 mb-6">
                <h2 className="text-sm font-black text-foreground uppercase tracking-widest mb-4 flex items-center gap-2">
                    <CreditCard size={14} className="text-primary/60" />
                    {t("selectGateway")}
                </h2>
                <select
                    value={selectedGatewayId}
                    onChange={e => {
                        setSelectedGatewayId(e.target.value);
                        setTestResult(null);
                    }}
                    className="w-full border border-gray-200 rounded-xl p-3 text-sm font-medium focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                >
                    <option value="">-- Select a gateway --</option>
                    {gateways.map(gw => (
                        <option key={gw.id} value={gw.id}>
                            {gw.name} {gw.isActive ? "" : "(Inactive)"}
                        </option>
                    ))}
                </select>
                {selectedGateway && selectedGateway.description && (
                    <p className="text-xs text-gray-400 mt-2">{selectedGateway.description}</p>
                )}
                {existingConfig && (
                    <p className="text-xs text-primary/70 mt-2 font-semibold">
                        Currently configured: {existingConfig.gatewayName}
                        {existingConfig.isTestMode ? ` (${t("testMode")})` : ` (${t("production")})`}
                    </p>
                )}
            </div>

            {/* Card 2: Credentials */}
            {selectedGatewayId && (
                <div className="bg-white rounded-2xl p-6 shadow-sm border border-gray-100 mb-6">
                    <h2 className="text-sm font-black text-foreground uppercase tracking-widest mb-6 flex items-center gap-2">
                        <ShieldCheck size={14} className="text-primary/60" />
                        Credentials
                    </h2>

                    <div className="space-y-5">
                        {/* API Key */}
                        <div>
                            <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1.5">
                                {t("apiKey")}
                            </label>
                            <input
                                type="text"
                                value={apiKey}
                                onChange={e => setApiKey(e.target.value)}
                                placeholder="pk_test_..."
                                className="w-full border border-gray-200 rounded-xl p-3 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                            />
                        </div>

                        {/* API Secret */}
                        <div>
                            <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1.5">
                                {t("apiSecret")}
                            </label>
                            <div className="relative">
                                <input
                                    type={showApiSecret ? "text" : "password"}
                                    value={apiSecret}
                                    onChange={e => setApiSecret(e.target.value)}
                                    placeholder="sk_test_..."
                                    className="w-full border border-gray-200 rounded-xl p-3 pr-12 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                                />
                                <button
                                    type="button"
                                    onClick={() => setShowApiSecret(!showApiSecret)}
                                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 transition-colors"
                                >
                                    {showApiSecret ? <EyeOff size={16} /> : <Eye size={16} />}
                                </button>
                            </div>
                        </div>

                        {/* Webhook Secret */}
                        <div>
                            <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-1.5">
                                {t("webhookSecret")}
                            </label>
                            <div className="relative">
                                <input
                                    type={showWebhookSecret ? "text" : "password"}
                                    value={webhookSecret}
                                    onChange={e => setWebhookSecret(e.target.value)}
                                    placeholder="whsec_..."
                                    className="w-full border border-gray-200 rounded-xl p-3 pr-12 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                                />
                                <button
                                    type="button"
                                    onClick={() => setShowWebhookSecret(!showWebhookSecret)}
                                    className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 transition-colors"
                                >
                                    {showWebhookSecret ? <EyeOff size={16} /> : <Eye size={16} />}
                                </button>
                            </div>
                            {existingConfig?.hasWebhookSecret && !webhookSecret && (
                                <p className="text-[10px] text-gray-400 mt-1">Webhook secret is already configured. Leave blank to keep existing.</p>
                            )}
                        </div>

                        {/* Test Mode Toggle */}
                        <div className="flex items-center justify-between bg-gray-50 rounded-xl p-4 border border-gray-100">
                            <div>
                                <p className="text-sm font-bold text-foreground">{t("testMode")}</p>
                                <p className="text-[11px] text-gray-400 mt-0.5">
                                    {isTestMode
                                        ? "Using sandbox/test environment. No real charges."
                                        : "Using production environment. Real charges will be made."}
                                </p>
                            </div>
                            <button
                                type="button"
                                role="switch"
                                aria-checked={isTestMode}
                                onClick={() => setIsTestMode(!isTestMode)}
                                className={cn(
                                    "relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200",
                                    isTestMode ? "bg-primary" : "bg-gray-300"
                                )}
                            >
                                <span
                                    className={cn(
                                        "pointer-events-none inline-block h-5 w-5 rounded-full bg-white shadow ring-0 transition-transform duration-200",
                                        isTestMode ? "translate-x-5" : "translate-x-0"
                                    )}
                                />
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Card 3: Actions */}
            {selectedGatewayId && (
                <div className="bg-white rounded-2xl p-6 shadow-sm border border-gray-100">
                    <h2 className="text-sm font-black text-foreground uppercase tracking-widest mb-4 flex items-center gap-2">
                        <Wifi size={14} className="text-primary/60" />
                        Actions
                    </h2>

                    {/* Test Result */}
                    {testResult && (
                        <div
                            className={cn(
                                "mb-4 flex items-center gap-3 px-4 py-3 rounded-xl text-sm font-semibold border",
                                testResult.success
                                    ? "bg-green-50 border-green-200 text-green-700"
                                    : "bg-red-50 border-red-200 text-red-700"
                            )}
                        >
                            {testResult.success ? <CheckCircle size={18} /> : <XCircle size={18} />}
                            {testResult.success ? t("connectionSuccess") : t("connectionFailed")}
                            {testResult.message && (
                                <span className="text-xs font-normal ml-1">— {testResult.message}</span>
                            )}
                        </div>
                    )}

                    <div className="flex items-center gap-3">
                        <button
                            onClick={handleTestConnection}
                            disabled={testing}
                            className="flex items-center gap-2 px-5 py-2.5 rounded-xl text-xs font-bold border border-gray-200 hover:bg-gray-50 transition-all disabled:opacity-50"
                        >
                            {testing ? <Loader2 size={14} className="animate-spin" /> : <Wifi size={14} />}
                            {t("testConnection")}
                        </button>

                        <button
                            onClick={handleSave}
                            disabled={saving}
                            className="flex items-center gap-2 px-5 py-2.5 rounded-xl text-xs font-bold bg-primary text-primary-foreground hover:opacity-90 transition-all shadow-sm disabled:opacity-50"
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
