import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { JWT } from "next-auth/jwt";

import { authOptions, fetchCurrentUser, shouldRevalidate, REVALIDATE_INTERVAL_MS } from "../auth";

/**
 * A JWT session with no server-side lookup meant that changing a user's role,
 * deactivating them, or removing a membership did not touch an already-issued
 * browser session: the old role kept working for the life of the token, and the
 * proxy forwarded it to the backend verbatim. A demoted PROPERTY_MANAGER or an
 * offboarded TENANT_ADMIN kept full prior access — financial screens included —
 * long after an admin believed it was revoked.
 */
describe("session revalidation", () => {
    const fetchMock = vi.fn();

    beforeEach(() => {
        fetchMock.mockReset();
        vi.stubGlobal("fetch", fetchMock);
    });

    afterEach(() => {
        vi.unstubAllGlobals();
    });

    // The jwt callback is what NextAuth invokes on every request.
    const jwt = authOptions.callbacks!.jwt!;
    const session = authOptions.callbacks!.session!;

    const staleToken = (over: Partial<JWT> = {}): JWT =>
        ({
            id: "user-1",
            role: "PROPERTY_MANAGER",
            tenantId: "tenant-1",
            tenantIds: ["tenant-1"],
            revalidatedAt: Date.now() - (REVALIDATE_INTERVAL_MS + 1000),
            ...over,
        }) as JWT;

    const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body });

    describe("shouldRevalidate", () => {
        it("checks immediately when the token has never been revalidated", () => {
            // Tokens issued before this shipped carry no stamp.
            expect(shouldRevalidate(undefined)).toBe(true);
        });

        it("does not check again inside the interval", () => {
            expect(shouldRevalidate(Date.now())).toBe(false);
        });

        it("checks again once the interval has elapsed", () => {
            expect(shouldRevalidate(Date.now() - (REVALIDATE_INTERVAL_MS + 1))).toBe(true);
        });
    });

    describe("fetchCurrentUser", () => {
        it("reports revoked when the backend 404s the account", async () => {
            fetchMock.mockResolvedValue({ ok: false, status: 404 });
            await expect(fetchCurrentUser("user-1")).resolves.toEqual({ status: "revoked" });
        });

        it("reports unavailable on a network failure rather than revoking", async () => {
            fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
            await expect(fetchCurrentUser("user-1")).resolves.toEqual({ status: "unavailable" });
        });

        it("reports unavailable on a 5xx rather than revoking", async () => {
            fetchMock.mockResolvedValue({ ok: false, status: 503 });
            await expect(fetchCurrentUser("user-1")).resolves.toEqual({ status: "unavailable" });
        });

        it("returns the current role and tenant on success", async () => {
            fetchMock.mockResolvedValue(ok({ role: "TENANT_USER", tenantId: "tenant-9" }));
            await expect(fetchCurrentUser("user-1")).resolves.toEqual({
                status: "ok",
                role: "TENANT_USER",
                tenantId: "tenant-9",
            });
        });
    });

    describe("jwt callback", () => {
        it("picks up a role demotion made after sign-in", async () => {
            fetchMock.mockResolvedValue(ok({ role: "TENANT_USER", tenantId: "tenant-1" }));

            const out = (await jwt({ token: staleToken() } as never)) as JWT;

            // Before the fix this stayed PROPERTY_MANAGER for the token's life.
            expect(out.role).toBe("TENANT_USER");
            expect(out.revoked).toBe(false);
        });

        it("marks a deleted account revoked", async () => {
            fetchMock.mockResolvedValue({ ok: false, status: 404 });

            const out = (await jwt({ token: staleToken() } as never)) as JWT;

            expect(out.revoked).toBe(true);
        });

        it("does not call the backend inside the interval", async () => {
            const fresh = staleToken({ revalidatedAt: Date.now() });

            const out = (await jwt({ token: fresh } as never)) as JWT;

            // One call per user per interval, not one per request.
            expect(fetchMock).not.toHaveBeenCalled();
            expect(out.role).toBe("PROPERTY_MANAGER");
        });

        it("fails open on a backend outage and retries on the next request", async () => {
            fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
            const before = staleToken();
            const stampBefore = before.revalidatedAt;

            const out = (await jwt({ token: before } as never)) as JWT;

            // A backend blip must not sign every user out...
            expect(out.revoked).toBeUndefined();
            expect(out.role).toBe("PROPERTY_MANAGER");
            // ...and must not buy another full interval of staleness either.
            expect(out.revalidatedAt).toBe(stampBefore);
            expect(shouldRevalidate(out.revalidatedAt)).toBe(true);
        });

        it("stamps a freshly signed-in token without calling the backend", async () => {
            const out = (await jwt({
                token: {} as JWT,
                user: { id: "u", role: "TENANT_ADMIN", tenantId: "t", tenantIds: ["t"] },
            } as never)) as JWT;

            expect(fetchMock).not.toHaveBeenCalled();
            expect(out.role).toBe("TENANT_ADMIN");
            expect(typeof out.revalidatedAt).toBe("number");
        });
    });

    describe("session callback", () => {
        it("surfaces revocation so the UI can sign the user out", async () => {
            const out = await session({
                session: { user: {} },
                token: staleToken({ revoked: true }),
            } as never);

            expect((out as { revoked?: boolean }).revoked).toBe(true);
        });

        it("leaves a healthy session unflagged", async () => {
            const out = await session({
                session: { user: {} },
                token: staleToken(),
            } as never);

            expect((out as { revoked?: boolean }).revoked).toBe(false);
        });
    });
});
