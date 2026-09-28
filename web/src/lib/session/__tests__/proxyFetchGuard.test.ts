import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { installProxyFetchGuard, loginUrlFor } from "../proxyFetchGuard";

/**
 * The one place every browser call to /api/proxy passes through (the app has
 * ~150 raw fetch sites, so per-page handling is not an option):
 *  - F3: mutations carry the organisation the page was loaded for;
 *  - F6: a 401 means the session is gone — send the user to sign in instead of
 *    rendering "Request failed (status 401)" or a false empty state;
 *  - F3: the proxy's own 409 org-mismatch raises the "organisation changed" notice.
 */

type Call = { url: string; init?: RequestInit; headers: Headers };
let calls: Call[];
let respond: (url: string) => Response;
let uninstall: (() => void) | null = null;

beforeEach(() => {
    calls = [];
    respond = () => new Response("{}", { status: 200 });
    window.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
        calls.push({ url, init, headers: new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined)) });
        return respond(url);
    }) as unknown as typeof fetch;
});
afterEach(() => {
    uninstall?.();
    uninstall = null;
});

function install(overrides: Partial<Parameters<typeof installProxyFetchGuard>[0]> = {}) {
    const onUnauthorized = vi.fn();
    const onOrgMismatch = vi.fn();
    const onBackendUnauthorized = vi.fn();
    uninstall = installProxyFetchGuard({ getExpectedOrg: () => "brk1", onUnauthorized, onOrgMismatch, onBackendUnauthorized, ...overrides });
    return { onUnauthorized, onOrgMismatch, onBackendUnauthorized };
}

const headerOf = (c: Call, name: string) => c.headers.get(name);

describe("installProxyFetchGuard — expected organisation (F3)", () => {
    it.each(["POST", "PUT", "PATCH", "DELETE", "post"])("stamps a %s to /api/proxy with the page's organisation", async (method) => {
        install();
        await fetch("/api/proxy/v1/renters", { method, headers: { "Content-Type": "application/json" }, body: "{}" });
        expect(headerOf(calls[0], "X-Expected-Tenant-Id")).toBe("brk1");
        expect(headerOf(calls[0], "Content-Type")).toBe("application/json");
    });

    it("sends 'none' for a page loaded in Global View", async () => {
        install({ getExpectedOrg: () => "" });
        await fetch("/api/proxy/v1/renters", { method: "POST" });
        expect(headerOf(calls[0], "X-Expected-Tenant-Id")).toBe("none");
    });

    it("leaves GETs and non-proxy calls alone", async () => {
        install();
        await fetch("/api/proxy/v1/renters");
        await fetch("/api/auth/session", { method: "POST" });
        await fetch("https://example.com/api/proxy/x", { method: "POST" });
        expect(calls.map(c => headerOf(c, "X-Expected-Tenant-Id"))).toEqual([null, null, null]);
    });

    it("stamps an upload through /api/upload too, keeping its multipart body", async () => {
        const { onUnauthorized } = install();
        const fd = new FormData();
        fd.append("file", new Blob(["x"]), "a.pdf");
        respond = () => new Response("", { status: 401, headers: { "X-Session-Ended": "1" } });
        await fetch("/api/upload?path=/api/v1/leases/l1/attachments", { method: "POST", body: fd });
        expect(headerOf(calls[0], "X-Expected-Tenant-Id")).toBe("brk1");
        expect(calls[0].init?.body).toBe(fd);
        expect(onUnauthorized).toHaveBeenCalledTimes(1);
    });

    it("stamps a Request object too", async () => {
        install();
        await fetch(new Request("http://localhost:3000/api/proxy/v1/renters", { method: "POST" }));
        expect(headerOf(calls[0], "X-Expected-Tenant-Id")).toBe("brk1");
    });

    it("calls onOrgMismatch on the proxy's own 409, not on a backend conflict", async () => {
        const { onOrgMismatch } = install();
        respond = () => new Response("{}", { status: 409, headers: { "X-Org-Mismatch": "1" } });
        await fetch("/api/proxy/v1/renters", { method: "POST" });
        expect(onOrgMismatch).toHaveBeenCalledTimes(1);
        respond = () => new Response("{}", { status: 409 });
        await fetch("/api/proxy/v1/renters", { method: "POST" });
        expect(onOrgMismatch).toHaveBeenCalledTimes(1);
    });
});

describe("installProxyFetchGuard — session gone (F6)", () => {
    it("calls onUnauthorized on the proxy's session-ended 401, for reads and writes", async () => {
        const { onUnauthorized, onBackendUnauthorized } = install();
        respond = () => new Response("Unauthorized", { status: 401, headers: { "X-Session-Ended": "1" } });
        const res = await fetch("/api/proxy/v1/leases/paged");
        await fetch("/api/proxy/v1/renters", { method: "POST" });
        expect(onUnauthorized).toHaveBeenCalledTimes(2);
        // The caller still gets the real response.
        expect(res.status).toBe(401);
        expect(onBackendUnauthorized).not.toHaveBeenCalled();
    });

    it("never treats a backend 401 (e.g. 'Organisation is not active') as a signed-out session — no sign-in loop", async () => {
        const { onUnauthorized, onBackendUnauthorized } = install();
        respond = () => new Response("Organisation is not active.", { status: 401 });
        await fetch("/api/proxy/v1/leases/paged");
        expect(onUnauthorized).not.toHaveBeenCalled();
        await vi.waitFor(() => expect(onBackendUnauthorized).toHaveBeenCalledTimes(1));
        // The backend's message is passed on (fix round 2: inactive user vs inactive org).
        expect(onBackendUnauthorized).toHaveBeenCalledWith("Organisation is not active.", null);
    });

    it("ignores a 401 from outside /api/proxy and other statuses", async () => {
        const { onUnauthorized } = install();
        respond = (url) => new Response("", { status: url.includes("/api/proxy") ? 403 : 401 });
        await fetch("/api/auth/callback/credentials", { method: "POST" });
        await fetch("/api/proxy/v1/leases");
        expect(onUnauthorized).not.toHaveBeenCalled();
    });

    it("ignores the markers on a response that went to the backend (fix round 2)", async () => {
        const { onUnauthorized, onOrgMismatch, onBackendUnauthorized } = install();
        respond = () => new Response("", { status: 401, headers: { "X-Session-Ended": "1", "X-Rentaxis-Forwarded": "1" } });
        await fetch("/api/proxy/v1/leases");
        expect(onUnauthorized).not.toHaveBeenCalled();
        await vi.waitFor(() => expect(onBackendUnauthorized).toHaveBeenCalledTimes(1));
        respond = () => new Response("", { status: 409, headers: { "X-Org-Mismatch": "1", "X-Rentaxis-Forwarded": "1" } });
        await fetch("/api/proxy/v1/renters", { method: "POST" });
        expect(onOrgMismatch).not.toHaveBeenCalled();
    });

    it("restores the original fetch on uninstall", async () => {
        const original = window.fetch;
        install();
        expect(window.fetch).not.toBe(original);
        uninstall!();
        uninstall = null;
        expect(window.fetch).toBe(original);
    });
});

describe("loginUrlFor", () => {
    it("keeps the locale and the current page as callbackUrl", () => {
        expect(loginUrlFor({ pathname: "/ar/dashboard/leases", search: "?status=ACTIVE" }))
            .toBe(`/ar/auth/login?callbackUrl=${encodeURIComponent("/ar/dashboard/leases?status=ACTIVE")}`);
        expect(loginUrlFor({ pathname: "/en/dashboard", search: "" }))
            .toBe(`/en/auth/login?callbackUrl=${encodeURIComponent("/en/dashboard")}`);
    });

    it("never redirects from the auth pages themselves (no loop)", () => {
        expect(loginUrlFor({ pathname: "/en/auth/login", search: "?callbackUrl=%2Fen%2Fdashboard" })).toBeNull();
        expect(loginUrlFor({ pathname: "/ar/auth/set-password", search: "" })).toBeNull();
    });
});

describe("installProxyFetchGuard — backend refusal reason (batch 2 follow-up)", () => {
    it("passes X-Auth-Reason with a backend 401", async () => {
        const { onBackendUnauthorized } = install();
        respond = () => new Response("", { status: 401, headers: { "X-Auth-Reason": "USER_INACTIVE", "X-Rentaxis-Forwarded": "1" } });
        await fetch("/api/proxy/v1/renters");
        await vi.waitFor(() => expect(onBackendUnauthorized).toHaveBeenCalledWith("", "USER_INACTIVE"));
    });

    it("reports a 403 only when the backend says NOT_A_MEMBER", async () => {
        const { onBackendUnauthorized } = install();
        respond = () => new Response("", { status: 403 });
        await fetch("/api/proxy/v1/renters");
        respond = () => new Response("", { status: 403, headers: { "X-Auth-Reason": "NOT_A_MEMBER" } });
        await fetch("/api/proxy/v1/renters");
        await vi.waitFor(() => expect(onBackendUnauthorized).toHaveBeenCalledTimes(1));
        expect(onBackendUnauthorized).toHaveBeenCalledWith("", "NOT_A_MEMBER");
    });
});
