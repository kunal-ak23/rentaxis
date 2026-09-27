"use client";

import { useLayoutEffect, useState, type ReactNode } from "react";
import Cookies from "js-cookie";
import { signOut } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Building2, Loader2, RefreshCw } from "lucide-react";
import { installProxyFetchGuard, loginUrlFor } from "@/lib/session/proxyFetchGuard";
import { ACTIVE_ORG_COOKIE, getPageOrg, readActiveOrgCookie, setPageOrg, subscribeOrgChange } from "@/lib/session/orgSync";

/**
 * Session-wide guards for every authenticated page (break round 1):
 *
 * F3 — the active organisation is one cookie shared by all tabs. When another
 *      tab switches, this tab is blocked by a notice with a Reload button (it
 *      never reloads on its own: a half-filled form would be lost silently).
 *      The page underneath is made inert, and every mutation carries the org
 *      the page was loaded for, so the proxy refuses a stale write even if
 *      the notice were bypassed.
 * F6 — the proxy's own 401 (X-Session-Ended) means the session is gone: send the
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
    const [orgUnavailable, setOrgUnavailable] = useState(false);
    const [signingIn, setSigningIn] = useState(false);

    // A layout effect, not a passive one (review fix 4): it runs before any
    // child's useEffect, so the page's own mount-time calls are already
    // stamped with the org and covered by the 401 handling.
    useLayoutEffect(() => {
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
            // Review fix 2: the backend refused the org in the cookie (e.g.
            // "Organisation is not active"). Signing in again would keep the
            // cookie and loop, so drop the selection back to the home org and
            // ask for a reload. With no selection (or the home org itself)
            // there is nothing to repair; the page shows its own error.
            onBackendUnauthorized: (message: string) => {
                if (redirected) return;
                const selected = readActiveOrgCookie();
                const repairable = !!selected && role !== "SUPER_ADMIN" && selected !== homeTenantId;
                // Fix round 2: the backend's "Unknown or inactive user." 401
                // (ApiSecurityFilter, user deleted or deactivated) means this
                // person must be signed out, not shown an org notice. Spring
                // omits the message from the error body unless
                // server.error.include-message is on, so the match is
                // best-effort; the fallback below covers the rest.
                const inactiveUser = /unknown or inactive user/i.test(message);
                if (!inactiveUser && repairable) {
                    // Inactive (or no longer open) org in the cookie: drop the
                    // selection back to the home org and ask for a reload.
                    Cookies.remove(ACTIVE_ORG_COOKIE, { path: "/" });
                    setPageOrg("");
                    setOrgUnavailable(true);
                    return;
                }
                // Inactive user, or the refused org is the user's own (nothing
                // to repair): end the NextAuth session and go to sign in, where
                // a new attempt reports why (ACCOUNT_INACTIVE / ORG_INACTIVE).
                // No loop: the login page installs no guard.
                const url = loginUrlFor(window.location);
                if (!url) return;
                redirected = true;
                setSigningIn(true);
                void signOut({ callbackUrl: url });
            },
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

    const blocked = orgChanged || orgUnavailable || signingIn;
    const notice = signingIn ? null
        : orgUnavailable ? { title: t("orgUnavailableTitle"), body: t("orgUnavailableBody") }
        : orgChanged ? { title: t("orgChangedTitle"), body: t("orgChangedBody") }
        : null;

    return (
        <>
            <div inert={blocked} className="contents">{children}</div>
            {notice && (
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
                        <h2 id="org-changed-title" className="text-base font-bold text-foreground mb-2">{notice.title}</h2>
                        <p id="org-changed-body" className="text-sm text-muted mb-5">{notice.body}</p>
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
