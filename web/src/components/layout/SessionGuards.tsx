"use client";

import { useEffect, useState, type ReactNode } from "react";
import { useTranslations } from "next-intl";
import { Building2, Loader2, RefreshCw } from "lucide-react";
import { installProxyFetchGuard, loginUrlFor } from "@/lib/session/proxyFetchGuard";
import { getPageOrg, readActiveOrgCookie, subscribeOrgChange } from "@/lib/session/orgSync";

/**
 * Session-wide guards for every authenticated page (break round 1):
 *
 * F3 — the active organisation is one cookie shared by all tabs. When another
 *      tab switches, this tab is blocked by a notice with a Reload button (it
 *      never reloads on its own: a half-filled form would be lost silently).
 *      The page underneath is made inert, and every mutation carries the org
 *      the page was loaded for, so the proxy refuses a stale write even if
 *      the notice were bypassed.
 * F6 — a 401 from /api/proxy anywhere means the session is gone: send the
 *      user to sign in (callbackUrl = this page), once.
 */
export function SessionGuards({
    role,
    homeTenantId,
    children,
}: {
    role?: string;
    homeTenantId?: string;
    children: ReactNode;
}) {
    const t = useTranslations("PageState");
    const [orgChanged, setOrgChanged] = useState(false);
    const [signingIn, setSigningIn] = useState(false);

    useEffect(() => {
        // Snapshot the organisation this page was loaded for.
        getPageOrg();
        // No cookie means the home organisation for everyone but a super admin
        // (for whom it is Global View), exactly as the proxy resolves it.
        const effective = (orgId: string) => orgId || (role === "SUPER_ADMIN" ? "" : homeTenantId ?? "");
        const isStale = (orgId: string) => effective(orgId) !== effective(getPageOrg());

        let redirected = false;
        const uninstall = installProxyFetchGuard({
            getExpectedOrg: getPageOrg,
            onUnauthorized: () => {
                if (redirected) return;
                const url = loginUrlFor(window.location);
                if (!url) return;
                redirected = true;
                setSigningIn(true);
                window.location.assign(url);
            },
            onOrgMismatch: () => setOrgChanged(true),
        });
        const unsubscribe = subscribeOrgChange(orgId => {
            if (isStale(orgId)) setOrgChanged(true);
        });
        // Belt and braces for a switch that was not announced (e.g. an older build in another tab).
        const onFocus = () => {
            if (document.visibilityState === "hidden") return;
            if (isStale(readActiveOrgCookie())) setOrgChanged(true);
        };
        window.addEventListener("focus", onFocus);
        document.addEventListener("visibilitychange", onFocus);
        return () => {
            uninstall();
            unsubscribe();
            window.removeEventListener("focus", onFocus);
            document.removeEventListener("visibilitychange", onFocus);
        };
    }, [role, homeTenantId]);

    const blocked = orgChanged || signingIn;

    return (
        <>
            <div inert={blocked} className="contents">{children}</div>
            {orgChanged && !signingIn && (
                <div className="fixed inset-0 z-[10000] flex items-center justify-center bg-black/50 px-4">
                    <div
                        role="alertdialog"
                        aria-modal="true"
                        aria-labelledby="org-changed-title"
                        aria-describedby="org-changed-body"
                        data-testid="org-changed-notice"
                        className="w-full max-w-md bg-surface rounded-xl shadow-xl border border-border p-6 text-center"
                    >
                        <Building2 size={36} className="mx-auto text-muted mb-3" />
                        <h2 id="org-changed-title" className="text-base font-bold text-foreground mb-2">{t("orgChangedTitle")}</h2>
                        <p id="org-changed-body" className="text-sm text-muted mb-5">{t("orgChangedBody")}</p>
                        <button
                            type="button"
                            autoFocus
                            onClick={() => window.location.reload()}
                            className="cursor-pointer inline-flex items-center gap-1.5 text-sm font-semibold bg-primary text-primary-foreground px-4 py-2 rounded-lg"
                        >
                            <RefreshCw size={14} />
                            {t("reload")}
                        </button>
                    </div>
                </div>
            )}
            {signingIn && (
                <div className="fixed inset-0 z-[10000] flex items-center justify-center bg-background/80 px-4" role="status">
                    <div className="flex items-center gap-2 text-sm text-muted">
                        <Loader2 size={16} className="animate-spin" />
                        {t("sessionExpired")}
                    </div>
                </div>
            )}
        </>
    );
}

export default SessionGuards;
