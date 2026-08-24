import { getToken } from "next-auth/jwt";
import createIntlMiddleware from "next-intl/middleware";
import { routing } from "./i18n/routing";
import { NextRequest, NextResponse } from "next/server";

const intlMiddleware = createIntlMiddleware(routing);

// Pre-auth allowlist: the ONLY /api/proxy paths forwarded without a NextAuth session.
// Every entry must correspond to a backend route Spring SecurityConfig marks permitAll;
// keep entries exact-match — never prefixes — so nothing else is opened up.
const PUBLIC_PROXY_PATHS = new Set<string>([
    // -> POST /api/auth/set-password (permitAll via /api/auth/**): invite activation —
    //    the invitee is setting their first password, so they cannot have a session yet.
    '/api/proxy/auth/set-password',
    // -> POST /api/v1/public/renewal-intent (permitAll via /api/v1/public/**): signed-token
    //    renewal confirmation from the reminder email; must work for logged-out renters.
    '/api/proxy/v1/public/renewal-intent',
]);

function addSecurityHeaders(response: NextResponse): NextResponse {
    response.headers.set('X-Frame-Options', 'DENY');
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
            }));
        }

        // Authenticate proxy requests and attach tenant context headers
        const token = await getToken({ req });
        if (!token) {
            return addSecurityHeaders(new NextResponse('Unauthorized', { status: 401 }));
        }

        const requestHeaders = new Headers(req.headers);

        // X-Internal-Auth is a server-to-server secret: only this middleware may
        // assert it, so any inbound value is forged — drop it before deciding
        // whether to attach the real one.
        requestHeaders.delete('X-Internal-Auth');
        const internalProxySecret = process.env.INTERNAL_PROXY_SECRET;
        if (internalProxySecret) {
            // Proves to the backend that SUPER_ADMIN (and other identity headers)
            // were asserted by this trusted proxy, not replayed by a client.
            // Unset/empty env keeps the gate off (pre-rollout compatibility).
            requestHeaders.set('X-Internal-Auth', internalProxySecret);
        }

        if (token.id) requestHeaders.set('X-User-Id', token.id as string);
        if (token.role) requestHeaders.set('X-User-Role', token.role as string);
        if (token.tenantId) requestHeaders.set('X-User-Tenant-Id', token.tenantId as string);

        const activeTenantId = req.cookies.get('active_tenant_id')?.value;
        if (activeTenantId) {
            requestHeaders.set('X-Tenant-Id', activeTenantId);
        } else if (token.tenantId) {
            requestHeaders.set('X-Tenant-Id', token.tenantId as string);
        }

        return addSecurityHeaders(NextResponse.next({
            request: {
                headers: requestHeaders,
            },
        }));
    }

    // For all other routes, let next-intl handle internationalization
    const response = intlMiddleware(req);
    return addSecurityHeaders(response as NextResponse);
}

export const config = {
    // Match internationalized pathnames AND api proxy routes
    matcher: ['/', '/(ar|en)/:path*', '/api/proxy/:path*']
};
