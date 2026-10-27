"use client";

import { useTranslations } from "next-intl";
import type { PromoAdDTO } from "@/types/promotion";
import { sharesRenterData } from "./RedirectLinkFields";

/**
 * A coupon ad's redirect link and the names (with sources) of its parameters,
 * for an ad list. This is what an admin reads before approving: it says exactly
 * what the app will append to the link and whether tenant details are in it.
 */
export function RedirectSummary({ ad }: { ad: Pick<PromoAdDTO, "redirectUrl" | "redirectParams"> }) {
    const t = useTranslations("Promotions");
    if (!ad.redirectUrl) return null;
    const params = ad.redirectParams ?? [];
    return (
        <span data-testid="redirect-summary" className="mt-1 block max-w-md text-xs text-gray-500">
            <span className="block break-all" dir="ltr">{t("redirectSummary", { url: ad.redirectUrl })}</span>
            {params.length > 0 && (
                <span className="block" dir="ltr">
                    {t("redirectSummaryParams", {
                        list: params.map(p => `${p.name} (${t(`redirectSource${p.source}`)})`).join(", "),
                    })}
                </span>
            )}
            {sharesRenterData(params) && (
                <span className="mt-0.5 inline-block rounded-full bg-amber-50 px-2 py-0.5 text-amber-800">
                    {t("redirectSharesBadge")}
                </span>
            )}
        </span>
    );
}
