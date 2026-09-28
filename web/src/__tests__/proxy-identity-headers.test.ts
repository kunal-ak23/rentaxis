import { beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

const getTokenMock = vi.fn();
vi.mock("next-auth/jwt", () => ({ getToken: (...args: unknown[]) => getTokenMock(...args) }));
vi.mock("next-intl/middleware", () => ({ default: () => () => new Response(null, { status: 200 }) }));
vi.mock("@/i18n/routing", () => ({ routing: {} }));

import middleware from "../proxy";

/** Every identity header is the proxy's to assert: an inbound one is forged and dropped. */
beforeEach(() => getTokenMock.mockReset());

describe("proxy — inbound identity headers", () => {
    it("drops a client-sent X-Tenant-Id / X-User-Tenant-Id that the session does not assert", async () => {
        getTokenMock.mockResolvedValue({ id: "sa", role: "SUPER_ADMIN" });
        const res = await middleware(new NextRequest("http://localhost:3000/api/proxy/v1/properties", {
            headers: { "X-Tenant-Id": "forged", "X-User-Tenant-Id": "forged", "X-User-Role": "RENTER" },
        }));
        expect(res.headers.get("x-middleware-request-x-tenant-id")).toBeNull();
        expect(res.headers.get("x-middleware-request-x-user-tenant-id")).toBeNull();
        expect(res.headers.get("x-middleware-request-x-user-role")).toBe("SUPER_ADMIN");
    });
});
