import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// QA-38: a failed session refetch reads as "unauthenticated" in next-auth v4.
// The layout must check with the server before sending anyone to sign in.
vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
vi.mock("next/navigation", () => ({ usePathname: () => "/en/dashboard/leases" }));
type S = { user: { id: string; role: string; tenantId?: string } } | null;
const auth = { data: null as S, status: "authenticated" as string };
const update = vi.fn();
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: auth.data, status: auth.status, update }) }));
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

import AuthenticatedLayout, { SESSION_RECHECK_MS } from "../AuthenticatedLayout";

const Page = () => <div data-testid="page-body">form in progress</div>;
const signedIn: S = { user: { id: "u", role: "TENANT_ADMIN", tenantId: "t1" } };
const fetchMock = vi.fn();

function renderThenLoseSession() {
    auth.data = signedIn;
    auth.status = "authenticated";
    const view = render(<AuthenticatedLayout><Page /></AuthenticatedLayout>);
    auth.data = null;
    auth.status = "unauthenticated";
    view.rerender(<AuthenticatedLayout><Page /></AuthenticatedLayout>);
    return view;
}
const flush = () => act(async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve(); });

beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    update.mockReset();
    router.push.mockReset();
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe("AuthenticatedLayout — failed session refetch (QA-38)", () => {
    it("keeps the page and restores the session when the cookie is still good", async () => {
        fetchMock.mockResolvedValue(new Response(JSON.stringify(signedIn), { status: 200 }));
        update.mockResolvedValue(signedIn);
        renderThenLoseSession();
        await flush();
        expect(router.push).not.toHaveBeenCalled();
        expect(update).toHaveBeenCalledTimes(1);
        expect(screen.getByTestId("page-body")).toBeInTheDocument();
    });

    it("keeps the page and asks again later when the server cannot be reached", async () => {
        vi.useFakeTimers();
        fetchMock.mockRejectedValueOnce(new TypeError("Failed to fetch"))
            .mockResolvedValueOnce(new Response("bad gateway", { status: 502 }))
            .mockResolvedValue(new Response("{}", { status: 200 }));
        renderThenLoseSession();
        await flush();
        expect(router.push).not.toHaveBeenCalled();
        expect(screen.getByTestId("page-body")).toBeInTheDocument();

        await act(async () => { vi.advanceTimersByTime(SESSION_RECHECK_MS); });
        await flush();
        expect(router.push).not.toHaveBeenCalled();

        await act(async () => { vi.advanceTimersByTime(SESSION_RECHECK_MS); });
        await flush();
        expect(fetchMock).toHaveBeenCalledTimes(3);
        expect(router.push).toHaveBeenCalledWith("/auth/login?callbackUrl=%2F");
    });

    it("sends the user to sign in when the server confirms there is no session", async () => {
        fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
        renderThenLoseSession();
        await flush();
        expect(router.push).toHaveBeenCalledTimes(1);
        expect(router.push.mock.calls[0][0]).toMatch(/^\/auth\/login\?callbackUrl=/);
        expect(update).not.toHaveBeenCalled();
    });

    it("sends a visitor with no session at all to sign in", async () => {
        fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
        auth.data = null;
        auth.status = "unauthenticated";
        render(<AuthenticatedLayout><Page /></AuthenticatedLayout>);
        await flush();
        expect(router.push).toHaveBeenCalledTimes(1);
        expect(screen.queryByTestId("page-body")).not.toBeInTheDocument();
    });
});
