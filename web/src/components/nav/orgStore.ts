"use client";
import { useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import Cookies from "js-cookie";

/**
 * An organisation the user may act in. logoVersion is set when it has a logo;
 * the header loads the image from the app (orgLogoSrc), never from storage.
 */
export type Org = { id: string; name: string; logoVersion?: string | null };

/**
 * The current organisation's logo, streamed by the backend from our own storage
 * (tenant containers are private). The version busts the browser cache when the
 * logo changes; the organisation is the request's own (the active one).
 */
export function orgLogoSrc(org: Pick<Org, "id" | "logoVersion"> | null | undefined): string | null {
    // The organisation is part of the address: the backend refuses (404) when it is
    // not the session's current one, so a tab left on org 1 after another tab
    // switched to org 2 can never cache org 2's image under org 1's key.
    return org?.logoVersion
        ? `/api/proxy/v1/org/branding/logo?org=${encodeURIComponent(org.id)}&v=${encodeURIComponent(org.logoVersion)}`
        : null;
}

/**
 * GET /auth/me/tenants, once per signed-in session (PR #363 R1): the header's
 * switcher and the panel's read-only org name share this one request instead
 * of each panel mount asking again (for a super admin that is the full
 * organisation list). A failed request is not cached, so the next mount retries.
 */
let cache: { key: string; promise: Promise<Org[]> } | null = null;

export function loadMyOrgs(key: string): Promise<Org[]> {
    if (cache?.key === key) return cache.promise;
    const promise = fetch("/api/proxy/auth/me/tenants")
        .then(res => (res.ok ? res.json() as Promise<Org[]> : Promise.reject(new Error(String(res.status)))))
        .catch((e: unknown) => {
            if (cache?.promise === promise) cache = null;
            console.error(e);
            return [] as Org[];
        });
    cache = { key, promise };
    return promise;
}

/** Test hook: forget the cached list. */
export function resetMyOrgsCache() {
    cache = null;
}

const listeners = new Set<() => void>();

/**
 * The list changed (an organisation was created, renamed, activated,
 * deactivated or deleted on this page): drop the cache and have every mounted
 * switcher fetch it again, so a new organisation is pickable without a reload.
 */
export function refreshMyOrgs() {
    cache = null;
    listeners.forEach(listener => listener());
}

/**
 * The saved context, then the user's own organisation, then the first membership.
 *
 * A super admin with no saved context is in Global View (null). Break round 1,
 * F7: falling back to the first organisation here made the switcher re-set the
 * cookie straight after "Global View" was picked, so it could never stick.
 */
export function pickActiveOrg(orgs: Org[], homeTenantId: string | undefined, role?: string): Org | null {
    const saved = Cookies.get("active_tenant_id");
    if (role === "SUPER_ADMIN") return saved ? orgs.find(o => o.id === saved) ?? null : null;
    const preferred = saved || homeTenantId;
    return orgs.find(o => o.id === preferred) ?? orgs[0] ?? null;
}

/** The session's organisations (null until loaded) and the active one. */
export function useMyOrgs(): { orgs: Org[] | null; active: Org | null } {
    const { data: session } = useSession();
    const user = session?.user as { id?: string; email?: string | null; role?: string; tenantId?: string } | undefined;
    const key = user ? `${user.id ?? user.email ?? ""}|${user.role ?? ""}|${user.tenantId ?? ""}` : "";
    const [state, setState] = useState<{ key: string; orgs: Org[] } | null>(null);
    const [generation, setGeneration] = useState(0);
    useEffect(() => {
        const bump = () => setGeneration(g => g + 1);
        listeners.add(bump);
        return () => { listeners.delete(bump); };
    }, []);
    useEffect(() => {
        if (!key) return;
        let alive = true;
        void loadMyOrgs(key).then(orgs => { if (alive) setState({ key, orgs }); });
        return () => { alive = false; };
    }, [key, generation]);
    const orgs = state && state.key === key ? state.orgs : null;
    return { orgs, active: orgs ? pickActiveOrg(orgs, user?.tenantId, user?.role) : null };
}
