"use client";

import type { CSSProperties } from "react";
import type { PromoCtaType } from "@/types/promotion";

/** Miftah mobile tokens — see mobile/packages/rentaxis_core/lib/ui/miftah_tokens.dart. */
const INK = "#12101A";
const SURFACE = "#FFFFFF";
const BRASS_TINT = "#FBF3E2";
const BRASS_TINT_BORDER = "#EBD7A8";
const WARNING = "#8A6412";
const TEXT_SECONDARY = "#4A4358";

const HEX_COLOUR = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;

/**
 * The stored contract is `#AARRGGBB` — Flutter's `Color` channel order, which
 * is what `parseHexColor` and the backend's `@Pattern` both mean. CSS reads an
 * 8-digit hex as `#RRGGBBAA`, so handing the stored value straight to the
 * browser renders a different colour entirely: `#80FBF3E2` is rgb(FB,F3,E2) at
 * 50% alpha on the phone, and rgb(80,FB,F3) at 89% in CSS. Six-digit values are
 * unambiguous and pass through untouched.
 *
 * This is the whole job of this component — showing the admin what the renter
 * will actually see — so getting it wrong here is worse than not previewing.
 */
export function toCssColour(hex: string): string {
    if (hex.length !== 9) return hex;
    return `#${hex.slice(3)}${hex.slice(1, 3)}`;
}

export interface AdCardPreviewProps {
    title: string;
    subtitle?: string | null;
    businessName?: string | null;
    backgroundImageUrl?: string | null;
    accentColor?: string | null;
    ctaType: PromoCtaType;
    ctaLabel?: string | null;
    rtl?: boolean;
}

/** Matches AdsCarousel's default labels so the preview never over-promises. */
export function defaultCtaLabel(type: PromoCtaType, rtl: boolean): string {
    switch (type) {
        case "WEBSITE": return rtl ? "زيارة الموقع" : "Visit site";
        case "COUPON": return rtl ? "استخدام الكوبون" : "Redeem coupon";
        case "CALL": return rtl ? "اتصال" : "Call";
        case "WHATSAPP": return rtl ? "واتساب" : "WhatsApp";
        default: return "";
    }
}

export function AdCardPreview({
    title, subtitle, businessName, backgroundImageUrl, accentColor,
    ctaType, ctaLabel, rtl = false,
}: AdCardPreviewProps) {
    const hasImage = Boolean(backgroundImageUrl);
    // Accepts what Flutter's parseHexColor accepts — only #RRGGBB / #AARRGGBB —
    // and falls back to the brass tint otherwise. This component renders
    // unsaved draft values straight from the editor, so an admin half-way
    // through typing "#FF" must see what the phone would show, not a colour CSS
    // happens to accept or a transparent card from invalid CSS.
    //
    // The fallback is a tracked flag rather than a `fill === BRASS_TINT`
    // comparison, so an admin who deliberately types the brass tint as their
    // accent colour is not told they left the field empty.
    const usesFallback = !HEX_COLOUR.test(accentColor ?? "");
    const fill = usesFallback ? BRASS_TINT : toCssColour(accentColor!);
    const fg = hasImage ? "#FFFFFF" : INK;
    const label = (ctaLabel?.trim() || defaultCtaLabel(ctaType, rtl));

    const clamp2: CSSProperties = {
        display: "-webkit-box",
        WebkitLineClamp: 2,
        WebkitBoxOrient: "vertical",
        overflow: "hidden",
    };

    return (
        <div
            dir={rtl ? "rtl" : "ltr"}
            data-testid="ad-card-preview"
            className="relative flex h-[140px] w-[300px] flex-col justify-between overflow-hidden p-[14px]"
            style={{
                borderRadius: 20,
                background: hasImage ? undefined : fill,
                // A real edge, not the old `1px solid ${SURFACE_ALT}` on a
                // SURFACE_ALT fill — that border was invisible by construction,
                // and it only looked fine here because the admin panel's page
                // is white. On the phone's #F6F5FA home canvas the same card
                // was a six-channel-total difference from its background.
                border: hasImage ? undefined : `1px solid ${BRASS_TINT_BORDER}`,
            }}
        >
            {hasImage && (
                <>
                    <img
                        src={backgroundImageUrl!}
                        alt=""
                        className="absolute inset-0 h-full w-full object-cover"
                    />
                    {/* The Flutter card darkens the image by 0.35 so white copy stays legible. */}
                    <div className="absolute inset-0" style={{ background: "rgba(0,0,0,0.35)" }} />
                </>
            )}

            <div className="relative">
                {(subtitle || businessName) && (
                    <p
                        style={{
                            ...clamp2,
                            color: hasImage ? "#E7C883" : WARNING,
                            fontSize: 11,
                            fontWeight: 800,
                            letterSpacing: "0.08em",
                            textTransform: "uppercase",
                            margin: 0,
                        }}
                    >
                        {subtitle || businessName}
                    </p>
                )}
                <p
                    style={{
                        ...clamp2,
                        color: fg,
                        fontSize: 18,
                        fontWeight: 800,
                        lineHeight: 1.1,
                        margin: "3px 0 0",
                    }}
                >
                    {title || (rtl ? "عنوان الإعلان" : "Ad title")}
                </p>
                {!hasImage && businessName && subtitle && (
                    <p style={{ color: TEXT_SECONDARY, fontSize: 12, margin: "3px 0 0" }}>
                        {businessName}
                    </p>
                )}
            </div>

            {ctaType !== "NONE" && (
                <div className="relative">
                    <span
                        data-testid="ad-card-cta"
                        style={{
                            display: "inline-block",
                            background: hasImage ? SURFACE : INK,
                            color: hasImage ? INK : SURFACE,
                            borderRadius: 9999,
                            padding: "7px 14px",
                            fontSize: 11,
                            fontWeight: 800,
                        }}
                    >
                        {rtl ? `← ${label}` : `${label} →`}
                    </span>
                </div>
            )}

            {!hasImage && usesFallback && (
                <span className="sr-only">No card colour set; using the default</span>
            )}
        </div>
    );
}
