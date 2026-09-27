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

function upload(opts: { path?: string; expected?: string } = {}) {
  const headers: Record<string, string> = {};
  if (opts.expected !== undefined) headers["X-Expected-Tenant-Id"] = opts.expected;
  const path = opts.path ?? "/api/v1/leases/l1/attachments";
  return new NextRequest(`http://localhost:3000/api/upload?path=${encodeURIComponent(path)}`, { method: "POST", headers });
}

describe("POST /api/upload — same session and organisation rules as the proxy (break round 1, batch 5)", () => {
  it("refuses a revoked session with the proxy's own session-ended 401 and never calls the backend", async () => {
    getTokenMock.mockResolvedValue({ id: "u", role: "TENANT_ADMIN", tenantId: "home", revoked: true });
    const res = await POST(upload());
    expect(res.status).toBe(401);
    expect(res.headers.get("X-Session-Ended")).toBe("1");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("answers a missing session with the session-ended 401", async () => {
    getTokenMock.mockResolvedValue(null);
    const res = await POST(upload());
    expect(res.status).toBe(401);
    expect(res.headers.get("X-Session-Ended")).toBe("1");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("ignores an org cookie the session is not a member of and falls back to the home tenant", async () => {
    getTokenMock.mockResolvedValue({ id: "u", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home"] });
    cookieGetMock.mockReturnValue({ value: "someone-elses-org" });
    await POST(upload());
    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(headers["X-Tenant-Id"]).toBe("home");
    expect(headers["X-User-Tenant-Id"]).toBe("home");
  });

  it("honours a membership org from the cookie, in both tenant headers", async () => {
    getTokenMock.mockResolvedValue({ id: "u", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home", "second"] });
    cookieGetMock.mockReturnValue({ value: "second" });
    await POST(upload());
    const headers = fetchMock.mock.calls[0][1].headers as Record<string, string>;
    expect(headers["X-Tenant-Id"]).toBe("second");
    expect(headers["X-User-Tenant-Id"]).toBe("second");
  });

  it("refuses an upload stamped for another organisation than the active one (409) and never calls the backend", async () => {
    getTokenMock.mockResolvedValue({ id: "sa", role: "SUPER_ADMIN" });
    cookieGetMock.mockReturnValue({ value: "org2" });
    const res = await POST(upload({ expected: "brk1" }));
    expect(res.status).toBe(409);
    expect(res.headers.get("X-Org-Mismatch")).toBe("1");
    expect((await res.json()).code).toBe("ORG_CHANGED");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("forwards when the stamped organisation matches", async () => {
    getTokenMock.mockResolvedValue({ id: "sa", role: "SUPER_ADMIN" });
    cookieGetMock.mockReturnValue({ value: "brk1" });
    const res = await POST(upload({ expected: "brk1" }));
    expect(res.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it.each(["@evil.example/x", "//evil.example/api/x", "/api/../actuator", "https://evil.example/api/x", "/v1/x", ""])(
    "refuses a path that could leave the backend or its /api (%s)",
    async (path) => {
      vi.stubEnv("INTERNAL_PROXY_SECRET", "real-secret");
      getTokenMock.mockResolvedValue({ id: "u", role: "TENANT_ADMIN", tenantId: "home" });
      const res = await POST(upload({ path }));
      expect(res.status).toBe(400);
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );

  it("forwards a normal path to the backend", async () => {
    getTokenMock.mockResolvedValue({ id: "u", role: "TENANT_ADMIN", tenantId: "home" });
    await POST(upload({ path: "/api/v1/tickets/t1/attachments" }));
    expect(String(fetchMock.mock.calls[0][0])).toBe("http://localhost:8080/api/v1/tickets/t1/attachments");
  });
});
