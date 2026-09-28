import { afterEach, describe, expect, it, vi } from "vitest";
import { isForbidden, loadList } from "../listLoad";
import { ApiError } from "../facilities";

/**
 * Break round 1, F8: list pages treated every non-OK response as "no rows",
 * so a role the backend refuses (403) saw "No users found" / "No bank
 * accounts" plus create buttons. One helper tells the three outcomes apart.
 */
const respond = (status: number, body: unknown = []) =>
    vi.fn(async () => new Response(JSON.stringify(body), { status })) as unknown as typeof fetch;

afterEach(() => vi.restoreAllMocks());

describe("loadList", () => {
    it("returns the rows on 200", async () => {
        global.fetch = respond(200, [{ id: "a" }]);
        expect(await loadList<{ id: string }>("/api/proxy/v1/x")).toEqual({ kind: "ok", items: [{ id: "a" }] });
    });

    it("says forbidden on 403 — never an empty list", async () => {
        global.fetch = respond(403, { message: "Forbidden" });
        expect(await loadList("/api/proxy/v1/x")).toEqual({ kind: "forbidden" });
    });

    it("passes the caller's abort signal to fetch", async () => {
        const f = respond(200, []);
        global.fetch = f;
        const c = new AbortController();
        await loadList("/api/proxy/v1/x", c.signal);
        expect(f).toHaveBeenCalledWith("/api/proxy/v1/x", { signal: c.signal });
    });

    it("says failed on any other status or a network error", async () => {
        global.fetch = respond(500);
        expect(await loadList("/api/proxy/v1/x")).toEqual({ kind: "failed", status: 500 });
        global.fetch = vi.fn(async () => { throw new TypeError("offline"); }) as unknown as typeof fetch;
        expect(await loadList("/api/proxy/v1/x")).toEqual({ kind: "failed", status: 0 });
    });
});

describe("isForbidden", () => {
    it("recognises a 403 ApiError only", () => {
        expect(isForbidden(new ApiError(403, "no"))).toBe(true);
        expect(isForbidden(new ApiError(404, "no"))).toBe(false);
        expect(isForbidden(new Error("x"))).toBe(false);
    });
});
