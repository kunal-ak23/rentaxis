import { getToken } from "next-auth/jwt";
import createIntlMiddleware from "next-intl/middleware";
import { routing } from "./i18n/routing";
import { NextRequest, NextResponse } from "next/server";

const intlMiddleware = createIntlMiddleware(routing);

export default async function middleware(req: NextRequest) {
    const isApiProxy = req.nextUrl.pathname.startsWith('/api/proxy');

    if (isApiProxy) {
        // Authenticate proxy requests and attach tenant context headers
        const token = await getToken({ req });
        if (!token) {
            return new NextResponse('Unauthorized', { status: 401 });
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

        return NextResponse.next({
            request: {
                headers: requestHeaders,
            },
        });
    }

    // For all other routes, let next-intl handle internationalization
    return intlMiddleware(req);
}

export const config = {
    // Match internationalized pathnames AND api proxy routes
    matcher: ['/', '/(ar|en)/:path*', '/api/proxy/:path*']
};
