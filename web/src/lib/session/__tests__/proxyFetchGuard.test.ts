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

/**
 * Break round 3, F1: leaving the page (a full navigation — link to another
 * document, typed URL, reload) cancels every in-flight fetch, and Chrome
 * rejects each with `TypeError: Failed to fetch` AFTER pagehide/unload but
 * while microtasks still run — so each page's `catch (err) { console.error(err) }`
 * logged it. Verified in Chromium: order is beforeunload → pagehide → unload →
 * rejection → microtasks (timers no longer run). The guard swallows rejections
 * that land while the page is going away; a real network failure before that
 * still rejects.
 */
describe("installProxyFetchGuard — page leaving (break round 3, F1)", () => {
    const NEVER = Symbol("never");
    const settledOrNever = (p: Promise<unknown>) =>
        Promise.race([p.then(v => v, e => e), new Promise(r => setTimeout(() => r(NEVER), 30))]);
    const pagehide = (persisted = false) => {
        const e = new Event("pagehide") as PageTransitionEvent;
        Object.defineProperty(e, "persisted", { value: persisted });
        window.dispatchEvent(e);
    };
    const pageshow = () => window.dispatchEvent(new Event("pageshow"));
    const failing = () => {
        window.fetch = vi.fn(async () => { throw new TypeError("Failed to fetch"); }) as unknown as typeof fetch;
    };
    afterEach(() => pageshow());

    it("a real network failure (page not leaving) still rejects", async () => {
        failing();
        install();
        const out = await settledOrNever(fetch("/api/proxy/v1/leases"));
        expect(out).toBeInstanceOf(TypeError);
    });

    it("a fetch cancelled because the page is being unloaded never settles (nothing for the page to log)", async () => {
        failing();
        install();
        pagehide();
        expect(await settledOrNever(fetch("/api/proxy/v1/leases"))).toBe(NEVER);
        // Non-proxy calls made by the page are cancelled the same way.
        expect(await settledOrNever(fetch("/api/auth/session"))).toBe(NEVER);
    });

    it("the rejection that lands after pagehide is swallowed even when the fetch started before it", async () => {
        let reject!: (e: unknown) => void;
        window.fetch = vi.fn(() => new Promise((_, r) => { reject = r; })) as unknown as typeof fetch;
        install();
        const p = fetch("/api/proxy/v1/units/property/p1");
        pagehide();
        reject(new TypeError("Failed to fetch"));
        expect(await settledOrNever(p)).toBe(NEVER);
    });

    it("a body read cancelled by the unload is swallowed too", async () => {
        window.fetch = vi.fn(async () => {
            const res = new Response("{}", { status: 200 });
            res.json = () => Promise.reject(new TypeError("network error"));
            return res;
        }) as unknown as typeof fetch;
        install();
        const res = await fetch("/api/proxy/v1/dashboard/summary");
        pagehide();
        expect(await settledOrNever(res.json())).toBe(NEVER);
    });

    it("a body read that fails while the page stays still rejects", async () => {
        window.fetch = vi.fn(async () => {
            const res = new Response("{}", { status: 200 });
            res.json = () => Promise.reject(new SyntaxError("bad json"));
            return res;
        }) as unknown as typeof fetch;
        install();
        const res = await fetch("/api/proxy/v1/dashboard/summary");
        expect(await settledOrNever(res.json())).toBeInstanceOf(SyntaxError);
    });

    it("safety net (review M1): the page becoming visible again resets leaving; going hidden does not", async () => {
        failing();
        install();
        const setVisibility = (state: DocumentVisibilityState) => {
            Object.defineProperty(document, "visibilityState", { value: state, configurable: true });
            document.dispatchEvent(new Event("visibilitychange"));
        };
        pagehide();
        setVisibility("hidden"); // what a real unload does next: still leaving
        expect(await settledOrNever(fetch("/api/proxy/v1/leases"))).toBe(NEVER);
        setVisibility("visible"); // a page that is still alive and shown is not leaving
        expect(await settledOrNever(fetch("/api/proxy/v1/leases"))).toBeInstanceOf(TypeError);
        Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true });
    });

    it("a page put in the back/forward cache (persisted) is not leaving; pageshow resets", async () => {
        failing();
        install();
        pagehide(true);
        expect(await settledOrNever(fetch("/api/proxy/v1/leases"))).toBeInstanceOf(TypeError);
        pagehide();
        pageshow();
        expect(await settledOrNever(fetch("/api/proxy/v1/leases"))).toBeInstanceOf(TypeError);
    });
});
