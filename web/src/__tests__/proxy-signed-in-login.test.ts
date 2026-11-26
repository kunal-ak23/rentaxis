import { beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

// QA-39: a signed-in user who opens the login page is sent on, and /login is an alias.
const getTokenMock = vi.fn();
vi.mock("next-auth/jwt", () => ({ getToken: (...args: unknown[]) => getTokenMock(...args) }));
vi.mock("next-intl/middleware", () => ({ default: () => () => new Response(null, { status: 200 }) }));
vi.mock("@/i18n/routing", () => ({ routing: {} }));

import middleware, { config } from "../proxy";

const get = (path: string) => middleware(new NextRequest(`http://localhost:3000${path}`));
const location = (res: Response) => {
    const l = res.headers.get("location");
    return l ? new URL(l).pathname + new URL(l).search : null;
};

beforeEach(() => getTokenMock.mockReset());

describe("proxy — login page with a live session (QA-39)", () => {
    it("sends a signed-in user on the login page to the dashboard of that locale", async () => {
        getTokenMock.mockResolvedValue({ id: "u1", role: "TENANT_ADMIN", tenantId: "t1" });
        expect(location(await get("/ar/auth/login"))).toBe("/ar/dashboard");
    });

    it("lets set-password's welcome hand-off and the register page through", async () => {
        getTokenMock.mockResolvedValue({ id: "u1", role: "TENANT_ADMIN", tenantId: "t1" });
        expect((await get("/en/auth/login?welcome=1")).headers.get("location")).toBeNull();
        expect((await get("/en/auth/register")).headers.get("location")).toBeNull();
    });

    it("honours a safe callbackUrl and refuses an off-site one", async () => {
        getTokenMock.mockResolvedValue({ id: "u1", role: "TENANT_ADMIN", tenantId: "t1" });
        expect(location(await get(`/en/auth/login?callbackUrl=${encodeURIComponent("/en/dashboard/leases?x=1")}`)))
            .toBe("/en/dashboard/leases?x=1");
        expect(location(await get(`/en/auth/login?callbackUrl=${encodeURIComponent("//evil.example/x")}`)))
            .toBe("/en/dashboard");
    });

    it("leaves the login page alone with no session or a revoked one", async () => {
        getTokenMock.mockResolvedValue(null);
        const none = await get("/en/auth/login");
        expect(none.status).toBe(200);
        expect(none.headers.get("location")).toBeNull();

        getTokenMock.mockResolvedValue({ id: "u1", role: "TENANT_ADMIN", revoked: true });
        const revoked = await get("/en/auth/login");
        expect(revoked.status).toBe(200);
        expect(revoked.headers.get("location")).toBeNull();
    });

    it("does not look up the session on the other auth pages", async () => {
        getTokenMock.mockResolvedValue({ id: "u1", role: "TENANT_ADMIN" });
        const res = await get("/en/auth/set-password?token=abc");
        expect(res.headers.get("location")).toBeNull();
        expect(getTokenMock).not.toHaveBeenCalled();
    });
});

describe("proxy — /login alias (QA-39)", () => {
    it.each([
        ["/login", "/en/auth/login"],
        ["/en/login", "/en/auth/login"],
        ["/ar/login?callbackUrl=%2Far%2Fdashboard", "/ar/auth/login?callbackUrl=%2Far%2Fdashboard"],
    ])("redirects %s to %s", async (from, to) => {
        getTokenMock.mockResolvedValue(null);
        const res = await get(from);
        expect(res.status).toBe(307);
        expect(location(res)).toBe(to);
    });

    it("is reached by the matcher", () => {
        expect(config.matcher).toContain("/login");
        expect(config.matcher).toContain("/auth/:path*");
    });
});
