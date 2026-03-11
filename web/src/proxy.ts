import { getToken } from "next-auth/jwt";
import createIntlMiddleware from "next-intl/middleware";
import { routing } from "./i18n/routing";
import { NextRequest, NextResponse } from "next/server";

const intlMiddleware = createIntlMiddleware(routing);

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
        // Authenticate proxy requests and attach tenant context headers
        const token = await getToken({ req });
        if (!token) {
            return addSecurityHeaders(new NextResponse('Unauthorized', { status: 401 }));
        }

        const requestHeaders = new Headers(req.headers);

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
