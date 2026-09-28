"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";

/**
 * The organisation's mark in the header switcher: its logo when one is set, in
 * the same fixed 32 px round box the initials use (so nothing moves or changes
 * shape while it loads or if it fails), otherwise — or if the image cannot load —
 * the initials.
 *
 * `logoSrc` is the app's own streaming route (orgLogoSrc in orgStore), never a
 * storage URL: tenant containers are private, and a stored URL on a third-party
 * host would see every signed-in user's browser.
 */
export function OrgAvatar({ name, initials, logoSrc, className }: {
    name: string;
    initials: string;
    logoSrc?: string | null;
    className?: string;
}) {
    const [failed, setFailed] = useState<string | null>(null);
    if (logoSrc && failed !== logoSrc) {
        return (
            <span
                data-testid="org-avatar-logo"
                className={cn("flex h-8 w-8 items-center justify-center overflow-hidden rounded-full bg-white", className)}
                style={{ boxShadow: "inset 0 0 0 1px var(--border)" }}
            >
                {/* eslint-disable-next-line @next/next/no-img-element -- streamed by our backend, private cache */}
                <img
                    src={logoSrc}
                    alt={name}
                    width={32}
                    height={32}
                    className="h-full w-full object-contain p-1"
                    onError={() => setFailed(logoSrc)}
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
