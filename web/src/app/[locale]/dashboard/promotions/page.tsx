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

const BUSINESS_LOOKUP_CAP = 200;

export default function PromotionsPage() {
    const t = useTranslations("Promotions");
    const { data: session, status: sessionStatus } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canManagePromotions");

    const [tab, setTab] = useState<Tab>("ads");
    const [businesses, setBusinesses] = useState<PromoBusinessDTO[]>([]);
    const [properties, setProperties] = useState<PropertyOption[]>([]);
    const [lookupWarning, setLookupWarning] = useState<string | null>(null);

    // The ad editor needs the full business list (for the picker and the
    // allowlist hint) and the property list (for targeting), so both are
    // loaded once at the shell rather than per tab render.
    const loadLookups = useCallback(async (cancelled: () => boolean = () => false) => {
        setLookupWarning(null);
        // One page, deliberately: the editor needs every business in its
        // picker, and the stated scale is ~40. Past the cap the picker would
        // silently omit the tail AND AdsTab's lookup would miss, falling back
        // to "active" and showing Live for a deactivated business's ads — so
        // fail loudly rather than degrade quietly.
        const page = await fetchBusinesses(0, BUSINESS_LOOKUP_CAP);
        if (cancelled()) return;
        if (page.totalElements > BUSINESS_LOOKUP_CAP) {
            setLookupWarning(t("tooManyBusinesses", { cap: BUSINESS_LOOKUP_CAP }));
        }
        setBusinesses(page.content);
        const res = await fetch("/api/proxy/v1/properties");
        if (!res.ok || cancelled()) return;
        const data: Array<{ property: { id: string; nameEn: string } }> = await res.json();
        if (cancelled()) return;
        setProperties(data.map(s => ({ id: s.property.id, nameEn: s.property.nameEn })));
    }, [t]);

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
    if (!canView) {
        // A blank page is indistinguishable from a broken build. The sidebar
        // hides this entry for the wrong role, but the URL is still reachable.
        return <p className="p-6 text-sm text-gray-500">{t("noAccess")}</p>;
    }

    return (
        <div className="space-y-6 p-6">
            <h1 className="text-2xl font-semibold">{t("title")}</h1>

            {lookupWarning && (
                <p role="alert" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-800">
                    {lookupWarning}
                </p>
            )}

            <div role="tablist" className="flex gap-2 border-b">
                <button type="button" role="tab" aria-selected={tab === "ads"}
                    onClick={() => setTab("ads")}
                    className={`px-4 py-2 text-sm ${tab === "ads" ? "border-b-2 border-gray-900 font-medium" : "text-gray-500"}`}>
                    {t("adsTab")}
                </button>
                <button type="button" role="tab" aria-selected={tab === "businesses"}
                    onClick={() => setTab("businesses")}
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
