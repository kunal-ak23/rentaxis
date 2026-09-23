import { getToken } from "next-auth/jwt";
import createIntlMiddleware from "next-intl/middleware";
import { routing } from "./i18n/routing";
import { NextRequest, NextResponse } from "next/server";

const intlMiddleware = createIntlMiddleware(routing);

// Pre-auth allowlist: the ONLY /api/proxy paths forwarded without a NextAuth session.
// Every entry must correspond to a backend route Spring SecurityConfig marks permitAll;
// keep entries exact-match — never prefixes — so nothing else is opened up.
const PUBLIC_PROXY_PATHS = new Set<string>([
    // -> POST /api/auth/register (permitAll via /api/auth/**): creates the
    //    caller's first organisation and tenant-admin account.
    '/api/proxy/auth/register',
    // -> POST /api/auth/set-password (permitAll via /api/auth/**): invite activation —
    //    the invitee is setting their first password, so they cannot have a session yet.
    '/api/proxy/auth/set-password',
    // -> POST /api/v1/public/renewal-intent (permitAll via /api/v1/public/**): signed-token
    //    renewal confirmation from the reminder email; must work for logged-out renters.
    '/api/proxy/v1/public/renewal-intent',
]);

/**
 * The one proxied path that may be framed by this app.
 *
 * `DENY` forbids framing even by the same origin, and issue #300 moved private
 * attachments onto the proxy — including the settlement page's deduction PDF,
 * which is rendered in an `<iframe>`. `SAMEORIGIN` keeps every cross-origin
 * framing attempt refused while letting our own page display the document it
 * just asked for. Scoped to this prefix: nothing else served through the proxy
 * has any business inside a frame.
 */
const FRAMEABLE_PROXY_PREFIX = '/api/proxy/v1/assets/serve/';

export function frameOptionsFor(pathname: string): 'DENY' | 'SAMEORIGIN' {
    return pathname.startsWith(FRAMEABLE_PROXY_PREFIX) ? 'SAMEORIGIN' : 'DENY';
}

function addSecurityHeaders(response: NextResponse, pathname: string): NextResponse {
    response.headers.set('X-Frame-Options', frameOptionsFor(pathname));
    response.headers.set('X-Content-Type-Options', 'nosniff');
    response.headers.set('Referrer-Policy', 'strict-origin-when-cross-origin');
    response.headers.set('X-XSS-Protection', '1; mode=block');
    return response;
}

export default async function middleware(req: NextRequest) {
    const isApiProxy = req.nextUrl.pathname.startsWith('/api/proxy');

    if (isApiProxy) {
        if (PUBLIC_PROXY_PATHS.has(req.nextUrl.pathname)) {
            // Public pre-auth endpoint: forward without a session, but strip any
            // client-supplied identity headers so an unauthenticated caller cannot
            // smuggle auth/tenant context to the backend.
            const requestHeaders = new Headers(req.headers);
            requestHeaders.delete('X-User-Id');
            requestHeaders.delete('X-User-Role');
            requestHeaders.delete('X-User-Tenant-Id');
            requestHeaders.delete('X-Tenant-Id');
            requestHeaders.delete('X-Internal-Auth');

            return addSecurityHeaders(NextResponse.next({
                request: {
                    headers: requestHeaders,
                },
            }), req.nextUrl.pathname);
        }

        // Authenticate proxy requests and attach tenant context headers
        const token = await getToken({ req });
        if (!token) {
            return addSecurityHeaders(new NextResponse('Unauthorized', { status: 401 }),
                req.nextUrl.pathname);
        }

        // The jwt callback marks a token revoked once the backend reports the
        // account no longer exists. Refuse it here rather than forwarding the
        // stale role to the backend, which trusts these headers as presented.
        if (token.revoked === true) {
            return addSecurityHeaders(new NextResponse('Session revoked', { status: 401 }),
                req.nextUrl.pathname);
        }

        const requestHeaders = new Headers(req.headers);

        // X-Internal-Auth is a server-to-server secret: only this middleware may
        // assert it, so any inbound value is forged — drop it before deciding
        // whether to attach the real one.
        requestHeaders.delete('X-Internal-Auth');
        // The web authenticates to the backend with the headers below, never a
        // backend bearer token: the NextAuth session carries none, so the
        // marketplace helpers send "Bearer " or "Bearer undefined". Once
        // APP_AUTH_TOKEN_SECRET is set the backend takes any presented Bearer
        // over the headers and 401s an unverifiable one, which would break
        // every such call. Drop it so the session is what authenticates.
        requestHeaders.delete('Authorization');
        const internalProxySecret = process.env.INTERNAL_PROXY_SECRET;
        if (internalProxySecret) {
            // Proves to the backend that SUPER_ADMIN (and other identity headers)
            // were asserted by this trusted proxy, not replayed by a client.
            // Unset/empty env keeps the gate off (pre-rollout compatibility).
            requestHeaders.set('X-Internal-Auth', internalProxySecret);
        }

        if (token.id) requestHeaders.set('X-User-Id', token.id as string);
        if (token.role) requestHeaders.set('X-User-Role', token.role as string);

        // Resolve the active tenant.
        //
        // The switcher is enabled for TENANT_ADMIN as well as SUPER_ADMIN
        // (see rbac.ts canSwitchTenants), and getMyTenants legitimately returns
        // several tenants for a multi-membership user. But the backend's
        // legacy-header path authorizes a non-SUPER_ADMIN only when the
        // requested tenant equals the home tenant, and this proxy always sent
        // the home tenant as X-User-Tenant-Id — so picking a secondary
        // organisation 403'd every subsequent request until the user cleared
        // the cookie. The switch was non-functional for the exact role it was
        // built for.
        //
        // The cookie is client-writable, so it is treated as a *selection*, not
        // as authorization: it is honoured only when the verified session token
        // already proves membership of that tenant. The decision is made here,
        // server-side, against JWT claims a client cannot forge — deliberately
        // NOT by forwarding the membership list as another trusted header,
        // which would widen the spoofable X-User-* surface (#133).
        const homeTenantId = token.tenantId as string | undefined;
        const memberships = (token.tenantIds as string[] | undefined) ?? [];
        const requestedTenantId = req.cookies.get('active_tenant_id')?.value;
        const isSuperAdmin = token.role === 'SUPER_ADMIN';

        // SUPER_ADMIN reaches any tenant by design; everyone else may only
        // select a tenant they are actually a member of. An unrecognised or
        // stale cookie falls back to the home tenant rather than being
        // forwarded to certainly-403.
        const activeTenantId =
            requestedTenantId &&
            (isSuperAdmin || requestedTenantId === homeTenantId || memberships.includes(requestedTenantId))
                ? requestedTenantId
                : homeTenantId;

        if (activeTenantId) {
            requestHeaders.set('X-Tenant-Id', activeTenantId);
        }
        // For a non-SUPER_ADMIN the backend compares the requested tenant
        // against X-User-Tenant-Id, so an authorized non-home selection has to
        // travel as the user's tenant for this request. SUPER_ADMIN keeps its
        // real home tenant, since the backend authorizes that role outright and
        // other code reads it.
        const assertedUserTenantId = isSuperAdmin ? homeTenantId : activeTenantId;
        if (assertedUserTenantId) {
            requestHeaders.set('X-User-Tenant-Id', assertedUserTenantId);
        }

        return addSecurityHeaders(NextResponse.next({
            request: {
                headers: requestHeaders,
            },
        }), req.nextUrl.pathname);
    }

    // For all other routes, let next-intl handle internationalization
    const response = intlMiddleware(req);
    return addSecurityHeaders(response as NextResponse, req.nextUrl.pathname);
}

export const config = {
    // Match internationalized pathnames AND api proxy routes
    matcher: ['/', '/(ar|en)/:path*', '/api/proxy/:path*']
};
