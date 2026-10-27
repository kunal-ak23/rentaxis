"use client";

import { useTranslations } from "next-intl";
import type { PromoAdDTO } from "@/types/promotion";
import { REDIRECT_LINK_KEYS, REDIRECT_LINK_LABEL, sharesRenterData } from "./RedirectLinkFields";

/**
 * A coupon ad's redirect links (the default and, when set, the Android and iPhone
 * ones, each labelled) and the names (with sources) of its parameters, for an ad
 * list. This is what an admin reads before approving: it says exactly what the app
 * will append to each link and whether tenant details are in it.
 *
 * The sentences are translated and run in the page's direction; only the URLs and
 * the parameter names, which are always left-to-right, are isolated (`<bdi dir="ltr">`),
 * so an Arabic sentence is not flipped around a Latin address.
 */
export function RedirectSummary({ ad }: {
    ad: Pick<PromoAdDTO, "redirectUrl" | "redirectUrlAndroid" | "redirectUrlIos" | "redirectParams">;
}) {
    const t = useTranslations("Promotions");
    const links = REDIRECT_LINK_KEYS.flatMap(key => {
        const url = ad[key]?.trim();
        return url ? [{ key, url }] : [];
    });
    if (links.length === 0) return null;
    const params = ad.redirectParams ?? [];
    return (
        <span data-testid="redirect-summary" className="mt-1 block max-w-md text-xs text-gray-500">
            {links.map(({ key, url }) => (
                <span key={key} data-testid={`redirect-summary-${key}`} className="block break-all">
                    {t.rich("redirectSummary", {
                        label: t(REDIRECT_LINK_LABEL[key]),
                        url: () => <bdi dir="ltr">{url}</bdi>,
                    })}
                </span>
            ))}
            {params.length > 0 && (
                <span className="block">
                    {t.rich("redirectSummaryParams", {
                        list: () => params.map((p, i) => (
                            <span key={`${p.name}-${i}`}>
                                {i > 0 && ", "}
                                <bdi dir="ltr">{p.name}</bdi> ({t(`redirectSource${p.source}`)})
                            </span>
                        )),
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
