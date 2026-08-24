import { getToken } from "next-auth/jwt";
import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";

export const runtime = "nodejs";
export const maxDuration = 300; // seconds — allow up to 5 min for large file uploads (up to 250MB)

const backendUrl = process.env.BACKEND_URL || "http://localhost:8080";

export async function POST(req: NextRequest) {
    const token = await getToken({ req });
    if (!token) {
        return NextResponse.json({ error: "Unauthorized" }, { status: 401 });
    }

    const targetPath = req.nextUrl.searchParams.get("path");
    if (!targetPath) {
        return NextResponse.json({ error: "Missing path parameter" }, { status: 400 });
    }

    // Build auth headers (fresh object — client headers are never forwarded)
    const headers: Record<string, string> = {};
    if (token.id) headers["X-User-Id"] = token.id as string;
    if (token.role) headers["X-User-Role"] = token.role as string;
    if (token.tenantId) headers["X-User-Tenant-Id"] = token.tenantId as string;

    // This route asserts X-User-Role from the session (which can be SUPER_ADMIN)
    // and calls the backend directly over the Docker network, bypassing the
    // /api/proxy middleware. Attach the internal proxy secret so the backend's
    // SUPER_ADMIN header gate accepts it. Unset/empty env keeps the gate off.
    const internalProxySecret = process.env.INTERNAL_PROXY_SECRET;
    if (internalProxySecret) {
        headers["X-Internal-Auth"] = internalProxySecret;
    }

    const cookieStore = await cookies();
    const activeTenantId = cookieStore.get("active_tenant_id")?.value;
    if (activeTenantId) {
        headers["X-Tenant-Id"] = activeTenantId;
    } else if (token.tenantId) {
        headers["X-Tenant-Id"] = token.tenantId as string;
    }

    // Preserve the original content-type (includes multipart boundary)
    const contentType = req.headers.get("content-type");
    if (contentType) {
        headers["Content-Type"] = contentType;
    }

    // Read the entire body as ArrayBuffer and forward to backend
    const body = await req.arrayBuffer();

    const backendRes = await fetch(`${backendUrl}${targetPath}`, {
        method: "POST",
        headers,
        body,
    });

    const data = await backendRes.json().catch(() => ({ error: "Backend error" }));
    return NextResponse.json(data, { status: backendRes.status });
}
