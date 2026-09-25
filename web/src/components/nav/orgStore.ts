"use client";
import { useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import Cookies from "js-cookie";

export type Org = { id: string; name: string };

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

/** The saved context, then the user's own organisation, then the first membership. */
export function pickActiveOrg(orgs: Org[], homeTenantId: string | undefined): Org | null {
    const preferred = Cookies.get("active_tenant_id") || homeTenantId;
    return orgs.find(o => o.id === preferred) ?? orgs[0] ?? null;
}

/** The session's organisations (null until loaded) and the active one. */
export function useMyOrgs(): { orgs: Org[] | null; active: Org | null } {
    const { data: session } = useSession();
    const user = session?.user as { id?: string; email?: string | null; role?: string; tenantId?: string } | undefined;
    const key = user ? `${user.id ?? user.email ?? ""}|${user.role ?? ""}|${user.tenantId ?? ""}` : "";
    const [state, setState] = useState<{ key: string; orgs: Org[] } | null>(null);
    useEffect(() => {
        if (!key) return;
        let alive = true;
        void loadMyOrgs(key).then(orgs => { if (alive) setState({ key, orgs }); });
        return () => { alive = false; };
    }, [key]);
    const orgs = state && state.key === key ? state.orgs : null;
    return { orgs, active: orgs ? pickActiveOrg(orgs, user?.tenantId) : null };
}
