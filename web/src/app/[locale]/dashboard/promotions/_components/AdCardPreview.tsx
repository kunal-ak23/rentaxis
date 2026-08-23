"use client";

import type { CSSProperties } from "react";
import type { PromoCtaType } from "@/types/promotion";

/** Miftah mobile tokens — see mobile/packages/rentaxis_core/lib/ui/miftah_tokens.dart. */
const INK = "#12101A";
const SURFACE = "#FFFFFF";
const SURFACE_ALT = "#F4F2F9";
const WARNING = "#8A6412";
const TEXT_SECONDARY = "#4A4358";

const HEX_COLOUR = /^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;

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
    // Matches Flutter's parseHexColor: only #RRGGBB / #AARRGGBB is a colour,
    // anything else falls back to the neutral surface tint. This component
    // renders unsaved draft values straight from the editor, so an admin
    // half-way through typing "#FF" must see what the phone would show, not a
    // colour CSS happens to accept or a transparent card from invalid CSS.
    const fill = HEX_COLOUR.test(accentColor ?? "") ? accentColor! : SURFACE_ALT;
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
                border: hasImage ? undefined : `1px solid ${SURFACE_ALT}`,
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

            {!hasImage && fill === SURFACE_ALT && (
                <span className="sr-only">No card colour set; using the default</span>
            )}
        </div>
    );
}
