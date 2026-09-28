import { getToken } from "next-auth/jwt";
import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { AUTH_REASON_HEADER, EXPECTED_TENANT_HEADER } from "@/lib/session/orgHeaders";
import {
    backendTarget,
    orgMismatchResponse,
    resolveBackendIdentity,
    sessionEndedResponse,
} from "@/lib/session/backendIdentity";

export const runtime = "nodejs";
export const maxDuration = 300; // seconds — allow up to 5 min for large file uploads (up to 250MB)

const backendUrl = process.env.BACKEND_URL || "http://localhost:8080";

export async function POST(req: NextRequest) {
    const target = backendTarget(req.nextUrl.searchParams.get("path"), backendUrl);

    // The same session, membership and expected-organisation rules as the
    // /api/proxy middleware (lib/session/backendIdentity.ts, break round 1
    // batch 5): this route calls the backend directly, bypassing the proxy, and
    // used to skip the revoked-session check, forward the client-writable org
    // cookie unchecked, and ignore X-Expected-Tenant-Id.
    const cookieStore = await cookies();
    const decision = resolveBackendIdentity({
        token: await getToken({ req }),
        cookieTenant: cookieStore.get("active_tenant_id")?.value,
        expectedTenant: req.headers.get(EXPECTED_TENANT_HEADER),
        method: req.method,
        internalProxySecret: process.env.INTERNAL_PROXY_SECRET,
    });
    if (decision.kind === "session-ended") return sessionEndedResponse(decision.body);
    if (decision.kind === "org-mismatch") return orgMismatchResponse();

    if (!target) {
        return NextResponse.json({ error: "Missing or invalid path parameter" }, { status: 400 });
    }

    // A fresh object: client headers are never forwarded.
    const headers: Record<string, string> = { ...decision.headers };

    // Preserve the original content-type (includes multipart boundary)
    const contentType = req.headers.get("content-type");
    if (contentType) {
        headers["Content-Type"] = contentType;
    }

    // Read the entire body as ArrayBuffer and forward to backend
    const body = await req.arrayBuffer();

    const backendRes = await fetch(target.toString(), {
        method: "POST",
        headers,
        body,
    });

    const data = await backendRes.json().catch(() => ({ error: "Backend error" }));
    const res = NextResponse.json(data, { status: backendRes.status });
    // The backend's refusal reason is the one header passed on: the browser
    // guard uses it to choose between signing out and repairing the org.
    const reason = backendRes.headers.get(AUTH_REASON_HEADER);
    if (reason) res.headers.set(AUTH_REASON_HEADER, reason);
    return res;
}
