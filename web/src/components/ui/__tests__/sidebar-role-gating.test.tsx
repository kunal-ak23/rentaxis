import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";

import en from "../../../../messages/en.json";

/**
 * The shell's per-role links. The finance-gating assertions that used to live
 * here (which ledger/ops/report links each role is offered, and why) moved to
 * the pure models: src/lib/nav/__tests__/models.test.ts (buildAccountingNav)
 * and navModel.test.ts (buildNav), and the whole-sidebar before/after check to
 * rbacParity.test.ts. What stays here renders the real shell: the renter's Home
 * panel and the active-item rule (M-9).
 */

const role = { current: "ACCOUNTANT" };
const path = { current: "/en/dashboard" };

vi.mock("next/navigation", () => ({
    useSearchParams: () => new URLSearchParams(),
    usePathname: () => path.current,
    useRouter: () => ({ push: vi.fn(), refresh: vi.fn() }),
}));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
        <a href={href} {...rest}>{children}</a>
    ),
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next-auth/react", () => ({
    useSession: () => ({
        data: { user: { role: role.current, name: "User", tenantId: "tenant-1" } },
    }),
}));
vi.mock("@/hooks/useTenantFeatures", () => ({
    useTenantFeatures: () => ({ isEnabled: () => true, features: {}, loading: false, tenantSlug: "acme" }),
}));
vi.mock("@/components/nav/useNavCounts", () => ({
    useNavCounts: () => ({ collectionBadge: null, chequesToDeposit: null, booksLockedThrough: null, booksLive: true }),
}));
vi.mock("framer-motion", () => ({
    motion: new Proxy({}, {
        get: () => ({ children, ...rest }: { children?: React.ReactNode }) => <div {...rest}>{children}</div>,
    }),
    AnimatePresence: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));
vi.mock("next/image", () => ({
    default: ({ alt }: { alt: string }) => <img alt={alt} />,
}));

import MvpSidebar, { activeNavHref } from "../MvpSidebar";
import { NavShellProvider } from "@/components/nav/NavShellContext";

function renderAs(userRole: string) {
    role.current = userRole;
    return render(
        <NextIntlClientProvider locale="en" messages={en}>
            <NavShellProvider><MvpSidebar /></NavShellProvider>
        </NextIntlClientProvider>,
    );
}

function hrefs(container: HTMLElement): string[] {
    return Array.from(container.querySelectorAll("a")).map(a => a.getAttribute("href") ?? "");
}

beforeEach(() => {
    path.current = "/en/dashboard";
    global.fetch = vi.fn(async () => ({ ok: true, json: async () => [] })) as unknown as typeof fetch;
    Object.defineProperty(window, "localStorage", {
        value: { getItem: () => null, setItem: () => {}, removeItem: () => {} },
        writable: true,
    });
});

afterEach(() => {
    cleanup();
    vi.clearAllMocks();
});

describe("sidebar renter portal", () => {
    /** Gap #39: `/dashboard/renter-portal/penalties` worked, but nothing linked to it. */
    it("offers a renter the penalties page next to leases and payments", () => {
        const { container } = renderAs("RENTER");
        expect(hrefs(container)).toEqual(expect.arrayContaining([
            "/dashboard/renter-portal",
            "/dashboard/renter-portal/payments",
            "/dashboard/renter-portal/penalties",
        ]));
        expect(screen.getByText(en.Navigation.myPenalties)).toBeInTheDocument();
        expect(screen.getByTestId("sidebar-my-penalties")).toHaveAttribute("href", "/dashboard/renter-portal/penalties");
    });
});

describe("sidebar active item (M-9)", () => {
    const current = (container: HTMLElement) =>
        Array.from(container.querySelectorAll('a[aria-current="page"]')).map(a => a.getAttribute("href"));

    it("lights only My Penalties on the penalties page, not My Leases too", () => {
        path.current = "/ar/dashboard/renter-portal/penalties";
        const { container } = renderAs("RENTER");
        expect(current(container)).toEqual(["/dashboard/renter-portal/penalties"]);
    });

    it("still lights My Leases on the renter home and its own sub-pages", () => {
        path.current = "/en/dashboard/renter-portal";
        expect(current(renderAs("RENTER").container)).toEqual(["/dashboard/renter-portal"]);
    });

    it("matches whole segments and prefers the longest href", () => {
        const hrefs = ["/dashboard/renter-portal", "/dashboard/renter-portal/payments", "/dashboard/tickets"];
        expect(activeNavHref("/en/dashboard/renter-portal/payments/123", hrefs)).toBe("/dashboard/renter-portal/payments");
        expect(activeNavHref("/en/dashboard/renter-portal/leases/9", hrefs)).toBe("/dashboard/renter-portal");
        expect(activeNavHref("/dashboard/tickets-archive", hrefs)).toBeNull();
        expect(activeNavHref("/ar/dashboard/tickets", hrefs)).toBe("/dashboard/tickets");
    });
});
