import { cleanup, renderHook, waitFor, act } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

/**
 * Covers the module-level feature cache in useTenantFeatures:
 *   - fetches and caches flags for an authenticated session
 *   - clears the cache when a different user signs in (no cross-session bleed)
 *   - revalidates on window focus once the TTL has passed, so superadmin
 *     toggles become visible without a hard reload
 */

const sessionHolder = vi.hoisted(() => ({
    current: {
        data: { user: { id: "user-a" } } as { user: { id: string } } | null,
        status: "authenticated" as string,
    },
}));

vi.mock("next-auth/react", () => ({
    useSession: () => sessionHolder.current,
}));

type FetchResult = {
    ok: boolean;
    status?: number;
    json: () => Promise<unknown>;
    body?: { cancel: ReturnType<typeof vi.fn> };
};

let featuresResponse: Record<string, boolean>;
let fetchMock: ReturnType<typeof vi.fn>;
let now: number;

function featureFetchCalls() {
    return fetchMock.mock.calls.filter(([url]) => String(url).includes("/tenant/features")).length;
}

async function loadHook() {
    // The cache is module-scoped, so each test needs a fresh module instance.
    const mod = await import("@/hooks/useTenantFeatures");
    return mod.useTenantFeatures;
}

beforeEach(() => {
    vi.resetModules();
    now = 1_000_000;
    vi.spyOn(Date, "now").mockImplementation(() => now);
    featuresResponse = { LISTINGS: true };
    fetchMock = vi.fn((url: string): Promise<FetchResult> => {
        if (String(url).includes("/tenant/features")) {
            return Promise.resolve({ ok: true, json: () => Promise.resolve(featuresResponse) });
        }
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ slug: "acme" }) });
    });
    vi.stubGlobal("fetch", fetchMock);
    sessionHolder.current = { data: { user: { id: "user-a" } }, status: "authenticated" };
});

afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
});

describe("useTenantFeatures", () => {
    it("fetches features and tenant slug for an authenticated session", async () => {
        const useTenantFeatures = await loadHook();
        const { result } = renderHook(() => useTenantFeatures());

        await waitFor(() => {
            expect(result.current.isEnabled("LISTINGS")).toBe(true);
            expect(result.current.tenantSlug).toBe("acme");
        });
        expect(featureFetchCalls()).toBe(1);
    });

    it("does not refetch on a remount within the TTL", async () => {
        const useTenantFeatures = await loadHook();
        const first = renderHook(() => useTenantFeatures());
        await waitFor(() => expect(first.result.current.isEnabled("LISTINGS")).toBe(true));
        first.unmount();

        const second = renderHook(() => useTenantFeatures());
        expect(second.result.current.isEnabled("LISTINGS")).toBe(true);
        expect(featureFetchCalls()).toBe(1);
    });

    it("clears the cache when a different user signs in", async () => {
        const useTenantFeatures = await loadHook();
        const first = renderHook(() => useTenantFeatures());
        await waitFor(() => expect(first.result.current.isEnabled("LISTINGS")).toBe(true));
        first.unmount();

        featuresResponse = { LISTINGS: false, MEETINGS: true };
        sessionHolder.current = { data: { user: { id: "user-b" } }, status: "authenticated" };

        const second = renderHook(() => useTenantFeatures());
        // user-a's cached flags must not leak into user-b's initial state.
        expect(second.result.current.isEnabled("LISTINGS")).toBe(false);
        await waitFor(() => expect(second.result.current.isEnabled("MEETINGS")).toBe(true));
        expect(featureFetchCalls()).toBe(2);
    });

    it("revalidates on window focus after the TTL", async () => {
        const useTenantFeatures = await loadHook();
        const { result } = renderHook(() => useTenantFeatures());
        await waitFor(() => expect(result.current.isEnabled("LISTINGS")).toBe(true));
        expect(featureFetchCalls()).toBe(1);

        // Focus within the TTL: still fresh, no refetch.
        act(() => {
            window.dispatchEvent(new Event("focus"));
        });
        expect(featureFetchCalls()).toBe(1);

        // Superadmin flips the flag elsewhere; TTL passes; user refocuses the tab.
        featuresResponse = { LISTINGS: false };
        now += 61_000;
        act(() => {
            window.dispatchEvent(new Event("focus"));
        });
        await waitFor(() => expect(result.current.isEnabled("LISTINGS")).toBe(false));
        expect(featureFetchCalls()).toBe(2);
    });

    it("leaves features at defaults and cancels the body when the features fetch 500s", async () => {
        const cancel = vi.fn().mockResolvedValue(undefined);
        fetchMock.mockImplementation((url: string): Promise<FetchResult> => {
            if (String(url).includes("/tenant/features")) {
                return Promise.resolve({
                    ok: false,
                    status: 500,
                    json: () => Promise.reject(new Error("should not be read")),
                    body: { cancel },
                });
            }
            return Promise.resolve({ ok: true, json: () => Promise.resolve({ slug: "acme" }) });
        });

        const useTenantFeatures = await loadHook();
        const { result } = renderHook(() => useTenantFeatures());

        // The tenant slug call still succeeds independently, so wait on that
        // to know both fetches have settled before asserting on features.
        await waitFor(() => expect(result.current.tenantSlug).toBe("acme"));

        expect(result.current.isEnabled("LISTINGS")).toBe(false);
        expect(cancel).toHaveBeenCalledTimes(1);
    });
});
