import { beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

const getTokenMock = vi.fn();
vi.mock("next-auth/jwt", () => ({ getToken: (...args: unknown[]) => getTokenMock(...args) }));
vi.mock("next-intl/middleware", () => ({ default: () => () => new Response(null, { status: 200 }) }));
vi.mock("@/i18n/routing", () => ({ routing: {} }));

import middleware from "../proxy";

/**
 * Break round 1, F3: tab A (loaded for org brk1) submitted a form after tab B
 * had switched the shared cookie to org2, and the record landed in org2. The
 * client now sends the org its page was loaded for on every mutating call;
 * the proxy refuses a mismatch with 409 instead of forwarding it.
 */
function req(method: string, opts: { cookie?: string; expected?: string } = {}) {
    const headers: Record<string, string> = {};
    if (opts.cookie) headers.cookie = `active_tenant_id=${opts.cookie}`;
    if (opts.expected !== undefined) headers["X-Expected-Tenant-Id"] = opts.expected;
    return new NextRequest("http://localhost:3000/api/proxy/v1/renters", { method, headers });
}

beforeEach(() => {
    getTokenMock.mockReset();
    getTokenMock.mockResolvedValue({ id: "sa", role: "SUPER_ADMIN", tenantId: undefined });
});

describe("proxy — expected organisation on mutating requests (F3)", () => {
    it.each(["POST", "PUT", "PATCH", "DELETE"])("refuses a %s loaded for another organisation with a readable 409", async (method) => {
        const res = await middleware(req(method, { cookie: "org2", expected: "brk1" }));
        expect(res.status).toBe(409);
        expect(res.headers.get("X-Org-Mismatch")).toBe("1");
        // Not forwarded to the backend.
        expect(res.headers.get("x-middleware-next")).toBeNull();
        const body = await res.json();
        expect(body.code).toBe("ORG_CHANGED");
        expect(body.message).toMatch(/organisation changed/i);
    });

    it("forwards a mutation whose expected organisation matches the cookie, without the expectation header", async () => {
        const res = await middleware(req("POST", { cookie: "brk1", expected: "brk1" }));
        expect(res.status).toBe(200);
        expect(res.headers.get("x-middleware-next")).toBe("1");
        expect(res.headers.get("x-middleware-request-x-tenant-id")).toBe("brk1");
        expect(res.headers.get("x-middleware-request-x-expected-tenant-id")).toBeNull();
    });

    it("never blocks a GET, even when it is stale", async () => {
        const res = await middleware(req("GET", { cookie: "org2", expected: "brk1" }));
        expect(res.status).toBe(200);
    });

    it("forwards a mutation that carries no expectation (other callers are unaffected)", async () => {
        const res = await middleware(req("POST", { cookie: "org2" }));
        expect(res.status).toBe(200);
    });

    it("refuses a Global View page's mutation once another tab picked an organisation", async () => {
        const res = await middleware(req("POST", { cookie: "org2", expected: "none" }));
        expect(res.status).toBe(409);
    });

    it("refuses a mutation from a page loaded for an organisation after another tab went to Global View", async () => {
        const res = await middleware(req("POST", { expected: "brk1" }));
        expect(res.status).toBe(409);
    });

    it("treats no cookie and the home tenant as the same organisation for a member", async () => {
        getTokenMock.mockResolvedValue({ id: "u", role: "TENANT_ADMIN", tenantId: "home", tenantIds: ["home", "second"] });
        const sameHome = await middleware(req("POST", { cookie: "home", expected: "none" }));
        expect(sameHome.status).toBe(200);
        const switched = await middleware(req("POST", { cookie: "second", expected: "home" }));
        expect(switched.status).toBe(409);
    });
});

describe("proxy — its own session-ended 401s are marked (review fix 2)", () => {
    it("marks the no-session 401", async () => {
        getTokenMock.mockResolvedValue(null);
        const res = await middleware(req("GET"));
        expect(res.status).toBe(401);
        expect(res.headers.get("X-Session-Ended")).toBe("1");
    });

    it("marks the revoked-session 401", async () => {
        getTokenMock.mockResolvedValue({ id: "u", role: "TENANT_ADMIN", tenantId: "t1", revoked: true });
        const res = await middleware(req("GET"));
        expect(res.status).toBe(401);
        expect(res.headers.get("X-Session-Ended")).toBe("1");
    });

    it("does not mark a forwarded request (a backend 401 carries no such header)", async () => {
        const res = await middleware(req("GET", { cookie: "brk1" }));
        expect(res.headers.get("X-Session-Ended")).toBeNull();
    });
});

describe("proxy — its own markers are provable (fix round 2)", () => {
    // Next applies the middleware's response headers and then the backend's
    // (external rewrite via httpxy), so a forwarded response cannot have
    // backend headers stripped here. Instead every forwarded response is
    // stamped X-Rentaxis-Forwarded; the client trusts X-Session-Ended /
    // X-Org-Mismatch only on a response WITHOUT that stamp — i.e. one this
    // middleware answered itself and never sent to the backend.
    it("stamps every forwarded response", async () => {
        const res = await middleware(req("POST", { cookie: "brk1", expected: "brk1" }));
        expect(res.headers.get("x-middleware-next")).toBe("1");
        expect(res.headers.get("X-Rentaxis-Forwarded")).toBe("1");
    });

    it("never stamps its own 401 / 409", async () => {
        const mismatch = await middleware(req("POST", { cookie: "org2", expected: "brk1" }));
        expect(mismatch.status).toBe(409);
        expect(mismatch.headers.get("X-Rentaxis-Forwarded")).toBeNull();
        getTokenMock.mockResolvedValue(null);
        const ended = await middleware(req("GET"));
        expect(ended.headers.get("X-Rentaxis-Forwarded")).toBeNull();
    });

    it("the backend never sets the markers the client trusts", async () => {
        const fs = await import("node:fs");
        const path = await import("node:path");
        const root = path.resolve(__dirname, "../../../backend/src/main");
        const hits: string[] = [];
        const walk = (dir: string) => {
            for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
                const full = path.join(dir, e.name);
                if (e.isDirectory()) walk(full);
                else if (/\.(java|ya?ml|properties)$/.test(e.name)
                    && /X-Session-Ended|X-Org-Mismatch|X-Rentaxis-Forwarded/i.test(fs.readFileSync(full, "utf8"))) hits.push(full);
            }
        };
        if (fs.existsSync(root)) walk(root);
        expect(hits).toEqual([]);
    });
});
