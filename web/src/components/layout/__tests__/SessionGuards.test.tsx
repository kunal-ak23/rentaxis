import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import Cookies from "js-cookie";

vi.mock("next-intl", async () => (await import("@/test/intlMock")).englishIntl());
const { signOut } = vi.hoisted(() => ({ signOut: vi.fn() }));
vi.mock("next-auth/react", () => ({ signOut }));

import { SessionGuards } from "../SessionGuards";
import { resetPageOrg } from "@/lib/session/orgSync";

/**
 * Break round 1:
 *  F3 — tab B switching organisation must block tab A (which still shows the
 *       old organisation) until it is reloaded;
 *  F6 — a 401 from /api/proxy anywhere sends the user to sign in, keeping the
 *       page as callbackUrl.
 */

let assign: ReturnType<typeof vi.fn>;
let reload: ReturnType<typeof vi.fn>;
let fetchStatus = 200;
let fetchHeaders: Record<string, string> = {};
const realLocation = window.location;

beforeEach(() => {
    resetPageOrg();
    Cookies.set("active_tenant_id", "brk1", { path: "/" });
    assign = vi.fn();
    reload = vi.fn();
    Object.defineProperty(window, "location", {
        configurable: true,
        value: { ...realLocation, href: "http://localhost:3000/en/dashboard/renters?q=a", origin: "http://localhost:3000",
            pathname: "/en/dashboard/renters", search: "?q=a", assign, reload },
    });
    fetchStatus = 200;
    fetchHeaders = {};
    window.fetch = vi.fn(async () => new Response("{}", { status: fetchStatus, headers: fetchHeaders })) as unknown as typeof fetch;
});
afterEach(() => {
    cleanup();
    Cookies.remove("active_tenant_id", { path: "/" });
    Object.defineProperty(window, "location", { configurable: true, value: realLocation });
});

function renderGuards(role = "SUPER_ADMIN", homeTenantId?: string) {
    return render(
        <SessionGuards role={role} homeTenantId={homeTenantId}>
            <form><button type="submit">Create</button></form>
        </SessionGuards>,
    );
}

function otherTabSwitchesTo(orgId: string) {
    Cookies.set("active_tenant_id", orgId, { path: "/" });
    act(() => {
        window.dispatchEvent(new StorageEvent("storage", {
            key: "rentaxis-org-change",
            newValue: JSON.stringify({ orgId, from: "other-tab", at: Date.now() }),
        }));
    });
}

describe("SessionGuards — organisation changed in another tab (F3)", () => {
    it("blocks the page with a reload notice when another tab switches", () => {
        renderGuards();
        expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
        otherTabSwitchesTo("org2");
        const dialog = screen.getByRole("alertdialog", { name: /organisation changed in another tab/i });
        expect(dialog).toBeInTheDocument();
        // The page underneath cannot be used.
        expect(screen.getByRole("button", { name: "Create", hidden: true }).closest("[inert]")).not.toBeNull();
        fireEvent.click(screen.getByRole("button", { name: /reload/i }));
        expect(reload).toHaveBeenCalled();
    });

    it("does not block when the other tab picked the organisation this page already shows", () => {
        renderGuards();
        otherTabSwitchesTo("brk1");
        expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    });

    it("blocks when the cookie changed behind the tab's back (checked on focus)", () => {
        renderGuards();
        Cookies.set("active_tenant_id", "org2", { path: "/" });
        act(() => { window.dispatchEvent(new Event("focus")); });
        expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    });

    it("stamps mutations with the organisation the page was loaded for, even after the cookie moved", async () => {
        const inner = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(async () => new Response("{}", { status: 200 }));
        window.fetch = inner as unknown as typeof fetch;
        renderGuards();
        Cookies.set("active_tenant_id", "org2", { path: "/" });
        await act(async () => { await fetch("/api/proxy/v1/renters", { method: "POST" }); });
        expect(new Headers(inner.mock.calls[0][1]?.headers).get("X-Expected-Tenant-Id")).toBe("brk1");
    });

    it("shows the notice when the proxy refuses a stale write (409 org mismatch)", async () => {
        window.fetch = vi.fn(async () => new Response("{}", { status: 409, headers: { "X-Org-Mismatch": "1" } })) as unknown as typeof fetch;
        renderGuards();
        await act(async () => { await fetch("/api/proxy/v1/renters", { method: "POST" }); });
        expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    });
});

describe("SessionGuards — session gone (F6)", () => {
    it("sends the user to sign in with the current page as callbackUrl on a 401", async () => {
        renderGuards("TENANT_ADMIN", "brk1");
        fetchStatus = 401;
        fetchHeaders = { "X-Session-Ended": "1" };
        await act(async () => { await fetch("/api/proxy/v1/leases/paged"); });
        expect(assign).toHaveBeenCalledTimes(1);
        expect(assign).toHaveBeenCalledWith(`/en/auth/login?callbackUrl=${encodeURIComponent("/en/dashboard/renters?q=a")}`);
        // A burst of 401s redirects once.
        await act(async () => { await fetch("/api/proxy/v1/renters"); });
        expect(assign).toHaveBeenCalledTimes(1);
    });
});

describe("SessionGuards — a backend 401 is not a signed-out session (review fix 2)", () => {
    it("repairs a cookie pointing at an organisation the backend refuses, instead of looping through sign-in", async () => {
        Cookies.set("active_tenant_id", "second", { path: "/" });
        renderGuards("TENANT_ADMIN", "brk1");
        fetchStatus = 401; // "Organisation is not active." — no X-Session-Ended
        await act(async () => { await fetch("/api/proxy/v1/leases/paged"); });
        await act(async () => { await new Promise(r => setTimeout(r, 0)); });
        expect(assign).not.toHaveBeenCalled();
        expect(signOut).not.toHaveBeenCalled();
        expect(Cookies.get("active_tenant_id")).toBeUndefined();
        expect(screen.getByRole("alertdialog", { name: /this organisation is not available/i })).toBeInTheDocument();
        fireEvent.click(screen.getByRole("button", { name: /reload/i }));
        expect(reload).toHaveBeenCalled();
    });

    it("leaves the home organisation cookie alone (nothing to repair)", async () => {
        renderGuards("TENANT_ADMIN", "brk1"); // cookie is brk1 = home
        fetchStatus = 401;
        await act(async () => { await fetch("/api/proxy/v1/leases/paged"); });
        expect(assign).not.toHaveBeenCalled();
        expect(Cookies.get("active_tenant_id")).toBe("brk1");
        expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    });
});

describe("SessionGuards — installed before the page's own mount effects (review fix 4)", () => {
    it("stamps a mutation a child makes in its mount effect", async () => {
        const inner = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(async () => new Response("{}", { status: 200 }));
        window.fetch = inner as unknown as typeof fetch;
        function Child() {
            useEffect(() => { void fetch("/api/proxy/v1/renters", { method: "POST" }); }, []);
            return null;
        }
        await act(async () => {
            render(<SessionGuards role="TENANT_ADMIN" homeTenantId="brk1"><Child /></SessionGuards>);
        });
        expect(inner).toHaveBeenCalledTimes(1);
        expect(new Headers(inner.mock.calls[0][1]?.headers).get("X-Expected-Tenant-Id")).toBe("brk1");
    });
});

describe("SessionGuards — a deactivated or deleted user is signed out (fix round 2)", () => {
    it("signs out on the backend's 'Unknown or inactive user.' 401, keeping the page as callbackUrl", async () => {
        signOut.mockClear();
        Cookies.set("active_tenant_id", "second", { path: "/" });
        window.fetch = vi.fn(async () => new Response("Unknown or inactive user.", { status: 401 })) as unknown as typeof fetch;
        renderGuards("TENANT_ADMIN", "brk1");
        await act(async () => { await fetch("/api/proxy/v1/leases/paged"); });
        await vi.waitFor(() => expect(signOut).toHaveBeenCalledTimes(1));
        expect(signOut).toHaveBeenCalledWith({ callbackUrl: `/en/auth/login?callbackUrl=${encodeURIComponent("/en/dashboard/renters?q=a")}` });
        // Not mistaken for the inactive-organisation case.
        expect(Cookies.get("active_tenant_id")).toBe("second");
        expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    });

    it("signs out when the refused organisation is the user's own (nothing to repair; the login page explains)", async () => {
        signOut.mockClear();
        window.fetch = vi.fn(async () => new Response("", { status: 401 })) as unknown as typeof fetch;
        renderGuards("TENANT_ADMIN", "brk1");
        await act(async () => { await fetch("/api/proxy/v1/leases/paged"); });
        await vi.waitFor(() => expect(signOut).toHaveBeenCalledTimes(1));
    });
});
