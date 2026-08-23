"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { fetchBusinesses } from "@/lib/api/promotions";
import type { PromoBusinessDTO } from "@/types/promotion";
import { BusinessesTab } from "./_components/BusinessesTab";
import { AdsTab } from "./_components/AdsTab";
import type { PropertyOption } from "./_components/AdEditor";

type Tab = "businesses" | "ads";

export default function PromotionsPage() {
    const t = useTranslations("Promotions");
    const { data: session, status: sessionStatus } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canManagePromotions");

    const [tab, setTab] = useState<Tab>("ads");
    const [businesses, setBusinesses] = useState<PromoBusinessDTO[]>([]);
    const [properties, setProperties] = useState<PropertyOption[]>([]);

    // The ad editor needs the full business list (for the picker and the
    // allowlist hint) and the property list (for targeting), so both are
    // loaded once at the shell rather than per tab render.
    const loadLookups = useCallback(async (cancelled: () => boolean = () => false) => {
        const page = await fetchBusinesses(0, 200);
        if (cancelled()) return;
        setBusinesses(page.content);
        const res = await fetch("/api/proxy/v1/properties");
        if (!res.ok || cancelled()) return;
        const data: Array<{ property: { id: string; nameEn: string } }> = await res.json();
        if (cancelled()) return;
        setProperties(data.map(s => ({ id: s.property.id, nameEn: s.property.nameEn })));
    }, []);

    useEffect(() => {
        if (sessionStatus !== "authenticated" || !canView) return;
        // Guarded rather than fire-and-forget: both setters run after awaits, so
        // navigating away mid-flight would otherwise set state on an unmounted
        // component. This is also what satisfies react-hooks/set-state-in-effect,
        // but the guard is worth having on its own terms.
        let cancelled = false;
        loadLookups(() => cancelled).catch(() => {
            // Lookups are for the pickers; the tabs surface their own errors.
        });
        return () => { cancelled = true; };
    }, [sessionStatus, canView, loadLookups]);

    if (sessionStatus === "loading") return null;
    if (!canView) return null;

    return (
        <div className="space-y-6 p-6">
            <h1 className="text-2xl font-semibold">{t("title")}</h1>

            <div className="flex gap-2 border-b">
                <button type="button" onClick={() => setTab("ads")}
                    className={`px-4 py-2 text-sm ${tab === "ads" ? "border-b-2 border-gray-900 font-medium" : "text-gray-500"}`}>
                    {t("adsTab")}
                </button>
                <button type="button" onClick={() => setTab("businesses")}
                    className={`px-4 py-2 text-sm ${tab === "businesses" ? "border-b-2 border-gray-900 font-medium" : "text-gray-500"}`}>
                    {t("businessesTab")}
                </button>
            </div>

            {tab === "ads"
                ? <AdsTab businesses={businesses} properties={properties} />
                : <BusinessesTab onChanged={loadLookups} />}
        </div>
    );
}
