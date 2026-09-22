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

import middleware, { frameOptionsFor } from "../proxy";

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

describe("proxy middleware — active tenant selection", () => {
  // The active_tenant_id cookie is client-writable, so it is a *selection*,
  // not authorization: the proxy honours it only when the verified session
  // token proves membership. The backend's legacy-header path authorizes a
  // non-SUPER_ADMIN only when the requested tenant equals X-User-Tenant-Id, so
  // an authorized non-home selection has to travel as the user's tenant.
  function makeRequestWithCookie(path: string, cookie?: string) {
    const headers: Record<string, string> = {};
    if (cookie) headers.cookie = `active_tenant_id=${cookie}`;
    return new NextRequest(`http://localhost:3000${path}`, { method: "POST", headers });
  }

  const activeTenant = (res: Response) => res.headers.get("x-middleware-request-x-tenant-id");
  const userTenant = (res: Response) => res.headers.get("x-middleware-request-x-user-tenant-id");

  it("lets a multi-membership TENANT_ADMIN switch to a tenant they belong to", async () => {
    getTokenMock.mockResolvedValue({
      id: "u1", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home", "second"],
    });

    const res = await middleware(makeRequestWithCookie("/api/proxy/v1/leases", "second"));

    // Before the fix X-User-Tenant-Id stayed "home", so the backend 403'd every
    // request after the switch and the feature was unusable.
    expect(activeTenant(res)).toBe("second");
    expect(userTenant(res)).toBe("second");
  });

  it("ignores a tenant the session does not prove membership of", async () => {
    getTokenMock.mockResolvedValue({
      id: "u1", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home"],
    });

    const res = await middleware(makeRequestWithCookie("/api/proxy/v1/leases", "someone-elses-tenant"));

    // A forged cookie must not become a cross-tenant reach; fall back to home.
    expect(activeTenant(res)).toBe("home");
    expect(userTenant(res)).toBe("home");
  });

  it("falls back to the home tenant for a stale cookie rather than sending a certain 403", async () => {
    getTokenMock.mockResolvedValue({
      id: "u1", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home", "old"],
    });

    const res = await middleware(makeRequestWithCookie("/api/proxy/v1/leases", "revoked"));

    expect(activeTenant(res)).toBe("home");
  });

  it("still lets SUPER_ADMIN reach any tenant", async () => {
    getTokenMock.mockResolvedValue({
      id: "root", role: "SUPER_ADMIN", tenantId: "home", tenantIds: [],
    });

    const res = await middleware(makeRequestWithCookie("/api/proxy/v1/leases", "any-tenant"));

    expect(activeTenant(res)).toBe("any-tenant");
    // SUPER_ADMIN keeps its real home tenant: the backend authorizes the role
    // outright, and other code reads this value.
    expect(userTenant(res)).toBe("home");
  });

  it("uses the home tenant when no cookie is set", async () => {
    getTokenMock.mockResolvedValue({
      id: "u1", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home", "second"],
    });

    const res = await middleware(makeRequestWithCookie("/api/proxy/v1/leases"));

    expect(activeTenant(res)).toBe("home");
    expect(userTenant(res)).toBe("home");
  });

  it("handles a session with no membership list at all", async () => {
    getTokenMock.mockResolvedValue({ id: "u1", role: "PROPERTY_MANAGER", tenantId: "home" });

    const res = await middleware(makeRequestWithCookie("/api/proxy/v1/leases", "second"));

    expect(activeTenant(res)).toBe("home");
    expect(userTenant(res)).toBe("home");
  });
});

describe("proxy middleware — X-Frame-Options", () => {
  it("lets the app frame an asset it fetched through the proxy (issue #300)", async () => {
    getTokenMock.mockResolvedValue({ id: "u1", role: "TENANT_ADMIN", tenantId: "home" });

    // The settlement page renders a deduction PDF in an <iframe>; DENY forbids
    // framing even same-origin, so the preview would not render at all.
    const res = await middleware(makeRequest(
      "/api/proxy/v1/assets/serve/settlement-deductions/d1/damage.pdf"));

    expect(res.headers.get("X-Frame-Options")).toBe("SAMEORIGIN");
    expect(frameOptionsFor("/api/proxy/v1/assets/serve/ticket-attachments/a.png"))
      .toBe("SAMEORIGIN");
  });

  it("still denies framing for every other proxied path", async () => {
    getTokenMock.mockResolvedValue({ id: "u1", role: "TENANT_ADMIN", tenantId: "home" });

    const res = await middleware(makeRequest("/api/proxy/v1/leases"));

    expect(res.headers.get("X-Frame-Options")).toBe("DENY");
    expect(frameOptionsFor("/api/proxy/v1/finance/vouchers")).toBe("DENY");
    expect(frameOptionsFor("/api/proxy/v1/assets/upload")).toBe("DENY");
    // Not a proxy path at all, and not a prefix match either.
    expect(frameOptionsFor("/en/dashboard/leases")).toBe("DENY");
  });

  it("keeps the other security headers on the framed path", async () => {
    getTokenMock.mockResolvedValue({ id: "u1", role: "TENANT_ADMIN", tenantId: "home" });

    const res = await middleware(makeRequest("/api/proxy/v1/assets/serve/lease-docs/a.pdf"));

    expect(res.headers.get("X-Content-Type-Options")).toBe("nosniff");
    expect(res.headers.get("Referrer-Policy")).toBe("strict-origin-when-cross-origin");
  });
});
