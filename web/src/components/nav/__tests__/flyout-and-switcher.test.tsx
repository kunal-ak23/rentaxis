import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";

/**
 * PR #363 R1 (P1, P2): between 768 and 1279 px a rail click opens that
 * section's panel as a flyout without navigating; the flyout survives
 * navigation and closes on outside click, Esc, the same rail item again or
 * its close button. The organisation switcher is ONE instance, in the header,
 * and /auth/me/tenants is read once per session.
 */

const role = { current: "TENANT_ADMIN" };
const path = { current: "/en/dashboard" };
const wide = { current: false };

vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => path.current,
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => <a href={href} {...rest}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({ data: { user: { id: `u-${role.current}`, role: role.current, name: "U", tenantId: role.current === "SUPER_ADMIN" ? undefined : "t1" } } }),
    signOut: vi.fn(),
}));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: () => true, tenantSlug: "acme", features: {}, loading: false }),
}));
vi.mock("@/components/nav/useNavCounts", () => ({
    useNavCounts: () => ({ collectionBadge: null, chequesToDeposit: null, booksLockedThrough: null, booksLive: true }),
}));
vi.mock("@/components/ui/GlobalSearch", () => ({ default: () => <div /> }));
vi.mock("next/image", () => ({ default: ({ alt }: { alt: string }) => <img alt={alt} /> }));

import MvpSidebar from "@/components/ui/MvpSidebar";
import { TopHeader } from "@/components/ui/TopHeader";
import { NavShellProvider } from "@/components/nav/NavShellContext";
import { resetMyOrgsCache } from "@/components/nav/orgStore";

const ORGS = [{ id: "t1", name: "Acme Holdings" }, { id: "t2", name: "Bayview Estates" }];
const orgCalls = () => (global.fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls
    .filter(c => String(c[0]).endsWith("/auth/me/tenants")).length;

function Shell() {
    return (
        <NextIntlClientProvider locale="en" messages={en}>
            <NavShellProvider>
                <MvpSidebar />
                <TopHeader />
                <div data-testid="page-body">page</div>
            </NavShellProvider>
        </NextIntlClientProvider>
    );
}

beforeEach(() => {
    role.current = "TENANT_ADMIN";
    path.current = "/en/dashboard";
    wide.current = false;
    resetMyOrgsCache();
    window.matchMedia = ((q: string) => ({
        matches: q.includes("1280") ? wide.current : true,
        media: q, addEventListener: () => {}, removeEventListener: () => {},
    })) as unknown as typeof window.matchMedia;
    Object.defineProperty(window, "localStorage", { value: { getItem: () => null, setItem: () => {}, removeItem: () => {} }, writable: true });
    global.fetch = vi.fn(async (input: RequestInfo | URL) => ({
        ok: true, status: 200, json: async () => (String(input).endsWith("/auth/me/tenants") ? ORGS : []),
    })) as unknown as typeof fetch;
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe("flyout at 768–1279 px", () => {
    it("opens the section's panel on a rail click without navigating", () => {
        render(<Shell />);
        expect(screen.queryByTestId("nav-flyout")).toBeNull();
        const link = screen.getByTestId("rail-accounting");
        const click = new MouseEvent("click", { bubbles: true, cancelable: true });
        act(() => { link.dispatchEvent(click); });
        expect(click.defaultPrevented).toBe(true);
        expect(within(screen.getByTestId("nav-flyout")).getByTestId("sidebar-trial-balance")).toBeInTheDocument();
    });

    it("stays open when the page changes, so a second page is one click away", () => {
        const { rerender } = render(<Shell />);
        fireEvent.click(screen.getByTestId("rail-accounting"));
        path.current = "/en/dashboard/finance/trial-balance";
        rerender(<Shell />);
        const flyout = screen.getByTestId("nav-flyout");
        expect(within(flyout).getByTestId("sidebar-trial-balance")).toHaveAttribute("aria-current", "page");
        expect(within(flyout).getByTestId("sidebar-general-ledger")).toBeInTheDocument();
    });

    it("closes on Esc", () => {
        render(<Shell />);
        fireEvent.click(screen.getByTestId("rail-accounting"));
        fireEvent.keyDown(window, { key: "Escape" });
        expect(screen.queryByTestId("nav-flyout")).toBeNull();
    });

    it("closes on an outside click, and not on a click inside it", () => {
        render(<Shell />);
        fireEvent.click(screen.getByTestId("rail-accounting"));
        fireEvent.mouseDown(screen.getByTestId("sidebar-journals"));
        expect(screen.getByTestId("nav-flyout")).toBeInTheDocument();
        fireEvent.mouseDown(screen.getByTestId("page-body"));
        expect(screen.queryByTestId("nav-flyout")).toBeNull();
    });

    it("closes when the same rail item is clicked again, and switches on another", () => {
        render(<Shell />);
        fireEvent.click(screen.getByTestId("rail-accounting"));
        fireEvent.click(screen.getByTestId("rail-leasing"));
        expect(within(screen.getByTestId("nav-flyout")).getByTestId("sidebar-leases")).toBeInTheDocument();
        fireEvent.click(screen.getByTestId("rail-leasing"));
        expect(screen.queryByTestId("nav-flyout")).toBeNull();
    });

    it("closes from its close button", () => {
        render(<Shell />);
        fireEvent.click(screen.getByTestId("rail-accounting"));
        fireEvent.click(screen.getByTestId("nav-flyout-close"));
        expect(screen.queryByTestId("nav-flyout")).toBeNull();
    });

    it("renders no hidden inline panel below 1280 px (tour targets stay unique)", () => {
        path.current = "/en/dashboard/finance/journals";
        render(<Shell />);
        expect(screen.queryAllByTestId("sidebar-journals")).toHaveLength(0);
        fireEvent.click(screen.getByTestId("rail-accounting"));
        expect(screen.getAllByTestId("sidebar-journals")).toHaveLength(1);
    });
});

describe("at ≥ 1280 px", () => {
    it("lets a rail click navigate and shows the page's panel inline, with no flyout", () => {
        wide.current = true;
        render(<Shell />);
        const link = screen.getByTestId("rail-accounting");
        const click = new MouseEvent("click", { bubbles: true, cancelable: true });
        act(() => { link.dispatchEvent(click); });
        expect(click.defaultPrevented).toBe(false);
        expect(screen.queryByTestId("nav-flyout")).toBeNull();
        expect(screen.getByTestId("nav-panel")).toBeInTheDocument();
    });
});

describe("organisation switcher", () => {
    it("is one instance, in the header, and a super admin at tablet width can open it and see the organisations", async () => {
        role.current = "SUPER_ADMIN";
        window.innerWidth = 1024;
        render(<Shell />);
        const buttons = screen.getAllByTestId("org-switcher-button");
        expect(buttons).toHaveLength(1);
        expect(within(screen.getByTestId("header-org-switcher")).getByTestId("org-switcher-button")).toBe(buttons[0]);
        await waitFor(() => expect(buttons[0]).toHaveTextContent("AH"));
        fireEvent.click(buttons[0]);
        expect(await screen.findByRole("button", { name: /Bayview Estates/ })).toBeInTheDocument();
    });

    it("reads /auth/me/tenants once, however often the panel remounts", async () => {
        const { rerender } = render(<Shell />);
        await waitFor(() => expect(screen.getByTestId("header-org-switcher")).toHaveTextContent("AH"));
        for (const id of ["rail-accounting", "rail-leasing", "rail-operations", "rail-settings"]) {
            fireEvent.click(screen.getByTestId(id));
        }
        path.current = "/en/dashboard/leases";
        rerender(<Shell />);
        await new Promise(r => setTimeout(r, 0));
        expect(orgCalls()).toBe(1);
    });

    it("shows the organisation's name, read-only, in the panel", async () => {
        render(<Shell />);
        fireEvent.click(screen.getByTestId("rail-accounting"));
        expect(await within(screen.getByTestId("nav-flyout")).findByText("Acme Holdings")).toBeInTheDocument();
    });
});

describe("phone drawer focus (PR #363 R1 P3)", () => {
    it("moves focus into the drawer on open and back to the menu button on close", async () => {
        render(<Shell />);
        const menu = screen.getByTestId("header-menu");
        menu.focus();
        fireEvent.click(menu);
        await waitFor(() => expect(document.activeElement).toBe(screen.getByTestId("nav-drawer-close")));
        fireEvent.keyDown(window, { key: "Escape" });
        await waitFor(() => expect(document.activeElement).toBe(screen.getByTestId("header-menu")));
    });

    it("swaps the drawer's panel on a rail click without navigating", () => {
        render(<Shell />);
        fireEvent.click(screen.getByTestId("header-menu"));
        const drawer = screen.getByTestId("nav-drawer");
        const click = new MouseEvent("click", { bubbles: true, cancelable: true });
        act(() => { within(drawer).getByTestId("rail-accounting").dispatchEvent(click); });
        expect(click.defaultPrevented).toBe(true);
        expect(within(screen.getByTestId("nav-drawer")).getByTestId("sidebar-journals")).toBeInTheDocument();
    });
});
