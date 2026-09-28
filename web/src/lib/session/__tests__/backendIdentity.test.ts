import { describe, expect, it } from "vitest";
import type { JWT } from "next-auth/jwt";
import { backendTarget, resolveBackendIdentity } from "../backendIdentity";

const jwt = (o: object) => o as unknown as JWT;

/**
 * The shared rule behind /api/proxy (proxy.ts) and /api/upload (break round 1,
 * batch 5): one implementation, so the upload route cannot skip a check again.
 */
const base = { cookieTenant: undefined, expectedTenant: null, method: "GET", internalProxySecret: undefined };

describe("resolveBackendIdentity", () => {
    it("ends a missing or revoked session", () => {
        expect(resolveBackendIdentity({ ...base, token: null }).kind).toBe("session-ended");
        expect(resolveBackendIdentity({ ...base, token: jwt({ id: "u", role: "TENANT_ADMIN", revoked: true }) }).kind)
            .toBe("session-ended");
    });

    it("honours the cookie only for a membership (SUPER_ADMIN: any)", () => {
        const ta = { id: "u", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home", "m"] };
        const pick = (token: Record<string, unknown>, cookieTenant: string) => {
            const d = resolveBackendIdentity({ ...base, token: jwt(token), cookieTenant });
            return d.kind === "ok" ? [d.headers["X-Tenant-Id"], d.headers["X-User-Tenant-Id"]] : d.kind;
        };
        expect(pick(ta, "m")).toEqual(["m", "m"]);
        expect(pick(ta, "foreign")).toEqual(["home", "home"]);
        expect(pick({ id: "sa", role: "SUPER_ADMIN" }, "any")).toEqual(["any", undefined]);
    });

    it("refuses a mutation stamped for another organisation, never a read", () => {
        const token = jwt({ id: "sa", role: "SUPER_ADMIN" });
        expect(resolveBackendIdentity({ ...base, token, cookieTenant: "b", expectedTenant: "a", method: "POST" }).kind)
            .toBe("org-mismatch");
        expect(resolveBackendIdentity({ ...base, token, cookieTenant: "b", expectedTenant: "a", method: "GET" }).kind)
            .toBe("ok");
        expect(resolveBackendIdentity({ ...base, token, cookieTenant: "b", expectedTenant: "none", method: "DELETE" }).kind)
            .toBe("org-mismatch");
    });

    /**
     * Break-it round 2 (portal2) F2 was first read as a sign-in race: the page
     * snapshots its org from the active_tenant_id cookie before the switcher
     * has set it (GET /auth/me/tenants still in flight), so the first
     * mutation is stamped "none". Reproduced against the stack: that request
     * is forwarded (200); the 409 was the backend refusing a 256+ character
     * name. These pin why the stamp cannot race: an absent stamp, an absent
     * cookie and the home organisation all resolve to the same tenant.
     */
    it("never refuses a first mutation stamped before the org cookie was set", () => {
        const renter = jwt({ id: "r", role: "RENTER", tenantId: "home", tenantIds: ["home"] });
        const ta = jwt({ id: "u", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home", "m"] });
        const put = (token: JWT, expectedTenant: string, cookieTenant: string | undefined) =>
            resolveBackendIdentity({ ...base, token, expectedTenant, cookieTenant, method: "PUT" }).kind;
        for (const token of [renter, ta]) {
            // Snapshot "none"; cookie still absent, or since set to the resolved default (home).
            expect(put(token, "none", undefined)).toBe("ok");
            expect(put(token, "none", "home")).toBe("ok");
            // Snapshot home (the switcher's setPageOrg); cookie absent (cleared) or home.
            expect(put(token, "home", undefined)).toBe("ok");
            // A leftover cookie from another account resolves to home on both sides.
            expect(put(token, "foreign", "foreign")).toBe("ok");
            expect(put(token, "none", "foreign")).toBe("ok");
        }
    });

    it("still refuses a form loaded for one org after another tab switched (F3)", () => {
        const ta = jwt({ id: "u", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home", "m"] });
        const put = (expectedTenant: string, cookieTenant: string | undefined) =>
            resolveBackendIdentity({ ...base, token: ta, expectedTenant, cookieTenant, method: "PUT" }).kind;
        expect(put("none", "m")).toBe("org-mismatch");
        expect(put("home", "m")).toBe("org-mismatch");
        expect(put("m", "home")).toBe("org-mismatch");
        expect(put("m", undefined)).toBe("org-mismatch");
    });

    it("attaches the internal secret only when configured", () => {
        const token = jwt({ id: "u", role: "RENTER", tenantId: "h" });
        const withSecret = resolveBackendIdentity({ ...base, token, internalProxySecret: "s" });
        const without = resolveBackendIdentity({ ...base, token, internalProxySecret: "" });
        expect(withSecret.kind === "ok" && withSecret.headers["X-Internal-Auth"]).toBe("s");
        expect(without.kind === "ok" && "X-Internal-Auth" in without.headers).toBe(false);
    });
});

describe("backendTarget", () => {
    const b = "http://backend:8080";
    it("keeps a path under /api on the backend", () => {
        expect(backendTarget("/api/v1/assets/upload", b)?.toString()).toBe("http://backend:8080/api/v1/assets/upload");
    });
    it.each(["@evil.example/x", "//evil.example/api/x", "/api/../actuator/env", "/api/..%2f", "https://evil.example/api/x",
        "/public/x", "/api\\x", null])("refuses %s", (p) => {
        expect(backendTarget(p as string | null, b)).toBeNull();
    });
});
