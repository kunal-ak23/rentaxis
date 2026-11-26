import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionProvider, useSession } from "next-auth/react";

// QA-38 end to end with the REAL next-auth SessionProvider: a refetch that
// fails on tab focus must not sign the user out, and the session must come
// back for every useSession() once the server confirms the cookie.
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next/navigation", () => ({ usePathname: () => "/en/dashboard/leases" }));
const router = { push: vi.fn(), replace: vi.fn() };
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
    useRouter: () => router,
}));
vi.mock("@/components/ui/MvpSidebar", () => ({ default: () => <nav /> }));
vi.mock("@/components/ui/TopHeader", () => ({ TopHeader: () => <header /> }));
vi.mock("@/components/tour/TourProvider", () => ({ default: ({ children }: { children: React.ReactNode }) => <>{children}</> }));
vi.mock("@/components/help/HelpFAB", () => ({ default: () => null }));
vi.mock("@/components/workspace/WorkspaceHost", () => ({
    default: ({ children }: { children: React.ReactNode }) => <main>{children}</main>,
}));
vi.mock("@/hooks/useTenantFeatures", () => ({ useTenantFeatures: () => ({ isEnabled: () => false, tenantSlug: "" }) }));

import AuthenticatedLayout from "../AuthenticatedLayout";

const user = { user: { id: "u-38", role: "TENANT_ADMIN", tenantId: "t1" }, expires: "2099-01-01T00:00:00.000Z" };
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

/** Another consumer of the session, like the header's user menu. */
function Who() {
    const { data, status } = useSession();
    return <span data-testid="who">{status}:{data?.user?.id ?? "-"}</span>;
}

const fetchMock = vi.fn();
beforeEach(() => {
    fetchMock.mockReset();
    router.push.mockReset();
    vi.stubGlobal("fetch", fetchMock);
    Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true });
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("AuthenticatedLayout with the real SessionProvider (QA-38)", () => {
    it("survives a failed focus refetch and restores the session everywhere", async () => {
        fetchMock.mockResolvedValueOnce(json(user)); // first load
        render(
            <SessionProvider refetchInterval={0}>
                <AuthenticatedLayout><div data-testid="page-body">form in progress</div><Who /></AuthenticatedLayout>
            </SessionProvider>,
        );
        await waitFor(() => expect(screen.getByTestId("who").textContent).toBe("authenticated:u-38"));

        fetchMock
            .mockRejectedValueOnce(new TypeError("Failed to fetch")) // focus refetch: network not back yet
            .mockImplementation(async () => json(user)); // the layout's own check, then the restore
        await act(async () => { document.dispatchEvent(new Event("visibilitychange")); });

        await waitFor(() => expect(screen.getByTestId("who").textContent).toBe("authenticated:u-38"));
        expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(4);
        expect(router.push).not.toHaveBeenCalled();
        expect(screen.getByTestId("page-body")).toBeInTheDocument();
    });

    it("still sends a really signed-out user to sign in", async () => {
        fetchMock.mockResolvedValueOnce(json(user));
        render(
            <SessionProvider refetchInterval={0}>
                <AuthenticatedLayout><div data-testid="page-body" /></AuthenticatedLayout>
            </SessionProvider>,
        );
        await waitFor(() => expect(screen.getByTestId("page-body")).toBeInTheDocument());

        fetchMock.mockImplementation(async () => json({})); // cookie gone
        await act(async () => { document.dispatchEvent(new Event("visibilitychange")); });

        await waitFor(() => expect(router.push).toHaveBeenCalledTimes(1));
        expect(router.push.mock.calls[0][0]).toMatch(/^\/auth\/login\?callbackUrl=/);
    });
});
