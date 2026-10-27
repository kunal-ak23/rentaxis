"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Loader2 } from "lucide-react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import {
    ApiError, fetchPromoterBusinesses, fetchPromoterMe, fetchPromoterProperties,
} from "@/lib/api/promoter";
import type { PromoBusinessDTO, PromoterMeDTO, PromoterPropertyDTO } from "@/types/promotion";
import { PromoterAdsTab } from "./_components/PromoterAdsTab";
import { PromoterBusinessesTab } from "./_components/PromoterBusinessesTab";

type Tab = "ads" | "businesses";

/**
 * The promoter's portal (/dashboard/promoter): an outside advertiser's ads and
 * businesses and nothing else. Every call here is to /promoter/**; the backend
 * refuses a PROMOTER everything outside it, so there is no fallback to the
 * admin Promotions endpoints.
 */
export default function PromoterPortalPage() {
    const t = useTranslations("Promoter");
    const { data: session, status: sessionStatus } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canUsePromoterPortal");

    const [tab, setTab] = useState<Tab>("ads");
    const [me, setMe] = useState<PromoterMeDTO | null>(null);
    const [businesses, setBusinesses] = useState<PromoBusinessDTO[]>([]);
    const [properties, setProperties] = useState<PromoterPropertyDTO[]>([]);
    const [loading, setLoading] = useState(true);
    const [loadError, setLoadError] = useState<string | null>(null);

    // What the tabs and the ad editor need, loaded once: who this promoter is,
    // the businesses they were given and the properties they may target.
    const loadLookups = useCallback(async (cancelled: () => boolean = () => false) => {
        try {
            const [meDto, bizList, propList] = await Promise.all([
                fetchPromoterMe(), fetchPromoterBusinesses(), fetchPromoterProperties(),
            ]);
            if (cancelled()) return;
            setMe(meDto);
            setBusinesses(bizList);
            setProperties(propList);
            setLoadError(null);
        } catch (e) {
            if (cancelled()) return;
            setLoadError(e instanceof ApiError ? e.message : t("loadError"));
        } finally {
            if (!cancelled()) setLoading(false);
        }
    }, [t]);

    useEffect(() => {
        if (sessionStatus !== "authenticated" || !canView) return;
        // Guarded for the same reason as the admin page: every setter runs after an await.
        let cancelled = false;
        void loadLookups(() => cancelled);
        return () => { cancelled = true; };
    }, [sessionStatus, canView, loadLookups]);

    if (sessionStatus === "loading") return null;
    if (!canView) {
        return <p className="p-6 text-sm text-gray-500">{t("noAccess")}</p>;
    }

    return (
        <div className="space-y-6 p-6">
            <div>
                <h1 className="text-2xl font-semibold">{t("title")}</h1>
                {me && (
                    <p className="mt-1 text-sm text-gray-600">
                        {t("subtitle", { organisation: me.organisationName })}
                    </p>
                )}
            </div>

            {loadError && (
                <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">{loadError}</p>
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

            {loading ? (
                <div className="flex justify-center py-10"><Loader2 className="animate-spin" /></div>
            ) : tab === "ads" ? (
                <PromoterAdsTab businesses={businesses} properties={properties} />
            ) : (
                <PromoterBusinessesTab businesses={businesses} canCreate={me?.canCreateBusinesses ?? false}
                    onChanged={() => loadLookups()} />
            )}
        </div>
    );
}
