import { getToken } from "next-auth/jwt";
import createIntlMiddleware from "next-intl/middleware";
import { routing } from "./i18n/routing";
import { NextRequest, NextResponse } from "next/server";
import { legacyRedirect } from "./lib/nav/routeMap";
import { EXPECTED_TENANT_HEADER } from "./lib/session/orgHeaders";
import { orgMismatchResponse, resolveBackendIdentity, sessionEndedResponse } from "./lib/session/backendIdentity";

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

        // Authenticate proxy requests and attach identity and tenant headers.
        // The rule lives in lib/session/backendIdentity.ts, shared with the Next
        // API routes that call the backend themselves (/api/upload), so the two
        // cannot drift: no session or a revoked one is this proxy's own 401;
        // the active_tenant_id cookie is a selection honoured only for an
        // organisation the session proves membership of (SUPER_ADMIN: any); and
        // a mutation stamped for another organisation than the active one is a
        // 409 (break round 1, F3).
        const decision = resolveBackendIdentity({
            token: await getToken({ req }),
            cookieTenant: req.cookies.get('active_tenant_id')?.value,
            expectedTenant: req.headers.get(EXPECTED_TENANT_HEADER),
            method: req.method,
            internalProxySecret: process.env.INTERNAL_PROXY_SECRET,
        });
        if (decision.kind === 'session-ended') {
            return addSecurityHeaders(sessionEndedResponse(decision.body), req.nextUrl.pathname);
        }
        if (decision.kind === 'org-mismatch') {
            return addSecurityHeaders(orgMismatchResponse(), req.nextUrl.pathname);
        }

        const requestHeaders = new Headers(req.headers);
        // Every identity header is this proxy's to assert: an inbound value is
        // forged. X-Internal-Auth is a server-to-server secret. Authorization is
        // dropped because the web authenticates with the headers below, never a
        // backend bearer token: the marketplace helpers send "Bearer " or
        // "Bearer undefined", and once APP_AUTH_TOKEN_SECRET is set the backend
        // takes any presented Bearer over the headers and 401s an unverifiable
        // one. The expectation header is consumed here.
        for (const name of ['X-Internal-Auth', 'Authorization', 'X-User-Id', 'X-User-Role',
            'X-Tenant-Id', 'X-User-Tenant-Id', EXPECTED_TENANT_HEADER]) {
            requestHeaders.delete(name);
        }
        for (const [name, value] of Object.entries(decision.headers)) {
            requestHeaders.set(name, value);
        }

        return addSecurityHeaders(NextResponse.next({
            request: {
                headers: requestHeaders,
            },
        }), req.nextUrl.pathname);
    }

    // Admin UI simplification: a moved page answers its old URL with a
    // permanent redirect that keeps the locale and every query parameter
    // (routeMap.ts). Runs before next-intl so the locale is never rewritten.
    const moved = legacyRedirect(new URL(req.nextUrl.toString()));
    if (moved) {
        return addSecurityHeaders(NextResponse.redirect(moved, 308), req.nextUrl.pathname);
    }

    // For all other routes, let next-intl handle internationalization
    const response = intlMiddleware(req);
    return addSecurityHeaders(response as NextResponse, req.nextUrl.pathname);
}

export const config = {
    // Match internationalized pathnames AND api proxy routes
    matcher: ['/', '/(ar|en)/:path*', '/dashboard/:path*', '/api/proxy/:path*']
};
