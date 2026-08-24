import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

// Control the NextAuth session: this route asserts X-User-* headers from it.
const getTokenMock = vi.fn();
vi.mock("next-auth/jwt", () => ({
  getToken: (...args: unknown[]) => getTokenMock(...args),
}));

// The route reads the active-tenant cookie via next/headers.
const cookieGetMock = vi.fn();
vi.mock("next/headers", () => ({
  cookies: async () => ({ get: cookieGetMock }),
}));

import { POST } from "../route";

const fetchMock = vi.fn();

function makeRequest() {
  return new NextRequest("http://localhost:3000/api/upload?path=/api/v1/tickets/attachments", {
    method: "POST",
  });
}

beforeEach(() => {
  getTokenMock.mockReset();
  cookieGetMock.mockReset();
  cookieGetMock.mockReturnValue(undefined);
  vi.stubGlobal("fetch", fetchMock);
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(
    new Response(JSON.stringify({ ok: true }), { status: 200 })
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("POST /api/upload — X-Internal-Auth forwarding", () => {
  it("sends X-Internal-Auth to the backend when INTERNAL_PROXY_SECRET is configured", async () => {
    vi.stubEnv("INTERNAL_PROXY_SECRET", "real-secret");
    getTokenMock.mockResolvedValue({ id: "user-1", role: "SUPER_ADMIN", tenantId: "tenant-1" });

    const res = await POST(makeRequest());

    expect(res.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(headers["X-Internal-Auth"]).toBe("real-secret");
    // Identity headers still asserted from the verified session.
    expect(headers["X-User-Id"]).toBe("user-1");
    expect(headers["X-User-Role"]).toBe("SUPER_ADMIN");
  });

  it("sends no X-Internal-Auth when the secret is not configured", async () => {
    vi.stubEnv("INTERNAL_PROXY_SECRET", "");
    getTokenMock.mockResolvedValue({ id: "user-1", role: "TENANT_ADMIN", tenantId: "tenant-1" });

    const res = await POST(makeRequest());

    expect(res.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(headers).not.toHaveProperty("X-Internal-Auth");
  });
});
