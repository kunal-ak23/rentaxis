import { afterEach, describe, expect, it, vi, beforeEach } from "vitest";
import { NextRequest } from "next/server";

// Mock next-auth session lookup so we control authenticated vs anonymous.
const getTokenMock = vi.fn();
vi.mock("next-auth/jwt", () => ({
  getToken: (...args: unknown[]) => getTokenMock(...args),
}));

// next-intl middleware is irrelevant to the proxy branch — stub it out.
vi.mock("next-intl/middleware", () => ({
  default: () => () => new Response(null, { status: 200 }),
}));
vi.mock("@/i18n/routing", () => ({ routing: {} }));

import middleware from "../proxy";

function makeRequest(path: string, headers: Record<string, string> = {}) {
  return new NextRequest(`http://localhost:3000${path}`, {
    method: "POST",
    headers,
  });
}

beforeEach(() => {
  getTokenMock.mockReset();
});

describe("proxy middleware — /api/proxy auth gate", () => {
  it("returns 401 for an unauthenticated request to a protected proxy path", async () => {
    getTokenMock.mockResolvedValue(null);

    const res = await middleware(makeRequest("/api/proxy/v1/leases"));

    expect(res.status).toBe(401);
  });

  it("attaches identity headers for an authenticated request", async () => {
    getTokenMock.mockResolvedValue({ id: "user-1", role: "TENANT_ADMIN", tenantId: "tenant-1" });

    const res = await middleware(makeRequest("/api/proxy/v1/leases"));

    expect(res.status).toBe(200);
    expect(res.headers.get("x-middleware-request-x-user-id")).toBe("user-1");
    expect(res.headers.get("x-middleware-request-x-user-role")).toBe("TENANT_ADMIN");
    expect(res.headers.get("x-middleware-request-x-tenant-id")).toBe("tenant-1");
  });

  describe("public pre-auth allowlist", () => {
    it.each([
      "/api/proxy/auth/register",
      "/api/proxy/auth/set-password",
      "/api/proxy/v1/public/renewal-intent",
    ])("forwards %s without a session (no 401)", async (path) => {
      getTokenMock.mockResolvedValue(null);

      const res = await middleware(makeRequest(path));

      expect(res.status).toBe(200);
      // Passed through to the backend rewrite, not rejected.
      expect(res.headers.get("x-middleware-next")).toBe("1");
      // No session lookup is even needed on the public branch.
      expect(getTokenMock).not.toHaveBeenCalled();
    });

    it("strips client-supplied identity headers on public paths", async () => {
      const res = await middleware(
        makeRequest("/api/proxy/v1/public/renewal-intent", {
          "X-User-Id": "attacker",
          "X-User-Role": "SUPER_ADMIN",
          "X-User-Tenant-Id": "tenant-x",
          "X-Tenant-Id": "tenant-x",
        })
      );

      expect(res.status).toBe(200);
      expect(res.headers.get("x-middleware-request-x-user-id")).toBeNull();
      expect(res.headers.get("x-middleware-request-x-user-role")).toBeNull();
      expect(res.headers.get("x-middleware-request-x-user-tenant-id")).toBeNull();
      expect(res.headers.get("x-middleware-request-x-tenant-id")).toBeNull();
    });

    it.each([
      // Exact-match only: sub-paths and siblings of allowlisted entries stay gated.
      "/api/proxy/auth/set-password/extra",
      "/api/proxy/auth/register/extra",
      "/api/proxy/v1/public/other-endpoint",
      "/api/proxy/v1/publicish",
      "/api/proxy/auth/login",
    ])("still 401s an unauthenticated request to %s", async (path) => {
      getTokenMock.mockResolvedValue(null);

      const res = await middleware(makeRequest(path));

      expect(res.status).toBe(401);
    });
  });

  describe("X-Internal-Auth (internal proxy secret)", () => {
    afterEach(() => {
      vi.unstubAllEnvs();
    });

    it("strips a spoofed inbound X-Internal-Auth on the authenticated branch when no secret is configured", async () => {
      vi.stubEnv("INTERNAL_PROXY_SECRET", "");
      getTokenMock.mockResolvedValue({ id: "user-1", role: "TENANT_ADMIN", tenantId: "tenant-1" });

      const res = await middleware(
        makeRequest("/api/proxy/v1/leases", { "X-Internal-Auth": "forged-by-client" })
      );

      expect(res.status).toBe(200);
      expect(res.headers.get("x-middleware-request-x-internal-auth")).toBeNull();
    });

    it("replaces any inbound X-Internal-Auth with the configured secret on the authenticated branch", async () => {
      vi.stubEnv("INTERNAL_PROXY_SECRET", "real-secret");
      getTokenMock.mockResolvedValue({ id: "user-1", role: "TENANT_ADMIN", tenantId: "tenant-1" });

      const res = await middleware(
        makeRequest("/api/proxy/v1/leases", { "X-Internal-Auth": "forged-by-client" })
      );

      expect(res.status).toBe(200);
      expect(res.headers.get("x-middleware-request-x-internal-auth")).toBe("real-secret");
    });

    it("sets X-Internal-Auth from INTERNAL_PROXY_SECRET on authenticated requests", async () => {
      vi.stubEnv("INTERNAL_PROXY_SECRET", "real-secret");
      getTokenMock.mockResolvedValue({ id: "user-1", role: "SUPER_ADMIN", tenantId: "tenant-1" });

      const res = await middleware(makeRequest("/api/proxy/v1/leases"));

      expect(res.status).toBe(200);
      expect(res.headers.get("x-middleware-request-x-internal-auth")).toBe("real-secret");
    });

    it("does not invent an X-Internal-Auth header when the secret env is empty", async () => {
      vi.stubEnv("INTERNAL_PROXY_SECRET", "");
      getTokenMock.mockResolvedValue({ id: "user-1", role: "TENANT_ADMIN", tenantId: "tenant-1" });

      const res = await middleware(makeRequest("/api/proxy/v1/leases"));

      expect(res.status).toBe(200);
      expect(res.headers.get("x-middleware-request-x-internal-auth")).toBeNull();
    });

    it("strips inbound X-Internal-Auth on the public pre-auth branch", async () => {
      vi.stubEnv("INTERNAL_PROXY_SECRET", "real-secret");

      const res = await middleware(
        makeRequest("/api/proxy/v1/public/renewal-intent", { "X-Internal-Auth": "forged-by-client" })
      );

      expect(res.status).toBe(200);
      expect(res.headers.get("x-middleware-request-x-internal-auth")).toBeNull();
    });
  });
});
