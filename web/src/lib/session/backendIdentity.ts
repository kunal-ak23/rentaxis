import type { JWT } from "next-auth/jwt";
import { NextResponse } from "next/server";
import {
    MUTATING_METHODS,
    NO_ORG,
    ORG_MISMATCH_CODE,
    ORG_MISMATCH_HEADER,
    SESSION_ENDED_HEADER,
} from "./orgHeaders";

/**
 * The one rule for turning a NextAuth session into the identity headers the
 * backend's legacy path reads. Used by the /api/proxy middleware (proxy.ts) and
 * by every Next API route that calls the backend itself (/api/upload), so the
 * two cannot drift (break round 1, batch 5): the upload route used to skip the
 * revoked-session check, forward the client-writable org cookie unchecked, and
 * ignore the expected-organisation guard.
 *
 * Server-only (imports next/server); orgHeaders.ts stays browser-safe.
 */
export type IdentityDecision =
    | { kind: "session-ended"; body: string }
    | { kind: "org-mismatch" }
    | { kind: "ok"; headers: Record<string, string> };

export function resolveBackendIdentity(args: {
    token: JWT | null;
    /** The client-writable active_tenant_id cookie: a selection, never authorization. */
    cookieTenant: string | undefined;
    /** X-Expected-Tenant-Id as the client sent it, or null. */
    expectedTenant: string | null;
    method: string;
    internalProxySecret: string | undefined;
}): IdentityDecision {
    const { token } = args;
    if (!token) return { kind: "session-ended", body: "Unauthorized" };
    // The jwt callback marks a token revoked once the backend reports the
    // account gone (or deactivated). Refuse it rather than forwarding the stale
    // identity.
    if (token.revoked === true) return { kind: "session-ended", body: "Session revoked" };

    const headers: Record<string, string> = {};
    if (args.internalProxySecret) {
        // Proves to the backend that SUPER_ADMIN (and other identity headers)
        // were asserted by this trusted server, not replayed by a client.
        // Unset/empty keeps the gate off (pre-rollout compatibility).
        headers["X-Internal-Auth"] = args.internalProxySecret;
    }
    if (token.id) headers["X-User-Id"] = token.id as string;
    if (token.role) headers["X-User-Role"] = token.role as string;

    // Resolve the active tenant. The cookie is client-writable, so it is a
    // *selection*: honoured only when the session token already proves
    // membership of that tenant (SUPER_ADMIN reaches any). An unrecognised or
    // stale cookie falls back to the home tenant. The backend checks the same
    // thing against the database (it no longer trusts these headers for role or
    // membership); deciding here too keeps a stale cookie from turning every
    // request into a 403.
    const homeTenantId = token.tenantId as string | undefined;
    const memberships = (token.tenantIds as string[] | undefined) ?? [];
    const isSuperAdmin = token.role === "SUPER_ADMIN";
    const resolveTenant = (requested: string | undefined) =>
        requested && (isSuperAdmin || requested === homeTenantId || memberships.includes(requested))
            ? requested
            : homeTenantId;
    const activeTenantId = resolveTenant(args.cookieTenant);

    // Break round 1, F3: the cookie is shared by every tab, so a form loaded
    // for org A in one tab would be submitted into org B once another tab
    // switched. The client stamps each mutation with the organisation its page
    // was loaded for; resolved by the same rule, a mismatch is refused.
    // Reads are unaffected, and a request without the header is forwarded.
    if (args.expectedTenant !== null && MUTATING_METHODS.has(args.method.toUpperCase())) {
        const expectedTenantId = resolveTenant(args.expectedTenant === NO_ORG ? undefined : args.expectedTenant);
        if ((expectedTenantId ?? "") !== (activeTenantId ?? "")) return { kind: "org-mismatch" };
    }

    if (activeTenantId) headers["X-Tenant-Id"] = activeTenantId;
    // The backend selects the tenant from X-Tenant-Id and, without it, from
    // X-User-Tenant-Id, then checks it against the user's stored home tenant
    // and memberships. A non-SUPER_ADMIN's selection travels in both so they
    // agree; SUPER_ADMIN keeps its real home tenant (other code reads it).
    const assertedUserTenantId = isSuperAdmin ? homeTenantId : activeTenantId;
    if (assertedUserTenantId) headers["X-User-Tenant-Id"] = assertedUserTenantId;

    return { kind: "ok", headers };
}

/** This server's own "sign in again" answer, marked so the client can tell it from a backend 401. */
export function sessionEndedResponse(body: string): NextResponse {
    const res = new NextResponse(body, { status: 401 });
    res.headers.set(SESSION_ENDED_HEADER, "1");
    return res;
}

/** The expected-organisation refusal (F3), marked so the client can tell it from a backend 409. */
export function orgMismatchResponse(): NextResponse {
    const response = NextResponse.json(
        {
            error: true,
            code: ORG_MISMATCH_CODE,
            status: 409,
            message: "The active organisation changed in another tab. Reload this page before saving, so the change is not made in the wrong organisation.",
        },
        { status: 409 },
    );
    response.headers.set(ORG_MISMATCH_HEADER, "1");
    return response;
}

/**
 * The backend URL a Next API route may forward `path` to, or null. Only an
 * absolute path under /api/ that resolves to the backend itself: identity
 * headers and the internal proxy secret travel with that request, so a crafted
 * path must never point it at another host. (The upload route used to build
 * `${BACKEND_URL}${path}`, where "@evil.example/x" made evil.example the host.)
 */
export function backendTarget(path: string | null, backendUrl: string): URL | null {
    if (!path || !path.startsWith("/api/") || path.includes("\\") || path.includes("..")) return null;
    let url: URL;
    try {
        url = new URL(path, backendUrl);
    } catch {
        return null;
    }
    return url.origin === new URL(backendUrl).origin && url.pathname.startsWith("/api/") ? url : null;
}
