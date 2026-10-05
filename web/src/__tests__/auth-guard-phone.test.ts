import { afterEach, describe, expect, it, vi } from "vitest";
import { authOptions } from "@/auth";

/**
 * Tutorial 25: guards had no way into the web gate desk. The guard-phone
 * provider exchanges a Firebase ID token for the guard at the backend — the
 * Security app's endpoint — and refuses anyone the backend does not return as a
 * guard.
 */
type Authorize = (c: Record<string, string> | undefined, req: unknown) => Promise<unknown>;
// next-auth applies options.id when it parses the providers; the raw entry still says "credentials".
const provider = authOptions.providers.find(p => (p as unknown as { options?: { id?: string } }).options?.id === "guard-phone") as unknown as { options: { authorize: Authorize } };
const authorize: Authorize = (c, req) => provider.options.authorize(c, req);

afterEach(() => { vi.restoreAllMocks(); });

const reply = (status: number, body?: unknown) =>
    vi.spyOn(global, "fetch").mockResolvedValue(new Response(body === undefined ? "" : JSON.stringify(body), { status }));

describe("guard-phone sign-in", () => {
    it("posts the ID token to the Firebase exchange, forwarding the browser's address for the rate limit", async () => {
        const fetchSpy = reply(200, { id: "g1", email: "g@x", name: "Guard", role: "SECURITY_GUARD", tenantId: "t1", tenantIds: ["t1"] });
        const user = await authorize({ idToken: "tok" }, { headers: { "x-forwarded-for": "203.0.113.9" } });
        expect(user).toMatchObject({ id: "g1", role: "SECURITY_GUARD", tenantId: "t1" });
        const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
        expect(url).toMatch(/\/api\/v1\/auth\/firebase$/);
        expect(JSON.parse(String(init.body))).toEqual({ idToken: "tok" });
        expect((init.headers as Record<string, string>)["X-Forwarded-For"]).toBe("203.0.113.9");
    });

    it("refuses a non-guard even if the backend answered", async () => {
        reply(200, { id: "a1", role: "TENANT_ADMIN", tenantId: "t1" });
        expect(await authorize({ idToken: "tok" }, {})).toBeNull();
    });

    it("refuses an unknown phone (401) and a missing token", async () => {
        reply(401);
        expect(await authorize({ idToken: "tok" }, {})).toBeNull();
        expect(await authorize({}, {})).toBeNull();
    });

    it("reports the rate limit and an unconfigured backend", async () => {
        reply(429);
        await expect(authorize({ idToken: "tok" }, {})).rejects.toThrow("RATE_LIMITED");
        reply(503);
        await expect(authorize({ idToken: "tok" }, {})).rejects.toThrow("GUARD_PHONE_UNAVAILABLE");
    });
});
