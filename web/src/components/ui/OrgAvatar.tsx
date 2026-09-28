"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";
import { assetSrc } from "@/lib/assetUrl";

/**
 * Only our own stored assets, an https URL or an inline image may be shown; a
 * logo URL is free text on the organisation, so anything else (javascript:,
 * plain http, a relative path elsewhere) falls back to the initials.
 */
export function safeLogoSrc(url: string | null | undefined): string | null {
    const u = url?.trim();
    if (!u) return null;
    if (u.startsWith("/api/v1/assets/serve/") || /^https:\/\//i.test(u) || /^data:image\/(png|jpe?g|gif|webp);/i.test(u)) {
        return assetSrc(u);
    }
    return null;
}

/**
 * The organisation's mark in the header switcher: its logo when one is set, in
 * the same fixed 32 px box the initials use (so nothing moves while it loads or
 * if it fails), otherwise — or if the image cannot load — the initials.
 */
export function OrgAvatar({ name, initials, logoUrl, className }: {
    name: string;
    initials: string;
    logoUrl?: string | null;
    className?: string;
}) {
    const src = safeLogoSrc(logoUrl);
    const [failed, setFailed] = useState<string | null>(null);
    if (src && failed !== src) {
        return (
            <span
                data-testid="org-avatar-logo"
                className={cn("flex h-8 w-8 items-center justify-center overflow-hidden rounded-md border border-border bg-white", className)}
            >
                {/* eslint-disable-next-line @next/next/no-img-element -- an organisation's uploaded logo, any host we allow */}
                <img
                    src={src}
                    alt={name}
                    width={32}
                    height={32}
                    className="h-full w-full object-contain p-0.5"
                    onError={() => setFailed(src)}
                />
            </span>
        );
    }
    return (
        <div
            data-testid="org-avatar-initials"
            className={cn("flex h-8 w-8 items-center justify-center rounded-full text-[11px] font-semibold", className)}
            style={{ background: "var(--ink-900)", color: "var(--gold-500)" }}
        >
            {initials}
        </div>
    );
}
