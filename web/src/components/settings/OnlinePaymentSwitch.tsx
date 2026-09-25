// src/components/settings/OnlinePaymentSwitch.tsx
"use client";
import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { readRentSettings, toRentSettingsBody, type RentSettingsData } from "@/lib/rentSettings";

type Property = { id: string; nameEn: string; nameAr?: string };

/**
 * Settings › Payments: the per-property online-payment switch that used to sit
 * inside Rent settings. The save endpoint takes the whole row, so the switch
 * re-reads it and sends every other field back exactly as loaded.
 */
export default function OnlinePaymentSwitch() {
    const t = useTranslations("SettingsPage");
    const tOp = useTranslations("OnlinePayments");
    const locale = useLocale();
    const [properties, setProperties] = useState<Property[]>([]);
    const [propertyId, setPropertyId] = useState("");
    const [settings, setSettings] = useState<RentSettingsData | null>(null);
    const [status, setStatus] = useState<"idle" | "saving" | "saved" | "error">("idle");

    useEffect(() => {
        fetch("/api/proxy/v1/properties").then(r => (r.ok ? r.json() : [])).then((rows: { property: Property }[]) => setProperties(rows.map(r => r.property))).catch(() => setProperties([]));
    }, []);
    useEffect(() => {
        setSettings(null);
        if (!propertyId) return;
        fetch(`/api/proxy/v1/rent-settings/${propertyId}`).then(res => readRentSettings(res, propertyId)).then(setSettings).catch(() => setSettings(null));
    }, [propertyId]);

    const toggle = async () => {
        if (!settings) return;
        const next = { ...settings, onlinePaymentEnabled: !settings.onlinePaymentEnabled };
        setStatus("saving");
        try {
            const res = await fetch(`/api/proxy/v1/rent-settings/${propertyId}`, {
                method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(toRentSettingsBody(next)),
            });
            if (!res.ok) throw new Error(String(res.status));
            setSettings(next);
            setStatus("saved");
        } catch {
            setStatus("error");
        }
    };

    return (
        <section className="bg-surface rounded-xl border border-border p-5 space-y-4" data-testid="online-payment-switch">
            <div>
                <h3 className="text-sm font-bold text-foreground">{t("onlinePaymentsHeading")}</h3>
                <p className="text-[11px] text-muted mt-0.5">{t("onlinePaymentsHint")}</p>
            </div>
            <select value={propertyId} onChange={e => setPropertyId(e.target.value)} aria-label={t("selectProperty")}
                className="w-full border border-border rounded-lg bg-surface p-3 text-sm">
                <option value="">{t("selectProperty")}</option>
                {properties.map(p => <option key={p.id} value={p.id}>{locale === "ar" && p.nameAr ? p.nameAr : p.nameEn}</option>)}
            </select>
            {settings && (
                <div className="flex items-center justify-between bg-input rounded-xl p-4 border border-border">
                    <p className="text-sm font-bold text-foreground">{tOp("onlinePaymentEnabled")}</p>
                    <button type="button" role="switch" aria-checked={settings.onlinePaymentEnabled} aria-label={tOp("toggleOnlinePayment")}
                        disabled={status === "saving"} onClick={toggle}
                        className={cn("relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors",
                            settings.onlinePaymentEnabled ? "bg-primary" : "bg-muted/40")}>
                        <span className={cn("pointer-events-none inline-block h-5 w-5 rounded-full bg-white shadow transition-transform",
                            settings.onlinePaymentEnabled ? "translate-x-5 rtl:-translate-x-5" : "translate-x-0")} />
                    </button>
                </div>
            )}
            {status === "saved" && <p className="text-xs text-success" role="status">{t("onlineSaved")}</p>}
            {status === "error" && <p className="text-xs text-error" role="alert">{t("onlineSaveFailed")}</p>}
        </section>
    );
}
